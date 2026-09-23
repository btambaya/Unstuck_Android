package tech.csalliance.unstuck.ui.onboarding

import tech.csalliance.unstuck.core.logic.labelNameTaken
import tech.csalliance.unstuck.core.model.LifeArea

/**
 * The onboarding gate's decisions and its life-area seed. Pure — unit-tested.
 *
 * The gate used to be read ONCE, as soon as the session was authed (MainScaffold's
 * `remember { !vm.onboarded }`): before the first pull on a new phone, so a web / iOS
 * account was walked through setup again, and with no uid at all during an offline
 * RefreshFailure. Life areas also counted as "onboarded elsewhere", but the server seeds
 * them for EVERY account, so a brand-new user was marked onboarded after one pull and a
 * rotation mid-setup dropped them into the app (Android audit 2026-09-23, A9).
 */
internal object OnboardingGate {
    /** What the server's signup trigger (handle_new_user → seed_life_areas) creates for
     *  EVERY account, before the app ever runs. */
    val SERVER_SEEDED_AREAS = listOf("Work", "Personal", "Volunteering", "Home", "Health")

    /** Continues the server seed's colours (indigo, coral, green, amber, teal for sort
     *  orders 0–4), so a picked extra area gets the next one by its sort order. */
    private val PALETTE = listOf("indigo", "coral", "green", "amber", "teal", "blue", "violet", "red")

    /** null = not known yet (splash) · true = the onboarding steps · false = the app.
     *  An account not onboarded on this device waits until [resolvedFor] names it: the
     *  server answered after the first pull, or the deadline passed (iOS's
     *  `onboardingResolved` — an unknown answer lets the local flag decide). */
    fun show(uid: String?, onboarded: Boolean, resolvedFor: String?): Boolean? = when {
        uid == null -> null
        onboarded -> false
        resolvedFor == uid -> true
        else -> null
    }

    /** The account was onboarded on another platform: struggles saved or the interview
     *  finished (iOS), or it already has a task (web's first-run rule — its onboarding
     *  always files one, and struggles may be left empty). Never life areas: the server
     *  seeds those for every new account. */
    fun onboardedElsewhere(serverStruggles: List<String>?, interviewDoneAt: String?, hasTasks: Boolean): Boolean =
        serverStruggles?.isNotEmpty() == true || interviewDoneAt != null || hasTasks

    /** The picked areas the account doesn't have yet, as new rows. A name already in
     *  [existing] is skipped ignoring case. With nothing pulled yet, [existing] is empty
     *  and the server's signup seed is assumed: it already created [SERVER_SEEDED_AREAS]
     *  (a local copy with a fresh id breaks its unique(user_id, name) on every flush and
     *  shows twice in every picker — Android audit 2026-09-23, A8). Once pulled, the
     *  account's own rows decide, so a seeded area it deleted can be picked back. */
    fun areasToSeed(picked: List<String>, existing: List<LifeArea>, newId: () -> String): List<LifeArea> {
        val taken = ((if (existing.isEmpty()) SERVER_SEEDED_AREAS else emptyList()) + existing.map { it.name }).toMutableList()
        var order = maxOf(SERVER_SEEDED_AREAS.size, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        val out = mutableListOf<LifeArea>()
        for (raw in picked) {
            val name = raw.trim()
            if (name.isEmpty() || labelNameTaken(name, taken)) continue
            taken += name
            out += LifeArea(id = newId(), name = name, color = PALETTE[order % PALETTE.size], sortOrder = order)
            order++
        }
        return out
    }
}
