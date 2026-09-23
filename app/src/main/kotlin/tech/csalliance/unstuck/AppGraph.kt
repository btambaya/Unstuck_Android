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
    // With `configured = false` there is no SyncCoordinator, so there is no
    // signed-in uid to attribute the PER-ACCOUNT onboarded flag to — which makes
    // the flag unsettable and pins an offline test to the onboarding screen. This
    // supplies the account id instead. Null in production → the auth session's own
    // uid, exactly as before.
    private val uidOverride: (() -> String?)? = null,
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
    /** Why an `unstuck://auth-callback` link couldn't sign the user in (expired, already
     *  used, offline). Set by MainActivity while signed out; AuthScreen shows it once. */
    val authLinkError = MutableStateFlow<String?>(null)
    /** Fires on every app FOREGROUND (UnstuckApp's ProcessLifecycle onStart). The
     *  co-focus reconnect re-exchange listens here as belt-and-braces alongside the
     *  realtime status flow: after a doze/backgrounded socket death the SDK can take
     *  up to a heartbeat (~15s) to notice, and a send in that window silently
     *  vanishes — the foreground hello/re-announce closes the gap sooner. */
    val foregrounds = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    // A test may supply an in-memory store; production builds the file-backed DB.
    val db: UnstuckDatabase? = if (storeOverride == null) UnstuckDatabase.build(context.applicationContext) else null
    val store: LocalStore = storeOverride ?: LocalStore(db!!)

    val provider: SupabaseClientProvider? =
        if (configured) SupabaseClientProvider(SyncConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)) else null

    val coordinator: SyncCoordinator? =
        provider?.let { SyncCoordinator(it, store, context.applicationContext, scope) }

    private val appPrefs = context.applicationContext.getSharedPreferences("unstuck.app", Context.MODE_PRIVATE)
    // The sync engine's own prefs: `unstuck.prevUserId` is the account that was signed
    // in — the only one the pre-2026-09 device-global onboarded flag may migrate to.
    private val syncPrefs = context.applicationContext.getSharedPreferences("unstuck.sync", Context.MODE_PRIVATE)

    /** Onboarding, PER ACCOUNT (`onboarded.<uid>`): a second account signing in on
     *  the same phone must onboard itself (its own struggles, its own area seed, its
     *  own one-time tour offer) — the old device-global flag let it skip all three.
     *  Reconciled from the SERVER after every pull (AppViewModel.reconcileOnboarded)
     *  so a returning account on a fresh install isn't re-onboarded either. See
     *  [OnboardedFlag] for the legacy-key migration rule. */
    private val onboardedUid: String? get() = uidOverride?.invoke() ?: coordinator?.auth?.currentUserId
    var onboarded: Boolean
        get() = OnboardedFlag.get(appPrefs, onboardedUid, syncPrefs.getString("unstuck.prevUserId", null))
        set(value) = OnboardedFlag.set(appPrefs, onboardedUid, value)

    /** Device-local settings (theme / focus / sound / a11y). */
    val settings = SettingsStore(context.applicationContext)

    init {
        // Sign-out: the legacy device-global flag must never survive into the next
        // account's session (the per-account keys are inert for anyone else).
        coordinator?.onSignedOut = { OnboardedFlag.clearLegacy(appPrefs) }
    }

    fun start() {
        coordinator?.start()
    }
}

/**
 * The per-account onboarded flag. Keys: `onboarded.<uid>`. The pre-2026-09 build
 * kept ONE device-global `onboarded` key; it migrates to an account exactly once,
 * and ONLY to the account that was signed in when the build upgraded
 * ([legacyOwnerUid] = the engine's prevUserId) — never to whoever signs in next on
 * a signed-out phone. With no session yet (cold start before the session restores)
 * nothing is granted. Pure over SharedPreferences — unit-tested.
 */
internal object OnboardedFlag {
    const val LEGACY_KEY = "onboarded"
    fun key(uid: String) = "onboarded.$uid"

    fun get(p: android.content.SharedPreferences, uid: String?, legacyOwnerUid: String?): Boolean {
        if (uid == null) return false
        val k = key(uid)
        if (p.contains(k)) return p.getBoolean(k, false)
        if (p.getBoolean(LEGACY_KEY, false) && legacyOwnerUid == uid) {
            p.edit().putBoolean(k, true).remove(LEGACY_KEY).apply()
            return true
        }
        return false
    }

    fun set(p: android.content.SharedPreferences, uid: String?, value: Boolean) {
        // No account → nothing to attribute it to (onboarding completes signed-in).
        if (uid == null) return
        p.edit().putBoolean(key(uid), value).remove(LEGACY_KEY).apply()
    }

    fun clearLegacy(p: android.content.SharedPreferences) {
        p.edit().remove(LEGACY_KEY).apply()
    }
}
