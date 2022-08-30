package net.corda.flow.application.sessions.factory

import net.corda.flow.application.sessions.IncomingFlowSessionImpl
import net.corda.flow.application.sessions.OutgoingFlowSessionImpl
import net.corda.flow.fiber.FlowFiberService
import net.corda.v5.application.messaging.IncomingFlowSession
import net.corda.v5.application.messaging.OutgoingFlowSession
import net.corda.v5.base.types.MemberX500Name
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import java.security.AccessController
import java.security.PrivilegedActionException
import java.security.PrivilegedExceptionAction

@Component(service = [FlowSessionFactory::class])
class FlowSessionFactoryImpl @Activate constructor(
    @Reference(service = FlowFiberService::class)
    private val flowFiberService: FlowFiberService
) : FlowSessionFactory {

    override fun createIncoming(sessionId: String, x500Name: MemberX500Name): IncomingFlowSession {
        return try {
            AccessController.doPrivileged(PrivilegedExceptionAction {
                IncomingFlowSessionImpl(counterparty = x500Name, sessionId, flowFiberService)
            })
        } catch (e: PrivilegedActionException) {
            throw e.exception
        }
    }

    override fun createOutgoing(sessionId: String, x500Name: MemberX500Name): OutgoingFlowSession {
        return try {
            AccessController.doPrivileged(PrivilegedExceptionAction {
                OutgoingFlowSessionImpl(counterparty = x500Name, sessionId, flowFiberService)
            })
        } catch (e: PrivilegedActionException) {
            throw e.exception
        }
    }
}
