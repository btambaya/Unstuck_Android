package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlin.time.Duration.Companion.seconds

// Builds + holds the shared SupabaseClient. PKCE flow (required for the OAuth /
// magic-link deep-link callback) + the custom `unstuck://auth-callback`
// redirect. The app injects the real URL + anon key via SyncConfig (kept out
// of source — see secrets.properties / BuildConfig).

data class SyncConfig(
    val url: String,
    val anonKey: String,
    val authScheme: String = "unstuck",
    val authHost: String = "auth-callback",
)

class SupabaseClientProvider(config: SyncConfig) {
    val client: SupabaseClient = createSupabaseClient(config.url, config.anonKey) {
        // The default request timeout (~10s) is too short for the assistant edge
        // fn, which proxies a large LLM (qwen-max) + can cold-start — calls were
        // throwing "network". Raise the ceiling; fast calls are unaffected.
        requestTimeout = 90.seconds
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = config.authScheme
            host = config.authHost
            autoLoadFromStorage = true
            autoSaveToStorage = true
        }
        install(Postgrest)
        install(Realtime)
        install(Functions)
    }
}
