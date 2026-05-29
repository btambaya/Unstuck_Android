package tech.csalliance.unstuck

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.SupabaseClientProvider
import tech.csalliance.unstuck.sync.SyncConfig
import tech.csalliance.unstuck.sync.SyncCoordinator

// Lightweight manual DI container — one instance per process, created in
// UnstuckApp. Holds the Room store, the Supabase client, and the sync engine.
// `configured` is false until the anon key is supplied (secrets.properties →
// BuildConfig); the UI shows a setup screen until then, exactly like iOS.
class AppGraph(context: Context) {
    val configured: Boolean = BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: UnstuckDatabase = UnstuckDatabase.build(context.applicationContext)
    val store = LocalStore(db)

    val provider: SupabaseClientProvider? =
        if (configured) SupabaseClientProvider(SyncConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)) else null

    val coordinator: SyncCoordinator? =
        provider?.let { SyncCoordinator(it, store, context.applicationContext, scope) }

    private val appPrefs = context.applicationContext.getSharedPreferences("unstuck.app", Context.MODE_PRIVATE)
    var onboarded: Boolean
        get() = appPrefs.getBoolean("onboarded", false)
        set(value) { appPrefs.edit().putBoolean("onboarded", value).apply() }

    fun start() {
        coordinator?.start()
    }
}
