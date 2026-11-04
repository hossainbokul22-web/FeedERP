package com.feederp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.ui.AppNav
import com.feederp.ui.theme.FeedErpTheme
import com.feederp.ui.theme.ThemeSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themeSettings: ThemeSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode by themeSettings.mode.collectAsStateWithLifecycle()
            FeedErpTheme(mode) {
                AppNav()
            }
        }
    }
}
