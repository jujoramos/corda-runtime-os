package net.corda.cordapptestutils.internal.messaging

import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.membership.MemberInfo
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BaseMemberLookupFactoryTest {

    @Test
    fun `should create a MemberLookup which knows about the member for who it was created`() {

        // Given a registry knows about one member
        val member = MemberX500Name.parse("O=Alice, L=London, C=GB")

        val memberRegistry = mock<HasMemberInfos>()
        val mapItem = member to object : MemberInfo by mock() {
            override val name: MemberX500Name
                get() = member
        }
        whenever(memberRegistry.members).thenReturn(mapOf(mapItem))

        val ml = BaseMemberLookupFactory().createMemberLookup(member, memberRegistry)

        assertThat(ml.myInfo().name, `is`(member))
    }

    @Test
    fun `should know about other members which the fiber knows about`() {

        // Given some members infos which a registry will return
        val members = listOf("Alice", "Bob", "Charlie").map {
            MemberX500Name.parse("O=$it, L=London, C=GB")
        }
        val memberRegistry = mock<HasMemberInfos>()
        val mapItems = members.associateWith {
            object : MemberInfo by mock() {
                override val name: MemberX500Name
                    get() = it
            }
        }
        whenever(memberRegistry.members).thenReturn(mapItems)

        // When we create a membership lookup and use it to find the members
        val ml = BaseMemberLookupFactory().createMemberLookup(members[0], memberRegistry)
        val foundMembers = ml.lookup()

        assertThat(foundMembers.map {it.name}.sorted(), `is`(members))


    }
}