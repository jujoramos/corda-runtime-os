package net.corda.flow.application.sessions

import net.corda.flow.fiber.FlowFiberService
import net.corda.flow.fiber.FlowIORequest
import net.corda.flow.state.impl.FlatSerializableContext
import net.corda.v5.application.flows.FlowContextProperties
import net.corda.v5.application.messaging.OutgoingFlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name

class OutgoingFlowSessionImpl(
    counterparty: MemberX500Name,
    sourceSessionId: String,
    flowFiberService: FlowFiberService
) : FlowSessionImpl(counterparty, sourceSessionId, flowFiberService), OutgoingFlowSession {

    override var isSessionConfirmed = false

    /**
     * This class can be serialized, so we need to ensure all properties support that too. In this case that means we
     * cannot touch the executing fiber when setting or getting this property, because we might not be in one. Instead
     * that is done when [contextProperties] is requested or [confirmSession] called, because both of those things can
     * only happen inside a fiber by contract.
     */
    private var sessionLocalFlowContext: FlatSerializableContext? = null

    override val contextProperties: FlowContextProperties
        get() {
            initialiseSessionLocalFlowContext()
            return sessionLocalFlowContext!!
        }

    private fun initialiseSessionLocalFlowContext() {
        if (sessionLocalFlowContext == null) {
            with(fiber.getExecutionContext().flowCheckpoint.flowContext) {
                sessionLocalFlowContext = FlatSerializableContext(
                    contextUserProperties = this.flattenUserProperties(),
                    contextPlatformProperties = this.flattenPlatformProperties()
                )
            }
        }
    }

    @Suspendable
    override fun confirmSession() {
        if (!isSessionConfirmed) {
            initialiseSessionLocalFlowContext()
            fiber.suspend(
                FlowIORequest.InitiateFlow(
                    counterparty,
                    sourceSessionId,
                    contextUserProperties = sessionLocalFlowContext!!.flattenUserProperties(),
                    contextPlatformProperties = sessionLocalFlowContext!!.flattenPlatformProperties()
                )
            )
            isSessionConfirmed = true
        }
    }
}
