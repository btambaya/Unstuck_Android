package tech.csalliance.unstuck.ui.sharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.csalliance.unstuck.core.logic.ShareAccess
import tech.csalliance.unstuck.core.logic.ShareExistingGrant
import tech.csalliance.unstuck.core.logic.ShareFailure
import tech.csalliance.unstuck.core.logic.ShareItemKind
import tech.csalliance.unstuck.core.logic.SharePendingRow
import tech.csalliance.unstuck.core.logic.SharePersonRow
import tech.csalliance.unstuck.core.logic.ShareResult
import tech.csalliance.unstuck.core.logic.composeSharePeople
import tech.csalliance.unstuck.core.logic.isEmailLike
import tech.csalliance.unstuck.core.logic.normalizedShareEmail
import tech.csalliance.unstuck.core.logic.shareResultLine
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.sync.CollectionMemberInfo
import tech.csalliance.unstuck.sync.CollectionShareClient
import tech.csalliance.unstuck.sync.ShareLinkOutcome
import tech.csalliance.unstuck.sync.ShareOutcome
import tech.csalliance.unstuck.sync.TaskShareOutcome
import tech.csalliance.unstuck.sync.TaskSharePendingInvite
import tech.csalliance.unstuck.ui.AppViewModel

// The ONE Share screen's model (unified sharing v1 — docs/unified-sharing-spec.md
// §2 / §4), the Android port of iOS App/Features/ShareScreen.swift's
// ShareScreenModel + ShareScreenTransport. State lives in a StateFlow so the
// composable (ShareScreen.kt) simply renders it and every action is
// serialised on `busyId` (one in-flight write at a time) and ends with a
// reload so the rows show the SERVER's truth, never an optimistic guess.
//
// The transport is a seam (ShareScreenTransport) so the model is unit-tested
// with a fake; LiveShareTransport wires it to the AppViewModel (CircleClient
// task_share / task_unshare / roster, TaskShareClient share-task add / list /
// remove / link, CollectionShareClient share-collection add / list / remove /
// link).

/** The item a Share screen is opened for. */
sealed class ShareTarget {
    abstract val itemId: String
    abstract val name: String
    abstract val kind: ShareItemKind

    data class Task(override val itemId: String, override val name: String) : ShareTarget() {
        override val kind get() = ShareItemKind.TASK
    }

    data class Collection(override val itemId: String, override val name: String) : ShareTarget() {
        override val kind get() = ShareItemKind.COLLECTION
    }
}

/** SHARE = the grade picker + People / Someone new / Share a link; HAND_OVER =
 *  the same people picker for the task action "Hand over to…" (level `assign`). */
enum class ShareMode { SHARE, HAND_OVER }

/** Everything the Share screen does over the network, behind one interface so
 *  the screen model is testable with a fake. Every call reports what the
 *  SERVER did (a refusal is never a fabricated success). */
interface ShareScreenTransport {
    /** `circle_list()` — active connections + pending circle invites. */
    suspend fun listCircle(): List<CircleMember>
    /** `task_shares_for_task` — who holds the task, at what level. */
    suspend fun taskShares(taskId: String): List<ShareForTask>
    /** `share-task list` → pending email invites (tolerant → []). */
    suspend fun taskPendingInvites(taskId: String): List<TaskSharePendingInvite>
    /** `task_share(p_task_id, p_user, p_level)` — THROWS on a server refusal.
     *  [notify] false for a quiet grade change. */
    suspend fun shareTask(taskId: String, userId: String, level: ShareLevel, notify: Boolean)
    /** `task_unshare` — true only when confirmed. */
    suspend fun unshareTask(shareId: String): Boolean
    /** `share-task add` — shared / invited / failed(reason). */
    suspend fun shareTaskByEmail(taskId: String, email: String, level: ShareLevel): TaskShareOutcome
    /** `share-task remove {inviteId}` — true only when confirmed. */
    suspend fun cancelTaskInvite(taskId: String, inviteId: String): Boolean
    /** `share-task link`. */
    suspend fun taskLink(taskId: String, level: ShareLevel): ShareLinkOutcome
    /** `share-collection list`. */
    suspend fun collectionMembers(collectionId: String): List<CollectionMemberInfo>
    /** `share-collection add` by email (Someone new) or by user id (People). */
    suspend fun shareCollection(collectionId: String, email: String?, userId: String?, role: String): CollectionShareClient.ShareResult
    /** `share-collection remove {userId}` — true only when confirmed. */
    suspend fun unshareCollection(collectionId: String, userId: String): Boolean
    /** `share-collection remove {email}` — true only when confirmed. */
    suspend fun cancelCollectionInvite(collectionId: String, email: String): Boolean
    /** `share-collection link`. */
    suspend fun collectionLink(collectionId: String, role: String): ShareLinkOutcome
    /** `block_user(p_user)` (migration 075) — true only when the server blocked
     *  them. Android had no Block at all before (audit 2026-09-22 C10). */
    suspend fun block(userId: String): Boolean
}

