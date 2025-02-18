package net.corda.membership.impl.persistence.client

import net.corda.configuration.read.ConfigurationReadService
import net.corda.data.crypto.wire.CryptoSignatureWithKey
import net.corda.data.membership.common.RegistrationStatusDetails
import net.corda.data.membership.db.request.MembershipPersistenceRequest
import net.corda.data.membership.db.request.query.QueryGroupPolicy
import net.corda.data.membership.db.request.query.QueryMemberInfo
import net.corda.data.membership.db.request.query.QueryMemberSignature
import net.corda.data.membership.db.request.query.QueryRegistrationRequest
import net.corda.data.membership.db.request.query.QueryRegistrationRequests
import net.corda.data.membership.db.response.query.GroupPolicyQueryResponse
import net.corda.data.membership.db.response.query.MemberInfoQueryResponse
import net.corda.data.membership.db.response.query.MemberSignatureQueryResponse
import net.corda.data.membership.db.response.query.PersistenceFailedResponse
import net.corda.data.membership.db.response.query.RegistrationRequestQueryResponse
import net.corda.data.membership.db.response.query.RegistrationRequestsQueryResponse
import net.corda.layeredpropertymap.LayeredPropertyMapFactory
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.membership.lib.MemberInfoFactory
import net.corda.membership.lib.registration.RegistrationRequestStatus
import net.corda.membership.lib.toMap
import net.corda.membership.persistence.client.MembershipQueryClient
import net.corda.membership.persistence.client.MembershipQueryResult
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.utilities.time.Clock
import net.corda.utilities.time.UTCClock
import net.corda.v5.base.types.LayeredPropertyMap
import net.corda.v5.base.util.contextLogger
import net.corda.v5.membership.MemberInfo
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.toAvro
import net.corda.virtualnode.toCorda
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

