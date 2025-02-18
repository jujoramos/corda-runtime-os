package net.corda.cordapptestutils.internal.flows

import net.corda.cordapptestutils.exceptions.NoDefaultConstructorException
import net.corda.cordapptestutils.exceptions.UnrecognizedFlowClassException
import net.corda.cordapptestutils.internal.testflows.NonConstructableFlow
import net.corda.cordapptestutils.internal.testflows.PingAckFlow
import net.corda.cordapptestutils.internal.testflows.PingAckResponderFlow
import net.corda.cordapptestutils.internal.testflows.ValidResponderFlow
import net.corda.cordapptestutils.internal.testflows.ValidStartingFlow
import net.corda.v5.base.types.MemberX500Name
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.isA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BaseFlowFactoryTest {

    private val member = MemberX500Name.parse("CN=IRunCorDapps, OU=Application, O=R3, L=London, C=GB")

    @Test
    fun `should be able to construct an initiating flow from a class string`() {
        val flow = BaseFlowFactory().createInitiatingFlow(member, PingAckFlow::class.java.name)
        assertThat(flow, isA(PingAckFlow::class.java))
    }

    @Test
    fun `should be able to construct a responder flow from its class`() {
        val flow = BaseFlowFactory().createResponderFlow(member, PingAckResponderFlow::class.java)
        assertThat(flow, isA(PingAckResponderFlow::class.java))
    }

    @Test
    fun `should error if the flow class is not recognized`() {
        val notAFlowClass = Object::class.java
        assertThrows<UnrecognizedFlowClassException> {
            BaseFlowFactory().createInitiatingFlow(member, notAFlowClass.name)
        }
    }

    @Test
    fun `should error if initiating flow being constructed is not an RPCStartableFlow`() {
        val notAnInitFlow = ValidResponderFlow::class.java
        assertThrows<UnrecognizedFlowClassException> {
            BaseFlowFactory().createInitiatingFlow(member, notAnInitFlow.name)
        }
    }

    @Test
    fun `should error if responder flow is not a ResponderFlow`() {
        val flowClass = ValidStartingFlow::class.java
        assertThrows<UnrecognizedFlowClassException> { BaseFlowFactory().createResponderFlow(member, flowClass) }
    }

    @Test
    fun `should error if the flow cannot be constructed`() {
        val flowClass = NonConstructableFlow::class.java
        assertThrows<NoDefaultConstructorException> { BaseFlowFactory().createInitiatingFlow(member, flowClass.name) }
    }
}