package tech.csalliance.unstuck.ui

import io.github.jan.supabase.auth.status.SessionSource

/**
 * Which authenticated emission may CONSUME the one-shot PKCE recovery probe
 * (AppGraph.pendingRecoveryProbe, armed by MainActivity on an `unstuck://auth-callback`
 * link). Only the deep-link code exchange — supabase-kt imports it with
 * [SessionSource.External]. Never the stored session a new subscription receives
 * first ([SessionSource.Storage]), a token refresh, or a metadata change: a reset
 * link tapped while the app was killed with a session stored used to spend the
 * probe against the OLD token, so the set-new-password screen never appeared and
 * the single-use link was burned. Pure — unit-tested.
 */
internal object RecoveryProbe {
    fun consumes(source: SessionSource): Boolean = source is SessionSource.External
}
