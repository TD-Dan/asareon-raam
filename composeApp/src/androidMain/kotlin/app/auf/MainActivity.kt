--- MODIFY androidMain/kotlin/app/auf/MainActivity.kt ---
package app.auf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.auf.ui.AUFTheme // <<< MODIFIED: Import our theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Pass the API key from BuildConfig to the StateManager.
        val stateManager = StateManager(BuildConfig.OPENAI_API_KEY)
        stateManager.loadCatalogue()

        setContent {
            // No need to wrap here, App() itself is now themed correctly.
            App(stateManager)
        }
    }
}

@Preview(name = "Light Theme Preview")
@Composable
fun AppAndroidPreviewLight() {
    AUFTheme(darkTheme = false) {
        // For the preview, a dummy key can be used as it won't be making real calls.
        App(StateManager("dummy_preview_key"))
    }
}

@Preview(name = "Dark Theme Preview")
@Composable
fun AppAndroidPreviewDark() {
    AUFTheme(darkTheme = true) {
        // For the preview, a dummy key can be used as it won't be making real calls.
        App(StateManager("dummy_preview_key"))
    }
}