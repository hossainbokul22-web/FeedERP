package com.feederp.accounts

import com.feederp.core.AccountCodes

/** ভাউচার যাচাই: ডেবিট যোগফল = ক্রেডিট যোগফল, অন্তত দুটি লাইন, নেগেটিভ নয়, একই লাইনে দুই পাশ নয়। */
object VoucherValidator {
    /** ত্রুটি থাকলে বাংলা বার্তা, না থাকলে null। [knownCode] দিলে অজানা খাত কোডও ধরা পড়ে। */
    fun validate(lines: List<LedgerLine>, knownCode: (String) -> Boolean = { true }): String? {
        if (lines.size < 2) return "ভাউচারে অন্তত দুটি লাইন লাগবে"
        var debit = 0L
        var credit = 0L
        for (l in lines) {
            if (l.debitPaisa < 0 || l.creditPaisa < 0) return "টাকার অঙ্ক ঋণাত্মক হতে পারবে না"
            if (l.debitPaisa > 0 && l.creditPaisa > 0) return "একই লাইনে ডেবিট ও ক্রেডিট দুটোই দেওয়া যাবে না"
            if (l.debitPaisa == 0L && l.creditPaisa == 0L) return "শূন্য অঙ্কের লাইন গ্রহণযোগ্য নয়"
            if (!knownCode(l.accountCode)) return "অজানা হিসাব কোড: ${l.accountCode}"
            if ((l.accountCode == AccountCodes.RECEIVABLE || l.accountCode == AccountCodes.PAYABLE) && l.partyId == null) {
                return "বাকি পাওনা/দেনার লাইনে পার্টি দিতে হবে (${l.accountCode})"
            }
            debit += l.debitPaisa
            credit += l.creditPaisa
        }
        if (debit != credit) return "ডেবিট ($debit) ও ক্রেডিট ($credit) সমান নয়"
        return null
    }

    /** বিপরীত (রিভার্সাল) লাইন: ডেবিট-ক্রেডিট অদলবদল। */
    fun reverse(lines: List<LedgerLine>): List<LedgerLine> =
        lines.map { it.copy(debitPaisa = it.creditPaisa, creditPaisa = it.debitPaisa) }
}

/** একটি খাতের মোট ডেবিট/ক্রেডিট (DAO থেকে)। */
data class AccountTotal(val accountCode: String, val debitPaisa: Long, val creditPaisa: Long)

data class TrialBalanceRow(val code: String, val name: String, val debitPaisa: Long, val creditPaisa: Long)

data class TrialBalance(val rows: List<TrialBalanceRow>, val totalDebitPaisa: Long, val totalCreditPaisa: Long) {
    val balanced: Boolean get() = totalDebitPaisa == totalCreditPaisa
}

data class PnlLine(val code: String, val name: String, val amountPaisa: Long)

data class ProfitAndLoss(
    val revenue: List<PnlLine>,
    val directCosts: List<PnlLine>,
    val expenses: List<PnlLine>,
) {
    val totalRevenuePaisa: Long get() = revenue.sumOf { it.amountPaisa }
    val totalDirectCostPaisa: Long get() = directCosts.sumOf { it.amountPaisa }
    val grossProfitPaisa: Long get() = totalRevenuePaisa - totalDirectCostPaisa
    val totalExpensePaisa: Long get() = expenses.sumOf { it.amountPaisa }
    val netProfitPaisa: Long get() = grossProfitPaisa - totalExpensePaisa
}

data class CashBookRow(
    val entryId: String,
    val date: Long,
    val accountCode: String,
    val memo: String?,
    val inPaisa: Long,
    val outPaisa: Long,
    val balancePaisa: Long,
)

/** ক্যাশবুক গণনার ইনপুট (লেজার এন্ট্রির প্রয়োজনীয় অংশ)। */
data class CashEntry(
    val entryId: String,
    val date: Long,
    val accountCode: String,
    val memo: String?,
    val debitPaisa: Long,
    val creditPaisa: Long,
)

object AccountingCalc {
    /** ট্রায়াল ব্যালেন্স: প্রতিটি খাতের নিট ব্যালেন্স তার স্বাভাবিক নয় বরং প্রকৃত পাশে (ডেবিট>ক্রেডিট হলে ডেবিট কলামে) বসে। */
    fun trialBalance(totals: List<AccountTotal>, chart: List<AccountDef> = ChartOfAccounts.defaults): TrialBalance {
        val names = chart.associateBy { it.code }
        val merged = totals.groupBy { it.accountCode }.map { (code, list) ->
            AccountTotal(code, list.sumOf { it.debitPaisa }, list.sumOf { it.creditPaisa })
        }.sortedBy { it.accountCode }
        val rows = merged.mapNotNull { t ->
            val net = t.debitPaisa - t.creditPaisa
            if (net == 0L && t.debitPaisa == 0L) return@mapNotNull null
            TrialBalanceRow(
                code = t.accountCode,
                name = names[t.accountCode]?.nameBn ?: t.accountCode,
                debitPaisa = if (net > 0) net else 0L,
                creditPaisa = if (net < 0) -net else 0L,
            )
        }
        return TrialBalance(rows, rows.sumOf { it.debitPaisa }, rows.sumOf { it.creditPaisa })
    }

