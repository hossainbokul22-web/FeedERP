package com.feederp.ui.purchase

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** ক্রয়: PO, গুডস রিসিভ, সাপ্লায়ার পেমেন্ট। */
object PurchaseModule : ModuleEntry {
    private const val GROUP = "ক্রয়"
    const val LIST = "purchase/list"
    const val FORM = "purchase/form"
    const val DETAIL = "purchase/detail"
    const val RECEIVE = "purchase/receive"
    const val GRNS = "purchase/grns"
    const val PAYMENTS = "purchase/payments"

    override val menu: List<MenuItem> = listOf(
        MenuItem(LIST, "ক্রয় অর্ডার (PO)", "Purchase Orders", Permission.PURCHASE, GROUP),
        MenuItem(RECEIVE, "মাল রিসিভ", "Goods Receipt", Permission.PURCHASE, GROUP),
        MenuItem(GRNS, "রিসিভ তালিকা", "Receipts", Permission.PURCHASE, GROUP),
        MenuItem(PAYMENTS, "সাপ্লায়ার পেমেন্ট", "Supplier Payments", Permission.ACCOUNTS, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(LIST) {
            PoListScreen(
                onBack = { nav.popBackStack() },
                onNew = { nav.navigate(FORM) },
                onOpen = { id -> nav.navigate("$DETAIL/$id") },
            )
        }
        builder.composable(
            route = "$FORM?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { PoFormScreen(onBack = { nav.popBackStack() }) }
        builder.composable(
            route = "$DETAIL/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            PoDetailScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("$FORM?id=$id") },
                onReceive = { id -> nav.navigate("$RECEIVE?poId=$id") },
            )
        }
        builder.composable(
            route = "$RECEIVE?poId={poId}",
            arguments = listOf(navArgument("poId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { GoodsReceiptScreen(onBack = { nav.popBackStack() }) }
        builder.composable(GRNS) { GrnListScreen(onBack = { nav.popBackStack() }) }
        builder.composable(PAYMENTS) { SupplierPaymentScreen(onBack = { nav.popBackStack() }) }
    }
}
