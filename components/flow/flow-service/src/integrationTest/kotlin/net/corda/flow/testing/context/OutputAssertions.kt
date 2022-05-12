package net.corda.flow.testing.context

import net.corda.data.flow.output.FlowStates

interface OutputAssertions {
    fun sessionAckEvent(
        flowId: String,
        sessionId: String,
        initiatingIdentity: net.corda.data.identity.HoldingIdentity? = null,
        initiatedIdentity: net.corda.data.identity.HoldingIdentity? = null
    )

    fun flowDidNotResume()

    fun flowResumedWithSessionData(vararg sessionData: Pair<String, ByteArray>)

    fun wakeUpEvent()

    fun noFlowEvents()

    fun checkpointHasRetry(expectedCount: Int)

    fun checkpointDoesNotHaveRetry()

    fun flowStatus(state: FlowStates, result: String? = null, errorType: String? = null, errorMessage:String? = null)

    fun nullStateRecord()

    fun markedForDlq()
}