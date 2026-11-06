package com.feederp.ui.periodreport

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** ওপেনিং স্টক এবং মাসিক/বার্ষিক রিপোর্ট। */
object PeriodReportModule : ModuleEntry {
    const val OPENING = "inventory/opening"
    const val PERIOD = "reports/period"

    override val menu: List<MenuItem> = listOf(
        MenuItem(OPENING, "ওপেনিং স্টক", "Opening Stock", Permission.INVENTORY, "ইনভেন্টরি"),
        MenuItem(PERIOD, "মাসিক/বার্ষিক রিপোর্ট", "Monthly/Yearly Report", Permission.REPORTS, "হিসাব"),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(OPENING) { OpeningStockScreen(onBack = { nav.popBackStack() }) }
        builder.composable(PERIOD) { PeriodReportScreen(onBack = { nav.popBackStack() }) }
    }
}