/** The live seam — the AppViewModel's coordinator clients. An unconfigured
 *  graph (signed out / no anon key) degrades to empty reads + `not_configured`. */
class LiveShareTransport(private val vm: AppViewModel) : ShareScreenTransport {
    override suspend fun listCircle(): List<CircleMember> = vm.listCircle()
    override suspend fun taskShares(taskId: String): List<ShareForTask> = vm.sharesForTask(taskId)
    override suspend fun taskPendingInvites(taskId: String): List<TaskSharePendingInvite> = vm.taskPendingInvites(taskId)
    override suspend fun shareTask(taskId: String, userId: String, level: ShareLevel, notify: Boolean) =
        vm.shareTask(taskId, userId, level, notify)
    override suspend fun unshareTask(shareId: String): Boolean = vm.unshareTask(shareId)
    override suspend fun shareTaskByEmail(taskId: String, email: String, level: ShareLevel): TaskShareOutcome =
        vm.shareTaskByEmail(taskId, email, level)
    override suspend fun cancelTaskInvite(taskId: String, inviteId: String): Boolean = vm.cancelTaskInvite(taskId, inviteId)
    override suspend fun taskLink(taskId: String, level: ShareLevel): ShareLinkOutcome = vm.taskShareLink(taskId, level)
    override suspend fun collectionMembers(collectionId: String): List<CollectionMemberInfo> = vm.listCollectionMembers(collectionId)
    override suspend fun shareCollection(collectionId: String, email: String?, userId: String?, role: String): CollectionShareClient.ShareResult =
        vm.shareCollectionDetailed(collectionId, email = email, userId = userId, role = role)
    override suspend fun unshareCollection(collectionId: String, userId: String): Boolean = vm.unshareCollection(collectionId, userId)
    override suspend fun cancelCollectionInvite(collectionId: String, email: String): Boolean = vm.cancelCollectionInvite(collectionId, email)
    override suspend fun collectionLink(collectionId: String, role: String): ShareLinkOutcome = vm.collectionShareLink(collectionId, role)
    override suspend fun block(userId: String): Boolean = vm.blockUser(userId)
}

/** Everything the screen renders. */
data class ShareScreenState(
    /** The grade the next share uses (People tap, Someone new, the link). */
    val access: ShareAccess = ShareAccess.EDIT,
    /** The Someone-new field. */
    val email: String = "",
    val people: List<SharePersonRow> = emptyList(),
    val pending: List<SharePendingRow> = emptyList(),
    val loading: Boolean = true,
    /** The row (or "email" / "link") with a write in flight. */
    val busyId: String? = null,
    /** The honest line under the button after the last successful action. */
    val result: String? = null,
    /** The shown refusal / failure after the last action. */
    val error: String? = null,
    /** The last join link minted (the view hands it to the system share sheet). */
    val lastLink: String? = null,
    /** The ids that already held the item when this screen opened. Fixed by
     *  the FIRST load and never recomputed, so a row you just shared changes
     *  its trailing word IN PLACE and only floats to the top the next time the
     *  sheet is opened. In hand-over mode only a hand-over pins. */
    val pinnedIds: Set<String> = emptySet(),
)

/** An action's refusal, carried through `perform`. */
class ShareActionException(val failure: ShareFailure) : Exception(failure.message)

