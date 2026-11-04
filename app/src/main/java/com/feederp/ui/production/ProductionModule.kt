package com.feederp.ui.production

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** প্রোডাকশন ব্যাচ: অর্ডার, কনজাম্পশন, মেশিন রান, সম্পূর্ণ ও কস্ট শিট, ক্লিনিং চেকলিস্ট। */
object ProductionModule : ModuleEntry {
    private const val GROUP = "ফর্মুলা ও প্রোডাকশন"
    const val LIST = "production/list"
    const val NEW = "production/new"
    const val DETAIL = "production/detail"

    override val menu: List<MenuItem> = listOf(
        MenuItem(LIST, "প্রোডাকশন ব্যাচ", "Production", Permission.PRODUCTION, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(LIST) {
            BatchListScreen(
                onBack = { nav.popBackStack() },
                onNew = { nav.navigate(NEW) },
                onOpen = { id -> nav.navigate("$DETAIL/$id") },
            )
        }
        builder.composable(NEW) {
            NewBatchScreen(
                onBack = { nav.popBackStack() },
                // নতুন ব্যাচ খুলে দিই, "ফিরে যাও" তালিকায় যাবে (ফর্মে নয়)
                onCreated = { id -> nav.navigate("$DETAIL/$id") { popUpTo(LIST) } },
            )
        }
        builder.composable(
            route = "$DETAIL/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            BatchDetailScreen(onBack = { nav.popBackStack() })
        }
    }
}
