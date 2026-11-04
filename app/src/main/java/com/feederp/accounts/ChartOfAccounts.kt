package com.feederp.accounts

import com.feederp.core.AccountCodes

enum class AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE }

data class AccountDef(val code: String, val nameBn: String, val nameEn: String, val type: AccountType) {
    /** ডেবিট-প্রকৃতির খাত (সম্পদ, খরচ) হলে true। */
    val debitNature: Boolean get() = type == AccountType.ASSET || type == AccountType.EXPENSE
}

/** প্রথম চালুতে তৈরি হওয়া হিসাবের খাতের তালিকা (বিশুদ্ধ ডেটা, Android নির্ভরতা নেই)। */
object ChartOfAccounts {
    val defaults: List<AccountDef> = listOf(
        AccountDef(AccountCodes.CASH, "নগদ ক্যাশ", "Cash in Hand", AccountType.ASSET),
        AccountDef(AccountCodes.BANK, "ব্যাংক", "Bank", AccountType.ASSET),
        AccountDef(AccountCodes.MOBILE_BANKING, "মোবাইল ব্যাংকিং", "Mobile Banking", AccountType.ASSET),
        AccountDef(AccountCodes.RECEIVABLE, "বাকি পাওনা (কাস্টমার/ডিলার)", "Accounts Receivable", AccountType.ASSET),
        AccountDef(AccountCodes.EMPLOYEE_ADVANCE, "কর্মচারী অগ্রিম", "Employee Advance", AccountType.ASSET),
        AccountDef(AccountCodes.INVENTORY_RAW, "কাঁচামাল মজুদ", "Raw Material Inventory", AccountType.ASSET),
        AccountDef(AccountCodes.INVENTORY_FG, "ফিনিশড গুডস মজুদ", "Finished Goods Inventory", AccountType.ASSET),
        AccountDef(AccountCodes.INVENTORY_SPARE, "স্পেয়ার পার্টস মজুদ", "Spare Parts Inventory", AccountType.ASSET),
        AccountDef(AccountCodes.FIXED_ASSETS, "মেশিন ও যন্ত্রপাতি", "Fixed Assets", AccountType.ASSET),
        AccountDef(AccountCodes.ACCUM_DEPRECIATION, "পুঞ্জীভূত অবচয়", "Accumulated Depreciation", AccountType.ASSET),
        AccountDef(AccountCodes.PAYABLE, "বাকি দেনা (সাপ্লায়ার)", "Accounts Payable", AccountType.LIABILITY),
        AccountDef(AccountCodes.SALARY_PAYABLE, "বেতন বাকি", "Salary Payable", AccountType.LIABILITY),
        AccountDef(AccountCodes.CAPITAL, "মূলধন", "Capital", AccountType.EQUITY),
        AccountDef(AccountCodes.SALES_REVENUE, "বিক্রয় আয়", "Sales Revenue", AccountType.REVENUE),
        AccountDef(AccountCodes.SALES_RETURN, "বিক্রয় ফেরত", "Sales Returns", AccountType.REVENUE),
        AccountDef(AccountCodes.COGS, "বিক্রীত পণ্যের খরচ", "Cost of Goods Sold", AccountType.EXPENSE),
        AccountDef(AccountCodes.PRODUCTION_LABOR, "প্রোডাকশন লেবার", "Production Labor", AccountType.EXPENSE),
        AccountDef(AccountCodes.PRODUCTION_OVERHEAD, "প্রোডাকশন ওভারহেড", "Production Overhead", AccountType.EXPENSE),
        AccountDef(AccountCodes.WASTAGE_LOSS, "অপচয় ক্ষতি", "Wastage Loss", AccountType.EXPENSE),
        AccountDef(AccountCodes.SALARY_EXPENSE, "বেতন খরচ", "Salary Expense", AccountType.EXPENSE),
        AccountDef(AccountCodes.MAINTENANCE_EXPENSE, "রক্ষণাবেক্ষণ খরচ", "Maintenance Expense", AccountType.EXPENSE),
        AccountDef(AccountCodes.TRANSPORT_EXPENSE, "পরিবহন খরচ", "Transport Expense", AccountType.EXPENSE),
        AccountDef(AccountCodes.DEPRECIATION_EXPENSE, "অবচয় খরচ", "Depreciation Expense", AccountType.EXPENSE),
        AccountDef(AccountCodes.OTHER_EXPENSE, "অন্যান্য খরচ", "Other Expense", AccountType.EXPENSE),
    )

    private val byCode: Map<String, AccountDef> = defaults.associateBy { it.code }

    fun find(code: String): AccountDef? = byCode[code]

    /** খরচ এন্ট্রিতে বাছাইযোগ্য খাত (৫xxx ও ৬xxx, COGS বাদে)। */
    val expenseChoices: List<AccountDef> =
        defaults.filter { it.type == AccountType.EXPENSE && it.code != AccountCodes.COGS }

    /** ক্যাশবুকে অন্তর্ভুক্ত অর্থ-খাত। */
    val cashAccounts: List<String> = listOf(AccountCodes.CASH, AccountCodes.BANK, AccountCodes.MOBILE_BANKING)

    fun cashAccountForMethod(method: String): String = when (method) {
        "BANK" -> AccountCodes.BANK
        "MOBILE_BANKING" -> AccountCodes.MOBILE_BANKING
        else -> AccountCodes.CASH
    }
}
