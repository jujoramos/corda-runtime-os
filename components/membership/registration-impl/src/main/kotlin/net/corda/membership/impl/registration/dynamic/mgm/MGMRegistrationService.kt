package net.corda.membership.impl.registration.dynamic.mgm

import net.corda.configuration.read.ConfigChangedEvent
import net.corda.configuration.read.ConfigurationReadService
import net.corda.crypto.client.CryptoOpsClient
import net.corda.data.CordaAvroSerializationFactory
import net.corda.data.KeyValuePairList
import net.corda.data.membership.PersistentMemberInfo
import net.corda.data.membership.common.RegistrationStatus
import net.corda.layeredpropertymap.LayeredPropertyMapFactory
import net.corda.layeredpropertymap.toAvro
import net.corda.libs.configuration.helper.getConfig
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationHandle
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.membership.lib.MemberInfoExtension.Companion.CREATED_TIME
import net.corda.membership.lib.MemberInfoExtension.Companion.ECDH_KEY
import net.corda.membership.lib.MemberInfoExtension.Companion.GROUP_ID
import net.corda.membership.lib.MemberInfoExtension.Companion.IS_MGM
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_STATUS_ACTIVE
import net.corda.membership.lib.MemberInfoExtension.Companion.MODIFIED_TIME
import net.corda.membership.lib.MemberInfoExtension.Companion.PARTY_NAME
import net.corda.membership.lib.MemberInfoExtension.Companion.PARTY_SESSION_KEY
import net.corda.membership.lib.MemberInfoExtension.Companion.PLATFORM_VERSION
import net.corda.membership.lib.MemberInfoExtension.Companion.PROTOCOL_VERSION
import net.corda.membership.lib.MemberInfoExtension.Companion.SERIAL
import net.corda.membership.lib.MemberInfoExtension.Companion.SESSION_KEY_HASH
import net.corda.membership.lib.MemberInfoExtension.Companion.SOFTWARE_VERSION
import net.corda.membership.lib.MemberInfoExtension.Companion.STATUS
import net.corda.membership.lib.MemberInfoExtension.Companion.URL_KEY
import net.corda.membership.lib.MemberInfoFactory
import net.corda.membership.lib.grouppolicy.GroupPolicyConstants.PolicyValues.P2PParameters.SessionPkiMode
import net.corda.membership.lib.registration.RegistrationRequest
import net.corda.membership.lib.toWire
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.membership.persistence.client.MembershipPersistenceResult
import net.corda.membership.registration.MemberRegistrationService
import net.corda.membership.registration.MembershipRequestRegistrationOutcome
import net.corda.membership.registration.MembershipRequestRegistrationResult
import net.corda.messaging.api.publisher.Publisher
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas
import net.corda.schema.configuration.ConfigKeys.BOOT_CONFIG
import net.corda.schema.configuration.ConfigKeys.MESSAGING_CONFIG
import net.corda.utilities.time.UTCClock
import net.corda.v5.base.util.contextLogger
import net.corda.v5.cipher.suite.KeyEncodingService
import net.corda.v5.crypto.calculateHash
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.toAvro
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.TimeUnit

