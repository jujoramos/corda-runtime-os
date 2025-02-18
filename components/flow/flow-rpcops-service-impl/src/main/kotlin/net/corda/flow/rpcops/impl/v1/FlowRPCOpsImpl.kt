package net.corda.flow.rpcops.impl.v1

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import net.corda.data.virtualnode.VirtualNodeInfo
import net.corda.flow.rpcops.FlowRPCOpsServiceException
import net.corda.flow.rpcops.FlowStatusCacheService
import net.corda.flow.rpcops.factory.MessageFactory
import net.corda.flow.rpcops.impl.flowstatus.websocket.WebSocketFlowStatusUpdateListener
import net.corda.flow.rpcops.v1.FlowRpcOps
import net.corda.flow.rpcops.v1.types.request.StartFlowParameters
import net.corda.flow.rpcops.v1.types.response.FlowStatusResponse
import net.corda.flow.rpcops.v1.types.response.FlowStatusResponses
import net.corda.httprpc.PluggableRPCOps
import net.corda.httprpc.exception.ResourceAlreadyExistsException
import net.corda.httprpc.exception.ResourceNotFoundException
import net.corda.httprpc.response.ResponseEntity
import net.corda.httprpc.ws.DuplexChannel
import net.corda.httprpc.ws.WebSocketValidationException
import net.corda.libs.configuration.SmartConfig
import net.corda.lifecycle.Lifecycle
import net.corda.messaging.api.publisher.Publisher
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas.Flow.Companion.FLOW_MAPPER_EVENT_TOPIC
import net.corda.schema.Schemas.Flow.Companion.FLOW_STATUS_TOPIC
import net.corda.v5.base.util.contextLogger
import net.corda.virtualnode.ShortHash
import net.corda.virtualnode.ShortHashException
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import net.corda.virtualnode.toAvro
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger

@Component(service = [FlowRpcOps::class, PluggableRPCOps::class], immediate = true)
class FlowRPCOpsImpl @Activate constructor(
    @Reference(service = VirtualNodeInfoReadService::class)
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService,
    @Reference(service = FlowStatusCacheService::class)
    private val flowStatusCacheService: FlowStatusCacheService,
    @Reference(service = PublisherFactory::class)
    private val publisherFactory: PublisherFactory,
    @Reference(service = MessageFactory::class)
    private val messageFactory: MessageFactory
) : FlowRpcOps, PluggableRPCOps<FlowRpcOps>, Lifecycle {

    companion object {
        val log: Logger = contextLogger()
        val PUBLICATION_TIMEOUT_SECONDS = 30L
    }

    override val isRunning: Boolean get() = publisher != null

    override val targetInterface: Class<FlowRpcOps> = FlowRpcOps::class.java
    override val protocolVersion: Int = 1

    var publisher: Publisher? = null

    override fun initialise(config: SmartConfig) {
        publisher?.close()
        publisher = publisherFactory.createPublisher(PublisherConfig("FlowRPCOps"), config)
    }

    @Suppress("SpreadOperator")
    override fun startFlow(
        holdingIdentityShortHash: String,
        startFlow: StartFlowParameters
    ): ResponseEntity<FlowStatusResponse> {
        if (publisher == null) {
            throw FlowRPCOpsServiceException("FlowRPC has not been initialised ")
        }

        val vNode = getVirtualNode(ShortHash.of(holdingIdentityShortHash))
        val clientRequestId = startFlow.clientRequestId
        val flowStatus = flowStatusCacheService.getStatus(clientRequestId, vNode.holdingIdentity)

        if (flowStatus != null) {
            throw ResourceAlreadyExistsException("A flow has already been started with for the requested holdingId and clientRequestId")
        }

        val flowClassName = startFlow.flowClassName
        // TODO Platform properties to be populated correctly, for now a fixed 'account zero' is the only property
        // This is a placeholder which indicates access to everything, see CORE-6076
        val flowContextPlatformProperties = mapOf("corda.account" to "account-zero")
        val startEvent =
            messageFactory.createStartFlowEvent(
                clientRequestId,
                vNode,
                flowClassName,
                startFlow.requestData.escapedJson,
                flowContextPlatformProperties
            )
        val status = messageFactory.createStartFlowStatus(clientRequestId, vNode, flowClassName)

        val records = listOf(
            Record(FLOW_MAPPER_EVENT_TOPIC, status.key.toString(), startEvent),
            Record(FLOW_STATUS_TOPIC, status.key, status),
        )

        try {
            val recordFutures = publisher!!.publish(records)
            CompletableFuture.allOf(*recordFutures.toTypedArray())
                .get(PUBLICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw FlowRPCOpsServiceException("Failed to publish the Start Flow event.", e)
        }

        return ResponseEntity.accepted(messageFactory.createFlowStatusResponse(status))
    }

    override fun getFlowStatus(holdingIdentityShortHash: String, clientRequestId: String): FlowStatusResponse {
        val vNode = getVirtualNode(ShortHash.of(holdingIdentityShortHash))

        val flowStatus = flowStatusCacheService.getStatus(clientRequestId, vNode.holdingIdentity)
            ?: throw ResourceNotFoundException(
                "Failed to find the flow status for holding identity='${holdingIdentityShortHash} " +
                        "and Client Request ID='${clientRequestId}"
            )

        return messageFactory.createFlowStatusResponse(flowStatus)
    }

    override fun getMultipleFlowStatus(holdingIdentityShortHash: String): FlowStatusResponses {
        val vNode = getVirtualNode(ShortHash.of(holdingIdentityShortHash))
        val flowStatuses = flowStatusCacheService.getStatusesPerIdentity(vNode.holdingIdentity)
        return FlowStatusResponses(flowStatusResponses = flowStatuses.map { messageFactory.createFlowStatusResponse(it) })
    }

    override fun registerFlowStatusUpdatesFeed(
        channel: DuplexChannel,
        holdingIdentityShortHash: String,
        clientRequestId: String
    ) {
        val sessionId = channel.id
        val holdingIdentity = try {
            getVirtualNode(ShortHash.of(holdingIdentityShortHash)).holdingIdentity
        } catch (e: ShortHashException) {
            channel.error(WebSocketValidationException("Invalid holding identifier", e))
            return
        } catch (e: FlowRPCOpsServiceException) {
            channel.error(WebSocketValidationException("Invalid virtual node", e))
            return
        }
        try {
            val flowStatusFeedRegistration = flowStatusCacheService.registerFlowStatusListener(
                clientRequestId,
                holdingIdentity,
                WebSocketFlowStatusUpdateListener(clientRequestId, holdingIdentity, channel)
            )

            channel.onClose = { statusCode, reason ->
                log.info(
                    "Close hook called for duplex channel $sessionId with status $statusCode, reason: $reason " +
                            "(clientRequestId=$clientRequestId, holdingId=$holdingIdentityShortHash)"
                )
                flowStatusFeedRegistration.close()
            }
        } catch (e: WebSocketValidationException) {
            log.warn("Validation error while registering flow status listener - ${e.message}")
            error(e)
        } catch (e: Exception) {
            log.error("Unexpected error at registerFlowStatusListener")
            error(e)
        }
    }

    override fun start() = Unit

    override fun stop() {
        publisher?.close()
    }

    private fun getVirtualNode(shortId: ShortHash): VirtualNodeInfo {
        return virtualNodeInfoReadService.getByHoldingIdentityShortHash(shortId)?.toAvro()
            ?: throw FlowRPCOpsServiceException("Failed to find a Virtual Node for ID='${shortId}'")
    }
}
