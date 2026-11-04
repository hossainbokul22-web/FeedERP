package com.feederp.core

import com.feederp.data.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class UserSession(val userId: String, val username: String, val displayName: String, val role: Role)

/** বর্তমান লগইন করা ইউজার। সব রিপোজিটরি `createdBy` এর জন্য এটা ব্যবহার করে। */
@Singleton
class SessionManager @Inject constructor() {
    private val _current = MutableStateFlow<UserSession?>(null)
    val current: StateFlow<UserSession?> = _current.asStateFlow()

    fun login(session: UserSession) { _current.value = session }
    fun logout() { _current.value = null }

    val userId: String get() = requireNotNull(_current.value) { "লগইন করা নেই" }.userId
    val role: Role? get() = _current.value?.role
    fun can(permission: Permission): Boolean = role?.let { Permissions.has(it, permission) } ?: false
    fun require(permission: Permission) {
        check(can(permission)) { "এই কাজের অনুমতি নেই: $permission" }
    }
}
