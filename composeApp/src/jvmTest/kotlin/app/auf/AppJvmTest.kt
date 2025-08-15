// FILE: composeApp/src/jvmTest/kotlin/app/auf/AppJvmTest.kt
package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull

/**
 * A smoke test to validate the application's Dependency Injection setup.
 * If this test passes, it confirms that all major components can be instantiated
 * with their dependencies correctly.
 *
 * @version 2.2
 * @since 2025-08-15
 */
class AppJvmTest {

    private lateinit var stateManager: StateManager
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeGatewayManager: FakeGatewayManager
    private lateinit var fakeBackupManager: FakeBackupManager
    private lateinit var fakeGraphLoader: FakeGraphLoader
    private lateinit var fakeActionExecutor: FakeActionExecutor
    private lateinit var importExportViewModel: ImportExportViewModel

    @BeforeTest
    fun setup() {
        // --- 1. Instantiate the single fake platform dependency ---
        // This object will be shared across all components that need it.
        fakePlatform = FakePlatformDependencies()

        // --- 2. Instantiate all fakes that rely on the single platform fake ---
        fakeGatewayManager = FakeGatewayManager() // Doesn't need platform access
        fakeBackupManager = FakeBackupManager(fakePlatform)
        fakeGraphLoader = FakeGraphLoader(fakePlatform)
        fakeActionExecutor = FakeActionExecutor(fakePlatform)

        // The real ViewModel uses a fake manager, which also gets the shared fake platform
        // --- FIX IS HERE: The FakeImportExportManager is now correctly injected with the shared fakePlatform. ---
        val fakeImportExportManager = FakeImportExportManager(fakePlatform)
        importExportViewModel = ImportExportViewModel(fakeImportExportManager)

        // --- 3. Instantiate the real StateManager with all fakes injected ---
        stateManager = StateManager(
            gatewayManager = fakeGatewayManager,
            backupManager = fakeBackupManager,
            graphLoader = fakeGraphLoader,
            actionExecutor = fakeActionExecutor,
            importExportViewModel = importExportViewModel,
            platform = fakePlatform, // The real StateManager also gets the shared platform dependency
            initialSettings = UserSettings(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined) // Use Unconfined for immediate test execution
        )
    }

    @Test
    fun `StateManager should instantiate successfully with all fake dependencies`() {
        // This test's only purpose is to ensure the DI setup is valid.
        // If the setup() function completes without crashing, the test's primary goal is met.
        // We add a simple assertion to make the test's success explicit.
        assertNotNull(stateManager, "StateManager should be successfully instantiated.")
    }
}