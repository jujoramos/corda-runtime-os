package net.corda.flow.application.sessions.factory

import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.application.messaging.IncomingFlowSession
import net.corda.v5.application.messaging.OutgoingFlowSession
import net.corda.v5.base.types.MemberX500Name

/**
 * [FlowSessionFactory] creates [FlowSession]s.
 */
interface FlowSessionFactory {

    /**
     * Creates a [IncomingFlowSession].
     *
     * @param sessionId The session id of the [FlowSession].
     * @param x500Name The X500 name of the counterparty the [FlowSession] interacts with.
     *
     * @return An [IncomingFlowSession].
     */
    fun createIncoming(sessionId: String, x500Name: MemberX500Name): IncomingFlowSession

    /**
     * Creates a [OutgoingFlowSession].
     *
     * @param sessionId The session id of the [FlowSession].
     * @param x500Name The X500 name of the counterparty the [FlowSession] interacts with.
     *
     * @return An [OutgoingFlowSession].
     */
    fun createOutgoing(sessionId: String, x500Name: MemberX500Name): OutgoingFlowSession
}
