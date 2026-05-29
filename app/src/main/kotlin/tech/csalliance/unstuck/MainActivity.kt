package tech.csalliance.unstuck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.jan.supabase.auth.handleDeeplinks
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.surface.SyncWorker
import tech.csalliance.unstuck.surface.registerFcmToken
import tech.csalliance.unstuck.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val graph get() = (application as UnstuckApp).graph
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // OAuth / magic-link PKCE callback (unstuck://auth-callback).
        graph.provider?.client?.handleDeeplinks(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerFcmToken(application as UnstuckApp)
        SyncWorker.schedule(this)

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
