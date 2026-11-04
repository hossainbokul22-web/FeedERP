package com.feederp.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginThrottleTest {
    @Test
    fun locksAfterMaxFailuresAndUnlocksAfterTime() {
        val t = LoginThrottle(maxFailures = 3, lockMillis = 10_000)
        assertEquals(0L, t.remainingLockMillis("a", 0))
        t.recordFailure("a", 0); t.recordFailure("a", 0)
        assertEquals(0L, t.remainingLockMillis("a", 0))   // এখনও ২টি, লক নয়
        t.recordFailure("a", 1_000)                        // ৩য় ভুল → লক
        assertEquals(10_000L, t.remainingLockMillis("a", 1_000))
        assertEquals(5_000L, t.remainingLockMillis("a", 6_000))
        assertEquals(0L, t.remainingLockMillis("a", 11_000))
    }

    @Test
    fun counterResetsAfterLockStarts() {
        val t = LoginThrottle(maxFailures = 2, lockMillis = 1_000)
        t.recordFailure("a", 0); t.recordFailure("a", 0)
        assertEquals(0, t.failuresSoFar("a"))
    }

    @Test
    fun successClearsFailures() {
        val t = LoginThrottle(maxFailures = 3, lockMillis = 1_000)
        t.recordFailure("a", 0); t.recordFailure("a", 0)
        t.recordSuccess("a")
        t.recordFailure("a", 0); t.recordFailure("a", 0)
        assertEquals(0L, t.remainingLockMillis("a", 0))
    }

    @Test
    fun usersAreIndependent() {
        val t = LoginThrottle(maxFailures = 1, lockMillis = 1_000)
        t.recordFailure("a", 0)
        assertTrue(t.remainingLockMillis("a", 0) > 0)
        assertEquals(0L, t.remainingLockMillis("b", 0))
    }
}

class AuthRulesTest {
    @Test fun usernameNormalizedAndValidated() {
        assertEquals("rahim", AuthRules.normalizeUsername("  Rahim "))
        assertNotNull(AuthRules.usernameError(""))
        assertNotNull(AuthRules.usernameError("ab"))
        assertNotNull(AuthRules.usernameError("a b c"))
        assertNull(AuthRules.usernameError("store.01"))
        assertNull(AuthRules.usernameError("sales_rahim"))
    }

    @Test fun pinRules() {
        assertNotNull(AuthRules.pinError("123"))          // খুব ছোট
        assertNotNull(AuthRules.pinError("123456789"))    // খুব বড়
        assertNotNull(AuthRules.pinError("12a4"))         // সংখ্যা নয়
        assertNotNull(AuthRules.pinError("1111"))         // সব এক
        assertNull(AuthRules.pinError("1357"))
        assertNull(AuthRules.pinError("12345678"))
    }

    @Test fun setupErrorReportsFirstProblemInOrder() {
        assertNotNull(AuthRules.setupError("", "নাম", "1357", "1357"))
        assertEquals("নাম লিখুন", AuthRules.setupError("owner1", "  ", "1357", "1357"))
        assertNotNull(AuthRules.setupError("owner1", "মালিক", "11", "11"))
        assertEquals("দুই PIN মেলেনি", AuthRules.setupError("owner1", "মালিক", "1357", "1358"))
        assertNull(AuthRules.setupError("owner1", "মালিক", "1357", "1357"))
    }
}
