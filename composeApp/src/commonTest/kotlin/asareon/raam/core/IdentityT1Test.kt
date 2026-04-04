package asareon.raam.core

import kotlin.test.*

/**
 * Tier 1 Unit Tests for the Identity module.
 *
 * Mandate (P-TEST-001, T1): To test the Identity data class, typed ID wrappers,
 * string validators, and registry extension functions in complete isolation.
 * No Store, no features — pure function testing.
 */
class IdentityT1Test {

    // ========================================================================
    // STRING VALIDATORS — stringIsUUID, stringIsHandle, requireUUID
    // ========================================================================

    @Test
    fun `stringIsUUID accepts valid UUID v4 format`() {
        assertTrue(stringIsUUID("00000000-0000-4000-a000-000000000001"))
        assertTrue(stringIsUUID("abcdef12-3456-7890-abcd-ef1234567890"))
        assertTrue(stringIsUUID("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
    }

    @Test
    fun `stringIsUUID rejects non-UUID strings`() {
        assertFalse(stringIsUUID("session"))
        assertFalse(stringIsUUID("session.chat1"))
        assertFalse(stringIsUUID(""))
        assertFalse(stringIsUUID("not-a-uuid-at-all"))
        // Too short
        assertFalse(stringIsUUID("00000000-0000-4000-a000"))
        // Too long
        assertFalse(stringIsUUID("00000000-0000-4000-a000-0000000000010"))
        // Wrong separator placement
        assertFalse(stringIsUUID("000000000000-4000-a000-000000000001"))
        // Uppercase hex (UUID regex requires lowercase)
        assertFalse(stringIsUUID("ABCDEF12-3456-7890-ABCD-EF1234567890"))
    }

    @Test
    fun `stringIsHandle identifies dotted hierarchical handles`() {
        assertTrue(stringIsHandle("session.chat1"))
        assertTrue(stringIsHandle("agent.gemini-coder-1"))
        assertTrue(stringIsHandle("agent.gemini-coder.sub-task"))
        assertTrue(stringIsHandle("core.alice"))
    }

    @Test
    fun `stringIsHandle rejects non-hierarchical strings`() {
        assertFalse(stringIsHandle("session"))
        assertFalse(stringIsHandle("core"))
        assertFalse(stringIsHandle(""))
    }

    @Test
    fun `stringIsHandle rejects UUIDs even with dots`() {
        // A valid UUID has no dots, so this is really testing the UUID exclusion
        // UUIDs contain dashes not dots, so there's no overlap. But we verify the logic:
        assertFalse(stringIsHandle("00000000-0000-4000-a000-000000000001"))
    }

    @Test
    fun `requireUUID returns IdentityUUID for valid UUID`() {
        val uuid = requireUUID("00000000-0000-4000-a000-000000000001")
        assertEquals("00000000-0000-4000-a000-000000000001", uuid.uuid)
    }

    @Test
    fun `requireUUID throws for non-UUID string`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            requireUUID("session.chat1")
        }
        assertTrue(ex.message!!.contains("Expected UUID"))
    }

