package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * ## Tier 3 Integration Test: Agent Avatar Lifecycle x Session Feature
 *
 * **Mandate:** Verify cross-feature integration between AgentRuntimeFeature and SessionFeature
 * for avatar card posting, updating, cleanup, and session startup readiness.
 *
 * **Test Scope:**
 * 1. Avatar posted to session when updateAgentAvatars fires with valid session state
 * 2. Startup race: avatars reconciled when SESSION_SESSION_NAMES_UPDATED arrives (sessions loaded)
 * 3. Zombie cleanup when agent unsubscribes from a session
 * 4. Avatar senderId uses agent's identity handle (Phase 4 compliance)
 * 5. Graceful no-op when subscribed session doesn't exist in SessionState
 * 6. Avatar posted to multiple subscribed sessions
 * 7. SESSION_FEATURE_READY fires exactly once (zero sessions, multiple sessions)
 *
 * **Cross-Feature Flow:**
 * - AgentAvatarLogic.updateAgentAvatars → SESSION_POST (deferred)
 * - SessionFeature.reducer processes SESSION_POST → LedgerEntry created
 * - SessionFeature.handleSideEffects persists + broadcasts SESSION_MESSAGE_POSTED
 */
class AgentRuntimeFeatureT3AvatarSessionTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val json = Json { ignoreUnknownKeys = true }

    // --- Test Fixtures ---

    private val sessionLocalHandle = "session-1"
    private val testSession = testSession(id = "session-uuid-1", name = "Session 1")

    private val session2LocalHandle = "session-2"
    private val testSession2 = testSession(id = "session-uuid-2", name = "Session 2")

    private val agentId = "agent-1"
    private val agent = testAgent(
        id = agentId,
        name = "Avatar Test Agent",
        modelProvider = "mock",
        modelName = "mock-gpt",
        subscribedSessionIds = listOf(sessionLocalHandle)
    )

    // --- Helpers ---

    private fun buildHarness(
        agents: Map<String, AgentInstance> = mapOf(agentId to agent),
        sessions: Map<String, app.auf.feature.session.Session> = mapOf(sessionLocalHandle to testSession),
        agentStatuses: Map<String, AgentStatusInfo> = emptyMap(),
        agentAvatarCardIds: Map<String, Map<String, String>> = emptyMap()
    ) = TestEnvironment.create()
        .withFeature(AgentRuntimeFeature(platform, scope))
        .withFeature(SessionFeature(platform, scope))
        .withFeature(FileSystemFeature(platform))
        .withInitialState("agent", AgentRuntimeState(
            agents = agents,
            resources = AgentDefaults.builtInResources,
            agentStatuses = agentStatuses,
            agentAvatarCardIds = agentAvatarCardIds
        ))
        .withInitialState("session", SessionState(
            sessions = sessions,
            activeSessionLocalHandle = sessions.keys.firstOrNull(),
            sessionOrder = SessionState.deriveSessionOrder(sessions)
        ))
        .build(platform = platform)

    /** Finds all avatar-specific SESSION_POST actions (identified by partial_view_key metadata). */
    private fun List<Action>.avatarPosts(): List<Action> = filter { action ->
        action.name == ActionRegistry.Names.SESSION_POST &&
                action.payload?.get("metadata")?.jsonObject
                    ?.get("partial_view_key")?.jsonPrimitive?.content == "agent.avatar"
    }

    /** Finds all SESSION_DELETE_MESSAGE actions. */
    private fun List<Action>.deleteMessages(): List<Action> = filter { action ->
        action.name == ActionRegistry.Names.SESSION_DELETE_MESSAGE
    }

    // ========================================================================
    // TEST 1: Avatar posted to session on direct updateAgentAvatars call
    // ========================================================================

    @Test
    fun `avatar card posted to subscribed session`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            // Direct call — the same path used by AGENT_AGENT_LOADED, AGENT_UPDATE_CONFIG, etc.
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            // ASSERT: Avatar POST dispatched and processed
            val avatarPosts = harness.processedActions.avatarPosts()
            assertTrue(avatarPosts.isNotEmpty(), "At least one avatar card should be posted")

            val post = avatarPosts.first()
            assertEquals(
                sessionLocalHandle,
                post.payload?.get("session")?.jsonPrimitive?.content,
                "Avatar should target the subscribed session's localHandle"
            )

            // ASSERT: Avatar added to session ledger
            val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
            val session = sessionState?.sessions?.get(sessionLocalHandle)
            assertNotNull(session, "Session should exist in state")
            val avatarEntry = session.ledger.find {
                it.metadata?.get("partial_view_key")?.jsonPrimitive?.content == "agent.avatar"
            }
            assertNotNull(avatarEntry, "Avatar ledger entry should exist in session")
        }
    }

    // ========================================================================
    // TEST 2: senderId is agent's identity handle (Phase 4 compliance)
    // ========================================================================

    @Test
    fun `avatar senderId uses agent identity handle`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            val avatarPost = harness.processedActions.avatarPosts().firstOrNull()
            assertNotNull(avatarPost, "Avatar post should be dispatched")

            val senderId = avatarPost.payload?.get("senderId")?.jsonPrimitive?.content
            assertEquals(
                agent.identity.handle,
                senderId,
                "Avatar senderId must be the agent's handle ('${agent.identity.handle}'), not UUID"
            )
            // Verify it's NOT the UUID
            assertNotEquals(
                agentId,
                senderId,
                "Avatar senderId must not be the raw agent UUID"
            )
        }
    }

    // ========================================================================
    // TEST 3: Avatar metadata contains correct agent status
    // ========================================================================

    @Test
    fun `avatar metadata reflects agent status`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.PROCESSING)

            val avatarPost = harness.processedActions.avatarPosts().firstOrNull()
            assertNotNull(avatarPost, "Avatar post should be dispatched")

            val metadata = avatarPost.payload?.get("metadata")?.jsonObject
            assertNotNull(metadata, "Avatar metadata should be present")
            assertEquals(
                "PROCESSING",
                metadata["agentStatus"]?.jsonPrimitive?.content,
                "Metadata should reflect the dispatched status"
            )
            assertEquals(
                true,
                metadata["is_transient"]?.jsonPrimitive?.boolean,
                "Avatar entries must be transient (excluded from persistence)"
            )
            assertEquals(
                true,
                metadata["render_as_partial"]?.jsonPrimitive?.boolean,
                "Avatar entries must be rendered as partial views"
            )
        }
    }

    // ========================================================================
    // TEST 4: Avatar posted to multiple subscribed sessions
    // ========================================================================

    @Test
    fun `avatar posted to all subscribed sessions`() = runTest {
        val multiAgent = testAgent(
            id = agentId,
            name = "Avatar Test Agent",
            modelProvider = "mock",
            modelName = "mock-gpt",
            subscribedSessionIds = listOf(sessionLocalHandle, session2LocalHandle)
        )

        val harness = buildHarness(
            agents = mapOf(agentId to multiAgent),
            sessions = mapOf(
                sessionLocalHandle to testSession,
                session2LocalHandle to testSession2
            )
        )

        harness.runAndLogOnFailure {
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            val avatarPosts = harness.processedActions.avatarPosts()
            val targetedSessions = avatarPosts.map {
                it.payload?.get("session")?.jsonPrimitive?.content
            }.toSet()

            assertTrue(
                targetedSessions.contains(sessionLocalHandle),
                "Avatar should be posted to first subscribed session"
            )
            assertTrue(
                targetedSessions.contains(session2LocalHandle),
                "Avatar should be posted to second subscribed session"
            )
        }
    }

    // ========================================================================
    // TEST 5: Zombie cleanup — old avatar deleted when new one posted
    // ========================================================================

    @Test
    fun `old avatar card deleted when avatar is updated`() = runTest {
        val existingMessageId = "old-avatar-msg-1"
        val harness = buildHarness(
            agentAvatarCardIds = mapOf(
                agentId to mapOf(sessionLocalHandle to existingMessageId)
            )
        )

        harness.runAndLogOnFailure {
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            // ASSERT: Old avatar message deleted
            val deleteActions = harness.processedActions.deleteMessages()
            val deleteOld = deleteActions.find {
                it.payload?.get("messageId")?.jsonPrimitive?.content == existingMessageId
            }
            assertNotNull(deleteOld, "Old avatar card should be deleted")
            assertEquals(
                sessionLocalHandle,
                deleteOld.payload?.get("session")?.jsonPrimitive?.content,
                "Delete should target the correct session"
            )

            // ASSERT: New avatar posted
            val avatarPosts = harness.processedActions.avatarPosts()
            assertTrue(avatarPosts.isNotEmpty(), "New avatar card should be posted")
        }
    }

    // ========================================================================
    // TEST 6: Zombie cleanup — avatar removed from unsubscribed session
    // ========================================================================

    @Test
    fun `zombie avatar cleaned up from unsubscribed session`() = runTest {
        // Agent is subscribed to session-1 only, but has a stale avatar in session-2
        val harness = buildHarness(
            sessions = mapOf(
                sessionLocalHandle to testSession,
                session2LocalHandle to testSession2
            ),
            agentAvatarCardIds = mapOf(
                agentId to mapOf(
                    sessionLocalHandle to "msg-session-1",
                    session2LocalHandle to "zombie-msg-session-2"
                )
            )
        )

        harness.runAndLogOnFailure {
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            // ASSERT: Zombie avatar in session-2 deleted
            val deleteActions = harness.processedActions.deleteMessages()
            val zombieDelete = deleteActions.find {
                it.payload?.get("messageId")?.jsonPrimitive?.content == "zombie-msg-session-2"
            }
            assertNotNull(zombieDelete, "Zombie avatar in unsubscribed session should be deleted")
            assertEquals(
                session2LocalHandle,
                zombieDelete.payload?.get("session")?.jsonPrimitive?.content,
                "Zombie delete should target the unsubscribed session"
            )
        }
    }

    // ========================================================================
    // TEST 7: Graceful no-op when session doesn't exist
    // ========================================================================

    @Test
    fun `avatar post to nonexistent session fails gracefully`() = runTest {
        // Agent subscribed to "session-1" but SessionState has no sessions
        val harness = buildHarness(sessions = emptyMap())

        harness.runAndLogOnFailure {
            // Should not throw — just log an error
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            // ASSERT: SESSION_POST was dispatched (avatar logic fires regardless)
            val avatarPosts = harness.processedActions.avatarPosts()
            assertTrue(avatarPosts.isNotEmpty(), "Avatar logic should still dispatch SESSION_POST")

            // ASSERT: Session feature logged the resolution failure
            val errorLog = platform.capturedLogs.find {
                it.message.contains("Could not resolve session") && it.message.contains(sessionLocalHandle)
            }
            assertNotNull(errorLog, "Session feature should log resolution failure for missing session")

            // ASSERT: No ledger entry created (session doesn't exist)
            val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
            assertTrue(
                sessionState?.sessions?.isEmpty() ?: true,
                "No sessions should exist — POST should have been rejected"
            )
        }
    }

    // ========================================================================
    // TEST 8: Startup race — SESSION_SESSION_FEATURE_READY reconciles avatars
    // ========================================================================

    @Test
    fun `avatars posted when SESSION_SESSION_FEATURE_READY fires`() = runTest {
        // Simulates the startup race: agents are loaded but sessions weren't ready yet.
        // When SessionFeature broadcasts SESSION_FEATURE_READY (sessions are in the map),
        // AgentRuntimeFeature posts avatars for all active agents.
        val harness = buildHarness(
            sessions = mapOf(sessionLocalHandle to testSession)
        )

        harness.runAndLogOnFailure {
            // Simulate: SessionFeature signals that sessions are loaded and available
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_FEATURE_READY,
                buildJsonObject {
                    put("sessionCount", 1)
                }
            ))

            // ASSERT: Avatar posted to session (reconciliation happened)
            val avatarPosts = harness.processedActions.avatarPosts()
            assertTrue(
                avatarPosts.isNotEmpty(),
                "SESSION_SESSION_FEATURE_READY should trigger avatar posting for active agents"
            )

            val post = avatarPosts.first()
            assertEquals(
                sessionLocalHandle,
                post.payload?.get("session")?.jsonPrimitive?.content,
                "Avatar should target the now-available session"
            )

            // ASSERT: No resolution errors
            val resolutionError = platform.capturedLogs.find {
                it.message.contains("Could not resolve session")
            }
            assertNull(resolutionError, "No session resolution errors should occur")
        }
    }

    // ========================================================================
    // TEST 9: SESSION_SESSION_NAMES_UPDATED does NOT trigger avatar posting
    // ========================================================================

    @Test
    fun `session names update does not trigger avatar posting`() = runTest {
        // Verifies clean separation: SESSION_SESSION_NAMES_UPDATED handles sovereign sessions,
        // SESSION_SESSION_FEATURE_READY handles avatars. No cross-contamination.
        val harness = buildHarness(
            sessions = mapOf(sessionLocalHandle to testSession)
        )

        harness.runAndLogOnFailure {
            // Simulate: SESSION_SESSION_NAMES_UPDATED fires (sovereign session logic only)
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED,
                buildJsonObject {
                    put("names", buildJsonObject {
                        put(sessionLocalHandle, testSession.identity.name)
                    })
                }
            ))

            // ASSERT: No avatar posts (this signal is not for avatars)
            val avatarPosts = harness.processedActions.avatarPosts()
            assertTrue(
                avatarPosts.isEmpty(),
                "SESSION_SESSION_NAMES_UPDATED should NOT trigger avatar posting"
            )
        }
    }

    // ========================================================================
    // TEST 10: Inactive agent does not post avatars
    // ========================================================================

    @Test
    fun `inactive agent does not post avatar on session availability`() = runTest {
        val inactiveAgent = testAgent(
            id = agentId,
            name = "Avatar Test Agent",
            modelProvider = "mock",
            modelName = "mock-gpt",
            subscribedSessionIds = listOf(sessionLocalHandle),
            isAgentActive = false
        )

        val harness = buildHarness(agents = mapOf(agentId to inactiveAgent))

        harness.runAndLogOnFailure {
            // Simulate session readiness
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_FEATURE_READY,
                buildJsonObject {
                    put("sessionCount", 1)
                }
            ))

            // ASSERT: No avatar posted for inactive agent
            val avatarPosts = harness.processedActions.avatarPosts()
            assertTrue(
                avatarPosts.isEmpty(),
                "Inactive agent should not have avatars posted on SESSION_FEATURE_READY"
            )
        }
    }

    // ========================================================================
    // TEST 11: AVATAR_MOVED action updates avatar tracking state
    // ========================================================================

    @Test
    fun `avatar card ID tracked in agent state after post`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            AgentAvatarLogic.updateAgentAvatars(agentId, harness.store, AgentStatus.IDLE)

            // ASSERT: AVATAR_MOVED dispatched with the new message ID
            val avatarMoved = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_AVATAR_MOVED
            }
            assertNotNull(avatarMoved, "AVATAR_MOVED should be dispatched to track the card ID")
            assertEquals(agentId, avatarMoved.payload?.get("agentId")?.jsonPrimitive?.content)
            assertEquals(sessionLocalHandle, avatarMoved.payload?.get("sessionId")?.jsonPrimitive?.content)

            val trackedMessageId = avatarMoved.payload?.get("messageId")?.jsonPrimitive?.content
            assertNotNull(trackedMessageId, "AVATAR_MOVED must include the new messageId")

            // ASSERT: State reflects the tracked card
            val agentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
            val cardIds = agentState?.agentAvatarCardIds?.get(agentId)
            assertNotNull(cardIds, "Agent should have avatar card IDs tracked")
            assertEquals(trackedMessageId, cardIds[sessionLocalHandle], "Tracked message ID should match AVATAR_MOVED payload")
        }
    }

    // ========================================================================
    // TEST 12: SESSION_FEATURE_READY fires exactly once — no sessions on disk
    // ========================================================================

    @Test
    fun `SESSION_FEATURE_READY fires once when no sessions exist on disk`() = runTest {
        // Harness with SessionFeature only (no FileSystemFeature — we simulate responses).
        // Lifecycle set to INITIALIZING so SYSTEM_STARTING is permitted by the Store.
        val harness = TestEnvironment.create()
            .withFeature(SessionFeature(platform, scope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // 1. Trigger startup — SessionFeature sets startupLoadingActive, pendingStartupOps = 1
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))

            // 2. Simulate filesystem response: empty root listing (no UUID folders, no files)
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST,
                payload = buildJsonObject {
                    putJsonArray("listing") {} // empty directory
                },
                targetRecipient = "session"
            ))

            // ASSERT: Exactly one SESSION_FEATURE_READY
            val readyEvents = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_SESSION_FEATURE_READY
            }
            assertEquals(
                1, readyEvents.size,
                "SESSION_FEATURE_READY should fire exactly once with an empty disk, got ${readyEvents.size}"
            )

            // ASSERT: Payload reports 0 sessions
            assertEquals(
                0,
                readyEvents.first().payload?.get("sessionCount")?.jsonPrimitive?.int,
                "sessionCount should be 0 when no sessions exist on disk"
            )
        }
    }

    // ========================================================================
    // TEST 13: SESSION_FEATURE_READY fires exactly once — multiple disk sessions
    // ========================================================================

    @Test
    fun `SESSION_FEATURE_READY fires once when multiple sessions loaded from disk`() = runTest {
        // Same minimal harness — we replay the full filesystem conversation manually.
        val harness = TestEnvironment.create()
            .withFeature(SessionFeature(platform, scope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Pre-serialize the two test sessions for file-read simulation.
            val session1Content = json.encodeToString(testSession)
            val session2Content = json.encodeToString(testSession2)

            // 1. Trigger startup
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))

            // 2. Root listing → two UUID folders (no dots → treated as directories)
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST,
                payload = buildJsonObject {
                    putJsonArray("listing") {
                        add(buildJsonObject { put("path", "session-uuid-1"); put("isDirectory", true) })
                        add(buildJsonObject { put("path", "session-uuid-2"); put("isDirectory", true) })
                    }
                },
                targetRecipient = "session"
            ))

            // 3. Sub-listing for folder 1 → one .json session file
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST,
                payload = buildJsonObject {
                    putJsonArray("listing") {
                        add(buildJsonObject {
                            put("path", "session-uuid-1/$sessionLocalHandle.json")
                            put("isDirectory", false)
                        })
                    }
                },
                targetRecipient = "session"
            ))

            // 4. Sub-listing for folder 2 → one .json session file
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST,
                payload = buildJsonObject {
                    putJsonArray("listing") {
                        add(buildJsonObject {
                            put("path", "session-uuid-2/$session2LocalHandle.json")
                            put("isDirectory", false)
                        })
                    }
                },
                targetRecipient = "session"
            ))

            // No SESSION_FEATURE_READY yet — two file reads are still pending
            val prematureReady = harness.processedActions.count {
                it.name == ActionRegistry.Names.SESSION_SESSION_FEATURE_READY
            }
            assertEquals(0, prematureReady, "SESSION_FEATURE_READY must not fire while file reads are pending")

            // 5. File read for session 1
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
                payload = buildJsonObject {
                    put("subpath", "session-uuid-1/$sessionLocalHandle.json")
                    put("content", session1Content)
                },
                targetRecipient = "session"
            ))

            // Still not ready — one file read remains
            val afterFirstRead = harness.processedActions.count {
                it.name == ActionRegistry.Names.SESSION_SESSION_FEATURE_READY
            }
            assertEquals(0, afterFirstRead, "SESSION_FEATURE_READY must not fire until ALL file reads complete")

            // 6. File read for session 2
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
                payload = buildJsonObject {
                    put("subpath", "session-uuid-2/$session2LocalHandle.json")
                    put("content", session2Content)
                },
                targetRecipient = "session"
            ))

            // ASSERT: Exactly one SESSION_FEATURE_READY after all files loaded
            val readyEvents = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_SESSION_FEATURE_READY
            }
            assertEquals(
                1, readyEvents.size,
                "SESSION_FEATURE_READY must fire exactly once after all disk sessions load, got ${readyEvents.size}"
            )

            // ASSERT: Payload reports both sessions
            assertEquals(
                2,
                readyEvents.first().payload?.get("sessionCount")?.jsonPrimitive?.int,
                "sessionCount should reflect all loaded sessions"
            )

            // ASSERT: Both sessions are in SessionState
            val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
            assertNotNull(sessionState, "Session state should exist")
            assertTrue(
                sessionState.sessions.containsKey(sessionLocalHandle),
                "Session 1 should be in state"
            )
            assertTrue(
                sessionState.sessions.containsKey(session2LocalHandle),
                "Session 2 should be in state"
            )
        }
    }
}