package com.feederp.ui.master

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** মাস্টার ডাটা: আইটেম, পার্টি, গুদাম, ইউনিট। */
object MasterModule : ModuleEntry {
    private const val GROUP = "মাস্টার ডাটা"
    const val ITEMS = "master/items"
    const val ITEM_FORM = "master/items/form"
    const val PARTIES = "master/parties"
    const val WAREHOUSES = "master/warehouses"
    const val UNITS = "master/units"

    override val menu: List<MenuItem> = listOf(
        MenuItem(ITEMS, "আইটেম", "Items", Permission.MASTER_VIEW, GROUP),
        MenuItem(PARTIES, "পার্টি", "Parties", Permission.MASTER_VIEW, GROUP),
        MenuItem(WAREHOUSES, "গুদাম", "Warehouses", Permission.MASTER_VIEW, GROUP),
        MenuItem(UNITS, "ইউনিট", "Units", Permission.MASTER_VIEW, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(ITEMS) {
            ItemListScreen(
                onBack = { nav.popBackStack() },
                onNew = { nav.navigate(ITEM_FORM) },
                onEdit = { id -> nav.navigate("$ITEM_FORM?id=$id") },
            )
        }
        builder.composable(
            route = "$ITEM_FORM?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            ItemFormScreen(onBack = { nav.popBackStack() })
        }
        builder.composable(PARTIES) { PartyListScreen(onBack = { nav.popBackStack() }) }
        builder.composable(WAREHOUSES) { WarehouseListScreen(onBack = { nav.popBackStack() }) }
        builder.composable(UNITS) { UnitListScreen(onBack = { nav.popBackStack() }) }
    }
}
