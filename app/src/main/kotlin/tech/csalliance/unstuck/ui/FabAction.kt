package tech.csalliance.unstuck.ui

/**
 * What the ONE coral + in the bottom bar creates, from where the user is.
 *
 * The button itself never changes — same coral square, same centred position in
 * [BottomNavBar]. Only the action behind it and its screen-reader label follow
 * the surface, so "+" consistently means "make the kind of thing I'm looking
 * at" instead of always meaning "new task".
 *
 * ## Why this is a function and not an `if` at the call site
 * It's the only branch in the change with real product meaning, so it's kept
 * pure and unit-tested ([FabActionTest]) rather than buried in a lambda inside
 * a 600-line composable.
 *
 * ## The third rule, and why it isn't here
 * The agreed behaviour has a third case — *inside* a single collection the +
 * should drop the cursor into that collection's existing inline "Add to this
 * collection…" field (and fall back to New collection on a view-only shared
 * list, where the user has no right to add). Android has no such state to
 * decide about: a collection is a pushed [Route.Collection], and pushed routes
 * render in a full-screen opaque Box declared AFTER — i.e. on top of — the
 * Column that holds the bottom bar (MainScaffold), so the bar and the + are off
 * screen and touch-inert for the whole time a collection is open. (iOS keeps
 * its tab bar under the detail view, which is why the rule exists at all.)
 *
 * That was asserted here once without being checked, and it was only
 * three-quarters true. Driven on an emulator during development: with a
 * collection open the + was still in the ACCESSIBILITY tree, still announced
 * "New collection", and a TalkBack double-tap really did open the
 * New-collection sheet on top of the collection — so the case WAS reachable,
 * by exactly the users this button's per-surface label exists for. It is now
 * unreachable by construction rather than by assertion: MainScaffold clears the
 * bar's semantics while a route covers it, so the + leaves the a11y tree
 * exactly when it leaves the screen. MainScaffoldFabTest pins that; if it ever
 * regresses, this third rule becomes real behaviour that Android owes the user
 * and this file needs a branch for it.
 */
internal enum class FabAction {
    /** Today / Tasks / Calendar — the New-task sheet. Unchanged behaviour. */
    NEW_TASK,

    /** The Collections grid — the New-collection sheet. */
    NEW_COLLECTION,
}

/**
 * Key of the Collections tab in [NAV]. Shared by the nav spec and the decision
 * below so renaming the tab can't silently revert the + to New task
 * (FabActionTest pins the two together).
 */
internal const val TAB_COLLECTIONS = "lists"

/** The + 's action for [tab] — one of the [NAV] keys. */
internal fun fabAction(tab: String): FabAction =
    if (tab == TAB_COLLECTIONS) FabAction.NEW_COLLECTION else FabAction.NEW_TASK

/**
 * The + 's `contentDescription`. It has to change with the action: a button
 * announced as "New" that creates a task on three tabs and a collection on the
 * fourth is unusable with TalkBack, which is the only place the button's
 * meaning is ever spoken.
 */
internal fun fabLabel(action: FabAction): String = when (action) {
    FabAction.NEW_TASK -> "New task"
    FabAction.NEW_COLLECTION -> "New collection"
}
