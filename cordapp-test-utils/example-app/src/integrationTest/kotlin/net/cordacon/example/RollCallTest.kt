package net.cordacon.example

import net.corda.cordapptestutils.HoldingIdentity
import net.corda.cordapptestutils.RequestData
import net.corda.cordapptestutils.Simulator
import net.corda.v5.base.types.MemberX500Name
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

class RollCallTest {

    @Test
    fun `should get roll call from multiple recipients`() {
        // Given a RollCallFlow that's been uploaded to Corda for a teacher
        val corda = Simulator()
        val teacher = MemberX500Name.parse(
            "CN=Ben Stein, OU=Economics, O=Glenbrook North High School, L=Chicago, C=US")
        val teacherId = HoldingIdentity.create(teacher)
        val teacherVNode = corda.createVirtualNode(teacherId, RollCallFlow::class.java)

        // and recipients with the responder flow
        //
        // and a flow to invoke when someone is absent (they return an empty string)
        // Note: We don't actually need to do the upload, because it's a subflow so constructed inside the main flow -
        // initialization, checking etc. will happen when it's passed to the engine.
        //
        // and a response (which we do need, but it's exactly the same; Ferris Bueller continues to take a day off)
        val students = listOf("Albers", "Anderson", "Anheiser", "Busch", "Bueller"). map {
            "CN=$it, OU=Economics, O=Glenbrook North High School, L=Chicago, C=US"
        }
        students.forEach { corda.createVirtualNode(
            HoldingIdentity.create(MemberX500Name.parse(it)),
            RollCallResponderFlow::class.java,
            AbsenceCallResponderFlow::class.java) }

        // When we invoke it in Corda
        val response = teacherVNode.callFlow(
            RequestData.create(
                "r1",
                RollCallFlow::class.java,
                ""
        ))

        // Then we should get the response back
        assertThat(response, `is`("""
            BEN STEIN: Albers?
            ALBERS: Here!
            BEN STEIN: Anderson?
            ANDERSON: Here!
            BEN STEIN: Anheiser?
            ANHEISER: Here!
            BEN STEIN: Busch?
            BUSCH: Here!
            BEN STEIN: Bueller?
            BEN STEIN: Bueller?
            BEN STEIN: Bueller?
            
        """.trimIndent().replace("\n", System.lineSeparator())))

        // And Ferris Bueller's absence should have been persisted
        val persistence = teacherVNode.getPersistenceService()
        val absenceResponses = persistence.findAll(AbsenceRecordEntity::class.java)
        assertThat(absenceResponses.execute().map { it.name }, `is`(listOf("Bueller")))

        corda.close()
    }

    @Test
    fun `should default to using the org name if a student has no common name`() {
        // Given a RollCallFlow that's been uploaded to Corda
        val corda = Simulator()
        val teacherId = HoldingIdentity.create(MemberX500Name.parse("O=BEN STEIN, L=Chicago, C=US"))
        val teacherVNode = corda.createVirtualNode(teacherId, RollCallFlow::class.java)

        // and recipients with the responder and absence flow
        val students = listOf("Albers", "Anderson", "Anheiser", "Busch", "Bueller"). map {
            "O=$it, L=Chicago, C=US"
        }

        students.forEach { corda.createVirtualNode(
            HoldingIdentity.create(MemberX500Name.parse(it)),
            RollCallResponderFlow::class.java,
            AbsenceCallResponderFlow::class.java)
        }

        // When we invoke it in Corda
        val response = teacherVNode.callFlow(
            RequestData.create(
            "r1",
            RollCallFlow::class.java,
            ""
        ))

        // Then we should still get the response back
        assertThat(response, `is`("""
            BEN STEIN: Albers?
            ALBERS: Here!
            BEN STEIN: Anderson?
            ANDERSON: Here!
            BEN STEIN: Anheiser?
            ANHEISER: Here!
            BEN STEIN: Busch?
            BUSCH: Here!
            BEN STEIN: Bueller?
            BEN STEIN: Bueller?
            BEN STEIN: Bueller?
            
        """.trimIndent().replace("\n", System.lineSeparator())))

        corda.close()
    }
}