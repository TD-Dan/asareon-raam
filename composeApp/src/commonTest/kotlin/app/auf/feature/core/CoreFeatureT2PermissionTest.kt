package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.PermissionGrant
import app.auf.core.PermissionLevel
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Core Tests for CoreFeature's permission management.
 *
 * Mandate (P-TEST-001, T2): To test the permission management actions
 * (SET_PERMISSION, SET_PERMISSIONS_BATCH) and the default permission
 * application workflow within a realistic TestEnvironment.
 */
class CoreFeatureT2PermissionTest {

    private fun seedIdentities(
        store: app.auf.core.Store,
        vararg featureHandles: String,
        extraRegistry: Map<String, Identity> = emptyMap()
    ) {
        val featureIdentities = featureHandles.associate { handle ->
            handle to Identity(
                uuid = null, localHandle = handle, handle = handle,
                name = handle.replaceFirstChar { it.uppercase() }, parentHandle = null
            )
        }
        store.updateIdentityRegistry { it + featureIdentities + extraRegistry }
    }

    // ================================================================
    // SET_PERMISSION — single grant
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION updates identity grant in registry`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core",
            extraRegistry = mapOf(
                "core.alice" to Identity(
                    uuid = "user-1", handle = "core.alice", localHandle = "alice",
                    name = "Alice", parentHandle = "core"
                )
            )
        )

        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "test:access")
                put("level", "YES")
            }
        ))
        runCurrent()

        val alice = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(alice)
        val grant = alice.permissions["test:access"]
        assertNotNull(grant, "The permission grant should be set on the identity.")
        assertEquals(PermissionLevel.YES, grant.level)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION dispatches PERMISSIONS_UPDATED broadcast`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core",
            extraRegistry = mapOf(
                "core.alice" to Identity(
                    uuid = "user-1", handle = "core.alice", localHandle = "alice",
                    name = "Alice", parentHandle = "core"
                )
            )
        )

        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "test:access")
                put("level", "YES")
            }
        ))
        runCurrent()

        val broadcast = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_PERMISSIONS_UPDATED
        }
        assertNotNull(broadcast, "PERMISSIONS_UPDATED should be broadcast after a successful SET_PERMISSION.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION with invalid level logs error and does not broadcast`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core",
            extraRegistry = mapOf(
                "core.alice" to Identity(
                    uuid = "user-1", handle = "core.alice", localHandle = "alice",
                    name = "Alice", parentHandle = "core"
                )
            )
        )

        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "test:access")
                put("level", "INVALID_LEVEL")
            }
        ))
        runCurrent()

        val errorLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("invalid level")
        }
        assertNotNull(errorLog, "An error should be logged for an invalid permission level.")

        val broadcast = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_PERMISSIONS_UPDATED
        }
        assertNull(broadcast, "No PERMISSIONS_UPDATED should be broadcast on failure.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION with unknown identity logs error`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "nonexistent.handle")
                put("permissionKey", "test:access")
                put("level", "YES")
            }
        ))
        runCurrent()

        val errorLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("not found in registry")
        }
        assertNotNull(errorLog, "An error should be logged when the target identity doesn't exist.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSION can revoke an existing grant`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core",
            extraRegistry = mapOf(
                "core.alice" to Identity(
                    uuid = "user-1", handle = "core.alice", localHandle = "alice",
                    name = "Alice", parentHandle = "core",
                    permissions = mapOf("test:access" to PermissionGrant(PermissionLevel.YES))
                )
            )
        )

        // Verify pre-condition
        assertEquals(PermissionLevel.YES,
            harness.store.state.value.identityRegistry["core.alice"]?.permissions?.get("test:access")?.level)

        // Revoke
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSION,
            buildJsonObject {
                put("identityHandle", "core.alice")
                put("permissionKey", "test:access")
                put("level", "NO")
            }
        ))
        runCurrent()

        val alice = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(alice)
        assertEquals(PermissionLevel.NO, alice.permissions["test:access"]?.level,
            "The permission should be revoked to NO.")
    }

    // ================================================================
    // SET_PERMISSIONS_BATCH — bulk grants
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSIONS_BATCH applies multiple grants in one action`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core",
            extraRegistry = mapOf(
                "core.alice" to Identity(
                    uuid = "user-1", handle = "core.alice", localHandle = "alice",
                    name = "Alice", parentHandle = "core"
                ),
                "core.bob" to Identity(
                    uuid = "user-2", handle = "core.bob", localHandle = "bob",
                    name = "Bob", parentHandle = "core"
                )
            )
        )

        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSIONS_BATCH,
            buildJsonObject {
                put("grants", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("identityHandle", "core.alice")
                        put("permissionKey", "test:read")
                        put("level", "YES")
                    })
                    add(buildJsonObject {
                        put("identityHandle", "core.bob")
                        put("permissionKey", "test:write")
                        put("level", "YES")
                    })
                })
            }
        ))
        runCurrent()

        val registry = harness.store.state.value.identityRegistry
        assertEquals(PermissionLevel.YES, registry["core.alice"]?.permissions?.get("test:read")?.level)
        assertEquals(PermissionLevel.YES, registry["core.bob"]?.permissions?.get("test:write")?.level)

        val broadcast = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_PERMISSIONS_UPDATED
        }
        assertNotNull(broadcast, "PERMISSIONS_UPDATED should be broadcast after batch update.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SET_PERMISSIONS_BATCH does not broadcast when all grants fail`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_SET_PERMISSIONS_BATCH,
            buildJsonObject {
                put("grants", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("identityHandle", "nonexistent")
                        put("permissionKey", "test:access")
                        put("level", "YES")
                    })
                })
            }
        ))
        runCurrent()

        val broadcast = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_PERMISSIONS_UPDATED
        }
        assertNull(broadcast, "No PERMISSIONS_UPDATED should be broadcast when all grants fail.")
    }

    // ================================================================
    // Default permissions applied at registration
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `default permissions are applied when identity is registered`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "agent")

        // Register an agent child — should inherit permissions from the "agent" feature
        // which received its grants from DefaultPermissions at boot.
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "mercury"); put("name", "Mercury") }
        ))
        runCurrent()

        val mercury = harness.store.state.value.identityRegistry["agent.mercury"]
        assertNotNull(mercury, "Agent identity should be registered.")

        // Children inherit from parent feature — check effective permissions, not explicit.
        val effective = harness.store.resolveEffectivePermissions(mercury)
        val fsGrant = effective["filesystem:workspace"]
        assertNotNull(fsGrant, "Agent child should inherit filesystem:workspace from the agent feature parent.")
        assertEquals(PermissionLevel.YES, fsGrant.level)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `default permissions do not overwrite existing explicit grants`() = runTest {
        val platform = FakePlatformDependencies("test")

        // Put Alice in CoreState.userIdentities WITH her explicit NO grant.
        // When ADD_USER_IDENTITY fires, handleSideEffects rebuilds all core.* registry
        // entries from userIdentities. Alice's explicit NO should be preserved — children
        // inherit from the parent feature, but explicit grants on the child take precedence.
        val alice = Identity(
            uuid = "user-1", handle = "core.alice", localHandle = "alice",
            name = "Alice", parentHandle = "core",
            permissions = mapOf("filesystem:workspace" to PermissionGrant(PermissionLevel.NO))
        )
        @Suppress("DEPRECATION")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", CoreState(
                lifecycle = AppLifecycle.RUNNING,
                userIdentities = listOf(alice),
                activeUserId = "core.alice"
            ))
            .build(platform = platform)

        // Trigger the legacy identity sync flow
        harness.store.dispatch("core", Action(
            ActionRegistry.Names.CORE_ADD_USER_IDENTITY,
            buildJsonObject { put("name", "Bob") }
        ))
        runCurrent()

        // Alice's explicit NO should be preserved — the legacy sync copies her
        // permissions directly from userIdentities to the registry.
        val aliceState = harness.store.state.value.identityRegistry["core.alice"]
        assertNotNull(aliceState, "Alice should still be in the registry after identity sync.")
        assertEquals(PermissionLevel.NO, aliceState.permissions["filesystem:workspace"]?.level,
            "Existing explicit grants should not be overwritten by defaults.")
    }

    // ================================================================
    // resolveEffectivePermissions — direct unit tests via the Store
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resolveEffectivePermissions returns merged grants from parent chain`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withIdentity(Identity(
                uuid = null, handle = "agent", localHandle = "agent",
                name = "Agent", parentHandle = null,
                permissions = mapOf(
                    "perm:a" to PermissionGrant(PermissionLevel.YES),
                    "perm:b" to PermissionGrant(PermissionLevel.NO)
                )
            ))
            .withIdentity(Identity(
                uuid = "agent-1", handle = "agent.mercury", localHandle = "mercury",
                name = "Mercury", parentHandle = "agent",
                permissions = mapOf(
                    "perm:b" to PermissionGrant(PermissionLevel.YES),  // Override parent's NO
                    "perm:c" to PermissionGrant(PermissionLevel.YES)   // New grant
                )
            ))
            .build(platform = platform)

        val mercury = harness.store.state.value.identityRegistry["agent.mercury"]!!
        val effective = harness.store.resolveEffectivePermissions(mercury)

        assertEquals(PermissionLevel.YES, effective["perm:a"]?.level,
            "perm:a should be inherited from parent.")
        assertEquals(PermissionLevel.YES, effective["perm:b"]?.level,
            "perm:b should be overridden by child's YES.")
        assertEquals(PermissionLevel.YES, effective["perm:c"]?.level,
            "perm:c should come from child's own grant.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resolveEffectivePermissions returns empty map for identity with no grants and no parent`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withIdentity(Identity(
                uuid = "orphan-1", handle = "orphan", localHandle = "orphan",
                name = "Orphan", parentHandle = null,
                permissions = emptyMap()
            ))
            .build(platform = platform)

        val orphan = harness.store.state.value.identityRegistry["orphan"]!!
        val effective = harness.store.resolveEffectivePermissions(orphan)

        assertTrue(effective.isEmpty(),
            "Identity with no grants and no parent should have empty effective permissions.")
    }
}