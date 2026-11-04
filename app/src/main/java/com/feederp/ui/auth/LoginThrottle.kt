package com.feederp.ui.auth

/**
 * ভুল PIN বারবার দিলে সাময়িক লক। বিশুদ্ধ Kotlin (Android নির্ভরতা নেই), তাই ইউনিট-টেস্টযোগ্য।
 * লক শুধু মেমরিতে থাকে: অ্যাপ বন্ধ করে চালু করলে রিসেট হয়। এটা ফোন হাতে পাওয়া কারও
 * দৈবাৎ অনুমান ঠেকানোর জন্য, ফরেনসিক-মানের সুরক্ষা নয়।
 */
class LoginThrottle(
    private val maxFailures: Int = 5,
    private val lockMillis: Long = 60_000L,
) {
    private class State(var failures: Int = 0, var lockedUntil: Long = 0L)

    private val states = HashMap<String, State>()

    /** লক থাকলে বাকি মিলিসেকেন্ড, না থাকলে 0। */
    @Synchronized
    fun remainingLockMillis(key: String, now: Long): Long {
        val s = states[key] ?: return 0L
        return (s.lockedUntil - now).coerceAtLeast(0L)
    }

    /** ব্যর্থ চেষ্টা নথিভুক্ত করে; সীমা পেরোলে লক শুরু করে এবং কাউন্টার শূন্য করে। */
    @Synchronized
    fun recordFailure(key: String, now: Long) {
        val s = states.getOrPut(key) { State() }
        s.failures += 1
        if (s.failures >= maxFailures) {
            s.lockedUntil = now + lockMillis
            s.failures = 0
        }
    }

    @Synchronized
    fun recordSuccess(key: String) {
        states.remove(key)
    }

    @Synchronized
    fun failuresSoFar(key: String): Int = states[key]?.failures ?: 0
}

/** PIN ও ইউজারনেমের গঠন যাচাই (বিশুদ্ধ ফাংশন)। */
object AuthRules {
    const val MIN_PIN = 4
    const val MAX_PIN = 8

    fun normalizeUsername(raw: String): String = raw.trim().lowercase()

    fun usernameError(raw: String): String? {
        val u = normalizeUsername(raw)
        return when {
            u.isEmpty() -> "ইউজারনেম লিখুন"
            u.length < 3 -> "ইউজারনেম কমপক্ষে ৩ অক্ষর"
            !u.all { it.isLetterOrDigit() || it == '_' || it == '.' } -> "শুধু অক্ষর, সংখ্যা, _ ও . চলবে"
            else -> null
        }
    }

    /** প্রথম-চালুর সেটআপ ফর্মের প্রথম ত্রুটি, না থাকলে null। */
    fun setupError(username: String, displayName: String, pin: String, confirmPin: String): String? {
        usernameError(username)?.let { return it }
        if (displayName.isBlank()) return "নাম লিখুন"
        pinError(pin)?.let { return it }
        if (pin != confirmPin) return "দুই PIN মেলেনি"
        return null
    }

    fun pinError(pin: String): String? = when {
        pin.length < MIN_PIN || pin.length > MAX_PIN -> "PIN $MIN_PIN-$MAX_PIN অঙ্কের হতে হবে"
        !pin.all { it in '0'..'9' } -> "PIN শুধু সংখ্যা হতে হবে"
        pin.toSet().size == 1 -> "সব অঙ্ক একই হলে চলবে না (যেমন 1111)"
        else -> null
    }
}
