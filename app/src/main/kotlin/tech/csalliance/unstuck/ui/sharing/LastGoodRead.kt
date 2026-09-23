package tech.csalliance.unstuck.ui.sharing

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
 * One instance per flow; the flow's single upstream collector calls [next]
 * sequentially.
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
}
