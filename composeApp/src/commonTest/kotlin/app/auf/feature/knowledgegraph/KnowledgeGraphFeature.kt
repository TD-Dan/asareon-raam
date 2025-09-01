package app.auf.feature.knowledgegraph

import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeGraphFeatureTest {

    // --- REDUCER TESTS ---

    @Test
    fun `reducer handles LoadGraphSuccess and correctly updates state`() = runTest {
        // ARRANGE
        val feature = KnowledgeGraphFeature(FakePlatformDependencies(), this) // 'this' is the TestScope
        val initialState = AppState()
        val personaHeader = HolonHeader("sage-1", "AI_Persona_Root", "Sage", "")
        val testHolon = Holon(HolonHeader("test-1", "Project", "Test", ""), payload = kotlinx.serialization.json.JsonNull)
        val loadResult = GraphLoadResult(
            holonGraph = listOf(testHolon),
            availableAiPersonas = listOf(personaHeader),
            determinedPersonaId = "sage-1",
            parsingErrors = listOf("A minor error.")
        )
        val action = KnowledgeGraphAction.LoadGraphSuccess(loadResult)

        // ACT
        val newState = feature.reducer(initialState, action)
        val featureState = newState.featureStates[feature.name] as? KnowledgeGraphState

        // ASSERT
        assertNotNull(featureState)
        assertEquals(1, featureState.holonGraph.size)
        assertEquals("test-1", featureState.holonGraph.first().header.id)
        assertEquals("sage-1", featureState.aiPersonaId)
        assertEquals(1, featureState.parsingErrors.size)
        assertFalse(featureState.isLoading)
        assertNull(featureState.fatalError)
    }

    @Test
    fun `reducer handles LoadGraphFailure`() = runTest {
        // ARRANGE
        val feature = KnowledgeGraphFeature(FakePlatformDependencies(), this)
        val initialState = AppState(featureStates = mapOf(feature.name to KnowledgeGraphState(isLoading = true)))
        val action = KnowledgeGraphAction.LoadGraphFailure("FATAL ERROR")

        // ACT
        val newState = feature.reducer(initialState, action)
        val featureState = newState.featureStates[feature.name] as? KnowledgeGraphState

        // ASSERT
        assertNotNull(featureState)
        assertFalse(featureState.isLoading)
        assertEquals("FATAL ERROR", featureState.fatalError)
    }

    @Test
    fun `reducer handles SelectAiPersona`() = runTest {
        // ARRANGE
        val feature = KnowledgeGraphFeature(FakePlatformDependencies(), this)
        val initialState = AppState(featureStates = mapOf(feature.name to KnowledgeGraphState(aiPersonaId = "old-id")))
        val action = KnowledgeGraphAction.SelectAiPersona("new-id")

        // ACT
        val newState = feature.reducer(initialState, action)
        val featureState = newState.featureStates[feature.name] as? KnowledgeGraphState

        // ASSERT
        assertNotNull(featureState)
        assertEquals("new-id", featureState.aiPersonaId)
    }

    @Test
    fun `reducer handles ToggleHolonActive correctly`() = runTest {
        // ARRANGE
        val feature = KnowledgeGraphFeature(FakePlatformDependencies(), this)
        val initialState = AppState(featureStates = mapOf(feature.name to KnowledgeGraphState(contextualHolonIds = setOf("holon-A"))))
        val addAction = KnowledgeGraphAction.ToggleHolonActive("holon-B")
        val removeAction = KnowledgeGraphAction.ToggleHolonActive("holon-A")

        // ACT 1: Add a holon
        val stateAfterAdd = feature.reducer(initialState, addAction)
        val featureStateAfterAdd = stateAfterAdd.featureStates[feature.name] as KnowledgeGraphState

        // ASSERT 1
        assertTrue(featureStateAfterAdd.contextualHolonIds.contains("holon-B"))
        assertEquals(2, featureStateAfterAdd.contextualHolonIds.size)

        // ACT 2: Remove a holon
        val stateAfterRemove = feature.reducer(stateAfterAdd, removeAction)
        val featureStateAfterRemove = stateAfterRemove.featureStates[feature.name] as KnowledgeGraphState

        // ASSERT 2
        assertFalse(featureStateAfterRemove.contextualHolonIds.contains("holon-A"))
        assertEquals(1, featureStateAfterRemove.contextualHolonIds.size)
    }

    // --- ASYNC & LIFECYCLE TESTS ---

    @Test
    fun `start lifecycle method dispatches LoadGraph`() = runTest {
        // ARRANGE
        val feature = KnowledgeGraphFeature(FakePlatformDependencies(), this)
        val store = FakeStore(AppState(), this, features = listOf(feature))

        // ACT
        feature.start(store)

        // ASSERT
        val dispatchedAction = store.dispatchedActions.firstOrNull()
        assertNotNull(dispatchedAction)
        assertIs<KnowledgeGraphAction.LoadGraph>(dispatchedAction)
    }

    @Test
    fun `start lifecycle method triggers the full async loading process`() = runTest {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies()
        val feature = KnowledgeGraphFeature(fakePlatform, this)
        val store = FakeStore(AppState(), this, features = listOf(feature))

        // Simulate the root persona file existing
        val personaId = "sage-20250726T213010Z"
        val personaDir = "holons/$personaId"
        val personaFile = "$personaDir/$personaId.json"
        fakePlatform.writeFileContent(personaFile, """
            {
              "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Sage", "summary": "" },
              "payload": {}
            }
        """.trimIndent())


        // ACT
        feature.start(store)
        runCurrent() // Execute the coroutines launched by start()

        // ASSERT
        // The start() method dispatches LoadGraph. The feature's internal logic
        // then launches a coroutine which reads the file and dispatches LoadGraphSuccess.
        assertEquals(2, store.dispatchedActions.size)

        val action1 = store.dispatchedActions[0]
        assertIs<KnowledgeGraphAction.LoadGraph>(action1)

        val action2 = store.dispatchedActions[1]
        assertIs<KnowledgeGraphAction.LoadGraphSuccess>(action2)
        assertEquals(personaId, action2.result.determinedPersonaId)
    }
}