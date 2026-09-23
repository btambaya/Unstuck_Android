package tech.csalliance.unstuck.ui.sharing

import io.github.jan.supabase.auth.status.SessionStatus

/**
 * The last good answer of a read that returns NULL on a failure — kept for the
 * account that read it.
 *
 * The People roster, Shared-with-you and the delegation badges used to map a
 * failed read to empty, so any refresh while offline (a Remove, a tick, a share)
 * blanked them: "No one yet", Shared-with-you gone, handed-over tasks back in the
 * active list and Start-Next (parity with iOS build 79, audit 2026-09-22 C11).
 * Now a failed read keeps what is on screen. It never carries one account's rows
 * over to the next: a signed-out read, or a failed read for a different account,
 * shows empty.
 *
 * One instance per flow; the flow's single upstream collector calls [refresh]
 * (or [next]) sequentially.
 */
internal class LastGoodRead<T : Any>(private val empty: T) {
    /** The account whose good read is on screen, or null when none is. */
    private var owner: String? = null

    /** What the flow shows after [uid] read [read] (null = the read failed):
     *  the fresh answer; NULL to keep what is shown (a failure for the account
     *  already shown); otherwise [empty]. */
    fun next(uid: String?, read: T?): T? = when {
        uid != null && read != null -> { owner = uid; read }
        uid != null && uid == owner -> null
        else -> { owner = null; empty }
    }

    /** One refresh, for the signed-in user [uid], keyed on [account]: the
     *  session's account, which outlives [uid] through a failed token refresh
     *  (see [sessionAccount]). With no [uid] the read is skipped and counts as
     *  failed: it would go out without the user's token, where an anon call can
     *  answer empty. So offline with an expired token keeps what is shown. */
    suspend fun refresh(uid: String?, account: String?, read: suspend () -> T?): T? =
        next(uid ?: account, if (uid != null) read() else null)
}

/**
 * Whose session is live after [status], given the account before it.
 *
 * supabase-kt answers `currentUserOrNull()` = null in RefreshFailure (offline
 * with an expired access token), which AppViewModel.authed treats as still
 * signed in. So a hold keyed on the current user alone blanked in exactly the
 * long-offline case C11 is about (review of the C11 port, 2026-09-23). The
 * session there is still the last authenticated one; only a sign-out ends it.
 */
internal fun sessionAccount(status: SessionStatus, previous: String?): String? = when (status) {
    is SessionStatus.Authenticated -> status.session.user?.id
    is SessionStatus.NotAuthenticated -> null
    is SessionStatus.RefreshFailure, is SessionStatus.Initializing -> previous
}
