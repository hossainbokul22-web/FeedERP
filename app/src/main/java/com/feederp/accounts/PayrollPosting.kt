package com.feederp.accounts

import com.feederp.core.AccountCodes

/**
 * বেতন পোস্টিংয়ের লাইন বানানোর বিশুদ্ধ সহায়ক। Agent D (HR) বেতন অনুমোদনে এগুলো দিয়ে
 * `LedgerRepository.postPayrollAccrual(...)` / `postSalaryPayment(...)` ডাকবে (অথবা সরাসরি `post(...)`)।
 */
object PayrollPosting {
    /**
     * বেতন অনুমোদন: ডেবিট বেতন খরচ (মোট), ক্রেডিট কর্মচারী অগ্রিম (কর্তিত অংশ), ক্রেডিট বেতন বাকি (নিট প্রদেয়)।
     * [grossPaisa] ফাইন/অন্য কর্তন বাদ দেওয়া মোট ব্যয়; [advanceRecoveryPaisa] সেখান থেকে অগ্রিম কর্তন।
     */
    fun accrualLines(grossPaisa: Long, advanceRecoveryPaisa: Long = 0L): List<LedgerLine> {
        require(grossPaisa > 0) { "মোট বেতন ০ এর বেশি হতে হবে" }
        require(advanceRecoveryPaisa in 0..grossPaisa) { "অগ্রিম কর্তন মোট বেতনের বেশি হতে পারে না" }
        val net = grossPaisa - advanceRecoveryPaisa
        val lines = mutableListOf(LedgerLine(AccountCodes.SALARY_EXPENSE, debitPaisa = grossPaisa))
        if (advanceRecoveryPaisa > 0) lines += LedgerLine(AccountCodes.EMPLOYEE_ADVANCE, creditPaisa = advanceRecoveryPaisa)
        if (net > 0) lines += LedgerLine(AccountCodes.SALARY_PAYABLE, creditPaisa = net)
        return lines
    }

    /** বেতন পরিশোধ: ডেবিট বেতন বাকি, ক্রেডিট ক্যাশ/ব্যাংক/মোবাইল ব্যাংকিং। */
    fun paymentLines(amountPaisa: Long, method: String): List<LedgerLine> {
        require(amountPaisa > 0) { "পরিশোধের অঙ্ক ০ এর বেশি হতে হবে" }
        return listOf(
            LedgerLine(AccountCodes.SALARY_PAYABLE, debitPaisa = amountPaisa),
            LedgerLine(ChartOfAccounts.cashAccountForMethod(method), creditPaisa = amountPaisa),
        )
    }

    /** কর্মচারীকে অগ্রিম দেওয়া: ডেবিট অগ্রিম, ক্রেডিট ক্যাশ/ব্যাংক। */
    fun advanceLines(amountPaisa: Long, method: String): List<LedgerLine> {
        require(amountPaisa > 0) { "অগ্রিমের অঙ্ক ০ এর বেশি হতে হবে" }
        return listOf(
            LedgerLine(AccountCodes.EMPLOYEE_ADVANCE, debitPaisa = amountPaisa),
            LedgerLine(ChartOfAccounts.cashAccountForMethod(method), creditPaisa = amountPaisa),
        )
    }

    /** অবচয়: ডেবিট অবচয় খরচ, ক্রেডিট পুঞ্জীভূত অবচয়। */
    fun depreciationLines(amountPaisa: Long): List<LedgerLine> {
        require(amountPaisa > 0) { "অবচয়ের অঙ্ক ০ এর বেশি হতে হবে" }
        return listOf(
            LedgerLine(AccountCodes.DEPRECIATION_EXPENSE, debitPaisa = amountPaisa),
            LedgerLine(AccountCodes.ACCUM_DEPRECIATION, creditPaisa = amountPaisa),
        )
    }
}
