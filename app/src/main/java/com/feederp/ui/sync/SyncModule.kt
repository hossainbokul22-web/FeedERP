package com.feederp.ui.sync

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** Wi-Fi সিঙ্ক: হাব/স্পোক বাছাই, পেয়ারিং, এখনই সিঙ্ক। নতুন ফোনে লগইন পর্দা থেকেও এখানে আসা যায়। */
object SyncModule : ModuleEntry {
    const val MAIN = "sync/main"

    override val menu: List<MenuItem> = listOf(
        MenuItem(MAIN, "সিঙ্ক (Wi-Fi)", "Sync", Permission.SYNC, "সিস্টেম"),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(MAIN) { SyncScreen(onBack = { nav.popBackStack() }) }
    }
}
