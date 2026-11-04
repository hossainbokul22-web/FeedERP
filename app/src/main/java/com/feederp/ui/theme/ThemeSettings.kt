package com.feederp.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeSettings @Inject constructor(@ApplicationContext context: Context) {
    private val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(p.getString("theme_mode", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun cycle() {
        val next = ThemeMode.entries[(_mode.value.ordinal + 1) % ThemeMode.entries.size]
        _mode.value = next
        p.edit().putString("theme_mode", next.name).apply()
    }
}
