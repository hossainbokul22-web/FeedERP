package com.feederp.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AppLang { BN, EN }

val LocalLang = compositionLocalOf { AppLang.BN }

@Composable
fun tr(bn: String, en: String): String = if (LocalLang.current == AppLang.EN) en else bn

@Singleton
class LanguageSettings @Inject constructor(@ApplicationContext context: Context) {
    private val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _lang = MutableStateFlow(
        runCatching { AppLang.valueOf(p.getString("lang", null) ?: "BN") }.getOrDefault(AppLang.BN)
    )
    val lang: StateFlow<AppLang> = _lang.asStateFlow()

    fun toggle() {
        val next = if (_lang.value == AppLang.BN) AppLang.EN else AppLang.BN
        _lang.value = next
        p.edit().putString("lang", next.name).apply()
    }
}
