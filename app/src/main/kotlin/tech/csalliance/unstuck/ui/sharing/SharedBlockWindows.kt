package tech.csalliance.unstuck.ui.sharing

import kotlinx.coroutines.flow.FlowCollector
import tech.csalliance.unstuck.core.logic.IsoRange
import tech.csalliance.unstuck.core.model.SharedBlock

/**
 * The shared calendar blocks per visible window, with [LastGoodRead]'s rules:
 * a failed read keeps what the window last showed, and one account's blocks
 * never reach the next.
 *
 * A window already read is served from here until [invalidate] (a share
 * signal, a session edge, a completed pull); the next ask for it then reads
 * again. The pulls run about once a minute and complete offline too, so a
 * cache that was dropped and refilled with the failed read's empty answer
 * blanked the shared blocks every minute while offline (Android audit
 * 2026-09-23, A16).
 *
 * Only the sharedBlocks pipeline touches it, all on the main thread; its
 * [invalidate] can land while a [readInto] is suspended in the read.
 */
internal class SharedBlockWindows {
    /** The last good read of each window, for [owner]. */
    private val good = HashMap<IsoRange, List<SharedBlock>>()
    /** The windows read since the last [invalidate]. */
    private val fresh = HashSet<IsoRange>()
    /** The account [good] belongs to. */
    private var owner: String? = null
    /** Bumped by [invalidate]; a read that an invalidation overtook isn't fresh. */
    private var generation = 0

    /** Every window reads again on its next ask; what it has stays until then. */
    fun invalidate() {
        fresh.clear()
        generation++
    }

    /** Emits what [range] shows for the signed-in user [uid], keyed on the
     *  session's [account] (as [LastGoodRead.refresh]). [read] answers null on a
     *  failure. Without a [uid] it isn't called: an anon call could answer
     *  empty. When the account changes, empty goes out before the read. */
    suspend fun readInto(
        out: FlowCollector<List<SharedBlock>>,
        range: IsoRange,
        uid: String?,
        account: String?,
        read: suspend () -> List<SharedBlock>?,
    ) {
        val who = uid ?: account
        if (who != owner) {
            val shown = owner != null
            good.clear()
            fresh.clear()
            owner = who
            if (shown) out.emit(emptyList())
        }
        if (range !in fresh) {
            val asked = generation
            val got = if (uid != null) read() else null
            if (got != null) {
                good[range] = got
                if (generation == asked) fresh += range
            }
        }
        out.emit(good[range].orEmpty())
    }
}
