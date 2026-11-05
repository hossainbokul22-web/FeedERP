package com.feederp.ui.auth

import com.feederp.core.PinHasher
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.UserSession
import com.feederp.core.newId
import com.feederp.data.Role
import com.feederp.data.Seeder
import com.feederp.data.UserDao
import com.feederp.data.UserEntity
import com.feederp.inventory.AuditRepository
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult
    data object Inactive : LoginResult
    data class Locked(val secondsLeft: Long) : LoginResult
}

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val users: UserDao,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
    private val seeder: Seeder,
) {
    private val throttle = LoginThrottle()
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    /** শেষ লগইন করা ইউজারনেম (লগইন স্ক্রিনে আগে থেকে বসানোর জন্য)। */
    fun lastUsername(): String = prefs.getString("last_username", "") ?: ""

    /** "এই ফোনে মনে রাখুন" চালু থাকলে ৩০ দিনের মধ্যে PIN ছাড়াই সেশন ফিরিয়ে আনে। PIN কখনো সংরক্ষণ হয় না, শুধু ইউজার আইডি + মেয়াদ। */
    suspend fun tryRestore(): Boolean {
        if (session.current.value != null) return true
        val uid = prefs.getString("remember_uid", null) ?: return false
        if (System.currentTimeMillis() > prefs.getLong("remember_until", 0L)) { forget(); return false }
        val user = users.getById(uid)
        if (user == null || !user.active || user.deletedAt != null) { forget(); return false }
        session.login(UserSession(user.id, user.username, user.displayName, user.role))
        audit.log("LOGIN_RESTORED", "user", user.id)
        return true
    }

    private fun saveRemember(userId: String) {
        prefs.edit().putString("remember_uid", userId)
            .putLong("remember_until", System.currentTimeMillis() + REMEMBER_MILLIS).apply()
    }

    private fun forget() { prefs.edit().remove("remember_uid").remove("remember_until").apply() }

    /** ডাটাবেসে কোনো ইউজার না থাকলে প্রথম-চালুর সেটআপ (Owner তৈরি) দেখাতে হবে। */
    suspend fun needsSetup(): Boolean = users.count() == 0

    /** প্রথম Owner তৈরি। ইউজার আগে থেকে থাকলে কিছু করে না (দ্বিতীয় Owner এখান থেকে বানানো যাবে না)। */
    suspend fun createFirstOwner(username: String, displayName: String, pin: String): Result<Unit> {
        AuthRules.usernameError(username)?.let { return Result.failure(IllegalArgumentException(it)) }
        AuthRules.pinError(pin)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (displayName.isBlank()) return Result.failure(IllegalArgumentException("নাম লিখুন"))
        if (!needsSetup()) return Result.failure(IllegalStateException("সেটআপ আগেই হয়ে গেছে"))

        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        val user = UserEntity(
            id = newId(),
            username = AuthRules.normalizeUsername(username),
            displayName = displayName.trim(),
            role = Role.OWNER,
            pinHash = hash,
            pinSalt = salt,
            active = true,
            updatedAt = stamper.now(),
            deviceId = stamper.deviceId,
        )
        users.upsert(user)
        seeder.seedDefaults()
        return Result.success(Unit)
    }

    suspend fun login(username: String, pin: String, remember: Boolean = false): LoginResult {
        val key = AuthRules.normalizeUsername(username)
        val now = System.currentTimeMillis()
        val remaining = throttle.remainingLockMillis(key, now)
        if (remaining > 0) return LoginResult.Locked((remaining + 999) / 1000)

        val user = users.getByUsername(key)
        // ইউজার না থাকলেও হ্যাশ হিসাব চালাই, যাতে সময় দেখে ইউজারনেম আছে কি না বোঝা না যায়।
        val salt = user?.pinSalt ?: DUMMY_SALT
        val computed = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        val matches = user != null &&
            java.security.MessageDigest.isEqual(computed.toByteArray(), user.pinHash.toByteArray())

        if (user == null || !matches) {
            throttle.recordFailure(key, now)
            audit.log("LOGIN_FAILED", "user", user?.id, "username=$key")
            // (এখানে user null হতে পারে; নিচের কোড শুধু user != null হলে পৌঁছায়)
            return LoginResult.InvalidCredentials
        }
        if (!user.active) return LoginResult.Inactive

        throttle.recordSuccess(key)
        seeder.seedDefaults() // idempotent; অন্য ফোন থেকে সিঙ্ক হয়ে আসা ইউজারের ফোনেও ডিফল্ট ডাটা নিশ্চিত করে
        session.login(UserSession(user.id, user.username, user.displayName, user.role))
        prefs.edit().putString("last_username", user.username).apply()
        if (remember) saveRemember(user.id) else forget()
        audit.log("LOGIN", "user", user.id)
        return LoginResult.Success
    }

    suspend fun logout() {
        if (session.current.value != null) audit.log("LOGOUT", "user", session.current.value?.userId)
        forget()
        session.logout()
    }

    private companion object {
        const val REMEMBER_MILLIS = 30L * 24 * 60 * 60 * 1000
        // শুধু সময় সমান রাখার জন্য; কোনো আসল PIN এর সাথে সম্পর্ক নেই।
        const val DUMMY_SALT = "AAAAAAAAAAAAAAAAAAAAAA=="
    }
}
