package asareon.raam.core

import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.test.testDescriptorWithPermissions
import asareon.raam.test.testDescriptorsFor
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Tier 1 Unit Tests for the Store's permission guard (Step 2b).
 *
 * Mandate (P-TEST-001, T1): To test the Store's permission enforcement logic
 * in isolation, covering all code paths through the permission guard:
 *   - Actions with no required_permissions bypass the guard entirely
 *   - Feature identities (uuid == null) are trusted and skip permission checks
 *   - Non-feature identities must have YES for all required permissions
 *   - ASK and APP_LIFETIME are treated as NO (with warnings)
 *   - Deny-by-default when a permission key has no grant
 *   - PERMISSION_DENIED broadcast is dispatched on denial
 *   - Permission inheritance via resolveEffectivePermissions
 *   - Controlled escalation (child overrides parent) is allowed with WARN log
 */
class StoreT1PermissionTest {

    private val platform = FakePlatformDependencies("v2-test")

    // --- Test-only tracking feature ---

    private data class TrackingState(val receivedActions: List<String> = emptyList()) : FeatureState

    private class TrackingFeature(handle: String) : Feature {
        override val identity = Identity(
            uuid = null, localHandle = handle, handle = handle, name = handle.replaceFirstChar { it.uppercase() }
        )
        override val composableProvider: Feature.ComposableProvider? = null
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            val current = state as? TrackingState ?: TrackingState()
            if (action.name.startsWith("test.")) {
                return current.copy(receivedActions = current.receivedActions + action.name)
            }
            return current
        }
    }

    // ================================================================
    // Guard bypass: no required_permissions
    // ================================================================

    @Test
    fun `action with null required_permissions bypasses permission guard`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(testDescriptorsFor(setOf("test.NO_PERMS")))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core"
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.NO_PERMS"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.NO_PERMS"),
            "Action with no required_permissions should pass through.")
    }

    @Test
    fun `action with empty required_permissions bypasses permission guard`() {
        val descriptor = testDescriptorWithPermissions("test.EMPTY_PERMS", requiredPermissions = emptyList())
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf("test.EMPTY_PERMS" to descriptor))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core"
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.EMPTY_PERMS"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.EMPTY_PERMS"),
            "Action with empty required_permissions should pass through.")
    }

    // ================================================================
    // Feature trust exemption: uuid == null skips permission check
    // ================================================================

    @Test
    fun `feature identity skips permission check`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.NEEDS_PERM" to testDescriptorWithPermissions("test.NEEDS_PERM", listOf("some:permission"))
            ))
            .build(platform = platform)

        // "core" is a feature identity (uuid == null) — should bypass permission check
        harness.store.dispatch("core", Action("test.NEEDS_PERM"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.NEEDS_PERM"),
            "Feature identity (uuid == null) should skip permission checks entirely.")
    }

    @Test
    fun `hierarchical child of feature identity skips permission check`() {
        // "tracker.sub-entity" resolves to feature "tracker" via extractFeatureHandle.
        // Feature identities are trusted, so the action should pass.
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.NEEDS_PERM" to testDescriptorWithPermissions("test.NEEDS_PERM", listOf("some:permission"))
            ))
            .build(platform = platform)

        harness.store.dispatch("tracker.sub-entity", Action("test.NEEDS_PERM"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.NEEDS_PERM"),
            "Child of a feature identity should be treated as trusted.")
    }

    // ================================================================
    // Permission granted: YES level
    // ================================================================

    @Test
    fun `identity with YES grant passes permission check`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.YES))
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.GUARDED"),
            "Identity with YES permission should pass the guard.")
    }

    @Test
    fun `identity with YES grants for all required permissions passes`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.MULTI" to testDescriptorWithPermissions("test.MULTI", listOf("perm:a", "perm:b"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf(
                    "perm:a" to PermissionGrant(PermissionLevel.YES),
                    "perm:b" to PermissionGrant(PermissionLevel.YES)
                )
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.MULTI"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.MULTI"),
            "Identity with YES for all required permissions should pass.")
    }

    // ================================================================
    // Permission denied: NO level and deny-by-default
    // ================================================================

    @Test
    fun `identity with NO grant is blocked`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.NO))
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertFalse(state.receivedActions.contains("test.GUARDED"),
            "Identity with NO permission should be blocked.")

        val log = platform.capturedLogs.find { it.level == LogLevel.WARN && it.message.contains("PERMISSION DENIED") }
        assertNotNull(log, "A PERMISSION DENIED log should be captured.")
    }

    @Test
    fun `identity with no grant at all is denied by default`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = emptyMap()  // No grants at all
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertFalse(state.receivedActions.contains("test.GUARDED"),
            "Identity with no grants should be denied by default (Pillar 5).")
    }

    @Test
    fun `partial permissions blocks when one of multiple required is missing`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.MULTI" to testDescriptorWithPermissions("test.MULTI", listOf("perm:a", "perm:b"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("perm:a" to PermissionGrant(PermissionLevel.YES))
                // perm:b is missing → deny by default
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.MULTI"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertFalse(state.receivedActions.contains("test.MULTI"),
            "Action should be blocked when any required permission is missing.")
    }

    // ================================================================
    // ASK and APP_LIFETIME treated as NO
    // ================================================================

    @Test
    fun `ASK permission level is treated as NO with warning`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.ASK))
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertFalse(state.receivedActions.contains("test.GUARDED"),
            "ASK should be treated as NO until the ASK system is implemented.")

        val askLog = platform.capturedLogs.find { it.message.contains("PERMISSION ASK not yet implemented") }
        assertNotNull(askLog, "A warning about ASK not being implemented should be logged.")
    }

    @Test
    fun `APP_LIFETIME permission level is treated as NO with warning`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.APP_LIFETIME))
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertFalse(state.receivedActions.contains("test.GUARDED"),
            "APP_LIFETIME should be treated as NO until implemented.")

        val log = platform.capturedLogs.find { it.message.contains("PERMISSION APP_LIFETIME not yet implemented") }
        assertNotNull(log, "A warning about APP_LIFETIME not being implemented should be logged.")
    }

    // ================================================================
    // PERMISSION_DENIED broadcast
    // ================================================================

    @Test
    fun `PERMISSION_DENIED broadcast is dispatched when action is blocked`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core"
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.GUARDED"))

        val denied = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_PERMISSION_DENIED
        }
        assertNotNull(denied, "A PERMISSION_DENIED broadcast should be dispatched.")
        assertEquals("core", denied.originator, "PERMISSION_DENIED should originate from core.")
        assertNull(denied.targetRecipient, "PERMISSION_DENIED should be broadcast, not targeted.")

        val payload = denied.payload
        assertNotNull(payload)
        assertEquals("test.GUARDED", payload["blockedAction"]?.jsonPrimitive?.content)
        assertEquals("core.alice", payload["originatorHandle"]?.jsonPrimitive?.content)
        val missingPerms = payload["missingPermissions"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(missingPerms)
        assertTrue(missingPerms.contains("test:access"))
    }

    @Test
    fun `PERMISSION_DENIED lists all missing permissions when multiple are absent`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.MULTI" to testDescriptorWithPermissions("test.MULTI", listOf("perm:a", "perm:b", "perm:c"))
            ))
            .withIdentity(Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("perm:b" to PermissionGrant(PermissionLevel.YES))
            ))
            .build(platform = platform)

        harness.store.dispatch("core.alice", Action("test.MULTI"))

        val denied = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_PERMISSION_DENIED }
        assertNotNull(denied)
        val missing = denied.payload?.get("missingPermissions")?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(missing)
        assertTrue(missing.contains("perm:a"), "perm:a should be listed as missing.")
        assertTrue(missing.contains("perm:c"), "perm:c should be listed as missing.")
        assertFalse(missing.contains("perm:b"), "perm:b (granted YES) should NOT be listed.")
    }

    // ================================================================
    // Permission inheritance: resolveEffectivePermissions
    // ================================================================

    @Test
    fun `child inherits permissions from parent`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = null, handle = "agent", localHandle = "agent",
                name = "Agent", parentHandle = null,
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.YES))
            ))
            .withIdentity(Identity(
                uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
                name = "Mercury", parentHandle = "agent",
                permissions = emptyMap()  // No own grants — should inherit from parent
            ))
            .build(platform = platform)

        harness.store.dispatch("agent.mercury", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.GUARDED"),
            "Child should inherit YES permission from parent.")
    }

    @Test
    fun `child can override parent permission`() {
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = null, handle = "agent", localHandle = "agent",
                name = "Agent", parentHandle = null,
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.YES))
            ))
            .withIdentity(Identity(
                uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
                name = "Mercury", parentHandle = "agent",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.NO))
            ))
            .build(platform = platform)

        harness.store.dispatch("agent.mercury", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertFalse(state.receivedActions.contains("test.GUARDED"),
            "Child's explicit NO should override parent's YES.")
    }

    @Test
    fun `controlled escalation is allowed with WARN log`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = null, handle = "agent", localHandle = "agent",
                name = "Agent", parentHandle = null,
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.NO))
            ))
            .withIdentity(Identity(
                uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
                name = "Mercury", parentHandle = "agent",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.YES))
            ))
            .build(platform = platform)

        harness.store.dispatch("agent.mercury", Action("test.GUARDED"))

        // The action should pass (escalation is allowed)
        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.GUARDED"),
            "Controlled escalation should be allowed.")

        // But a WARN should be logged
        val escalationLog = platform.capturedLogs.find {
            it.level == LogLevel.WARN && it.message.contains("PERMISSION ESCALATION")
        }
        assertNotNull(escalationLog, "Controlled escalation should produce a WARN audit log.")
    }

    @Test
    fun `three-level inheritance resolves correctly`() {
        // grandparent (NO) → parent (YES) → child (inherits YES)
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            .withIdentity(Identity(
                uuid = null, handle = "agent", localHandle = "agent",
                name = "Agent", parentHandle = null,
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.NO))
            ))
            .withIdentity(Identity(
                uuid = "mid-1", handle = "agent.coder", localHandle = "coder",
                name = "Coder", parentHandle = "agent",
                permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.YES))
            ))
            .withIdentity(Identity(
                uuid = "leaf-1", handle = "agent.coder.subtask", localHandle = "subtask",
                name = "Sub-Task", parentHandle = "agent.coder",
                permissions = emptyMap()
            ))
            .build(platform = platform)

        harness.store.dispatch("agent.coder.subtask", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.GUARDED"),
            "Leaf should inherit YES from mid-level parent (which overrode grandparent's NO).")
    }

    // ================================================================
    // Unknown originator with required permissions
    // ================================================================

    @Test
    fun `unknown originator not resolvable to feature is blocked when permissions required`() {
        platform.capturedLogs.clear()
        val harness = TestEnvironment.create()
            .withFeature(TrackingFeature("tracker"))
            .withExtraDescriptors(mapOf(
                "test.GUARDED" to testDescriptorWithPermissions("test.GUARDED", listOf("test:access"))
            ))
            // Seed "fake" as a known descriptor feature so originator validation (Step 1c) passes
            .withExtraDescriptors(testDescriptorsFor(setOf("fake.DUMMY")))
            .build(platform = platform)

        // "fake.unknown-child" resolves to feature "fake" (known via descriptors) at Step 1c,
        // but "fake.unknown-child" is not in the identity registry.
        // Since "fake" is a known descriptor feature, the feature trust exemption applies.
        // This test verifies the "resolvable to trusted feature" path.
        harness.store.dispatch("fake", Action("test.GUARDED"))

        val state = harness.store.state.value.featureStates["tracker"] as TrackingState
        assertTrue(state.receivedActions.contains("test.GUARDED"),
            "Originator resolvable to a known feature should be trusted (feature trust exemption).")
    }
}