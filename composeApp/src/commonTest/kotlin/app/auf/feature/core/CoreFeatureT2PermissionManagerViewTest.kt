package app.auf.feature.core

import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Tests for the Permission Manager View's state computation and
 * integration with the Store's permission system.
 *
 * These tests verify:
 *   - SET_PERMISSION dispatched from the view correctly updates the registry
 *   - Effective permissions are computed correctly for display (inherited, explicit, escalated)
 *   - Toggle behavior: YES→NO and NO→YES round-trips
 *   - Persistence is triggered after permission changes from the view
 *   - View state derivation: editable identities filter, effective perms map, parent perms map
 *   - Escalation detection logic
 *   - Domain grouping of permission declarations
 */
class PermissionManagerViewTest {

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
    // View state derivation: editable identities filtering
    // ================================================================

    @Test
    fun `only identities with non-null uuid are editable`() {
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
        val editableIdentities = registry.values
            .filter { it.uuid != null }
            .sortedBy { it.handle }

        assertEquals(2, editableIdentities.size,
            "Only non-feature identities (uuid != null) should be editable.")
        assertEquals("agent.mercury", editableIdentities[0].handle)
        assertEquals("core.alice", editableIdentities[1].handle)
    }

    // ================================================================
    // Effective permissions map for display
    // ================================================================

    @Test
    fun `effective permissions include inherited grants from parent`() {
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
            "Alice should inherit core:read from the core feature parent.")
    }

    @Test
    fun `effective permissions show explicit grant overriding parent`() {
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
            "Alice's explicit NO should override parent's YES.")
    }

    // ================================================================
    // Escalation detection (used for warning icon in UI)
    // ================================================================

    @Test
    fun `escalation is detected when child grant exceeds parent effective level`() {
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
        val childEffective = harness.store.resolveEffectivePermissions(childIdentity)

        // Verify escalation: child has YES but parent has NO
        val childExplicit = childIdentity.permissions["gateway:generate"]
        val parentLevel = parentEffective["gateway:generate"]?.level ?: PermissionLevel.NO
        assertNotNull(childExplicit)
        assertTrue(childExplicit.level > parentLevel,
            "Child's YES should be detected as an escalation above parent's NO.")
    }

    @Test
    fun `no escalation when child grant matches parent effective level`() {
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

    @Test
    fun `no escalation when child has no explicit grant (inherited only)`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val childIdentity = Identity(
            uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
            name = "Mercury", parentHandle = "agent",
            permissions = emptyMap()  // No explicit grants — only inherited
        )

        harness.store.updateIdentityRegistry { it + mapOf(
            "agent" to Identity(
                uuid = null, localHandle = "agent", handle = "agent", name = "Agent",
                permissions = mapOf("gateway:generate" to PermissionGrant(PermissionLevel.YES))
            ),
            "agent.mercury" to childIdentity
        )}

        // Escalation requires an explicit grant on the child — inherited doesn't count
        val childExplicit = childIdentity.permissions["gateway:generate"]
        assertNull(childExplicit,
            "No explicit grant means no escalation (inherited permissions are not escalations).")
    }

    // ================================================================
    // Inherited state detection (used for gray background in UI)
    // ================================================================

    @Test
    fun `inherited state is detected when effective is YES but no explicit grant exists`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val childIdentity = Identity(
            uuid = "user-1", handle = "core.alice", localHandle = "alice",
            name = "Alice", parentHandle = "core",
            permissions = emptyMap()
        )

        harness.store.updateIdentityRegistry { it + mapOf(
            "core" to Identity(
                uuid = null, localHandle = "core", handle = "core", name = "Core",
                permissions = mapOf("core:read" to PermissionGrant(PermissionLevel.YES))
            ),
            "core.alice" to childIdentity
        )}

        val effective = harness.store.resolveEffectivePermissions(childIdentity)
        val effectiveLevel = effective["core:read"]?.level ?: PermissionLevel.NO
        val explicitGrant = childIdentity.permissions["core:read"]
        val isInherited = explicitGrant == null && effectiveLevel != PermissionLevel.NO

        assertTrue(isInherited,
            "Permission should be detected as inherited when effective=YES but no explicit grant.")
    }

    @Test
    fun `not inherited when identity has explicit grant`() {
        val childIdentity = Identity(
            uuid = "user-1", handle = "core.alice", localHandle = "alice",
            name = "Alice", parentHandle = "core",
            permissions = mapOf("core:read" to PermissionGrant(PermissionLevel.YES))
        )

        val explicitGrant = childIdentity.permissions["core:read"]
        val isInherited = explicitGrant == null

        assertFalse(isInherited,
            "Permission should NOT be detected as inherited when the identity has an explicit grant.")
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

        val content = writeAction.payload?.get("content")?.toString() ?: ""
        assertTrue(content.contains("gateway:generate"),
            "Persisted content should include the newly granted permission.")
        assertTrue(content.contains("filesystem:workspace"),
            "Persisted content should preserve existing permissions.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `permissions survive legacy identity flow after SET_PERMISSION`() = runTest {
        val platform = FakePlatformDependencies("test")
        val alice = Identity(
            uuid = "user-1", handle = "core.alice", localHandle = "alice",
            name = "Alice", parentHandle = "core"
        )

        @Suppress("DEPRECATION")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(app.auf.feature.filesystem.FileSystemFeature(platform))
            .withInitialState("core", CoreState(
                lifecycle = AppLifecycle.RUNNING,
                userIdentities = listOf(alice),
                activeUserId = "core.alice"
            ))
            .build(platform = platform)

        // First: set a permission via the view
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

        // Now trigger a legacy identity operation (e.g., adding a new user)
        // This used to wipe permissions because it rebuilt from CoreState.userIdentities
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_ADD_USER_IDENTITY,
            buildJsonObject { put("name", "Bob") }
        ))
        runCurrent()

        // Verify Alice's permission survives the legacy sync
        val aliceAfterSync = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(aliceAfterSync, "Alice should still be in the registry after legacy identity sync.")
        assertEquals(PermissionLevel.YES, aliceAfterSync.permissions["gateway:generate"]?.level,
            "Alice's permission set via SET_PERMISSION should survive the legacy identity sync flow.")
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
}