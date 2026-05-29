package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AuthErrorInfo
import tech.csalliance.unstuck.core.logic.detectSignupAlreadyExists
import tech.csalliance.unstuck.core.logic.humanizeAuthError
import tech.csalliance.unstuck.core.logic.nextSafePath

// Ported from AuthErrorsTests.swift / lib/auth-helpers.test.ts.
class AuthErrorsTest {

    @Test fun nextSafePathFallbackOnNilOrEmpty() {
        assertEquals("/dashboard", nextSafePath(null))
        assertEquals("/dashboard", nextSafePath(""))
    }

    @Test fun nextSafePathAcceptsSameOriginPath() {
        assertEquals("/tasks", nextSafePath("/tasks"))
        assertEquals("/calendar/week", nextSafePath("/calendar/week"))
    }

    @Test fun nextSafePathPreservesQueryStrings() {
        assertEquals("/tasks?new=1", nextSafePath("/tasks?new=1"))
    }

    @Test fun nextSafePathRejectsDoubleSlash() {
        assertEquals("/dashboard", nextSafePath("//evil.com"))
        assertEquals("/dashboard", nextSafePath("//evil.com/path"))
    }

    @Test fun nextSafePathRejectsAbsoluteURLs() {
        assertEquals("/dashboard", nextSafePath("https://evil.com"))
        assertEquals("/dashboard", nextSafePath("http://example.com/foo"))
        assertEquals("/dashboard", nextSafePath("javascript:alert(1)"))
    }

    @Test fun nextSafePathRejectsRelativeNotStartingWithSlash() {
        assertEquals("/dashboard", nextSafePath("tasks"))
        assertEquals("/dashboard", nextSafePath("../tasks"))
    }

    @Test fun nextSafePathDecodesEncodedPaths() {
        assertEquals("/tasks", nextSafePath("%2Ftasks"))
        assertEquals("/tasks?new=1", nextSafePath("%2Ftasks%3Fnew%3D1"))
    }

    @Test fun nextSafePathFallbackWhenDecodingThrows() {
        assertEquals("/dashboard", nextSafePath("%E0%A4%A"))
    }

    @Test fun nextSafePathHonoursCustomFallback() {
        assertEquals("/onboarding", nextSafePath(null, "/onboarding"))
        assertEquals("/onboarding", nextSafePath("//evil", "/onboarding"))
    }

    private fun lc(s: String) = s.lowercase()

    @Test fun humanizeEmptyError() {
        assertTrue(lc(humanizeAuthError(null)).contains("something went wrong"))
    }

    @Test fun humanizeRateLimitByCodeAndMessage() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(code = "over_email_send_rate_limit", message = "whatever"))).contains("few sign-up emails per hour"))
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "Email rate limit exceeded"))).contains("few sign-up emails per hour"))
    }

    @Test fun humanizeInvalidCredentials() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(code = "invalid_credentials"))).contains("email and password don't match"))
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "Invalid login credentials"))).contains("email and password don't match"))
    }

    @Test fun humanizeUserAlreadyExists() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(code = "user_already_exists"))).contains("account with that email already exists"))
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "User already registered"))).contains("account with that email already exists"))
    }

    @Test fun humanizeEmailNotConfirmed() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(code = "email_not_confirmed"))).contains("email isn't confirmed"))
    }

    @Test fun humanizeWeakPassword() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(code = "weak_password"))).contains("at least 8 characters"))
    }

    @Test fun humanizeStatus429() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "too many requests", status = 429))).contains("rate limit"))
    }

    @Test fun humanizeNetworkFailures() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "Failed to fetch"))).contains("couldn't reach the server"))
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "Operation timed out"))).contains("couldn't reach the server"))
    }

    @Test fun humanizeInvalidEmail() {
        assertTrue(lc(humanizeAuthError(AuthErrorInfo(message = "Invalid email"))).contains("email address looks off"))
    }

    @Test fun humanizeFallbackCapitalises() {
        assertEquals("Something weird happened", humanizeAuthError(AuthErrorInfo(message = "something weird happened")))
    }

    @Test fun detectEmptyIdentitiesMeansExists() {
        assertTrue(detectSignupAlreadyExists(0, null, null, false))
    }

    @Test fun detectNonEmptyIdentitiesIsGenuineNewUser() {
        assertFalse(detectSignupAlreadyExists(1, null, null, false))
    }

    @Test fun detectNilIdentitiesIsNotExists() {
        assertFalse(detectSignupAlreadyExists(null, null, null, false))
    }

    @Test fun detectConfirmedWithoutSessionMeansExists() {
        assertTrue(detectSignupAlreadyExists(1, "2026-01-01T00:00:00Z", null, false))
    }

    @Test fun detectConfirmedWithSessionIsNewlyVerified() {
        assertFalse(detectSignupAlreadyExists(1, "2026-01-01T00:00:00Z", null, true))
    }
}
