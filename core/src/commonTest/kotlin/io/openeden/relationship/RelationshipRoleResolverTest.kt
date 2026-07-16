package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelationshipRoleResolverTest {
    @Test
    fun `exact configured identity resolves as host with address`() {
        val resolver = RelationshipRoleResolver(
            host = HostIdentity(platform = "QQ", userId = "owner"),
            hostAddress = "Captain",
        )

        assertEquals(
            ResolvedRelationship(RelationshipRole.HOST, "Captain"),
            resolver.resolve(platform = "QQ", userId = "owner"),
        )
    }

    @Test
    fun `platform or user mismatch resolves as interlocutor`() {
        val resolver = RelationshipRoleResolver(HostIdentity(platform = "QQ", userId = "owner"))

        assertEquals(
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, null),
            resolver.resolve(platform = "CLI", userId = "owner"),
        )
        assertEquals(
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, null),
            resolver.resolve(platform = "QQ", userId = "member"),
        )
    }

    @Test
    fun `missing host configuration resolves every sender as interlocutor`() {
        val resolver = RelationshipRoleResolver(host = null)

        assertEquals(
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, null),
            resolver.resolve(platform = "QQ", userId = "owner"),
        )
        assertEquals(
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, null),
            resolver.resolve(platform = "INTERNAL", userId = "INTERNAL"),
        )
    }

    @Test
    fun `synthetic heartbeat sender cannot resolve as host`() {
        val resolver = RelationshipRoleResolver(
            host = HostIdentity(platform = "QQ", userId = "INTERNAL"),
            hostAddress = "Captain",
        )

        assertEquals(
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, null),
            resolver.resolve(platform = "QQ", userId = "INTERNAL"),
        )
    }

    @Test
    fun `resolver rejects address without host or blank address`() {
        assertFailsWith<IllegalArgumentException> {
            RelationshipRoleResolver(host = null, hostAddress = "Captain")
        }
        assertFailsWith<IllegalArgumentException> {
            RelationshipRoleResolver(HostIdentity("QQ", "owner"), hostAddress = " ")
        }
    }

    @Test
    fun `resolved relationship rejects address for interlocutor or blank address`() {
        assertFailsWith<IllegalArgumentException> {
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, "Captain")
        }
        assertFailsWith<IllegalArgumentException> {
            ResolvedRelationship(RelationshipRole.HOST, " ")
        }
    }

    @Test
    fun `host identity rejects blank coordinates`() {
        assertFailsWith<IllegalArgumentException> { HostIdentity(platform = "", userId = "owner") }
        assertFailsWith<IllegalArgumentException> { HostIdentity(platform = "QQ", userId = " ") }
    }
}