@Suppress("LongParameterList")
@Component(service = [MemberRegistrationService::class])
class MGMRegistrationService @Activate constructor(
    @Reference(service = PublisherFactory::class)
    private val publisherFactory: PublisherFactory,
    @Reference(service = ConfigurationReadService::class)
    private val configurationReadService: ConfigurationReadService,
    @Reference(service = LifecycleCoordinatorFactory::class)
    private val coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = CryptoOpsClient::class)
    private val cryptoOpsClient: CryptoOpsClient,
    @Reference(service = KeyEncodingService::class)
    private val keyEncodingService: KeyEncodingService,
    @Reference(service = MemberInfoFactory::class)
    private val memberInfoFactory: MemberInfoFactory,
    @Reference(service = MembershipPersistenceClient::class)
    private val membershipPersistenceClient: MembershipPersistenceClient,
    @Reference(service = LayeredPropertyMapFactory::class)
    private val layeredPropertyMapFactory: LayeredPropertyMapFactory,
    @Reference(service = CordaAvroSerializationFactory::class)
    cordaAvroSerializationFactory: CordaAvroSerializationFactory,
) : MemberRegistrationService {
    /**
     * Private interface used for implementation swapping in response to lifecycle events.
     */
    private interface InnerRegistrationService : AutoCloseable {
        fun register(
            registrationId: UUID,
            member: HoldingIdentity,
            context: Map<String, String>
        ): MembershipRequestRegistrationResult
    }

    private companion object {
        val logger: Logger = contextLogger()
        const val errorMessageTemplate = "No %s was provided."

        const val GROUP_POLICY_PREFIX = "corda.group"
        const val GROUP_POLICY_PREFIX_WITH_DOT = "$GROUP_POLICY_PREFIX."
        const val PUBLICATION_TIMEOUT_SECONDS = 30L
        const val SESSION_KEY_ID = "$PARTY_SESSION_KEY.id"
        const val ECDH_KEY_ID = "$ECDH_KEY.id"
        const val REGISTRATION_PROTOCOL = "$GROUP_POLICY_PREFIX.protocol.registration"
        const val SYNCHRONISATION_PROTOCOL = "$GROUP_POLICY_PREFIX.protocol.synchronisation"
        const val P2P_MODE = "$GROUP_POLICY_PREFIX.protocol.p2p.mode"
        const val SESSION_KEY_POLICY = "$GROUP_POLICY_PREFIX.key.session.policy"
        const val PKI_SESSION = "$GROUP_POLICY_PREFIX.pki.session"
        const val PKI_TLS = "$GROUP_POLICY_PREFIX.pki.tls"
        const val TRUSTSTORE_SESSION = "$GROUP_POLICY_PREFIX.truststore.session.%s"
        const val TRUSTSTORE_TLS = "$GROUP_POLICY_PREFIX.truststore.tls.%s"
        const val PLATFORM_VERSION_CONST = "5000"
        const val SOFTWARE_VERSION_CONST = "5.0.0"
        const val SERIAL_CONST = "1"

        val keyIdList = listOf(SESSION_KEY_ID, ECDH_KEY_ID)
        val errorMessageMap = errorMessageTemplate.run {
            mapOf(
                SESSION_KEY_ID to format("session key"),
                ECDH_KEY_ID to format("ECDH key"),
                REGISTRATION_PROTOCOL to format("registration protocol"),
                SYNCHRONISATION_PROTOCOL to format("synchronisation protocol"),
                P2P_MODE to format("P2P mode"),
                SESSION_KEY_POLICY to format("session key policy"),
                PKI_SESSION to format("session PKI property"),
                PKI_TLS to format("TLS PKI property"),
            )
        }
    }

    // for watching the config changes
    private var configHandle: AutoCloseable? = null

    // for checking the components' health
    private var componentHandle: RegistrationHandle? = null

    private var _publisher: Publisher? = null

    /**
     * Publisher for Kafka messaging. Recreated after every [MESSAGING_CONFIG] change.
     */
    private val publisher: Publisher
        get() = _publisher ?: throw IllegalArgumentException("Publisher is not initialized.")

    private val keyValuePairListSerializer =
        cordaAvroSerializationFactory.createAvroSerializer<KeyValuePairList> {
            logger.error("Failed to serialize key value pair list.")
        }

    // Component lifecycle coordinator
    private val coordinator = coordinatorFactory.createCoordinator(lifecycleCoordinatorName, ::handleEvent)

    private val clock = UTCClock()

    private var impl: InnerRegistrationService = InactiveImpl

    override val isRunning: Boolean
        get() = coordinator.isRunning

    override fun start() {
        logger.info("MGMRegistrationService started.")
        coordinator.start()
    }

    override fun stop() {
        logger.info("MGMRegistrationService stopped.")
        coordinator.stop()
    }

    private fun activate(coordinator: LifecycleCoordinator) {
        impl = ActiveImpl()
        coordinator.updateStatus(LifecycleStatus.UP)
    }

    private fun deactivate(coordinator: LifecycleCoordinator) {
        coordinator.updateStatus(LifecycleStatus.DOWN)
        impl.close()
        impl = InactiveImpl
    }

    override fun register(
        registrationId: UUID,
        member: HoldingIdentity,
        context: Map<String, String>
    ): MembershipRequestRegistrationResult = impl.register(registrationId, member, context)

    private object InactiveImpl : InnerRegistrationService {
        override fun register(
            registrationId: UUID,
            member: HoldingIdentity,
            context: Map<String, String>
        ): MembershipRequestRegistrationResult =
            MembershipRequestRegistrationResult(
                MembershipRequestRegistrationOutcome.NOT_SUBMITTED,
                "Registration failed. Reason: MGMRegistrationService is not running."
            )

        override fun close() = Unit
    }

    private inner class ActiveImpl : InnerRegistrationService {
        override fun register(
            registrationId: UUID,
            member: HoldingIdentity,
            context: Map<String, String>
        ): MembershipRequestRegistrationResult {
            try {
                validateContext(context)
                val sessionKey = getKeyFromId(context[SESSION_KEY_ID]!!, member.shortHash.value)
                val ecdhKey = getKeyFromId(context[ECDH_KEY_ID]!!, member.shortHash.value)
                val now = clock.instant().toString()
                val memberContext = context.filterKeys {
                    !keyIdList.contains(it)
                }.filterKeys {
                    !it.startsWith(GROUP_POLICY_PREFIX_WITH_DOT)
                } + mapOf(
                    GROUP_ID to member.groupId,
                    PARTY_NAME to member.x500Name.toString(),
                    PARTY_SESSION_KEY to sessionKey.toPem(),
                    SESSION_KEY_HASH to sessionKey.calculateHash().value,
                    ECDH_KEY to ecdhKey.toPem(),
                    // temporarily hardcoded
                    PLATFORM_VERSION to PLATFORM_VERSION_CONST,
                    SOFTWARE_VERSION to SOFTWARE_VERSION_CONST,
                    SERIAL to SERIAL_CONST,
                )
                val mgmInfo = memberInfoFactory.create(
                    memberContext = memberContext.toSortedMap(),
                    mgmContext = sortedMapOf(
                        CREATED_TIME to now,
                        MODIFIED_TIME to now,
                        STATUS to MEMBER_STATUS_ACTIVE,
                        IS_MGM to "true"
                    )
                )

                val persistenceResult = membershipPersistenceClient.persistMemberInfo(member, listOf(mgmInfo))
                if (persistenceResult is MembershipPersistenceResult.Failure) {
                    return MembershipRequestRegistrationResult(
                        MembershipRequestRegistrationOutcome.NOT_SUBMITTED,
                        "Registration failed, persistence error. Reason: ${persistenceResult.errorMsg}"
                    )
                }

                val groupPolicyMap = context.filterKeys {
                    it.startsWith(GROUP_POLICY_PREFIX_WITH_DOT)
                }.mapKeys {
                    it.key.removePrefix(GROUP_POLICY_PREFIX_WITH_DOT)
                }
                val groupPolicy = layeredPropertyMapFactory.createMap(groupPolicyMap)
                val groupPolicyPersistenceResult = membershipPersistenceClient.persistGroupPolicy(
                    member,
                    groupPolicy,
                )
                if (groupPolicyPersistenceResult is MembershipPersistenceResult.Failure) {
                    return MembershipRequestRegistrationResult(
                        MembershipRequestRegistrationOutcome.NOT_SUBMITTED,
                        "Registration failed, persistence error. Reason: ${groupPolicyPersistenceResult.errorMsg}"
                    )
                }

                val mgmRecord = Record(
                    Schemas.Membership.MEMBER_LIST_TOPIC,
                    "${member.shortHash}-${member.shortHash}",
                    PersistentMemberInfo(
                        member.toAvro(),
                        mgmInfo.memberProvidedContext.toAvro(),
                        mgmInfo.mgmProvidedContext.toAvro()
                    )
                )
                publisher.publish(listOf(mgmRecord)).first().get(PUBLICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val serializedMemberContext = keyValuePairListSerializer.serialize(memberContext.toWire())
                    ?: throw IllegalArgumentException("Failed to serialize the member context for this request.")
                membershipPersistenceClient.persistRegistrationRequest(
                    viewOwningIdentity = member,
                    registrationRequest = RegistrationRequest(
                        status = RegistrationStatus.APPROVED,
                        registrationId = registrationId.toString(),
                        requester = member,
                        memberContext = ByteBuffer.wrap(serializedMemberContext),
                        publicKey = ByteBuffer.wrap(byteArrayOf()),
                        signature = ByteBuffer.wrap(byteArrayOf()),
                    )
                )
            } catch (e: Exception) {
                logger.warn("Registration failed.", e)
                return MembershipRequestRegistrationResult(
                    MembershipRequestRegistrationOutcome.NOT_SUBMITTED,
                    "Registration failed. Reason: ${e.message}"
                )
            }

            return MembershipRequestRegistrationResult(MembershipRequestRegistrationOutcome.SUBMITTED)
        }

        override fun close() {
            publisher.close()
        }

        private fun validateContext(context: Map<String, String>) {
            for (key in errorMessageMap.keys) {
                context[key] ?: throw IllegalArgumentException(errorMessageMap[key])
            }
            context.keys.filter { URL_KEY.format("[0-9]+").toRegex().matches(it) }.apply {
                require(isNotEmpty()) { "No endpoint URL was provided." }
                require(isOrdered(this, 2)) { "Provided endpoint URLs are incorrectly numbered." }
            }
            context.keys.filter { PROTOCOL_VERSION.format("[0-9]+").toRegex().matches(it) }.apply {
                require(isNotEmpty()) { "No endpoint protocol was provided." }
                require(isOrdered(this, 2)) { "Provided endpoint protocols are incorrectly numbered." }
            }
            if (context[PKI_SESSION] != SessionPkiMode.NO_PKI.toString()) {
                context.keys.filter { TRUSTSTORE_SESSION.format("[0-9]+").toRegex().matches(it) }.apply {
                    require(isNotEmpty()) { "No session trust store was provided." }
                    require(isOrdered(this, 4)) { "Provided session trust stores are incorrectly numbered." }
                }
            }
            context.keys.filter { TRUSTSTORE_TLS.format("[0-9]+").toRegex().matches(it) }.apply {
                require(isNotEmpty()) { "No TLS trust store was provided." }
                require(isOrdered(this, 4)) { "Provided TLS trust stores are incorrectly numbered." }
            }
        }

        /**
         * Checks if [keys] are numbered correctly (0, 1, ..., n).
         *
         * @param keys List of property keys to validate.
         * @param position Position of numbering in each of the provided [keys]. For example, [position] is 2 in
         * "corda.endpoints.0.connectionURL".
         */
        private fun isOrdered(keys: List<String>, position: Int): Boolean =
            keys.map { it.split(".")[position].toInt() }
                .sorted()
                .run {
                    indices.forEach { index ->
                        if (this[index] != index) return false
                    }
                    true
                }

        private fun getKeyFromId(keyId: String, tenantId: String): PublicKey {
            return with(cryptoOpsClient) {
                lookup(tenantId, listOf(keyId)).firstOrNull()?.let {
                    keyEncodingService.decodePublicKey(it.publicKey.array())
                } ?: throw IllegalArgumentException("No key found for tenant: $tenantId under ID: $keyId.")
            }
        }

        private fun PublicKey.toPem(): String = keyEncodingService.encodeAsString(this)
    }

    private fun handleEvent(event: LifecycleEvent, coordinator: LifecycleCoordinator) {
        logger.info("Received event $event.")
        when (event) {
            is StartEvent -> handleStartEvent(coordinator)
            is StopEvent -> handleStopEvent(coordinator)
            is RegistrationStatusChangeEvent -> handleRegistrationChangeEvent(event, coordinator)
            is ConfigChangedEvent -> handleConfigChange(event, coordinator)
        }
    }

    private fun handleStartEvent(coordinator: LifecycleCoordinator) {
        logger.info("Handling start event.")
        componentHandle?.close()
        componentHandle = coordinator.followStatusChangesByName(
            setOf(
                LifecycleCoordinatorName.forComponent<ConfigurationReadService>(),
                LifecycleCoordinatorName.forComponent<CryptoOpsClient>(),
            )
        )
    }

    private fun handleStopEvent(coordinator: LifecycleCoordinator) {
        logger.info("Handling stop event.")
        deactivate(coordinator)
        componentHandle?.close()
        componentHandle = null
        configHandle?.close()
        configHandle = null
        _publisher?.close()
        _publisher = null
    }

    private fun handleRegistrationChangeEvent(
        event: RegistrationStatusChangeEvent,
        coordinator: LifecycleCoordinator
    ) {
        logger.info("Handling registration changed event.")
        when (event.status) {
            LifecycleStatus.UP -> {
                configHandle?.close()
                configHandle = configurationReadService.registerComponentForUpdates(
                    coordinator,
                    setOf(BOOT_CONFIG, MESSAGING_CONFIG)
                )
            }
            else -> {
                deactivate(coordinator)
                configHandle?.close()
            }
        }
    }

    // re-creates the publisher with the new config, sets the lifecycle status to UP when the publisher is ready for the first time
    private fun handleConfigChange(event: ConfigChangedEvent, coordinator: LifecycleCoordinator) {
        logger.info("Handling config changed event.")
        _publisher?.close()
        _publisher = publisherFactory.createPublisher(
            PublisherConfig("mgm-registration-service"),
            event.config.getConfig(MESSAGING_CONFIG)
        )
        _publisher?.start()
        activate(coordinator)
    }
}
