package com.feederp.ui.sales

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** বিক্রয়: ইনভয়েস, আদায়, দামের তালিকা। (ডেলিভারি ও ফেরত পরের ধাপে।) */
object SalesModule : ModuleEntry {
    private const val GROUP = "বিক্রয়"
    const val LIST = "sales/list"
    const val NEW = "sales/new"
    const val DETAIL = "sales/detail"
    const val PRICES = "sales/prices"
    const val RETURN = "sales/return"

    override val menu: List<MenuItem> = listOf(
        MenuItem(LIST, "বিক্রয় ইনভয়েস", "Sales", Permission.SALES, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(LIST) {
            InvoiceListScreen(
                onBack = { nav.popBackStack() }, onNew = { nav.navigate(NEW) },
                onOpen = { id -> nav.navigate("$DETAIL/$id") }, onPrices = { nav.navigate(PRICES) },
            )
        }
        builder.composable(NEW) {
            NewInvoiceScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id -> nav.navigate("$DETAIL/$id") { popUpTo(LIST) } },
            )
        }
        builder.composable(
            route = "$DETAIL/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { InvoiceDetailScreen(onBack = { nav.popBackStack() }, onReturn = { nav.navigate("$RETURN/$it") }) }
        builder.composable(
            route = "$RETURN/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { SalesReturnScreen(onBack = { nav.popBackStack() }) }
        builder.composable(PRICES) { PriceListScreen(onBack = { nav.popBackStack() }) }
    }
}
