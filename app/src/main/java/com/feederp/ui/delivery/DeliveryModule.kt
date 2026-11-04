package com.feederp.ui.delivery

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** ডেলিভারি নোট: প্ল্যান → লোড (FEFO, স্টক কমে) → ডেলিভারি নিশ্চিত (COGS)। */
object DeliveryModule : ModuleEntry {
    private const val GROUP = "বিক্রয়"
    const val LIST = "delivery/list"
    const val NEW = "delivery/new"
    const val DETAIL = "delivery/detail"

    override val menu: List<MenuItem> = listOf(
        MenuItem(LIST, "ডেলিভারি নোট", "Delivery", Permission.DELIVERY_VIEW, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(LIST) {
            DeliveryListScreen(
                onBack = { nav.popBackStack() }, onNew = { nav.navigate(NEW) }, onOpen = { id -> nav.navigate("$DETAIL/$id") },
            )
        }
        builder.composable(NEW) {
            NewDeliveryScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id -> nav.navigate("$DETAIL/$id") { popUpTo(LIST) } },
            )
        }
        builder.composable(
            route = "$DETAIL/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { DeliveryDetailScreen(onBack = { nav.popBackStack() }) }
    }
}
