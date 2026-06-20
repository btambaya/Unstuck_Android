package tech.csalliance.unstuck

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.SupabaseClientProvider
import tech.csalliance.unstuck.sync.SyncConfig
import tech.csalliance.unstuck.sync.SyncCoordinator

// Lightweight manual DI container — one instance per process, created in
// UnstuckApp. Holds the Room store, the Supabase client, and the sync engine.
// `configured` is false until the anon key is supplied (secrets.properties →
// BuildConfig); the UI shows a setup screen until then, exactly like iOS.
class AppGraph(
    context: Context,
    // --- TEST SEAMS (additive, optional) ---
    // Production `AppGraph(context)` is byte-identical: `configured` defaults to the
    // same BuildConfig expression and `storeOverride` defaults to null → the real
    // file-backed LocalStore. A unit test can force `configured = false` (so no
    // SupabaseClientProvider / network is built) and inject an in-memory LocalStore
    // so the whole graph reads + writes the same isolated store.
    configured: Boolean = BuildConfig.SUPABASE_ANON_KEY.isNotEmpty(),
    storeOverride: LocalStore? = null,
) {
    val configured: Boolean = configured
    val appContext: Context = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A pending notification deep-link (set by MainActivity from the launch intent,
     *  consumed by MainScaffold to navigate). e.g. "unstuck://task/{id}",
     *  "unstuck://today/recap", "unstuck://today/brief", or "capture". */
    val pendingDeepLink = MutableStateFlow<String?>(null)
    /** Set when a password-recovery deep link lands: AppRoot shows the set-new-password
     *  screen (a recovery session can change the password without the old one). */
    val pendingPasswordRecovery = MutableStateFlow(false)
    /** Armed by MainActivity when an `unstuck://auth-callback` deep link arrives. PKCE
     *  recovery links come back as `…/auth-callback?code=…` with NO `type=recovery`
     *  marker, so they can't be classified from the URL. Once the code is exchanged,
     *  the session observer reads the token's `amr`: a "recovery" session routes to
     *  set-new-password; magic-link / OAuth sign in normally. One-shot. */
    val pendingRecoveryProbe = MutableStateFlow(false)
    // A test may supply an in-memory store; production builds the file-backed DB.
    val db: UnstuckDatabase? = if (storeOverride == null) UnstuckDatabase.build(context.applicationContext) else null
    val store: LocalStore = storeOverride ?: LocalStore(db!!)

    val provider: SupabaseClientProvider? =
        if (configured) SupabaseClientProvider(SyncConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)) else null

    val coordinator: SyncCoordinator? =
        provider?.let { SyncCoordinator(it, store, context.applicationContext, scope) }

    private val appPrefs = context.applicationContext.getSharedPreferences("unstuck.app", Context.MODE_PRIVATE)
    var onboarded: Boolean
        get() = appPrefs.getBoolean("onboarded", false)
        set(value) { appPrefs.edit().putBoolean("onboarded", value).apply() }

    /** Device-local settings (theme / focus / sound / a11y). */
    val settings = SettingsStore(context.applicationContext)

    fun start() {
        coordinator?.start()
    }
}
