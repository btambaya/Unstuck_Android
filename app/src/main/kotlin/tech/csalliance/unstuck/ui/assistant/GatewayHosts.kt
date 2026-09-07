package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.flow.StateFlow
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource

// The slice of AppViewModel the interview and the "What Unstuck knows" panel
// need (gateway A2). Declared here — not on the ViewModel — so the two
// surfaces compile and unit-test against a fake host; AppViewModel implements
// both (`: InterviewHost, FactsHost`) and every member below is already one of
// its phase-1 members, verbatim signatures.

/** What the get-to-know-you interview needs from the host. */
interface InterviewHost {
    /** The interview is done for this account (local flag, pinned from the
     *  server after every pull). A flip to true while the panel is open
     *  closes it — someone who finished elsewhere is not asked again. */
    val interviewDone: StateFlow<Boolean>
    /** Which recurring PA moments run — the picker's toggles. */
    val rituals: StateFlow<RitualPrefs>

    /** The parked resume step, or null when nothing is persisted. */
    fun interviewParkedStep(maxStep: Int): Int?
    fun setInterviewStep(step: Int)
    fun clearInterviewStep()
    /** Every "done" path: local flag + resume step dropped + the account. */
    fun markInterviewDone()
    /** A USER change from the picker: cache, flag pending, push to the account. */
    fun setRituals(prefs: RitualPrefs)
    /** Routes through ProfileFactsService (prepare → guard → refine → persist →
     *  enqueue). Null = the text was rejected or the local write failed. The
     *  `whenIso = null` default lives on THIS interface only: AppViewModel
     *  implements both hosts, its override inherits the default from here, and
     *  Kotlin refuses two supertypes that each default the same parameter. */
    suspend fun saveProfileFact(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String? = null): ProfileFact?
}

/** What Settings → "What Unstuck knows" needs from the host. */
interface FactsHost {
    /** Active facts, newest first. */
    val profileFacts: StateFlow<List<ProfileFact>>
    val rituals: StateFlow<RitualPrefs>

    fun setRitual(key: RitualKey, on: Boolean)
    /** Same member as [InterviewHost.saveProfileFact]; the default is declared there. */
    suspend fun saveProfileFact(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String?): ProfileFact?
    /** Edit one fact's text IN PLACE: the SAME row and id, `updatedAt` bumped
     *  (never delete + re-add, which strands the old id in every device's
     *  cache); a fresh save only when the row has vanished. Failure carries the
     *  `ProfileFactSaveError` so the panel can say why the edit didn't stick. */
    suspend fun updateProfileFact(id: String, fact: String, whenIso: String? = null): Result<ProfileFact>
    /** Soft-delete one fact (a tombstone that syncs). False = no active fact with that id. */
    suspend fun forgetProfileFact(id: String): Boolean
    suspend fun forgetAllProfileFacts()
}
