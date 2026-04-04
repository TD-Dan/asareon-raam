package asareon.raam.feature.filesystem

import asareon.raam.core.Action
import asareon.raam.core.Identity
import asareon.raam.core.PermissionGrant
import asareon.raam.core.PermissionLevel
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.test.TestHarness
import asareon.raam.util.BasePath
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Sandbox Isolation Tests for FileSystemFeature.
 *
 * Mandate: To verify that the identity-aware sandbox mechanism correctly
 * confines each originator to its designated directory and that no path
 * construction can break out of the sandbox boundary.
 *
 * The threat model assumes a malicious or buggy agent that controls the
 * `path` field in filesystem action payloads. The sandbox must hold
 * regardless of what path string the agent provides.
 *
 * Sandbox rules:
 *   - Identity with UUID → {APP_ZONE}/{feature}/{uuid}/workspace/
 *   - Feature-level originator (no UUID) → {APP_ZONE}/{feature}/
 */
class FileSystemFeatureT2SandboxIsolationTest {

    // ====================================================================
    // Test infrastructure
    // ====================================================================

    private val agentUuid1 = "aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa"
    private val agentUuid2 = "bbbbbbbb-2222-2222-2222-bbbbbbbbbbbb"
    private val agentHandle1 = "agent.attacker"
    private val agentHandle2 = "agent.victim"

    /**
     * Builds a test harness with two agent identities registered in the
     * identity registry. Both are children of the "agent" feature and
     * have filesystem:workspace permission granted.
     */
    private fun buildHarnessWithAgents(platform: FakePlatformDependencies): TestHarness {
        val feature = FileSystemFeature(platform)
        val agentPermissions = mapOf(
            "filesystem:workspace" to PermissionGrant(PermissionLevel.YES)
        )
        return TestEnvironment.create()
            .withFeature(feature)
            .withIdentity(Identity(
                uuid = agentUuid1,
                handle = agentHandle1,
                localHandle = "attacker",
                name = "Attacker Agent",
                parentHandle = "agent",
                permissions = agentPermissions
            ))
            .withIdentity(Identity(
                uuid = agentUuid2,
                handle = agentHandle2,
                localHandle = "victim",
                name = "Victim Agent",
                parentHandle = "agent",
                permissions = agentPermissions
            ))
            .build(platform = platform)
    }

    /** Expected sandbox path for an identity with a UUID. */
    private fun workspacePath(platform: FakePlatformDependencies, uuid: String): String {
        val appZone = platform.getBasePathFor(BasePath.APP_ZONE)
        return "$appZone/agent/$uuid/workspace"
    }

    /** Expected sandbox path for a feature-level originator (no UUID). */
    private fun featureSandboxPath(platform: FakePlatformDependencies, feature: String): String {
        val appZone = platform.getBasePathFor(BasePath.APP_ZONE)
        return "$appZone/$feature"
    }

    // ====================================================================
    // 1. Sandbox Path Resolution
    // ====================================================================

