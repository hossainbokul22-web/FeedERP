package com.feederp.ui.maintenance

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** রক্ষণাবেক্ষণ: মেশিন, প্রিভেন্টিভ শিডিউল, ব্রেকডাউন/ওয়ার্ক অর্ডার, খরচ অনুমোদন। */
object MaintenanceModule : ModuleEntry {
    private const val GROUP = "রক্ষণাবেক্ষণ"
    const val MACHINES = "maintenance/machines"
    const val ORDERS = "maintenance/orders"
    const val ORDER = "maintenance/order"

    override val menu: List<MenuItem> = listOf(
        MenuItem(MACHINES, "মেশিন", "Machines", Permission.MAINTENANCE, GROUP),
        MenuItem(ORDERS, "ওয়ার্ক অর্ডার / ব্রেকডাউন", "Work Orders", Permission.MAINTENANCE_REPORT, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(MACHINES) { MachineListScreen(onBack = { nav.popBackStack() }) }
        builder.composable(ORDERS) {
            WorkOrderListScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("$ORDER/$id") })
        }
        builder.composable(
            route = "$ORDER/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { WorkOrderDetailScreen(onBack = { nav.popBackStack() }) }
    }
}
