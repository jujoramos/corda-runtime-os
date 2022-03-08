package net.corda.flow.mapper.impl.executor

import net.corda.data.flow.FlowKey
import net.corda.data.flow.event.FlowEvent
import net.corda.data.flow.event.MessageDirection
import net.corda.data.flow.event.SessionEvent
import net.corda.data.flow.event.mapper.FlowMapperEvent
import net.corda.data.flow.event.session.SessionInit
import net.corda.data.flow.state.mapper.FlowMapperState
import net.corda.data.flow.state.mapper.FlowMapperStateType
import net.corda.flow.mapper.FlowMapperResult
import net.corda.flow.mapper.executor.FlowMapperEventExecutor
import net.corda.messaging.api.records.Record
import net.corda.v5.base.util.contextLogger

class SessionInitExecutor(
    private val eventKey: String,
    private val sessionEvent: SessionEvent,
    private val sessionInit: SessionInit,
    private val flowMapperState: FlowMapperState?,
) : FlowMapperEventExecutor {

    private companion object {
        private val log = contextLogger()
    }

    private val messageDirection = sessionEvent.messageDirection
    private val outputTopic = getSessionEventOutputTopic(messageDirection)

    override fun execute(): FlowMapperResult {
        return if (flowMapperState == null) {
            processSessionInit(sessionEvent, sessionInit)
        } else {
            //duplicate
            log.warn(
                "Duplicate SessionInit event received. Key: $eventKey, Event: $sessionEvent"
            )
            FlowMapperResult(flowMapperState, emptyList())
        }
    }

    private fun processSessionInit(sessionEvent: SessionEvent, sessionInit: SessionInit): FlowMapperResult {
        val (flowKey, outputRecordKey, outputRecordValue) =
            getSessionInitOutputs(
                messageDirection,
                sessionEvent,
                sessionInit
            )

        return FlowMapperResult(
            FlowMapperState(flowKey, null, FlowMapperStateType.OPEN),
            listOf(Record(outputTopic, outputRecordKey, outputRecordValue))
        )
    }

    /**
     * Get a helper object to obtain:
     * - flow key
     * - output record key
     * - output record value
     */
    private fun getSessionInitOutputs(
        messageDirection: MessageDirection?,
        sessionEvent: SessionEvent,
        sessionInit: SessionInit,
    ): SessionInitOutputs {
        return if (messageDirection == MessageDirection.INBOUND) {
            val flowKey = generateFlowKey(sessionInit.initiatedIdentity)
            sessionInit.flowKey = flowKey
            SessionInitOutputs(flowKey, flowKey, FlowEvent(flowKey, sessionEvent))
        } else {
            //reusing SessionInit object for inbound and outbound traffic rather than creating a new object identical to SessionInit
            //with an extra field of flowKey. set flowkey to null to not expose it on outbound messages
            val tmpFLowEventKey = sessionInit.flowKey
            sessionInit.flowKey = null
            sessionEvent.payload = sessionInit
            sessionEvent.sessionId = toggleSessionId(sessionEvent.sessionId)

            SessionInitOutputs(
                tmpFLowEventKey,
                sessionEvent.sessionId,
                FlowMapperEvent(sessionEvent)
            )
        }
    }

    data class SessionInitOutputs(
        val flowKey: FlowKey,
        val outputRecordKey: Any,
        val outputRecordValue: Any
    )
}
