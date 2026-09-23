package tech.csalliance.unstuck.ui.auth

import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException

/**
 * The sign-up and magic-link emails' App Link (owner decision 2026-09-23, same
 * contract as iOS): `https://unstucknow.io/auth/app-confirm/?token_hash=…&type=signup|magiclink`.
 * The app asks for those emails with redirect `unstuck://auth-confirm`
 * (AuthService.EMAIL_LINK_REDIRECT) and the templates turn that into this link.
 * On a phone with the app, Android opens it here (autoVerify filter in the manifest,
 * backed by unstucknow.io/.well-known/assetlinks.json); on a computer it opens the
 * web page, which confirms the address and says "open the app". The app trades the
 * token hash for a session with verifyEmailOtp — unlike the `auth-callback` PKCE
 * code, that works whichever device asked for the email. Only this path: the web's
 * own `/auth/confirm` links stay in the browser. Pure — unit-tested.
 */
internal object AppConfirmLink {
    const val HOST = "unstucknow.io"
    const val PATH = "/auth/app-confirm"

    const val SIGNUP_USED = "That link has already been used — sign in."
    const val MAGIC_USED = "That sign-in link has expired or was already used — sign in, or ask for a new one."
    const val INCOMPLETE = "That link looks incomplete — sign in, or ask for a new link."
    const val RETRY = "Couldn't finish signing in — tap the link in the email again."

    data class Link(val tokenHash: String, val type: OtpType.Email)

    /** Is this intent's URI the confirm link at all? Exact host, `https` only, and the
     *  path with or without its trailing slash (the manifest's pathPrefix also lets
     *  `/auth/app-confirmX` through, which this refuses). */
    fun matches(scheme: String?, host: String?, path: String?): Boolean =
        scheme.equals("https", ignoreCase = true) &&
            host.equals(HOST, ignoreCase = true) &&
            (path == PATH || path == "$PATH/")

    /** The link's query → what to verify, or null when it's missing the token or
     *  carries a type the contract doesn't use (only `signup` and `magiclink`). */
    fun parse(tokenHash: String?, type: String?): Link? {
        val hash = tokenHash?.trim().orEmpty()
        if (hash.isEmpty() || hash.length > 1024) return null
        val otp = when (type) {
            "signup" -> OtpType.Email.SIGNUP
            "magiclink" -> OtpType.Email.MAGIC_LINK
            else -> return null
        }
        return Link(hash, otp)
    }

    /** Token hashes being verified right now, and the last few that signed in. A mail
     *  app can deliver one tap twice: the second POST of a single-use token comes back
     *  "already used" while (or after) the first signs the user in, and that answer
     *  must not reach the screen. Process-wide, like AuthLink's count. */
    private val inFlight = HashSet<String>()
    private val done = LinkedHashSet<String>()
    private const val DONE_KEPT = 16

    /** Null when the link signed the user in (or is a repeat of one that is doing so /
     *  did); otherwise the message to show. [verify] throws on failure. Cancellation
     *  is rethrown. */
    suspend fun complete(link: Link?, verify: suspend (Link) -> Unit): String? {
        if (link == null) return INCOMPLETE
        synchronized(this) {
            if (link.tokenHash in inFlight || link.tokenHash in done) return null
            inFlight += link.tokenHash
        }
        var ok = false
        try {
            verify(link)
            ok = true
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return failureMessage(link, e)
        } finally {
            synchronized(this) {
                inFlight -= link.tokenHash
                if (ok) {
                    done += link.tokenHash
                    if (done.size > DONE_KEPT) done.remove(done.first())
                }
            }
        }
    }

    /** GoTrue answers a used link and an expired one alike (403 `otp_expired`), and a
     *  sign-up link that was used DID confirm the email — so the way on is signing in.
     *  A network drop, a rate limit or a server error may leave the token good: tap
     *  it again. */
    fun failureMessage(link: Link, e: Throwable): String {
        if (isNetwork(e)) return AuthLink.OFFLINE
        val rest = generateSequence(e) { it.cause }.take(8).filterIsInstance<RestException>().firstOrNull()
        if (rest == null || rest.statusCode == 429 || rest.statusCode >= 500) return RETRY
        return if (link.type == OtpType.Email.MAGIC_LINK) MAGIC_USED else SIGNUP_USED
    }

    /** A signed-in user needs no "sign in" line for a link that's merely used up —
     *  they're in. The other failures still say so (they may have meant to switch
     *  account). */
    fun showWhenSignedIn(message: String): Boolean = message != SIGNUP_USED && message != MAGIC_USED

    private fun isNetwork(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it is HttpRequestException || it is java.io.IOException }

    /** Tests only: forget the in-flight / done hashes. */
    internal fun resetForTest() = synchronized(this) { inFlight.clear(); done.clear() }
}