class ShareScreenModel(
    val target: ShareTarget,
    val mode: ShareMode = ShareMode.SHARE,
    private val transport: ShareScreenTransport,
) {
    private val _state = MutableStateFlow(ShareScreenState())
    val state: StateFlow<ShareScreenState> = _state.asStateFlow()
    private var pinned = false

    fun setAccess(access: ShareAccess) = _state.update { it.copy(access = access) }
    fun setEmail(email: String) = _state.update { it.copy(email = email) }

    /** Re-read the roster + the item's grants and compose the sections. */
    suspend fun load() {
        val circle = transport.listCircle()
        val people: List<SharePersonRow>
        val pending: List<SharePendingRow>
        when (target) {
            is ShareTarget.Task -> {
                val shares = transport.taskShares(target.itemId)
                val invites = transport.taskPendingInvites(target.itemId)
                val grants = shares.map { s ->
                    ShareExistingGrant(
                        userId = s.recipientUserId, name = s.recipientName, email = null,
                        access = ShareAccess.fromTaskLevel(s.level), handedOver = s.level == ShareLevel.ASSIGN,
                        shareId = s.shareId,
                    )
                }
                people = composeSharePeople(circle, grants)
                pending = invites.map { SharePendingRow(it.id, it.email, ShareAccess.fromTaskLevel(it.level) ?: ShareAccess.EDIT) }
            }
            is ShareTarget.Collection -> {
                val members = transport.collectionMembers(target.itemId)
                val grants = members.filter { !it.pending && it.userId.isNotEmpty() }.map { m ->
                    ShareExistingGrant(userId = m.userId, name = null, email = m.email, access = ShareAccess.fromCollectionRole(m.role))
                }
                people = composeSharePeople(circle, grants)
                pending = members.filter { it.pending }.map {
                    SharePendingRow("pending:${it.email}", it.email, ShareAccess.fromCollectionRole(it.role))
                }
            }
        }
        _state.update { s ->
            val pins = if (!pinned && people.isNotEmpty()) {
                pinned = true
                people.filter { if (mode == ShareMode.HAND_OVER) it.handedOver else it.isShared }.map { it.id }.toSet()
            } else s.pinnedIds
            s.copy(people = people, pending = pending, loading = false, pinnedIds = pins)
        }
    }

    // ── people ──────────────────────────────────────────────────────────────

    /** One tap on a person: share at `access` (share mode) or hand the task
     *  over (hand-over mode). An already-shared person in share mode is
     *  changed through [setAccess] (the row's menu) — a bare tap is a no-op. */
    suspend fun tap(row: SharePersonRow) {
        if (_state.value.busyId != null) return
        when (mode) {
            ShareMode.HAND_OVER -> {
                val t = target as? ShareTarget.Task ?: return
                perform(row.id) {
                    transport.shareTask(t.itemId, row.userId, ShareLevel.ASSIGN, notify = true)
                    ShareResult.HandedOver(row.name)
                }
            }
            ShareMode.SHARE -> {
                if (row.isShared) return
                grant(row, _state.value.access, isNew = true)
            }
        }
    }

    /** Change what a shared person has; null = remove them. */
    suspend fun setAccess(row: SharePersonRow, next: ShareAccess?) {
        if (_state.value.busyId != null) return
        if (next == null) { remove(row); return }
        grant(row, next, isNew = !row.isShared)
    }

    private suspend fun grant(row: SharePersonRow, access: ShareAccess, isNew: Boolean) {
        when (target) {
            is ShareTarget.Task -> perform(row.id) {
                // A NEW share pings the recipient; a level change is quiet.
                transport.shareTask(target.itemId, row.userId, access.taskLevel, notify = isNew)
                if (isNew) ShareResult.Shared(row.name, access) else ShareResult.AccessChanged(row.name, access)
            }
            is ShareTarget.Collection -> perform(row.id) {
                val r = transport.shareCollection(target.itemId, email = row.email, userId = row.userId, role = access.collectionRole)
                when (r.outcome) {
                    ShareOutcome.OK, ShareOutcome.ACCEPTED ->
                        if (isNew) ShareResult.Shared(row.name, access) else ShareResult.AccessChanged(row.name, access)
                    ShareOutcome.INVITED -> ShareResult.Invited(row.email ?: row.name)
                    // `share-collection add` takes `{userId}` (v22); this is only the
                    // fallback for an older deployment that resolves emails only.
                    ShareOutcome.INVALID -> if (row.email == null) throw ShareActionException(ShareFailure.ListNeedsEmail)
                        else throw ShareActionException(ShareFailure.fromReason(r.outcome.failureReason))
                    else -> throw ShareActionException(ShareFailure.fromReason(r.outcome.failureReason))
                }
            }
        }
    }

    private suspend fun remove(row: SharePersonRow) {
        when (target) {
            is ShareTarget.Task -> {
                val shareId = row.shareId ?: return
                perform(row.id) {
                    if (!transport.unshareTask(shareId)) throw ShareActionException(ShareFailure.Network)
                    ShareResult.Removed(row.name)
                }
            }
            is ShareTarget.Collection -> perform(row.id) {
                if (!transport.unshareCollection(target.itemId, row.userId)) throw ShareActionException(ShareFailure.Network)
                ShareResult.Removed(row.name)
            }
        }
    }

    /** Block someone who has this item (task OR list). Server-side the block cuts
     *  everything between you — the connection, task shares and list memberships
     *  both ways — and refuses anything they share with you until you unblock them
     *  in Settings › People. The reload follows the server's answer, and a refusal
     *  says the BLOCK didn't land, not that a share failed (parity with iOS build
     *  79, audit 2026-09-22 C10). */
    suspend fun block(row: SharePersonRow) {
        if (_state.value.busyId != null) return
        perform(row.id) {
            if (!transport.block(row.userId)) throw ShareActionException(ShareFailure.BlockFailed(row.name))
            ShareResult.Blocked(row.name)
        }
    }

    // ── someone new ─────────────────────────────────────────────────────────

    /** Share with the typed email. Local guards first (shape), then the
     *  server's honest answer. */
    suspend fun shareWithEmail() {
        val s = _state.value
        if (s.busyId != null) return
        val addr = normalizedShareEmail(s.email)
        _state.update { it.copy(error = null) }
        if (!isEmailLike(addr)) { _state.update { it.copy(error = ShareFailure.InvalidEmail.message) }; return }
        when (target) {
            is ShareTarget.Task -> perform(EMAIL_BUSY_ID) {
                when (val r = transport.shareTaskByEmail(target.itemId, addr, s.access.taskLevel)) {
                    is TaskShareOutcome.Shared -> ShareResult.Shared(r.displayName.ifEmpty { addr }, s.access)
                    TaskShareOutcome.Invited -> ShareResult.Invited(addr)
                    is TaskShareOutcome.Failed -> throw ShareActionException(ShareFailure.fromReason(r.reason))
                }
            }
            is ShareTarget.Collection -> perform(EMAIL_BUSY_ID) {
                val r = transport.shareCollection(target.itemId, email = addr, userId = null, role = s.access.collectionRole)
                when (r.outcome) {
                    ShareOutcome.OK -> ShareResult.Shared(r.displayName ?: addr, s.access)
                    ShareOutcome.INVITED -> ShareResult.Invited(addr)
                    // `share-collection add` by email doesn't say shared-vs-invited
                    // (by design) — a neutral line that is still true.
                    ShareOutcome.ACCEPTED -> ShareResult.Accepted(addr)
                    else -> throw ShareActionException(ShareFailure.fromReason(r.outcome.failureReason))
                }
            }
        }
        if (_state.value.error == null) _state.update { it.copy(email = "") }
    }

    /** Cancel a pending email invite. */
    suspend fun cancelPending(row: SharePendingRow) {
        if (_state.value.busyId != null) return
        when (target) {
            is ShareTarget.Task -> perform(row.id) {
                if (!transport.cancelTaskInvite(target.itemId, row.id)) throw ShareActionException(ShareFailure.Network)
                ShareResult.InviteCancelled(row.email)
            }
            is ShareTarget.Collection -> perform(row.id) {
                if (!transport.cancelCollectionInvite(target.itemId, row.email)) throw ShareActionException(ShareFailure.Network)
                ShareResult.InviteCancelled(row.email)
            }
        }
    }

    // ── link ────────────────────────────────────────────────────────────────

    /** Mint a one-shot join link at `access`. Returns it (the view copies it +
     *  hands it to the system share sheet) and sets the "Link copied" line. */
    suspend fun makeLink(): String? {
        val s = _state.value
        if (s.busyId != null) return null
        _state.update { it.copy(busyId = LINK_BUSY_ID, error = null) }
        val outcome = try {
            when (target) {
                is ShareTarget.Task -> transport.taskLink(target.itemId, s.access.taskLevel)
                is ShareTarget.Collection -> transport.collectionLink(target.itemId, s.access.collectionRole)
            }
        } catch (e: Exception) {
            ShareLinkOutcome.Failed("network")
        }
        return when (outcome) {
            is ShareLinkOutcome.Ok -> {
                _state.update { it.copy(busyId = null, lastLink = outcome.url, result = shareResultLine(ShareResult.LinkCopied(target.kind))) }
                outcome.url
            }
            is ShareLinkOutcome.Failed -> {
                _state.update { it.copy(busyId = null, error = ShareFailure.fromReason(outcome.reason).message) }
                null
            }
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    /** Run one write under `busyId`, translate its outcome into the result /
     *  error lines, then reload so the rows show the server's state. */
    private suspend fun perform(id: String, op: suspend () -> ShareResult) {
        _state.update { it.copy(busyId = id, error = null) }
        try {
            val r = op()
            _state.update { it.copy(result = shareResultLine(r)) }
        } catch (e: ShareActionException) {
            _state.update { it.copy(error = e.failure.message) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(busyId = null) }
            throw e
        } catch (e: Exception) {
            // A thrown RPC (task_share's `raise exception '<code>'`) → the code's copy.
            _state.update { it.copy(error = ShareFailure.fromReason(ShareFailure.reasonFromMessage(e.message)).message) }
        }
        _state.update { it.copy(busyId = null) }
        runCatching { load() }
    }

    companion object {
        const val EMAIL_BUSY_ID = "email"
        const val LINK_BUSY_ID = "link"
    }
}
