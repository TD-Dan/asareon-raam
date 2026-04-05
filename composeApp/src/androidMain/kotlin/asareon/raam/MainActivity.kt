package asareon.raam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import asareon.raam.ui.AUFTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // This setup is simplified for the Android entry point.
        // In a full build, this would be handled by a dependency injection framework.
        val stateManager = StateManager(BuildConfig.OPENAI_API_KEY)
        stateManager.loadCatalogue()

        setContent {
            // The App composable now manages its own theme, so no wrapper is needed here.
            App(stateManager)
        }
    }
}

@Preview(name = "Light Theme Preview")
@Composable
fun AppAndroidPreviewLight() {
    // The Previews now correctly use the new Material 3 AUFTheme.
    AUFTheme(darkTheme = false) {
        // For the preview, a dummy key can be used as it won't be making real calls.
        App(StateManager("dummy_preview_key"))
    }
}

@Preview(name = "Dark Theme Preview")
@Composable
fun AppAndroidPreviewDark() {
    // The Previews now correctly use the new Material 3 AUFTheme.
    AUFTheme(darkTheme = true) {
        // For the preview, a dummy key can be used as it won't be making real calls.
        App(StateManager("dummy_preview_key"))
    }
}