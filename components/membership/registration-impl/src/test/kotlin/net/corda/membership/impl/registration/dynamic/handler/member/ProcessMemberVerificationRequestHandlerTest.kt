package net.corda.membership.impl.registration.dynamic.handler.member

import net.corda.data.CordaAvroSerializationFactory
import net.corda.data.CordaAvroSerializer
import net.corda.data.KeyValuePair
import net.corda.data.KeyValuePairList
import net.corda.data.membership.command.registration.RegistrationCommand
import net.corda.data.membership.command.registration.member.ProcessMemberVerificationRequest
import net.corda.data.membership.common.RegistrationStatus
import net.corda.data.membership.p2p.VerificationRequest
import net.corda.data.membership.p2p.VerificationResponse
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.messaging.api.records.Record
import net.corda.p2p.app.AppMessage
import net.corda.p2p.app.AuthenticatedMessage
import net.corda.test.util.identity.createTestHoldingIdentity
import net.corda.test.util.time.TestClock
import net.corda.virtualnode.toAvro
import net.corda.virtualnode.toCorda
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Instant

class ProcessMemberVerificationRequestHandlerTest {
    private companion object {
        const val GROUP_ID = "ABC123"
        const val REGISTRATION_ID = "REG-01"

        val clock = TestClock(Instant.ofEpochSecond(0))
    }

    private val mgm = createTestHoldingIdentity("C=GB, L=London, O=MGM", GROUP_ID).toAvro()
    private val member = createTestHoldingIdentity("C=GB, L=London, O=Alice", GROUP_ID).toAvro()
    private val requestBody = KeyValuePairList(listOf(KeyValuePair("KEY", "dummyKey")))
    private val verificationRequest = VerificationRequest(
        REGISTRATION_ID,
        requestBody
    )

    private val response: KArgumentCaptor<VerificationResponse> = argumentCaptor()
    private val responseSerializer: CordaAvroSerializer<VerificationResponse> = mock {
        on { serialize(response.capture()) } doReturn "RESPONSE".toByteArray()
    }
    private val cordaAvroSerializationFactory: CordaAvroSerializationFactory = mock {
        on { createAvroSerializer<VerificationResponse>(any()) } doReturn responseSerializer
    }
    private val membershipPersistenceClient = mock<MembershipPersistenceClient>()

    private val processMemberVerificationRequestHandler = ProcessMemberVerificationRequestHandler(
        clock,
        cordaAvroSerializationFactory,
        membershipPersistenceClient
    )

    @Test
    fun `handler returns response message`() {
        val result = processMemberVerificationRequestHandler.invoke(
            null,
            Record(
                "dummyTopic",
                member.toString(),
                RegistrationCommand(
                    ProcessMemberVerificationRequest(member, mgm, verificationRequest)
                )
            )
        )
        assertThat(result.outputStates).hasSize(1)
        assertThat(result.updatedState).isNull()
        val appMessage = result.outputStates.first().value as AppMessage
        with(appMessage.message as AuthenticatedMessage) {
            assertThat(this.header.source).isEqualTo(member)
            assertThat(this.header.destination).isEqualTo(mgm)
            assertThat(this.header.ttl).isNull()
            assertThat(this.header.messageId).isNotNull
            assertThat(this.header.traceId).isNull()
            assertThat(this.header.subsystem).isEqualTo("membership")
        }
        with(response.firstValue) {
            assertThat(this.registrationId).isEqualTo(REGISTRATION_ID)
        }
    }

    @Test
    fun `handler persist the request status`() {
        processMemberVerificationRequestHandler.invoke(
            null,
            Record(
                "dummyTopic",
                member.toString(),
                RegistrationCommand(
                    ProcessMemberVerificationRequest(member, mgm, verificationRequest)
                )
            )
        )

        verify(membershipPersistenceClient).setRegistrationRequestStatus(
            member.toCorda(),
            REGISTRATION_ID,
            RegistrationStatus.PENDING_MEMBER_VERIFICATION
        )
    }
}
