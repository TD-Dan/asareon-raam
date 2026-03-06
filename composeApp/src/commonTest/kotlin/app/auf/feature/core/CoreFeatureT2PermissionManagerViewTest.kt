package app.auf.feature.core

import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Tests for the Permission Manager View's state computation and
 * integration with the Store's permission system.
 *
 * These tests verify:
 *   - SET_PERMISSION dispatched from the view correctly updates the registry
 *   - Feature identities are shown in the view alongside children
 *   - Effective permissions via inheritance from parent features
 *   - Toggle behavior: YES→NO and NO→YES round-trips
 *   - Persistence is triggered after permission changes from the view
 *   - Escalation detection (child explicitly exceeds parent)
 *   - Danger-level cell tinting for YES grants
 */
class CoreFeatureT2PermissionManagerViewTest {

    // ================================================================
    // SET_PERMISSION toggle dispatched from view
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `toggling permission YES to NO via SET_PERMISSION updates registry`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.YES))
            )
        )}

        // Simulate the view dispatching SET_PERMISSION (toggle OFF)
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "filesystem:workspace")
                put("level", "NO")
            }
        ))
        runCurrent()

        val alice = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(alice)
        assertEquals(PermissionLevel.NO, alice.permissions["filesystem:workspace"]?.level,
            "Toggling should change the permission from YES to NO.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `toggling permission NO to YES via SET_PERMISSION updates registry`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.NO))
            )
        )}

        // Simulate the view dispatching SET_PERMISSION (toggle ON)
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "filesystem:workspace")
                put("level", "YES")
            }
        ))
        runCurrent()

        val alice = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(alice)
        assertEquals(PermissionLevel.YES, alice.permissions["filesystem:workspace"]?.level,
            "Toggling should change the permission from NO to YES.")
    }

    // ================================================================
    // View state derivation: all identities shown, features first
    // ================================================================

    @Test
    fun `all identities shown with features sorted first`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "session" to Identity(uuid = null, localHandle = "session", handle = "session", name = "Session"),
            "core.alice" to Identity(uuid = "user-1", handle = "core.alice", localHandle = "alice", name = "Alice", parentHandle = "core"),
            "agent.mercury" to Identity(uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury", name = "Mercury", parentHandle = "agent")
        )}

        val registry = harness.store.state.value.identityRegistry
        val allIdentities = registry.values
            .sortedWith(compareBy<Identity> { it.uuid != null }.thenBy { it.handle })

        // Features (uuid == null) should come first
        val features = allIdentities.takeWhile { it.uuid == null }
        val children = allIdentities.dropWhile { it.uuid == null }

        assertTrue(features.isNotEmpty(), "Features should be included in the view.")
        assertTrue(children.isNotEmpty(), "Children should be included in the view.")
        assertTrue(features.all { it.uuid == null }, "Features should be sorted first.")
        assertTrue(children.all { it.uuid != null }, "Children should come after features.")
    }

    // ================================================================
    // Effective permissions: inheritance from parent feature
    // ================================================================

    @Test
    fun `child inherits permissions from parent feature`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(
                uuid = null, localHandle = "core", handle = "core", name = "Core",
                permissions = mapOf("core:read" to PermissionGrant(PermissionLevel.YES))
            ),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = emptyMap()
            )
        )}

        val alice = harness.store.state.value.identityRegistry["core.alice"]!!
        val effective = harness.store.resolveEffectivePermissions(alice)

        assertEquals(PermissionLevel.YES, effective["core:read"]?.level,
            "Alice should inherit core:read YES from the core feature parent.")
    }

    @Test
    fun `child explicit grant overrides parent feature permission`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(
                uuid = null, localHandle = "core", handle = "core", name = "Core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.YES))
            ),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.NO))
            )
        )}

        val alice = harness.store.state.value.identityRegistry["core.alice"]!!
        val effective = harness.store.resolveEffectivePermissions(alice)

        assertEquals(PermissionLevel.NO, effective["filesystem:workspace"]?.level,
            "Alice's explicit NO should override parent feature's YES.")
    }

    // ================================================================
    // Escalation detection (used for warning icon in UI)
    // ================================================================

    @Test
    fun `escalation detected when child explicitly exceeds parent feature level`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val parentIdentity = Identity(
            uuid = null, localHandle = "agent", handle = "agent", name = "Agent",
            permissions = mapOf("gateway:generate" to PermissionGrant(PermissionLevel.NO))
        )
        val childIdentity = Identity(
            uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
            name = "Mercury", parentHandle = "agent",
            permissions = mapOf("gateway:generate" to PermissionGrant(PermissionLevel.YES))
        )

        harness.store.updateIdentityRegistry { it + mapOf(
            "agent" to parentIdentity,
            "agent.mercury" to childIdentity
        )}

        val parentEffective = harness.store.resolveEffectivePermissions(parentIdentity)
        val childExplicit = childIdentity.permissions["gateway:generate"]
        val parentLevel = parentEffective["gateway:generate"]?.level ?: PermissionLevel.NO
        assertNotNull(childExplicit)
        assertTrue(childExplicit.level > parentLevel,
            "Child's explicit YES should be detected as escalation above parent's NO.")
    }

    @Test
    fun `no escalation when child inherits from parent feature`() {
        // This is the key test for the new model: parent feature has YES,
        // child has no explicit grant, inherits YES → NOT escalated.
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val childIdentity = Identity(
            uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
            name = "Mercury", parentHandle = "agent",
            permissions = emptyMap()  // Inherits from parent — no explicit grant
        )

        harness.store.updateIdentityRegistry { it + mapOf(
            "agent" to Identity(
                uuid = null, localHandle = "agent", handle = "agent", name = "Agent",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.YES))
            ),
            "agent.mercury" to childIdentity
        )}

        // Inherited permissions don't count as escalation
        val childExplicit = childIdentity.permissions["filesystem:workspace"]
        assertNull(childExplicit,
            "No explicit grant → no escalation. Child inherits naturally from parent feature.")
    }

    @Test
    fun `no escalation when child explicit grant matches parent feature level`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val parentIdentity = Identity(
            uuid = null, localHandle = "agent", handle = "agent", name = "Agent",
            permissions = mapOf("gateway:generate" to PermissionGrant(PermissionLevel.YES))
        )
        val childIdentity = Identity(
            uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
            name = "Mercury", parentHandle = "agent",
            permissions = mapOf("gateway:generate" to PermissionGrant(PermissionLevel.YES))
        )

        harness.store.updateIdentityRegistry { it + mapOf(
            "agent" to parentIdentity,
            "agent.mercury" to childIdentity
        )}

        val parentEffective = harness.store.resolveEffectivePermissions(parentIdentity)
        val childExplicit = childIdentity.permissions["gateway:generate"]
        val parentLevel = parentEffective["gateway:generate"]?.level ?: PermissionLevel.NO
        assertNotNull(childExplicit)
        assertFalse(childExplicit.level > parentLevel,
            "No escalation when child and parent have the same level.")
    }

    // ================================================================
    // Cell tint: YES cells tinted by danger level, NO cells transparent
    // ================================================================

    @Test
    fun `YES cell should be tinted based on danger level`() {
        // This tests the view's rendering logic:
        // when isChecked=true, the cell background should use the danger-level tint.
        // We verify the data that drives the decision.
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.YES))
            )
        )}

        val alice = harness.store.state.value.identityRegistry["core.alice"]!!
        val effective = harness.store.resolveEffectivePermissions(alice)
        val effectiveLevel = effective["filesystem:workspace"]?.level ?: PermissionLevel.NO
        val isChecked = effectiveLevel == PermissionLevel.YES

        assertTrue(isChecked,
            "Cell with YES grant should be checked — and thus tinted by danger level.")
    }

    @Test
    fun `NO cell should not be tinted`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.NO))
            )
        )}

        val alice = harness.store.state.value.identityRegistry["core.alice"]!!
        val effective = harness.store.resolveEffectivePermissions(alice)
        val effectiveLevel = effective["filesystem:workspace"]?.level ?: PermissionLevel.NO
        val isChecked = effectiveLevel == PermissionLevel.YES

        assertFalse(isChecked,
            "Cell with NO grant should not be checked — and thus not tinted.")
    }

    // ================================================================
    // Persistence triggered from view toggle
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION from view triggers FILESYSTEM_WRITE with permissions in payload`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(app.auf.feature.filesystem.FileSystemFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.YES))
            )
        )}

        // Simulate the view toggle
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "gateway:generate")
                put("level", "YES")
            }
        ))
        runCurrent()

        // Verify persistence was triggered
        val writeAction = harness.processedActions.findLast {
            it.name == ActionRegistry.Names.FILESYSTEM_WRITE
        }
        assertNotNull(writeAction, "A FILESYSTEM_WRITE should be dispatched to persist permission changes.")

        val content = writeAction.payload?.get("content")?.jsonPrimitive?.content ?: ""
        assertTrue(content.contains("gateway:generate"),
            "Persisted content should include the newly granted permission.")
        assertTrue(content.contains("filesystem:workspace"),
            "Persisted content should preserve existing permissions.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `permissions survive adding a new user identity`() = runTest {
        val platform = FakePlatformDependencies("test")

        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(app.auf.feature.filesystem.FileSystemFeature(platform))
            .withInitialState("core", CoreState(
                lifecycle = AppLifecycle.RUNNING,
                activeUserId = "core.alice"
            ))
            .build(platform = platform)

        // Seed Alice in the registry
        harness.store.updateIdentityRegistry { it + ("core.alice" to Identity(
            uuid = "user-1", handle = "core.alice", localHandle = "alice",
            name = "Alice", parentHandle = "core"
        ))}

        // Set a permission on Alice via the view
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "gateway:generate")
                put("level", "YES")
            }
        ))
        runCurrent()

        // Verify permission is set
        val aliceAfterSet = harness.store.state.value.identityRegistry["core.alice"]
        assertEquals(PermissionLevel.YES, aliceAfterSet?.permissions?.get("gateway:generate")?.level,
            "Permission should be set after SET_PERMISSION.")

        // Now add a new user — Alice's permissions should be unaffected
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_ADD_USER_IDENTITY,
            buildJsonObject { put("name", "Bob") }
        ))
        runCurrent()

        // Verify Alice's permission survives
        val aliceAfterAdd = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(aliceAfterAdd, "Alice should still be in the registry after adding Bob.")
        assertEquals(PermissionLevel.YES, aliceAfterAdd.permissions["gateway:generate"]?.level,
            "Alice's permission should survive adding a new user identity.")

        // Verify Bob was added
        val bob = harness.store.state.value.identityRegistry.values
            .find { it.name == "Bob" }
        assertNotNull(bob, "Bob should be in the registry.")
        assertEquals("core", bob.parentHandle)
    }

    // ================================================================
    // Multiple identities in the matrix
    // ================================================================

    @Test
    fun `effective permissions computed independently per identity`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(uuid = null, localHandle = "core", handle = "core", name = "Core"),
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.YES))
            ),
            "core.bob" to Identity(
                uuid = "user-2", handle = "core.bob", localHandle = "bob",
                name = "Bob", parentHandle = "core",
                permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.NO))
            )
        )}

        val alice = harness.store.state.value.identityRegistry["core.alice"]!!
        val bob = harness.store.state.value.identityRegistry["core.bob"]!!

        val aliceEffective = harness.store.resolveEffectivePermissions(alice)
        val bobEffective = harness.store.resolveEffectivePermissions(bob)

        assertEquals(PermissionLevel.YES, aliceEffective["filesystem:workspace"]?.level)
        assertEquals(PermissionLevel.NO, bobEffective["filesystem:workspace"]?.level,
            "Each identity's effective permissions should be computed independently.")
    }

    // ================================================================
    // Feature identities receive DefaultPermissions at boot
    // ================================================================

    @Test
    fun `feature identities have default permissions after boot`() {
        // initFeatureLifecycles() applies DefaultPermissions to feature identities.
        // The core feature should have the grants defined in DefaultPermissions.
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val core = harness.store.state.value.identityRegistry["core"]
        assertNotNull(core, "Core feature should be in the registry after boot.")

        // DefaultPermissions grants "core" several permissions
        val defaults = DefaultPermissions.grantsFor("core")
        assertTrue(defaults.isNotEmpty(), "DefaultPermissions should define grants for 'core'.")

        for ((key, grant) in defaults) {
            assertEquals(grant.level, core.permissions[key]?.level,
                "Feature 'core' should have default permission '$key' = ${grant.level} after boot.")
        }
    }

    @Test
    fun `child inherits permissions from feature parent with no explicit grants`() {
        // The whole point of the new model: children inherit from their parent
        // feature's permissions instead of getting explicit per-child grants.
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        // Core should have defaults applied at boot
        harness.store.updateIdentityRegistry { it + mapOf(
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = emptyMap()  // No explicit grants — inherits from core
            )
        )}

        val alice = harness.store.state.value.identityRegistry["core.alice"]!!
        val effective = harness.store.resolveEffectivePermissions(alice)

        // Alice should inherit core's default permissions
        val coreDefaults = DefaultPermissions.grantsFor("core")
        for ((key, grant) in coreDefaults) {
            assertEquals(grant.level, effective[key]?.level,
                "Alice should inherit '$key' = ${grant.level} from core feature parent.")
        }
    }

    // ================================================================
    // Feature permission edits via SET_PERMISSION
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION on feature identity updates registry and persists`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(app.auf.feature.filesystem.FileSystemFeature(platform))
            .build(platform = platform)

        // Toggle a permission on the core feature itself
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core")
                put("permissionKey", "filesystem:workspace")
                put("level", "NO")
            }
        ))
        runCurrent()

        val core = harness.store.state.value.identityRegistry["core"]
        assertNotNull(core)
        assertEquals(PermissionLevel.NO, core.permissions["filesystem:workspace"]?.level,
            "Feature permission should be updated in registry.")

        // Persistence should include ALL identities
        val writeAction = harness.processedActions.findLast {
            it.name == ActionRegistry.Names.FILESYSTEM_WRITE
        }
        assertNotNull(writeAction, "FILESYSTEM_WRITE should be dispatched to persist feature permission changes.")
        val content = writeAction.payload?.get("content")?.jsonPrimitive?.content ?: ""
        assertTrue(content.contains("\"handle\":\"core\""),
            "Persisted content should include the core feature identity with its permissions.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `changing feature permission affects child effective permissions`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { it + mapOf(
            "core.alice" to Identity(
                uuid = "user-1", handle = "core.alice", localHandle = "alice",
                name = "Alice", parentHandle = "core",
                permissions = emptyMap()
            )
        )}

        // Verify Alice inherits the default
        val aliceBefore = harness.store.state.value.identityRegistry["core.alice"]!!
        val effectiveBefore = harness.store.resolveEffectivePermissions(aliceBefore)
        assertEquals(PermissionLevel.YES, effectiveBefore["filesystem:workspace"]?.level,
            "Alice should initially inherit YES from core.")

        // Revoke on the core feature
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core")
                put("permissionKey", "filesystem:workspace")
                put("level", "NO")
            }
        ))
        runCurrent()

        // Alice should now inherit NO
        val aliceAfter = harness.store.state.value.identityRegistry["core.alice"]!!
        val effectiveAfter = harness.store.resolveEffectivePermissions(aliceAfter)
        assertEquals(PermissionLevel.NO, effectiveAfter["filesystem:workspace"]?.level,
            "Alice should now inherit NO after core feature permission was revoked.")
    }
}