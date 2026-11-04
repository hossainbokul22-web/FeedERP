package com.feederp.ui.accounts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** হিসাব: খরচ (অনুমোদনসহ), লাভ-ক্ষতি ও ট্রায়াল ব্যালেন্স। */
object AccountsModule : ModuleEntry {
    private const val GROUP = "হিসাব"
    const val EXPENSES = "accounts/expenses"
    const val REPORTS = "accounts/reports"
    const val PARTY_LEDGER = "accounts/party-ledger"
    const val AGING = "accounts/aging"
    const val CASHBOOK = "accounts/cashbook"

    override val menu: List<MenuItem> = listOf(
        MenuItem(EXPENSES, "খরচ", "Expenses", Permission.ACCOUNTS, GROUP),
        MenuItem(REPORTS, "হিসাব রিপোর্ট", "Reports", Permission.REPORTS, GROUP),
        MenuItem(PARTY_LEDGER, "পার্টি লেজার", "Party Ledger", Permission.REPORTS, GROUP),
        MenuItem(AGING, "বাকির বয়স", "Receivable Aging", Permission.REPORTS, GROUP),
        MenuItem(CASHBOOK, "ক্যাশবুক", "Cash Book", Permission.ACCOUNTS, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(EXPENSES) { ExpenseScreen(onBack = { nav.popBackStack() }) }
        builder.composable(REPORTS) { ReportsScreen(onBack = { nav.popBackStack() }) }
        builder.composable(PARTY_LEDGER) { PartyLedgerScreen(onBack = { nav.popBackStack() }) }
        builder.composable(AGING) { AgingScreen(onBack = { nav.popBackStack() }) }
        builder.composable(CASHBOOK) { CashBookScreen(onBack = { nav.popBackStack() }) }
    }
}
