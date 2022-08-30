package net.corda.flow.application.sessions

import net.corda.flow.fiber.FlowFiberService
import net.corda.v5.application.messaging.IncomingFlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name

class IncomingFlowSessionImpl(
    counterparty: MemberX500Name,
    sourceSessionId: String,
    flowFiberService: FlowFiberService
) : FlowSessionImpl(counterparty, sourceSessionId, flowFiberService), IncomingFlowSession {

    /**
     * IncomingFlowSession are always confirmed as they are created by Corda as the result of an initiated flow being
     * launched.
     */
    override var isSessionConfirmed = true

    @Suspendable
    override fun confirmSession() {
        // Nothing to do
    }
}
