package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelationshipRoleResolverTest {
    @Test
    fun `exact configured identity resolves as host`() {
        val resolver = RelationshipRoleResolver(HostIdentity(platform = "QQ", userId = "owner"))

        assertEquals(RelationshipRole.HOST, resolver.resolve(platform = "QQ", userId = "owner"))
    }

    @Test
    fun `platform or user mismatch resolves as interlocutor`() {
        val resolver = RelationshipRoleResolver(HostIdentity(platform = "QQ", userId = "owner"))

        assertEquals(RelationshipRole.INTERLOCUTOR, resolver.resolve(platform = "CLI", userId = "owner"))
        assertEquals(RelationshipRole.INTERLOCUTOR, resolver.resolve(platform = "QQ", userId = "member"))
    }

    @Test
    fun `missing host configuration resolves every sender as interlocutor`() {
        val resolver = RelationshipRoleResolver(host = null)

        assertEquals(RelationshipRole.INTERLOCUTOR, resolver.resolve(platform = "QQ", userId = "owner"))
        assertEquals(RelationshipRole.INTERLOCUTOR, resolver.resolve(platform = "INTERNAL", userId = "INTERNAL"))
    }

    @Test
    fun `synthetic heartbeat sender cannot resolve as host`() {
        val resolver = RelationshipRoleResolver(HostIdentity(platform = "QQ", userId = "INTERNAL"))

        assertEquals(RelationshipRole.INTERLOCUTOR, resolver.resolve(platform = "QQ", userId = "INTERNAL"))
    }

    @Test
    fun `host identity rejects blank coordinates`() {
        assertFailsWith<IllegalArgumentException> { HostIdentity(platform = "", userId = "owner") }
        assertFailsWith<IllegalArgumentException> { HostIdentity(platform = "QQ", userId = " ") }
    }
}
