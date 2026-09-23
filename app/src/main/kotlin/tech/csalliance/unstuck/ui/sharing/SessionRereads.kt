package tech.csalliance.unstuck.ui.sharing

import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The session edges after which the sharing projections (Shared-with-you, the
 * delegation badges, People, the shared calendar blocks) must read again.
 *
 * Those flows read once when they start and then only on a realtime share
 * signal or my own share write. The badges start with the ViewModel, before
 * the stored session has loaded, and the widget and co-focus collectors keep
 * them running for good, so that first read was skipped (no user yet) and
 * nothing asked again: after a sign-in or a cold start, tasks I had handed
 * over came back into Today and Start-Next and a partner's focus session was
 * never joined (Android audit 2026-09-23, A16). So a projection also re-reads:
 *  - when the session becomes authenticated for an account, from not being
 *    authenticated: sign-in, the stored session loading on a cold start or
 *    after the SDK's background reset, a token refresh that works again after
 *    a failure, a switch to another account. Any read tried while there was
 *    no token was skipped ([LastGoodRead.refresh]);
 *  - when the session's [account] ends (sign-out), so the next read shows
 *    empty instead of the last account's rows. Keyed on [account] itself, so
 *    that read already sees it gone.
 *
 * The value each flow holds when collection starts is dropped: the projection
 * reads once when it starts anyway.
 */
internal fun sessionRereads(status: Flow<SessionStatus>, account: Flow<String?>): Flow<Unit> = merge(
    status.map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
        .distinctUntilChanged()
        .drop(1)
        .filterNotNull(),
    account.distinctUntilChanged()
        .drop(1)
        .filter { it == null },
).map { }