    @Test
    fun `requireUUID includes context in error message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            requireUUID("not-a-uuid", "agent lookup")
        }
        assertTrue(ex.message!!.contains("agent lookup"))
    }

    // ========================================================================
    // TYPED ID WRAPPERS — IdentityHandle, IdentityUUID
    // ========================================================================

    @Test
    fun `IdentityHandle wraps and unwraps correctly`() {
        val handle = IdentityHandle("agent.gemini-coder-1")
        assertEquals("agent.gemini-coder-1", handle.handle)
        assertEquals("agent.gemini-coder-1", handle.toString())
    }

    @Test
    fun `IdentityUUID wraps and unwraps correctly`() {
        val uuid = IdentityUUID("00000000-0000-4000-a000-000000000001")
        assertEquals("00000000-0000-4000-a000-000000000001", uuid.uuid)
        assertEquals("00000000-0000-4000-a000-000000000001", uuid.toString())
    }

    @Test
    fun `IdentityHandle equality works as value class`() {
        val a = IdentityHandle("session.chat1")
        val b = IdentityHandle("session.chat1")
        val c = IdentityHandle("session.chat2")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `IdentityUUID equality works as value class`() {
        val a = IdentityUUID("00000000-0000-4000-a000-000000000001")
        val b = IdentityUUID("00000000-0000-4000-a000-000000000001")
        val c = IdentityUUID("00000000-0000-4000-a000-000000000002")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ========================================================================
    // IDENTITY DATA CLASS — convenience accessors
    // ========================================================================

    private val featureIdentity = Identity(
        uuid = null,
        localHandle = "session",
        handle = "session",
        name = "Session Manager",
        parentHandle = null,
        registeredAt = 1000L
    )

    private val childIdentity = Identity(
        uuid = "00000000-0000-4000-a000-000000000001",
        localHandle = "chat1",
        handle = "session.chat1",
        name = "Chat Session 1",
        parentHandle = "session",
        registeredAt = 2000L
    )

    @Test
    fun `identityHandle returns typed IdentityHandle`() {
        assertEquals(IdentityHandle("session"), featureIdentity.identityHandle)
        assertEquals(IdentityHandle("session.chat1"), childIdentity.identityHandle)
    }

    @Test
    fun `identityUUID returns null for features`() {
        assertNull(featureIdentity.identityUUID)
    }

    @Test
    fun `identityUUID returns typed IdentityUUID for ephemeral entities`() {
        val uuid = childIdentity.identityUUID
        assertNotNull(uuid)
        assertEquals("00000000-0000-4000-a000-000000000001", uuid.uuid)
    }

    // ========================================================================
    // REGISTRY EXTENSIONS — findByUUID, resolve, suggestMatches
    // ========================================================================

    private val registry: Map<String, Identity> = mapOf(
        "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
        "session" to Identity(uuid = null, localHandle = "session", handle = "session", name = "Session Manager"),
        "session.chat1" to Identity(
            uuid = "00000000-0000-4000-a000-000000000001",
            localHandle = "chat1", handle = "session.chat1",
            name = "Chat Session 1", parentHandle = "session"
        ),
        "session.chat2" to Identity(
            uuid = "00000000-0000-4000-a000-000000000002",
            localHandle = "chat2", handle = "session.chat2",
            name = "Chat Session 2", parentHandle = "session"
        ),
        "agent" to Identity(uuid = null, localHandle = "agent", handle = "agent", name = "Agent Runtime"),
        "agent.gemini-coder-1" to Identity(
            uuid = "00000000-0000-4000-a000-000000000003",
            localHandle = "gemini-coder-1", handle = "agent.gemini-coder-1",
            name = "Gemini Coder nr.1", parentHandle = "agent"
        ),
        "core.alice" to Identity(
            uuid = "00000000-0000-4000-a000-000000000004",
            localHandle = "alice", handle = "core.alice",
            name = "Alice", parentHandle = "core"
        )
    )

    // --- findByUUID ---

    @Test
    fun `findByUUID returns identity with matching UUID`() {
        val result = registry.findByUUID(IdentityUUID("00000000-0000-4000-a000-000000000001"))
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `findByUUID string overload returns identity`() {
        val result = registry.findByUUID("00000000-0000-4000-a000-000000000003")
        assertNotNull(result)
        assertEquals("agent.gemini-coder-1", result.handle)
    }

    @Test
    fun `findByUUID returns null for unknown UUID`() {
        assertNull(registry.findByUUID(IdentityUUID("ffffffff-ffff-ffff-ffff-ffffffffffff")))
    }

    @Test
    fun `findByUUID returns null for features with null UUID`() {
        // Features have null UUIDs, so searching by any UUID should not match them
        assertNull(registry.findByUUID("session"))
    }

    // --- resolve (global) ---

    @Test
    fun `resolve by exact handle returns identity`() {
        val result = registry.resolve("session.chat1")
        assertNotNull(result)
        assertEquals("Chat Session 1", result.name)
    }

    @Test
    fun `resolve by UUID returns identity`() {
        val result = registry.resolve("00000000-0000-4000-a000-000000000001")
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `resolve by localHandle returns identity`() {
        val result = registry.resolve("chat1")
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `resolve by display name (case-insensitive) returns identity`() {
        val result = registry.resolve("alice")
        assertNotNull(result)
        assertEquals("core.alice", result.handle)
    }

    @Test
    fun `resolve by display name is case-insensitive`() {
        val result = registry.resolve("GEMINI CODER NR.1")
        assertNotNull(result)
        assertEquals("agent.gemini-coder-1", result.handle)
    }

    @Test
    fun `resolve returns null for unknown reference`() {
        assertNull(registry.resolve("totally-unknown"))
    }

    @Test
    fun `resolve prioritizes handle over UUID over localHandle over name`() {
        // The handle lookup is O(1) map key and takes priority.
        // Here "core" matches both as a handle and as a localHandle. Handle wins (same result).
        val result = registry.resolve("core")
        assertNotNull(result)
        assertEquals("core", result.handle)
    }

    // --- resolve (scoped) ---

    @Test
    fun `scoped resolve finds child by handle within parent scope`() {
        val result = registry.resolve("session.chat1", parentHandle = "session")
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `scoped resolve finds child by UUID within parent scope`() {
        val result = registry.resolve("00000000-0000-4000-a000-000000000001", parentHandle = "session")
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `scoped resolve finds child by localHandle within parent scope`() {
        val result = registry.resolve("chat1", parentHandle = "session")
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `scoped resolve finds child by display name within parent scope`() {
        val result = registry.resolve("Chat Session 1", parentHandle = "session")
        assertNotNull(result)
        assertEquals("session.chat1", result.handle)
    }

    @Test
    fun `scoped resolve does not return children from other parents`() {
        // "gemini-coder-1" is a child of "agent", not "session"
        val result = registry.resolve("gemini-coder-1", parentHandle = "session")
        assertNull(result)
    }

    @Test
    fun `scoped resolve returns null for unknown reference in scope`() {
        val result = registry.resolve("nonexistent", parentHandle = "session")
        assertNull(result)
    }

    // --- suggestMatches ---

    @Test
    fun `suggestMatches finds by partial name`() {
        val results = registry.suggestMatches("chat")
        assertEquals(2, results.size, "Should find chat1 and chat2.")
        assertTrue(results.all { it.localHandle.startsWith("chat") })
    }

    @Test
    fun `suggestMatches finds by partial handle`() {
        val results = registry.suggestMatches("gemini")
        assertEquals(1, results.size)
        assertEquals("agent.gemini-coder-1", results[0].handle)
    }

    @Test
    fun `suggestMatches is case-insensitive`() {
        val results = registry.suggestMatches("ALICE")
        assertEquals(1, results.size)
        assertEquals("core.alice", results[0].handle)
    }

    @Test
    fun `suggestMatches respects limit`() {
        // "session" matches: "session" (handle/localHandle), "session.chat1", "session.chat2"
        // (because their handles contain "session")
        val results = registry.suggestMatches("session", limit = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `suggestMatches with parentHandle scopes to children`() {
        val results = registry.suggestMatches("chat", parentHandle = "session")
        assertEquals(2, results.size)
        assertTrue(results.all { it.parentHandle == "session" })
    }

    @Test
    fun `suggestMatches with parentHandle excludes other parents`() {
        val results = registry.suggestMatches("gemini", parentHandle = "session")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `suggestMatches returns empty for no match`() {
        val results = registry.suggestMatches("zzz-nonexistent")
        assertTrue(results.isEmpty())
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `empty registry returns null for all resolve calls`() {
        val empty = emptyMap<String, Identity>()
        assertNull(empty.resolve("anything"))
        assertNull(empty.resolve("anything", "parent"))
        assertNull(empty.findByUUID(IdentityUUID("00000000-0000-4000-a000-000000000001")))
        assertTrue(empty.suggestMatches("anything").isEmpty())
    }

    @Test
    fun `stringIsUUID with exactly 36 chars but wrong format is rejected`() {
        // 36 chars but missing dashes in right places
        assertFalse(stringIsUUID("0000000000004000a000000000000001xxxx"))
    }

    @Test
    fun `requireUUID with empty string throws`() {
        assertFailsWith<IllegalArgumentException> {
            requireUUID("")
        }
    }
}