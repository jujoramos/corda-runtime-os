package net.corda.membership.impl.registration.dynamic.mgm

import com.typesafe.config.ConfigFactory
import net.corda.configuration.read.ConfigChangedEvent
import net.corda.configuration.read.ConfigurationReadService
import net.corda.crypto.client.CryptoOpsClient
import net.corda.crypto.impl.converter.PublicKeyConverter
import net.corda.crypto.impl.converter.PublicKeyHashConverter
import net.corda.data.CordaAvroSerializationFactory
import net.corda.data.CordaAvroSerializer
import net.corda.data.KeyValuePairList
import net.corda.data.crypto.wire.CryptoSigningKey
import net.corda.data.membership.PersistentMemberInfo
import net.corda.data.membership.common.RegistrationStatus
import net.corda.layeredpropertymap.LayeredPropertyMapFactory
import net.corda.layeredpropertymap.impl.LayeredPropertyMapFactoryImpl
import net.corda.libs.configuration.SmartConfigFactory
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleEventHandler
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
import net.corda.membership.lib.MemberInfoExtension.Companion.isMgm
import net.corda.membership.lib.MemberInfoFactory
import net.corda.membership.lib.impl.MemberInfoFactoryImpl
import net.corda.membership.lib.impl.converter.EndpointInfoConverter
import net.corda.membership.lib.registration.RegistrationRequest
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.membership.persistence.client.MembershipPersistenceResult
import net.corda.membership.registration.MembershipRequestRegistrationOutcome
import net.corda.membership.registration.MembershipRequestRegistrationResult
import net.corda.messaging.api.publisher.Publisher
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas
import net.corda.schema.configuration.ConfigKeys.BOOT_CONFIG
import net.corda.schema.configuration.ConfigKeys.MESSAGING_CONFIG
import net.corda.v5.base.types.LayeredPropertyMap
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.cipher.suite.KeyEncodingService
import net.corda.virtualnode.HoldingIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MGMRegistrationServiceTest {
    companion object {
        private const val SESSION_KEY_STRING = "1234"
        private const val SESSION_KEY_ID = "1"
        private const val ECDH_KEY_STRING = "5678"
        private const val ECDH_KEY_ID = "2"
        private const val PUBLISHER_CLIENT_ID = "mgm-registration-service"
    }

    private val groupId = "43b5b6e6-4f2d-498f-8b41-5e2f8f97e7e8"
    private val registrationRequest = UUID(1L, 2L)
    private val mgmName = MemberX500Name("Corda MGM", "London", "GB")
    private val mgm = HoldingIdentity(mgmName, groupId)
    private val mgmId = mgm.shortHash
    private val sessionKey: PublicKey = mock {
        on { encoded } doReturn SESSION_KEY_STRING.toByteArray()
    }
    private val sessionCryptoSigningKey: CryptoSigningKey = mock {
        on { publicKey } doReturn ByteBuffer.wrap(SESSION_KEY_STRING.toByteArray())
    }
    private val ecdhKey: PublicKey = mock {
        on { encoded } doReturn ECDH_KEY_STRING.toByteArray()
    }
    private val ecdhCryptoSigningKey: CryptoSigningKey = mock {
        on { publicKey } doReturn ByteBuffer.wrap(ECDH_KEY_STRING.toByteArray())
    }
    private val mockPublisher = mock<Publisher>().apply {
        whenever(publish(any())).thenReturn(listOf(CompletableFuture.completedFuture(Unit)))
    }

    private val publisherFactory: PublisherFactory = mock {
        on { createPublisher(any(), any()) } doReturn mockPublisher
    }
    private val keyEncodingService: KeyEncodingService = mock {
        on { decodePublicKey(SESSION_KEY_STRING.toByteArray()) } doReturn sessionKey
        on { decodePublicKey(ECDH_KEY_STRING.toByteArray()) } doReturn ecdhKey

        on { encodeAsString(any()) } doReturn SESSION_KEY_STRING
        on { encodeAsString(ecdhKey) } doReturn ECDH_KEY_STRING
    }
    private val cryptoOpsClient: CryptoOpsClient = mock {
        on { lookup(mgmId.value, listOf(SESSION_KEY_ID)) } doReturn listOf(sessionCryptoSigningKey)
        on { lookup(mgmId.value, listOf(ECDH_KEY_ID)) } doReturn listOf(ecdhCryptoSigningKey)
    }

    private val componentHandle: RegistrationHandle = mock()
    private val configHandle: AutoCloseable = mock()
    private val testConfig =
        SmartConfigFactory.create(ConfigFactory.empty()).create(ConfigFactory.parseString("instanceId=1"))
    private val dependentComponents = setOf(
        LifecycleCoordinatorName.forComponent<ConfigurationReadService>(),
        LifecycleCoordinatorName.forComponent<CryptoOpsClient>(),
    )

    private var coordinatorIsRunning = false
    private var coordinatorStatus: KArgumentCaptor<LifecycleStatus> = argumentCaptor()
    private val coordinator: LifecycleCoordinator = mock {
        on { followStatusChangesByName(eq(dependentComponents)) } doReturn componentHandle
        on { isRunning } doAnswer { coordinatorIsRunning }
        on { start() } doAnswer {
            coordinatorIsRunning = true
            lifecycleHandlerCaptor.firstValue.processEvent(StartEvent(), mock)
        }
        on { stop() } doAnswer {
            coordinatorIsRunning = false
            lifecycleHandlerCaptor.firstValue.processEvent(StopEvent(), mock)
        }
        doNothing().whenever(it).updateStatus(coordinatorStatus.capture(), any())
        on { status } doAnswer { coordinatorStatus.firstValue }
    }

    private val lifecycleHandlerCaptor: KArgumentCaptor<LifecycleEventHandler> = argumentCaptor()
    private val lifecycleCoordinatorFactory: LifecycleCoordinatorFactory = mock {
        on { createCoordinator(any(), lifecycleHandlerCaptor.capture()) } doReturn coordinator
    }
    private val configurationReadService: ConfigurationReadService = mock {
        on { registerComponentForUpdates(eq(coordinator), any()) } doReturn configHandle
    }
    private val layeredPropertyMapFactory: LayeredPropertyMapFactory = LayeredPropertyMapFactoryImpl(
        listOf(EndpointInfoConverter(), PublicKeyConverter(keyEncodingService), PublicKeyHashConverter())
    )
    private val memberInfoFactory: MemberInfoFactory = MemberInfoFactoryImpl(layeredPropertyMapFactory)
    private val statusUpdate = argumentCaptor<RegistrationRequest>()
    private val membershipPersistenceClient = mock<MembershipPersistenceClient> {
        on { persistMemberInfo(any(), any()) } doReturn MembershipPersistenceResult.Success(Unit)
        on { persistGroupPolicy(any(), any()) } doReturn MembershipPersistenceResult.Success(2)
        on { persistRegistrationRequest(eq(mgm), statusUpdate.capture()) } doReturn MembershipPersistenceResult.success()
    }
    private val keyValuePairListSerializer: CordaAvroSerializer<KeyValuePairList> = mock {
        on { serialize(any()) } doReturn byteArrayOf(1, 2, 3)
    }
    private val cordaAvroSerializationFactory = mock<CordaAvroSerializationFactory> {
        on { createAvroSerializer<KeyValuePairList>(any()) } doReturn keyValuePairListSerializer
    }
    private val registrationService = MGMRegistrationService(
        publisherFactory,
        configurationReadService,
        lifecycleCoordinatorFactory,
        cryptoOpsClient,
        keyEncodingService,
        memberInfoFactory,
        membershipPersistenceClient,
        layeredPropertyMapFactory,
        cordaAvroSerializationFactory,
    )

    private val properties = mapOf(
        "corda.session.key.id" to SESSION_KEY_ID,
        "corda.ecdh.key.id" to ECDH_KEY_ID,
        "corda.group.protocol.registration" to "net.corda.membership.impl.registration.dynamic.MemberRegistrationService",
        "corda.group.protocol.synchronisation" to "net.corda.membership.impl.sync.dynamic.MemberSyncService",
        "corda.group.protocol.p2p.mode" to "AUTHENTICATION_ENCRYPTION",
        "corda.group.key.session.policy" to "Combined",
        "corda.group.pki.session" to "Standard",
        "corda.group.pki.tls" to "C5",
        "corda.endpoints.0.connectionURL" to "localhost:1080",
        "corda.endpoints.0.protocolVersion" to "1",
        "corda.group.truststore.session.0" to "-----BEGIN CERTIFICATE-----Base64–encoded certificate-----END CERTIFICATE-----",
        "corda.group.truststore.tls.0" to "-----BEGIN CERTIFICATE-----Base64–encoded certificate-----END CERTIFICATE-----",
    )

    private fun postStartEvent() {
        lifecycleHandlerCaptor.firstValue.processEvent(StartEvent(), coordinator)
    }

    private fun postStopEvent() {
        lifecycleHandlerCaptor.firstValue.processEvent(StopEvent(), coordinator)
    }

    private fun postRegistrationStatusChangeEvent(
        status: LifecycleStatus,
        handle: RegistrationHandle = componentHandle
    ) {
        lifecycleHandlerCaptor.firstValue.processEvent(
            RegistrationStatusChangeEvent(
                handle,
                status
            ),
            coordinator
        )
    }

    private fun postConfigChangedEvent() {
        lifecycleHandlerCaptor.firstValue.processEvent(
            ConfigChangedEvent(
                setOf(BOOT_CONFIG, MESSAGING_CONFIG),
                mapOf(
                    BOOT_CONFIG to testConfig,
                    MESSAGING_CONFIG to testConfig
                )
            ),
            coordinator
        )
    }

    @Test
    fun `starting the service succeeds`() {
        registrationService.start()
        assertThat(registrationService.isRunning).isTrue
        verify(coordinator).start()
    }

    @Test
    fun `stopping the service succeeds`() {
        registrationService.start()
        registrationService.stop()
        assertThat(registrationService.isRunning).isFalse
        verify(coordinator).stop()
    }

    @Test
    fun `registration successfully builds MGM info and publishes it`() {
        postConfigChangedEvent()
        registrationService.start()
        val capturedPublishedList = argumentCaptor<List<Record<String, Any>>>()

        val result = registrationService.register(registrationRequest, mgm, properties)

        verify(mockPublisher, times(1)).publish(capturedPublishedList.capture())
        val publishedMgmInfoList = capturedPublishedList.firstValue
        assertSoftly {
            it.assertThat(result.outcome).isEqualTo(MembershipRequestRegistrationOutcome.SUBMITTED)
            it.assertThat(publishedMgmInfoList).hasSize(1)

            val publishedMgmInfo = publishedMgmInfoList.first()
            it.assertThat(publishedMgmInfo.topic).isEqualTo(Schemas.Membership.MEMBER_LIST_TOPIC)

            val expectedRecordKey = "$mgmId-$mgmId"
            it.assertThat(publishedMgmInfo.key).isEqualTo(expectedRecordKey)

            val persistedMgm = publishedMgmInfo.value as PersistentMemberInfo

            it.assertThat(persistedMgm.memberContext.items.map { item -> item.key })
                .containsExactlyInAnyOrderElementsOf(
                    listOf(
                        GROUP_ID,
                        PARTY_NAME,
                        PARTY_SESSION_KEY,
                        SESSION_KEY_HASH,
                        ECDH_KEY,
                        PLATFORM_VERSION,
                        SOFTWARE_VERSION,
                        SERIAL,
                        String.format(URL_KEY, 0),
                        String.format(PROTOCOL_VERSION, 0),
                    )
                )
            it.assertThat(persistedMgm.mgmContext.items.map { item -> item.key })
                .containsExactlyInAnyOrderElementsOf(
                    listOf(
                        CREATED_TIME,
                        MODIFIED_TIME,
                        STATUS,
                        IS_MGM
                    )
                )

            fun getProperty(prop: String): String {
                return persistedMgm
                    .memberContext.items.firstOrNull { item ->
                        item.key == prop
                    }?.value ?: persistedMgm.mgmContext.items.firstOrNull { item ->
                    item.key == prop
                }?.value ?: fail("Could not find property within published member for test")
            }

            it.assertThat(getProperty(PARTY_NAME)).isEqualTo(mgmName.toString())
            it.assertThat(getProperty(GROUP_ID)).isEqualTo(groupId)
            it.assertThat(getProperty(STATUS)).isEqualTo(MEMBER_STATUS_ACTIVE)
            it.assertThat(getProperty(IS_MGM)).isEqualTo("true")
            it.assertThat(statusUpdate.firstValue.status).isEqualTo(RegistrationStatus.APPROVED)
            it.assertThat(statusUpdate.firstValue.registrationId).isEqualTo(registrationRequest.toString())
        }
        registrationService.stop()
    }

    @Test
    fun `registration persist the group properties`() {
        postConfigChangedEvent()
        registrationService.start()
        val groupProperties = argumentCaptor<LayeredPropertyMap>()
        whenever(
            membershipPersistenceClient
                .persistGroupPolicy(
                    eq(mgm),
                    groupProperties.capture(),
                )
        ).thenReturn(MembershipPersistenceResult.Success(3))

        registrationService.register(registrationRequest, mgm, properties)

        assertThat(groupProperties.firstValue.entries)
            .containsExactlyInAnyOrderElementsOf(
                mapOf(
                    "protocol.registration" to "net.corda.membership.impl.registration.dynamic.MemberRegistrationService",
                    "protocol.synchronisation" to "net.corda.membership.impl.sync.dynamic.MemberSyncService",
                    "protocol.p2p.mode" to "AUTHENTICATION_ENCRYPTION",
                    "key.session.policy" to "Combined",
                    "pki.session" to "Standard",
                    "pki.tls" to "C5",
                    "truststore.session.0" to "-----BEGIN CERTIFICATE-----Base64–encoded certificate-----END CERTIFICATE-----",
                    "truststore.tls.0" to "-----BEGIN CERTIFICATE-----Base64–encoded certificate-----END CERTIFICATE-----",
                ).entries
            )
        registrationService.stop()
    }

    @Test
    fun `registration persist the MGM member info`() {
        postConfigChangedEvent()
        registrationService.start()

        registrationService.register(registrationRequest, mgm, properties)

        verify(membershipPersistenceClient).persistMemberInfo(
            eq(mgm),
            argThat {
                this.size == 1 &&
                    this.first().isMgm &&
                    this.first().name == mgmName
            }
        )
    }

    @Test
    fun `registration failure to persist return an error`() {
        postConfigChangedEvent()
        registrationService.start()
        whenever(membershipPersistenceClient.persistMemberInfo(eq(mgm), any()))
            .doReturn(MembershipPersistenceResult.Failure("Nop"))

        val result = registrationService.register(registrationRequest, mgm, properties)

        assertSoftly {
            it.assertThat(result.outcome).isEqualTo(MembershipRequestRegistrationOutcome.NOT_SUBMITTED)
            it.assertThat(result.message).isEqualTo("Registration failed, persistence error. Reason: Nop")
        }
    }

    @Test
    fun `registration fails when coordinator is not running`() {
        val registrationResult = registrationService.register(registrationRequest, mgm, mock())
        assertThat(registrationResult).isEqualTo(
            MembershipRequestRegistrationResult(
                MembershipRequestRegistrationOutcome.NOT_SUBMITTED,
                "Registration failed. Reason: MGMRegistrationService is not running."
            )
        )
    }

    @Test
    fun `registration fails when one or more properties are missing`() {
        postConfigChangedEvent()
        val testProperties = mutableMapOf<String, String>()
        registrationService.start()
        properties.entries.apply {
            for (index in indices) {
                val result = registrationService.register(registrationRequest, mgm, testProperties)
                assertSoftly {
                    it.assertThat(result.outcome).isEqualTo(MembershipRequestRegistrationOutcome.NOT_SUBMITTED)
                }
                elementAt(index).let { testProperties.put(it.key, it.value) }
            }
        }
        registrationService.stop()
    }

    @Test
    fun `registration fails when one or more properties are numbered incorrectly`() {
        postConfigChangedEvent()
        val testProperties =
            properties + mapOf(
                "corda.group.truststore.tls.100" to
                    "-----BEGIN CERTIFICATE-----Base64–encoded certificate-----END CERTIFICATE-----"
            )
        registrationService.start()
        val result = registrationService.register(registrationRequest, mgm, testProperties)
        assertSoftly {
            it.assertThat(result.outcome).isEqualTo(MembershipRequestRegistrationOutcome.NOT_SUBMITTED)
            it.assertThat(result.message)
                .isEqualTo("Registration failed. Reason: Provided TLS trust stores are incorrectly numbered.")
        }
        registrationService.stop()
    }

    @Test
    fun `if session PKI mode is NoPKI, session trust root is optional`() {
        postConfigChangedEvent()
        val testProperties = properties.toMutableMap()
        testProperties["corda.group.pki.session"] = "NoPKI"
        testProperties.remove("corda.group.truststore.session.0")
        registrationService.start()
        val result = registrationService.register(registrationRequest, mgm, testProperties)
        assertSoftly {
            it.assertThat(result.outcome).isEqualTo(MembershipRequestRegistrationOutcome.SUBMITTED)
        }
        registrationService.stop()
    }

    @Test
    fun `component handle created on start and closed on stop`() {
        postStartEvent()

        verify(componentHandle, never()).close()
        verify(coordinator).followStatusChangesByName(eq(dependentComponents))

        postStartEvent()

        verify(componentHandle).close()
        verify(coordinator, times(2)).followStatusChangesByName(eq(dependentComponents))

        postStopEvent()
        verify(componentHandle, times(2)).close()
    }

    @Test
    fun `status set to down after stop`() {
        postStopEvent()

        verify(coordinator).updateStatus(eq(LifecycleStatus.DOWN), any())
        verify(componentHandle, never()).close()
        verify(configHandle, never()).close()
        verify(mockPublisher, never()).close()
    }

    @Test
    fun `registration status UP creates config handle and closes it first if it exists`() {
        postStartEvent()
        postRegistrationStatusChangeEvent(LifecycleStatus.UP)

        val configArgs = argumentCaptor<Set<String>>()
        verify(configHandle, never()).close()
        verify(configurationReadService).registerComponentForUpdates(
            eq(coordinator),
            configArgs.capture()
        )
        assertThat(configArgs.firstValue).isEqualTo(setOf(BOOT_CONFIG, MESSAGING_CONFIG))

        postRegistrationStatusChangeEvent(LifecycleStatus.UP)
        verify(configHandle).close()
        verify(configurationReadService, times(2)).registerComponentForUpdates(eq(coordinator), any())

        postStopEvent()
        verify(configHandle, times(2)).close()
    }

    @Test
    fun `registration status DOWN sets status to DOWN`() {
        postRegistrationStatusChangeEvent(LifecycleStatus.DOWN)

        verify(coordinator).updateStatus(eq(LifecycleStatus.DOWN), any())
    }

    @Test
    fun `registration status ERROR sets status to DOWN`() {
        postRegistrationStatusChangeEvent(LifecycleStatus.ERROR)

        verify(coordinator).updateStatus(eq(LifecycleStatus.DOWN), any())
    }

    @Test
    fun `config changed event creates publisher`() {
        postConfigChangedEvent()

        val configCaptor = argumentCaptor<PublisherConfig>()
        verify(mockPublisher, never()).close()
        verify(publisherFactory).createPublisher(
            configCaptor.capture(),
            any()
        )
        verify(mockPublisher).start()
        verify(coordinator).updateStatus(eq(LifecycleStatus.UP), any())

        with(configCaptor.firstValue) {
            assertThat(clientId).isEqualTo(PUBLISHER_CLIENT_ID)
        }

        postConfigChangedEvent()
        verify(mockPublisher).close()
        verify(publisherFactory, times(2)).createPublisher(
            configCaptor.capture(),
            any()
        )
        verify(mockPublisher, times(2)).start()
        verify(coordinator, times(2)).updateStatus(eq(LifecycleStatus.UP), any())

        postStopEvent()
        verify(mockPublisher, times(3)).close()
    }
}
