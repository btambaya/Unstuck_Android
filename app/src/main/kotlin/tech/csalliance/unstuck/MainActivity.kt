package tech.csalliance.unstuck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jan.supabase.auth.handleDeeplinks
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val graph get() = (application as UnstuckApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // OAuth / magic-link PKCE callback (unstuck://auth-callback).
        graph.provider?.client?.handleDeeplinks(intent)
        setContent {
            UnstuckTheme {
                AppRoot(graph)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        graph.provider?.client?.handleDeeplinks(intent)
    }
}
