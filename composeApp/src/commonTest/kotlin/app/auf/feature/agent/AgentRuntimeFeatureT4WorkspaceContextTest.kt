package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Focused tests for Workspace Context Collapse/Uncollapse.
 *
 * Isolates the workspace file loading pipeline introduced alongside HKG collapse:
 *   listing → READ_MULTIPLE → RETURN_FILES_CONTENT → SET_WORKSPACE_FILE_CONTENTS
 *   → evaluateFullContext (gate) → executeTurn → GATEWAY_GENERATE_CONTENT
 *
 * ## Test Tiers
 * - Section 1 (T1): Pure reducer tests for the new workspace state actions.
 * - Section 2 (T3): Integration tests for the full cognitive pipeline with workspace files.
 *
 * ## Known Bug Chain (motivation for these tests)
 * 1. FileSystemFeature.READ_MULTIPLE hardcoded correlationId to null → RETURN_FILES_CONTENT
 *    arrived without the "ws:" prefix → routing check failed → contents never stored →
 *    pendingWorkspaceFileReads stayed true → 10s timeout.
 * 2. Agent used "workspace:" prefix instead of "ws:" → override stored but never matched
 *    by WorkspaceContextFormatter.resolveCollapseState → file stayed COLLAPSED.
 */
class AgentRuntimeFeatureT4WorkspaceContextTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val feature = AgentRuntimeFeature(platform, scope)

    private val agentUUID = "b0000000-0000-0000-0000-000000000001"
    private val sessionUUID = "a0000000-0000-0000-0000-000000000001"

    private val agent = testAgent(agentUUID, "Test", modelProvider = "p", modelName = "m",
        subscribedSessionIds = listOf(sessionUUID),
        resources = mapOf("system_instruction" to "res-sys-instruction-v1")
    )

    private fun uid(id: String) = IdentityUUID(id)

    // =========================================================================
    // Section 1 — T1 Pure Reducer Tests
    //
    // Direct AgentRuntimeReducer calls. No Store, no side effects.
    // Verifies the three new workspace state actions work at the reducer level.
    // =========================================================================

    @Test
    fun `SET_WORKSPACE_LISTING stores raw listing on agent status`() {
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentUUID) to AgentStatusInfo()))
        val listing = buildJsonArray {
            add(buildJsonObject { put("path", "workspace/test.md"); put("isDirectory", false) })
        }
        val action = Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_LISTING, buildJsonObject {
            put("agentId", agentUUID); put("listing", listing)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val status = newState.agentStatuses[uid(agentUUID)]!!

        assertEquals(listing, status.transientWorkspaceListing)
        // Should not affect other workspace fields
        assertFalse(status.pendingWorkspaceFileReads)
        assertTrue(status.transientWorkspaceFileContents.isEmpty())
    }

    @Test
    fun `SET_PENDING_WORKSPACE_FILES sets pending flag`() {
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentUUID) to AgentStatusInfo()))
        val action = Action(ActionRegistry.Names.AGENT_SET_PENDING_WORKSPACE_FILES, buildJsonObject {
            put("agentId", agentUUID); put("pending", true)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertTrue(newState.agentStatuses[uid(agentUUID)]!!.pendingWorkspaceFileReads)
    }

    @Test
    fun `SET_WORKSPACE_FILE_CONTENTS stores contents and clears pending flag`() {
        val initialStatus = AgentStatusInfo(pendingWorkspaceFileReads = true)
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentUUID) to initialStatus))
        val contents = buildJsonObject {
            put("test.md", "# Hello World")
            put("src/main.kt", "fun main() {}")
        }
        val action = Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_FILE_CONTENTS, buildJsonObject {
            put("agentId", agentUUID); put("contents", contents)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val status = newState.agentStatuses[uid(agentUUID)]!!

        assertFalse(status.pendingWorkspaceFileReads, "Pending flag should be cleared")
        assertEquals(2, status.transientWorkspaceFileContents.size)
        assertEquals("# Hello World", status.transientWorkspaceFileContents["test.md"])
        assertEquals("fun main() {}", status.transientWorkspaceFileContents["src/main.kt"])
    }

    @Test
    fun `INITIATE_TURN clears all workspace transient state`() {
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.IDLE,
            transientWorkspaceListing = JsonArray(listOf(buildJsonObject { put("path", "x"); put("isDirectory", false) })),
            transientWorkspaceFileContents = mapOf("x" to "data"),
            pendingWorkspaceFileReads = true,
            contextGatheringStartedAt = 5000L
        )
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentUUID) to initialStatus))

        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", agentUUID); put("preview", false)
        })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val status = newState.agentStatuses[uid(agentUUID)]!!

        assertNull(status.transientWorkspaceListing, "Listing should be cleared on new turn")
        assertTrue(status.transientWorkspaceFileContents.isEmpty(), "File contents should be cleared on new turn")
        assertFalse(status.pendingWorkspaceFileReads, "Pending flag should be cleared on new turn")
        assertNull(status.contextGatheringStartedAt, "Context gathering timestamp should be cleared")
    }

    @Test
    fun `SET_STATUS to IDLE clears workspace transient state`() {
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.PROCESSING,
            processingSinceTimestamp = 1000L,
            transientWorkspaceListing = JsonArray(emptyList()),
            transientWorkspaceFileContents = mapOf("file.md" to "content"),
            pendingWorkspaceFileReads = true
        )
        val state = AgentRuntimeState(
            agents = mapOf(uid(agentUUID) to testAgent(agentUUID, "Test")),
            agentStatuses = mapOf(uid(agentUUID) to initialStatus)
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentUUID); put("status", "IDLE")
        })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val status = newState.agentStatuses[uid(agentUUID)]!!

        assertNull(status.transientWorkspaceListing, "Listing should be cleared on IDLE")
        assertTrue(status.transientWorkspaceFileContents.isEmpty(), "File contents should be cleared on IDLE")
        assertFalse(status.pendingWorkspaceFileReads, "Pending flag should be cleared on IDLE")
    }

    // =========================================================================
    // Section 2 — T3 Integration: Full Pipeline with Workspace Files
    //
    // Uses TestEnvironment with real FileSystemFeature to exercise the
    // complete workspace file loading flow.
    // =========================================================================

    @Test
    fun `full pipeline completes without expanded workspace files`() = runTest {
        // ARRANGE: Agent with no workspace collapse overrides (default = all collapsed).
        // This is the baseline case — workspace listing arrives, no files need reading,
        // gate opens immediately.
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent.identityUUID to agent),
                resources = testBuiltInResources()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Deliver a ledger response to trigger the cognitive cycle
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", agentUUID)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user"); put("rawContent", "Hello"); put("timestamp", 1000L)
                        })
                    })
                },
                targetRecipient = "agent"
            ))

            // ASSERT: Pipeline completed — gateway request dispatched
            val gatewayAction = harness.processedActions.find { it.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayAction, "GATEWAY_GENERATE_CONTENT should be dispatched when no workspace files are expanded")

            // ASSERT: No READ_MULTIPLE was dispatched (no expanded files)
            val readMultiple = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE }
            assertNull(readMultiple, "READ_MULTIPLE should NOT be dispatched when no workspace files are expanded")

            // ASSERT: SET_WORKSPACE_LISTING was dispatched
            val setListing = harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_SET_WORKSPACE_LISTING }
            assertNotNull(setListing, "SET_WORKSPACE_LISTING should be dispatched after listing response")
        }
    }

    @Test
    fun `full pipeline completes with expanded workspace file`() = runTest {
        // ARRANGE: Agent with a workspace file marked as EXPANDED in collapse overrides.
        // The pipeline should: LIST → store listing → READ_MULTIPLE → store contents → gate opens.

        // Pre-create the workspace file so FileSystemFeature can read it.
        // The workspace path is: {agentUUID}/workspace/test.md
        val workspaceFilePath = "$agentUUID/workspace/test.md"
        val workspaceFileContent = "# Test Document\nThis is test content."

        // Pre-seed collapse overrides so the pipeline knows to fetch this file
        val statusWithOverrides = AgentStatusInfo(
            contextCollapseOverrides = mapOf("ws:test.md" to CollapseState.EXPANDED)
        )

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent.identityUUID to agent),
                agentStatuses = mapOf(agent.identityUUID to statusWithOverrides),
                resources = testBuiltInResources()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Pre-create the workspace file via filesystem.WRITE so READ_MULTIPLE can find it
            harness.store.dispatch("agent", Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                put("path", workspaceFilePath)
                put("content", workspaceFileContent)
            }))

            // ACT: Deliver a ledger response to trigger the cognitive cycle
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", agentUUID)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user"); put("rawContent", "Hello"); put("timestamp", 1000L)
                        })
                    })
                },
                targetRecipient = "agent"
            ))

            // === DIAGNOSTIC: Trace each step of the pipeline ===

            // Step 1: SET_WORKSPACE_LISTING should have been dispatched
            val setListing = harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_SET_WORKSPACE_LISTING }
            assertNotNull(setListing, "Step 1 FAILED: SET_WORKSPACE_LISTING not dispatched. handleWorkspaceListingResponse may not have been called.")

            // Step 2: SET_PENDING_WORKSPACE_FILES should have been dispatched (files need reading)
            val setPending = harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_SET_PENDING_WORKSPACE_FILES }
            assertNotNull(setPending, "Step 2 FAILED: SET_PENDING_WORKSPACE_FILES not dispatched. Pipeline didn't detect expanded files.")
            assertEquals(true, setPending.payload?.get("pending")?.jsonPrimitive?.boolean,
                "Step 2 FAILED: pending should be true")

            // Step 3: READ_MULTIPLE should have been dispatched with ws: correlationId
            val readMultiple = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE }
            assertNotNull(readMultiple, "Step 3 FAILED: READ_MULTIPLE not dispatched. Pipeline didn't request expanded workspace files.")
            val readCorrelationId = readMultiple.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
            assertNotNull(readCorrelationId, "Step 3 FAILED: READ_MULTIPLE missing correlationId.")
            assertTrue(readCorrelationId.startsWith("ws:"),
                "Step 3 FAILED: READ_MULTIPLE correlationId should start with 'ws:', got: $readCorrelationId")

            // Step 4: RETURN_FILES_CONTENT should have been dispatched by FileSystemFeature
            val returnContent = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT }
            assertNotNull(returnContent, "Step 4 FAILED: RETURN_FILES_CONTENT not dispatched. FileSystemFeature may not have handled READ_MULTIPLE.")
            val returnCorrelationId = returnContent.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
            assertNotNull(returnCorrelationId,
                "Step 4 FAILED: RETURN_FILES_CONTENT has null correlationId! " +
                        "FileSystemFeature.READ_MULTIPLE is dropping the correlationId. " +
                        "This is the known bug — put(\"correlationId\", JsonNull) must be replaced with pass-through.")
            assertTrue(returnCorrelationId.startsWith("ws:"),
                "Step 4 FAILED: RETURN_FILES_CONTENT correlationId should start with 'ws:', got: $returnCorrelationId. " +
                        "FileSystemFeature is not passing through the correlationId from the request.")

            // Step 5: SET_WORKSPACE_FILE_CONTENTS should have been dispatched by the pipeline
            val setContents = harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_SET_WORKSPACE_FILE_CONTENTS }
            assertNotNull(setContents,
                "Step 5 FAILED: SET_WORKSPACE_FILE_CONTENTS not dispatched. " +
                        "handleWorkspaceFileContentsResponse may not have been called. " +
                        "Check: (a) RETURN_FILES_CONTENT routing in handleTargetedResponse, " +
                        "(b) handleTargetedAction routing to handleWorkspaceFileContentsResponse, " +
                        "(c) ws: correlationId check in handleWorkspaceFileContentsResponse.")

            // Step 6: Agent state should reflect completed workspace loading
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val finalStatus = finalState.agentStatuses[agent.identityUUID]
            assertNotNull(finalStatus, "Step 6 FAILED: No agent status found after pipeline run.")
            assertFalse(finalStatus.pendingWorkspaceFileReads,
                "Step 6 FAILED: pendingWorkspaceFileReads is still true. " +
                        "SET_WORKSPACE_FILE_CONTENTS reducer may not have cleared the flag.")

            // Step 7: Gateway request should have been dispatched (pipeline completed)
            val gatewayAction = harness.processedActions.find { it.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayAction,
                "Step 7 FAILED: GATEWAY_GENERATE_CONTENT not dispatched. " +
                        "The context gathering gate did not open. " +
                        "evaluateFullContext may not have been called after SET_WORKSPACE_FILE_CONTENTS, " +
                        "or the workspace ready check (transientWorkspaceListing != null && !pendingWorkspaceFileReads) failed.")
        }
    }

    @Test
    fun `workspace collapse override with wrong prefix does not trigger file loading`() = runTest {
        // ARRANGE: Agent with a collapse override using the WRONG prefix "workspace:" instead of "ws:".
        // This simulates the actual bug Meridian experienced.
        // The pipeline should NOT attempt to read any files — the override won't match.
        val statusWithBadOverride = AgentStatusInfo(
            contextCollapseOverrides = mapOf("workspace:test.md" to CollapseState.EXPANDED)
        )

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent.identityUUID to agent),
                agentStatuses = mapOf(agent.identityUUID to statusWithBadOverride),
                resources = testBuiltInResources()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", agentUUID)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user"); put("rawContent", "Hello"); put("timestamp", 1000L)
                        })
                    })
                },
                targetRecipient = "agent"
            ))

            // "workspace:" prefix should NOT match ws: convention — no file reads dispatched
            val readMultiple = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE }
            assertNull(readMultiple,
                "READ_MULTIPLE should NOT be dispatched when override uses wrong 'workspace:' prefix. " +
                        "WorkspaceContextFormatter.getExpandedFilePaths should only match 'ws:' keys.")

            // Pipeline should still complete (listing-only path, no pending files)
            val gatewayAction = harness.processedActions.find { it.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayAction,
                "Pipeline should complete even with wrong-prefix overrides — they're simply ignored.")
        }
    }

    // =========================================================================
    // Section 3 — WorkspaceContextFormatter Unit Tests
    //
    // Pure function tests for the new formatter.
    // =========================================================================

    @Test
    fun `parseListingEntries correctly parses flat workspace listing`() {
        val listing = buildJsonArray {
            add(buildJsonObject { put("path", "agent1/workspace/readme.md"); put("isDirectory", false) })
            add(buildJsonObject { put("path", "agent1/workspace/src"); put("isDirectory", true) })
            add(buildJsonObject { put("path", "agent1/workspace/src/main.kt"); put("isDirectory", false) })
        }

        val entries = WorkspaceContextFormatter.parseListingEntries(listing, "agent1/workspace")

        assertEquals(3, entries.size)

        val readme = entries.find { it.name == "readme.md" }
        assertNotNull(readme)
        assertEquals("readme.md", readme.relativePath)
        assertFalse(readme.isDirectory)
        assertNull(readme.parentPath, "Root-level file should have null parent")
        assertEquals(0, readme.depth)

        val srcDir = entries.find { it.name == "src" }
        assertNotNull(srcDir)
        assertEquals("src/", srcDir.relativePath)
        assertTrue(srcDir.isDirectory)
        assertNull(srcDir.parentPath, "Root-level directory should have null parent")

        val mainKt = entries.find { it.name == "main.kt" }
        assertNotNull(mainKt)
        assertEquals("src/main.kt", mainKt.relativePath)
        assertFalse(mainKt.isDirectory)
        assertEquals("src/", mainKt.parentPath, "File in src/ should have src/ as parent")
        assertEquals(1, mainKt.depth)
    }

    @Test
    fun `resolveCollapseState returns COLLAPSED by default and EXPANDED for overrides`() {
        val overrides = mapOf(
            "ws:readme.md" to CollapseState.EXPANDED,
            "ws:src/" to CollapseState.EXPANDED
        )

        assertEquals(CollapseState.EXPANDED, WorkspaceContextFormatter.resolveCollapseState("readme.md", overrides))
        assertEquals(CollapseState.EXPANDED, WorkspaceContextFormatter.resolveCollapseState("src/", overrides))
        assertEquals(CollapseState.COLLAPSED, WorkspaceContextFormatter.resolveCollapseState("other.txt", overrides),
            "Files without an override should default to COLLAPSED")
    }

    @Test
    fun `getExpandedFilePaths only returns files with ws prefix and matching listing`() {
        val listing = buildJsonArray {
            add(buildJsonObject { put("path", "a/workspace/readme.md"); put("isDirectory", false) })
            add(buildJsonObject { put("path", "a/workspace/notes.md"); put("isDirectory", false) })
            add(buildJsonObject { put("path", "a/workspace/src"); put("isDirectory", true) })
        }
        val entries = WorkspaceContextFormatter.parseListingEntries(listing, "a/workspace")

        val overrides = mapOf(
            "ws:readme.md" to CollapseState.EXPANDED,
            "ws:notes.md" to CollapseState.COLLAPSED,
            "ws:src/" to CollapseState.EXPANDED,          // Directory — should NOT be in result
            "ws:ghost.md" to CollapseState.EXPANDED,       // Not in listing — should NOT be in result
            "workspace:bad.md" to CollapseState.EXPANDED   // Wrong prefix — should NOT be in result
        )

        val expanded = WorkspaceContextFormatter.getExpandedFilePaths(entries, overrides)

        assertEquals(setOf("readme.md"), expanded,
            "Only files with 'ws:' prefix that exist in listing AND are EXPANDED should be returned")
    }

    @Test
    fun `buildIndexTree shows EXPANDED and COLLAPSED badges`() {
        val listing = buildJsonArray {
            add(buildJsonObject { put("path", "a/workspace/readme.md"); put("isDirectory", false) })
            add(buildJsonObject { put("path", "a/workspace/config.yaml"); put("isDirectory", false) })
        }
        val entries = WorkspaceContextFormatter.parseListingEntries(listing, "a/workspace")
        val overrides = mapOf("ws:readme.md" to CollapseState.EXPANDED)

        val index = WorkspaceContextFormatter.buildIndexTree(entries, overrides)

        assertTrue(index.contains("readme.md [EXPANDED]"), "Expanded file should show [EXPANDED] badge")
        assertTrue(index.contains("config.yaml [COLLAPSED]"), "Non-expanded file should show [COLLAPSED] badge")
    }

    @Test
    fun `buildFilesSection only includes EXPANDED files`() {
        val contents = mapOf(
            "readme.md" to "# README",
            "secret.md" to "should not appear"
        )
        val overrides = mapOf(
            "ws:readme.md" to CollapseState.EXPANDED,
            "ws:secret.md" to CollapseState.COLLAPSED
        )

        val filesSection = WorkspaceContextFormatter.buildFilesSection(contents, overrides)

        assertTrue(filesSection.contains("# README"), "Expanded file content should appear")
        assertFalse(filesSection.contains("should not appear"), "Collapsed file content should NOT appear")
        assertTrue(filesSection.contains("START OF FILE readme.md"), "File delimiter should include path")
    }

    @Test
    fun `getSubtreeDirectoryPaths returns all directories under target`() {
        val listing = buildJsonArray {
            add(buildJsonObject { put("path", "a/workspace/src"); put("isDirectory", true) })
            add(buildJsonObject { put("path", "a/workspace/src/util"); put("isDirectory", true) })
            add(buildJsonObject { put("path", "a/workspace/src/util/helpers"); put("isDirectory", true) })
            add(buildJsonObject { put("path", "a/workspace/src/main.kt"); put("isDirectory", false) })
            add(buildJsonObject { put("path", "a/workspace/docs"); put("isDirectory", true) })
        }
        val entries = WorkspaceContextFormatter.parseListingEntries(listing, "a/workspace")

        val subtree = WorkspaceContextFormatter.getSubtreeDirectoryPaths("src/", entries)

        assertEquals(setOf("src/", "src/util/", "src/util/helpers/"), subtree,
            "Should include target directory and all nested sub-directories, but NOT files or sibling directories")
    }
}