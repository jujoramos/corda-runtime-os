package net.corda.flow.manager.impl

import co.paralleluniverse.concurrent.util.ScheduledSingleThreadExecutor
import co.paralleluniverse.fibers.FiberExecutorScheduler
import co.paralleluniverse.io.serialization.kryo.KryoUtil
import net.corda.cipher.suite.internal.BasicHashingServiceImpl
import net.corda.data.flow.Checkpoint
import net.corda.data.flow.RPCFlowResult
import net.corda.data.flow.event.FlowEvent
import net.corda.data.flow.event.FlowSessionMessage
import net.corda.data.flow.event.Wakeup
import net.corda.data.identity.HoldingIdentity
import net.corda.flow.manager.FlowManager
import net.corda.flow.manager.FlowResult
import net.corda.flow.statemachine.FlowStateMachine
import net.corda.flow.statemachine.HousekeepingState
import net.corda.flow.statemachine.NonSerializableState
import net.corda.flow.statemachine.factory.FlowStateMachineFactory
import net.corda.internal.di.DependencyInjectionService
import net.corda.kryoserialization.CheckpointSerializationService
import net.corda.kryoserialization.DefaultWhitelist
import net.corda.kryoserialization.KRYO_CHECKPOINT_CONTEXT
import net.corda.kryoserialization.KryoCheckpointSerializerBuilder
import net.corda.kryoserialization.factory.CheckpointSerializationServiceFactory
import net.corda.messaging.api.records.Record
import net.corda.sandbox.cache.FlowMetadata
import net.corda.sandbox.cache.SandboxCache
import net.corda.v5.application.flows.Flow
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.uncheckedCast
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import java.time.Clock

@Component(service = [FlowManager::class])
class FlowManagerImpl @Activate constructor(
    @Reference(service = SandboxCache::class)
    private val sandboxCache: SandboxCache,
    @Reference(service = CheckpointSerializationServiceFactory::class)
    private val checkpointSerializationServiceFactory: CheckpointSerializationServiceFactory,
    @Reference(service = DependencyInjectionService::class)
    private val dependencyInjector: DependencyInjectionService,
    @Reference(service = FlowStateMachineFactory::class)
    private val flowStateMachineFactory: FlowStateMachineFactory
) : FlowManager {

    companion object {
        val log = contextLogger()
    }

    private val scheduler = FiberExecutorScheduler("Same thread scheduler", ScheduledSingleThreadExecutor())
    private var checkpointSerialisationService: CheckpointSerializationService? = null

    // Should be set up by the configration service
    private val resultTopic = ""
    private val wakeupTopic = ""
    private val deadLetterTopic = ""

    override fun startInitiatingFlow(
        newFlowMetadata: FlowMetadata,
        clientId: String,
        args: List<Any?>
    ): FlowResult {
        log.info("start new flow clientId: $clientId flowName: ${newFlowMetadata.name} args $args")

        val kryoCheckpointSerializerBuilder =
            KryoCheckpointSerializerBuilder({ KryoUtil.newKryo() }, DefaultWhitelist, BasicHashingServiceImpl())
        checkpointSerialisationService = checkpointSerializationServiceFactory.createCheckpointSerializationService(
            KRYO_CHECKPOINT_CONTEXT,
            kryoCheckpointSerializerBuilder.build()
        )

        val flow = getOrCreate(newFlowMetadata.key.identity, newFlowMetadata, args)
        val stateMachine = flowStateMachineFactory.createStateMachine(
            clientId,
            newFlowMetadata.key,
            flow,
//            ourIdentity,
            scheduler,
        )

        stateMachine.housekeepingState(
            HousekeepingState(
                0,
//            ourIdentity,
                false,
                mutableListOf()
            )
        )
        setupFlow(stateMachine)
        stateMachine.startFlow()
        val (checkpoint, events) = stateMachine.waitForCheckpoint()

        return FlowResult(
            checkpoint,
            events.toRecordsWithKey(newFlowMetadata.name)
        )
    }

    override fun startRemoteInitiatedFlow(
        newFlowMetadata: FlowMetadata,
        flowSessionMessage: FlowSessionMessage
    ): FlowResult {
        TODO("Not yet implemented")
    }

    override fun wakeFlow(
        lastCheckpoint: Checkpoint
    ): FlowResult {
        TODO("Not yet implemented")
    }

    @Suppress("SpreadOperator")
    private fun getOrCreate(identity: HoldingIdentity, flow: FlowMetadata, args: List<Any?>): Flow<*> {
        val flowClazz: Class<Flow<*>> =
            uncheckedCast(sandboxCache.getSandboxGroupFor(identity, flow).loadClass(flow.name, Flow::class.java))
        val constructor = flowClazz.getDeclaredConstructor(*args.map { it!!::class.java }.toTypedArray())
        return constructor.newInstance(*args.toTypedArray())
    }

    private fun setupFlow(flow: FlowStateMachine<*>) {
        flow.nonSerializableState(
            NonSerializableState(
                checkpointSerialisationService!!,
                Clock.systemUTC()
            )
        )
        dependencyInjector.injectDependencies(flow.getFlowLogic(), flow)
    }

    private fun List<FlowEvent>.toRecordsWithKey(key: String): List<Record<String, ByteArray>> {
        return this.map { event ->
            val outputTopic = when (event.payload) {
                is RPCFlowResult -> resultTopic
                is Wakeup -> wakeupTopic
                else -> {
                    log.error("No topic available for $event")
                    deadLetterTopic
                }
            }
            Record(
                outputTopic,
                key,
                checkpointSerialisationService?.serialize(event)?.bytes
            )
        }
    }
}
