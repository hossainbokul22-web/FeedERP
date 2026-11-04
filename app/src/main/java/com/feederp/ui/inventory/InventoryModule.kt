package com.feederp.ui.inventory

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** ইনভেন্টরি: স্টক, লট/মেয়াদ, মুভমেন্ট, অ্যাডজাস্টমেন্ট, ট্রান্সফার, অ্যালার্ট। */
object InventoryModule : ModuleEntry {
    private const val GROUP = "ইনভেন্টরি"
    const val STOCK = "inventory/stock"
    const val LOTS = "inventory/lots"
    const val MOVEMENTS = "inventory/movements"
    const val ADJUST = "inventory/adjust"
    const val TRANSFER = "inventory/transfer"
    const val ALERTS = "inventory/alerts"

    override val menu: List<MenuItem> = listOf(
        MenuItem(STOCK, "স্টক", "Stock", Permission.INVENTORY, GROUP),
        MenuItem(LOTS, "লট ও মেয়াদ", "Lots & Expiry", Permission.INVENTORY, GROUP),
        MenuItem(MOVEMENTS, "স্টক মুভমেন্ট", "Stock Movements", Permission.INVENTORY, GROUP),
        MenuItem(ADJUST, "স্টক অ্যাডজাস্টমেন্ট", "Stock Adjustment", Permission.INVENTORY, GROUP),
        MenuItem(TRANSFER, "গুদাম ট্রান্সফার", "Warehouse Transfer", Permission.INVENTORY, GROUP),
        MenuItem(ALERTS, "স্টক অ্যালার্ট", "Stock Alerts", Permission.INVENTORY, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(STOCK) {
            StockListScreen(
                onBack = { nav.popBackStack() },
                onItemMovements = { id -> nav.navigate("$MOVEMENTS?itemId=$id") },
            )
        }
        builder.composable(LOTS) { LotListScreen(onBack = { nav.popBackStack() }) }
        builder.composable(
            route = "$MOVEMENTS?itemId={itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { MovementListScreen(onBack = { nav.popBackStack() }) }
        builder.composable(ADJUST) { AdjustmentScreen(onBack = { nav.popBackStack() }) }
        builder.composable(TRANSFER) { TransferScreen(onBack = { nav.popBackStack() }) }
        builder.composable(ALERTS) { AlertsScreen(onBack = { nav.popBackStack() }) }
    }
}