@Suppress("LongParameterList")
@Component(service = [MembershipQueryClient::class])
class MembershipQueryClientImpl(
    coordinatorFactory: LifecycleCoordinatorFactory,
    publisherFactory: PublisherFactory,
    configurationReadService: ConfigurationReadService,
    private val memberInfoFactory: MemberInfoFactory,
    clock: Clock,
    private val layeredPropertyMapFactory: LayeredPropertyMapFactory
) : MembershipQueryClient, AbstractPersistenceClient(
    coordinatorFactory,
    LifecycleCoordinatorName.forComponent<MembershipQueryClient>(),
    publisherFactory,
    configurationReadService,
    clock,
) {

    @Activate constructor(
        @Reference(service = LifecycleCoordinatorFactory::class)
        coordinatorFactory: LifecycleCoordinatorFactory,
        @Reference(service = PublisherFactory::class)
        publisherFactory: PublisherFactory,
        @Reference(service = ConfigurationReadService::class)
        configurationReadService: ConfigurationReadService,
        @Reference(service = MemberInfoFactory::class)
        memberInfoFactory: MemberInfoFactory,
        @Reference(service = LayeredPropertyMapFactory::class)
        layeredPropertyMapFactory: LayeredPropertyMapFactory
    ) : this(coordinatorFactory, publisherFactory, configurationReadService, memberInfoFactory, UTCClock(), layeredPropertyMapFactory)

    private companion object {
        val logger = contextLogger()
    }

    override val groupName = "membership.db.query.client.group"
    override val clientName = "membership.db.query.client"

    override fun queryMemberInfo(viewOwningIdentity: HoldingIdentity): MembershipQueryResult<Collection<MemberInfo>> {
        logger.info("Querying for all member infos visible from holding identity [${viewOwningIdentity.shortHash}].")
        return queryMemberInfo(viewOwningIdentity, emptyList())
    }

    override fun queryMemberInfo(
        viewOwningIdentity: HoldingIdentity,
        queryFilter: Collection<HoldingIdentity>
    ): MembershipQueryResult<Collection<MemberInfo>> {
        if (queryFilter.isNotEmpty()) {
            logger.info("Querying for member infos represented by ${queryFilter.size} holding identities")
        }
        val result = MembershipPersistenceRequest(
            buildMembershipRequestContext(viewOwningIdentity.toAvro()),
            QueryMemberInfo(queryFilter.map { it.toAvro() })
        ).execute()
        return when (val payload = result.payload) {
            is MemberInfoQueryResponse -> {
                logger.info("Found ${(result.payload as MemberInfoQueryResponse).members.size} results.")
                MembershipQueryResult.Success(
                    payload.members.map { memberInfoFactory.create(it) }
                )
            }
            is PersistenceFailedResponse -> {
                val err = "Query failed because of: ${payload.errorMessage}"
                logger.warn(err)
                MembershipQueryResult.Failure(err)
            }
            else -> {
                val err = "Query returned unexpected payload."
                logger.warn(err)
                MembershipQueryResult.Failure(err)
            }
        }
    }

    private fun RegistrationStatusDetails.toStatus() : RegistrationRequestStatus {
        return RegistrationRequestStatus(
            status = this.registrationStatus,
            registrationId = this.registrationId,
            registrationSent = this.registrationSent,
            registrationLastModified = this.registrationLastModified,
            protocolVersion = this.registrationProtocolVersion,
            memberContext = this.memberProvidedContext
        )
    }

    override fun queryRegistrationRequestStatus(
        viewOwningIdentity: HoldingIdentity,
        registrationId: String,
    ): MembershipQueryResult<RegistrationRequestStatus?> {
        val result = MembershipPersistenceRequest(
            buildMembershipRequestContext(viewOwningIdentity.toAvro()),
            QueryRegistrationRequest(registrationId)
        ).execute()
        return when (val payload = result.payload) {
            is RegistrationRequestQueryResponse -> {
                MembershipQueryResult.Success(
                    payload.registrationRequest?.toStatus()
                )
            }
            is PersistenceFailedResponse -> {
                val err = "Query failed because of: ${payload.errorMessage}"
                logger.warn(err)
                MembershipQueryResult.Failure(err)
            }
            else -> {
                val err = "Query returned unexpected payload."
                logger.warn(err)
                MembershipQueryResult.Failure(err)
            }
        }
    }

    override fun queryRegistrationRequestsStatus(viewOwningIdentity: HoldingIdentity):
            MembershipQueryResult<List<RegistrationRequestStatus>> {
        val result = MembershipPersistenceRequest(
            buildMembershipRequestContext(viewOwningIdentity.toAvro()),
            QueryRegistrationRequests()
        ).execute()
        return when (val payload = result.payload) {
            is RegistrationRequestsQueryResponse -> {
                MembershipQueryResult.Success(
                    payload.registrationRequests.map { it.toStatus() }
                )
            }
            is PersistenceFailedResponse -> {
                val err = "Query failed because of: ${payload.errorMessage}"
                logger.warn(err)
                MembershipQueryResult.Failure(err)
            }
            else -> {
                val err = "Query returned unexpected payload."
                logger.warn(err)
                MembershipQueryResult.Failure(err)
            }
        }
    }

    override fun queryMembersSignatures(
        viewOwningIdentity: HoldingIdentity,
        holdingsIdentities: Collection<HoldingIdentity>,
    ): MembershipQueryResult<Map<HoldingIdentity, CryptoSignatureWithKey>> {
        if (holdingsIdentities.isEmpty()) {
            return MembershipQueryResult.Success(emptyMap())
        }
        val result = MembershipPersistenceRequest(
            buildMembershipRequestContext(viewOwningIdentity.toAvro()),
            QueryMemberSignature(holdingsIdentities.map { it.toAvro() })
        ).execute()
        return when (val payload = result.payload) {
            is MemberSignatureQueryResponse -> {
                MembershipQueryResult.Success(
                    payload.membersSignatures.associate { memberSignature ->
                        memberSignature.holdingIdentity.toCorda() to memberSignature.signature
                    }
                )
            }
            is PersistenceFailedResponse -> {
                MembershipQueryResult.Failure("Failed to find members signatures: ${payload.errorMessage}")
            }
            else -> {
                MembershipQueryResult.Failure("Failed to find members signatures, unexpected response: $payload")
            }
        }
    }

    override fun queryGroupPolicy(viewOwningIdentity: HoldingIdentity): MembershipQueryResult<LayeredPropertyMap> {
        val result = MembershipPersistenceRequest(
            buildMembershipRequestContext(viewOwningIdentity.toAvro()),
            QueryGroupPolicy()
        ).execute()
        return when (val payload = result.payload) {
            is GroupPolicyQueryResponse -> {
                MembershipQueryResult.Success(
                    layeredPropertyMapFactory.createMap(payload.properties.toMap())
                )
            }
            else -> {
                MembershipQueryResult.Failure("Failed to find group policy information.")
            }
        }
    }
}