    @Test
    fun `identity with UUID writes to isolated workspace directory`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "notes.md")
            put("content", "my notes")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val expectedPath = "${workspacePath(platform, agentUuid1)}/notes.md"
            assertTrue(platform.fileExists(expectedPath),
                "File should be written inside the agent's workspace: $expectedPath. " +
                        "Written files: ${platform.writtenFiles.keys}")
            assertEquals("my notes", platform.readFileContent(expectedPath))
        }
    }

    @Test
    fun `feature-level originator without UUID writes to feature root`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "$agentUuid1/agent.json")
            put("content", "{}")
        })

        harness.runAndLogOnFailure {
            // "agent" is the feature handle — no UUID in registry
            harness.store.dispatch("agent", action)

            val expectedPath = "${featureSandboxPath(platform, "agent")}/$agentUuid1/agent.json"
            assertTrue(platform.fileExists(expectedPath),
                "Feature-level write should land in feature root. " +
                        "Written files: ${platform.writtenFiles.keys}")
        }
    }

    @Test
    fun `identity READ is confined to its own workspace`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        // Plant a file in agent1's workspace
        val filePath = "${workspacePath(platform, agentUuid1)}/secret.md"
        platform.writeFileContent(filePath, "agent1 secret")

        val action = Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
            put("path", "secret.md")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val response = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_RETURN_READ
            }
            assertNotNull(response, "Agent should receive a READ response.")
            assertEquals("agent1 secret",
                response.payload?.get("content")?.jsonPrimitive?.content,
                "Agent should be able to read its own file.")
        }
    }

    // ====================================================================
    // 2. Cross-Identity Isolation
    // ====================================================================

    @Test
    fun `agent cannot read another agent's files via its own READ`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        // Plant a secret in agent2's workspace
        val victimFile = "${workspacePath(platform, agentUuid2)}/secret.md"
        platform.writeFileContent(victimFile, "victim's secret data")

        // Agent1 tries to read "secret.md" — should look in agent1's workspace, not agent2's
        val action = Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
            put("path", "secret.md")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val response = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_RETURN_READ
            }
            assertNotNull(response)
            val content = response.payload?.get("content")
            assertTrue(
                content == null || content.toString() == "null",
                "Agent should NOT be able to read another agent's file. " +
                        "Content should be null (file not found in attacker's workspace)."
            )
        }
    }

    @Test
    fun `agent cannot overwrite another agent's files via WRITE`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        // Plant original content in agent2's workspace
        val victimFile = "${workspacePath(platform, agentUuid2)}/important.md"
        platform.writeFileContent(victimFile, "victim's important data")

        // Agent1 writes to "important.md" — should go to agent1's workspace
        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "important.md")
            put("content", "pwned by attacker")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            // Victim's file should be untouched
            assertEquals("victim's important data", platform.readFileContent(victimFile),
                "Victim's file must not be modified by another agent's WRITE.")

            // Attacker's file should be in attacker's workspace
            val attackerFile = "${workspacePath(platform, agentUuid1)}/important.md"
            assertTrue(platform.fileExists(attackerFile),
                "Attacker's write should land in its own workspace.")
            assertEquals("pwned by attacker", platform.readFileContent(attackerFile))
        }
    }

    @Test
    fun `agent cannot delete another agent's files via DELETE`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        // Plant a file in agent2's workspace
        val victimFile = "${workspacePath(platform, agentUuid2)}/target.md"
        platform.writeFileContent(victimFile, "do not delete me")

        // Agent1 tries to delete "target.md" — should only affect agent1's workspace
        val action = Action(ActionRegistry.Names.FILESYSTEM_DELETE_FILE, buildJsonObject {
            put("path", "target.md")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            assertTrue(platform.fileExists(victimFile),
                "Victim's file must survive a delete dispatched by another agent.")
        }
    }

    @Test
    fun `agent LIST only shows its own workspace contents`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        // Populate both workspaces
        val ws1 = workspacePath(platform, agentUuid1)
        val ws2 = workspacePath(platform, agentUuid2)
        platform.createDirectories(ws1)
        platform.createDirectories(ws2)
        platform.writeFileContent("$ws1/agent1-file.md", "mine")
        platform.writeFileContent("$ws2/agent2-file.md", "theirs")

        val action = Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
            put("path", "")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val response = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_RETURN_LIST
            }
            assertNotNull(response)
            val listingJson = response.payload?.get("listing")?.toString() ?: ""

            assertTrue(listingJson.contains("agent1-file.md"),
                "Agent should see its own files in listing.")
            assertFalse(listingJson.contains("agent2-file.md"),
                "Agent must NOT see another agent's files in listing.")
        }
    }

    // ====================================================================
    // 3. Path Traversal Attacks
    // ====================================================================

    @Test
    fun `agent cannot escape workspace via dot-dot traversal`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "../agent.json")
            put("content", "{\"pwned\": true}")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            assertTrue(platform.writtenFiles.isEmpty(),
                "No file should be written when path contains '..'")
            val log = platform.capturedLogs.find {
                it.message.contains("SECURITY") && it.message.contains("..")
            }
            assertNotNull(log, "A security log should be emitted for path traversal attempt.")
        }
    }

    @Test
    fun `agent cannot reach another agent's workspace via deep traversal`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
            put("path", "../../$agentUuid2/workspace/secret.md")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val log = platform.capturedLogs.find {
                it.message.contains("SECURITY") && it.message.contains("..")
            }
            assertNotNull(log, "Deep traversal targeting another agent must be blocked.")
        }
    }

    @Test
    fun `agent cannot escape workspace via dot-dot in DELETE`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        // Plant a config file outside the workspace
        val configPath = "${featureSandboxPath(platform, "agent")}/$agentUuid1/agent.json"
        platform.writeFileContent(configPath, "{\"name\": \"Attacker\"}")

        val action = Action(ActionRegistry.Names.FILESYSTEM_DELETE_FILE, buildJsonObject {
            put("path", "../agent.json")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            assertTrue(platform.fileExists(configPath),
                "Config file outside workspace must survive traversal delete attempt.")
        }
    }

    @Test
    fun `agent cannot escape workspace via dot-dot in DELETE_DIRECTORY`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject {
            put("path", "../../$agentUuid2/workspace")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val log = platform.capturedLogs.find {
                it.message.contains("SECURITY") && it.message.contains("..")
            }
            assertNotNull(log,
                "Traversal attempt to delete another agent's workspace directory must be blocked.")
        }
    }

    // ====================================================================
    // 4. Config File Protection
    // ====================================================================

    @Test
    fun `agent writing agent_json creates it inside workspace not at config level`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "agent.json")
            put("content", "{\"evil\": true}")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val workspaceFile = "${workspacePath(platform, agentUuid1)}/agent.json"
            val configFile = "${featureSandboxPath(platform, "agent")}/$agentUuid1/agent.json"

            assertTrue(platform.fileExists(workspaceFile),
                "File should be created inside the workspace: $workspaceFile")
            assertFalse(platform.fileExists(configFile),
                "File must NOT be created at the config level: $configFile")
        }
    }

    @Test
    fun `agent writing nvram_json creates it inside workspace not at config level`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "nvram.json")
            put("content", "{\"phase\": \"COMPROMISED\"}")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val workspaceFile = "${workspacePath(platform, agentUuid1)}/nvram.json"
            val configFile = "${featureSandboxPath(platform, "agent")}/$agentUuid1/nvram.json"

            assertTrue(platform.fileExists(workspaceFile),
                "nvram.json should land inside workspace.")
            assertFalse(platform.fileExists(configFile),
                "nvram.json must NOT be created at the config level.")
        }
    }

    // ====================================================================
    // 5. UUID Spoofing in Path
    // ====================================================================

    @Test
    fun `agent cannot embed another UUID in path to access their workspace`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "$agentUuid2/workspace/stolen.md")
            put("content", "planted evidence")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val attackerPath = "${workspacePath(platform, agentUuid1)}/$agentUuid2/workspace/stolen.md"
            val victimPath = "${workspacePath(platform, agentUuid2)}/stolen.md"

            assertFalse(platform.fileExists(victimPath),
                "UUID spoofing in path must not reach the victim's workspace.")

            if (platform.fileExists(attackerPath)) {
                assertEquals("planted evidence", platform.readFileContent(attackerPath),
                    "If the file was written, it should be confined to attacker's workspace subtree.")
            }
        }
    }

    // ====================================================================
    // 6. READ_MULTIPLE Isolation
    // ====================================================================

    @Test
    fun `READ_MULTIPLE only returns files from the requesting agent's workspace`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        platform.writeFileContent("${workspacePath(platform, agentUuid1)}/mine.md", "my content")
        platform.writeFileContent("${workspacePath(platform, agentUuid2)}/theirs.md", "their content")

        val action = Action(ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE, buildJsonObject {
            putJsonArray("paths") {
                add(JsonPrimitive("mine.md"))
                add(JsonPrimitive("theirs.md"))
            }
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val response = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT
            }
            assertNotNull(response)
            val contents = response.payload?.get("contents")?.toString() ?: ""

            assertTrue(contents.contains("my content"),
                "Agent should be able to read its own file via READ_MULTIPLE.")
            assertFalse(contents.contains("their content"),
                "Agent must NOT receive another agent's file content via READ_MULTIPLE.")
        }
    }

    // ====================================================================
    // 7. Rename Safety (UUID-based paths survive identity renames)
    // ====================================================================

    @Test
    fun `workspace path is UUID-based not handle-based`() {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithAgents(platform)

        val action = Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "test.md")
            put("content", "data")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(agentHandle1, action)

            val expectedPath = "${workspacePath(platform, agentUuid1)}/test.md"
            assertTrue(platform.fileExists(expectedPath),
                "Workspace path must use UUID ($agentUuid1), not handle ($agentHandle1).")

            val handleBasedPath = "${featureSandboxPath(platform, "agent")}/attacker/workspace/test.md"
            assertFalse(platform.fileExists(handleBasedPath),
                "Handle-based path must NOT be used. Paths must be UUID-based for rename safety.")
        }
    }
}