    /** লাভ-ক্ষতি: আয় = ক্রেডিট-ডেবিট (বিক্রয় ফেরত স্বয়ংক্রিয়ভাবে বিয়োগ), ৫xxx = সরাসরি খরচ, ৬xxx = পরিচালন খরচ। */
    fun profitAndLoss(totals: List<AccountTotal>, chart: List<AccountDef> = ChartOfAccounts.defaults): ProfitAndLoss {
        val defs = chart.associateBy { it.code }
        val merged = totals.groupBy { it.accountCode }.mapValues { (_, l) ->
            l.sumOf { it.debitPaisa } to l.sumOf { it.creditPaisa }
        }
        val revenue = mutableListOf<PnlLine>()
        val direct = mutableListOf<PnlLine>()
        val expense = mutableListOf<PnlLine>()
        for ((code, dc) in merged.toSortedMap()) {
            val def = defs[code] ?: continue
            when (def.type) {
                AccountType.REVENUE -> revenue += PnlLine(code, def.nameBn, dc.second - dc.first)
                AccountType.EXPENSE ->
                    if (code.startsWith("5")) direct += PnlLine(code, def.nameBn, dc.first - dc.second)
                    else expense += PnlLine(code, def.nameBn, dc.first - dc.second)
                else -> Unit
            }
        }
        return ProfitAndLoss(revenue, direct, expense)
    }

    /** ক্যাশবুক: এন্ট্রিগুলো তারিখ অনুযায়ী (একই সময়ে ইনপুটের ক্রম বজায় রেখে, কারণ sortedBy স্থিতিশীল) সাজিয়ে চলমান ব্যালেন্সসহ; [openingPaisa] = শুরুর জের। */
    fun cashBook(entries: List<CashEntry>, openingPaisa: Long = 0L): List<CashBookRow> {
        var bal = openingPaisa
        return entries.sortedBy { it.date }.map { e ->
            bal += e.debitPaisa - e.creditPaisa
            CashBookRow(e.entryId, e.date, e.accountCode, e.memo, e.debitPaisa, e.creditPaisa, bal)
        }
    }

    /** পার্টি লেজার চলমান ব্যালেন্স: + = আমরা পাব। */
    fun runningBalance(deltas: List<Long>, openingPaisa: Long = 0L): List<Long> {
        var b = openingPaisa
        return deltas.map { b += it; b }
    }
}

/** বাকি পাওনার এজিং বাকেট (দিন): 0-30, 31-60, 61-90, 90+। */
data class AgeingRow(
    val partyId: String,
    val currentPaisa: Long,   // 0-30
    val d31to60Paisa: Long,
    val d61to90Paisa: Long,
    val over90Paisa: Long,
    val advancePaisa: Long,   // অগ্রিম/বেশি পাওয়া (ধনাত্মক সংখ্যা)
) {
    val totalDuePaisa: Long get() = currentPaisa + d31to60Paisa + d61to90Paisa + over90Paisa
}

/** [amountPaisa]: ধনাত্মক = বিল (ডেবিট), ঋণাত্মক = আদায়/ফেরত (ক্রেডিট)। */
data class AgeInput(val partyId: String, val date: Long, val amountPaisa: Long)

object AgeingCalc {
    private const val DAY = 86_400_000L

    /** আদায়গুলো সবচেয়ে পুরনো বিল থেকে (FIFO) বাদ দিয়ে বাকির বয়স হিসাব করে। */
    fun age(inputs: List<AgeInput>, asOf: Long): List<AgeingRow> =
        inputs.groupBy { it.partyId }.map { (partyId, list) ->
            var credit = list.filter { it.amountPaisa < 0 }.sumOf { -it.amountPaisa }
            var b0 = 0L; var b1 = 0L; var b2 = 0L; var b3 = 0L
            for (inv in list.filter { it.amountPaisa > 0 }.sortedBy { it.date }) {
                val settle = minOf(credit, inv.amountPaisa)
                credit -= settle
                val open = inv.amountPaisa - settle
                if (open == 0L) continue
                val days = ((asOf - inv.date) / DAY).coerceAtLeast(0L)
                when {
                    days <= 30 -> b0 += open
                    days <= 60 -> b1 += open
                    days <= 90 -> b2 += open
                    else -> b3 += open
                }
            }
            AgeingRow(partyId, b0, b1, b2, b3, credit)
        }.filter { it.totalDuePaisa != 0L || it.advancePaisa != 0L }.sortedByDescending { it.totalDuePaisa }
}
