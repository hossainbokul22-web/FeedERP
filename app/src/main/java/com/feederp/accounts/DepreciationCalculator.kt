package com.feederp.accounts

import com.feederp.data.maintenance.DepreciationMethod
import kotlin.math.pow
import kotlin.math.roundToLong

data class DepreciationRow(val month: Int, val chargePaisa: Long, val accumulatedPaisa: Long, val bookValuePaisa: Long)

/**
 * অবচয় হিসাব (বিশুদ্ধ, Android নির্ভরতা নেই)। Agent D-র রক্ষণাবেক্ষণ মডিউল এটা সরাসরি ডাকতে পারে;
 * মাসিক চার্জ হিসাব করে `LedgerRepository.postDepreciation(...)` দিয়ে লেজারে তুলতে হবে।
 * সব অঙ্ক পয়সায়। মাস গণনা ১ থেকে শুরু।
 */
object DepreciationCalculator {

    /** সরলরৈখিক: (খরচ - স্ক্র্যাপ) / আয়ুষ্কাল (মাস); শেষ মাসে অবশিষ্টটুকু যায় যাতে ঠিক স্ক্র্যাপ মূল্যে পৌঁছায়। */
    fun straightLineCharge(costPaisa: Long, salvagePaisa: Long, lifeMonths: Int, month: Int, accumulatedPaisa: Long): Long {
        require(costPaisa >= 0 && salvagePaisa in 0..costPaisa) { "স্ক্র্যাপ মূল্য ০ থেকে ক্রয়মূল্যের মধ্যে হতে হবে" }
        require(lifeMonths > 0) { "আয়ুষ্কাল ০ এর বেশি হতে হবে" }
        if (month < 1 || month > lifeMonths) return 0L
        val depreciable = costPaisa - salvagePaisa
        val remaining = depreciable - accumulatedPaisa
        if (remaining <= 0) return 0L
        if (month == lifeMonths) return remaining
        val base = depreciable / lifeMonths
        return minOf(base, remaining)
    }

    /**
     * সুস্পষ্ট হার না দিলে হার নির্ধারণ: স্ক্র্যাপ > 0 হলে 1-(স্ক্র্যাপ/খরচ)^(1/বছর), নইলে ডাবল-ডিক্লাইনিং 2/বছর।
     * ফলাফল দশমিক (০.২ = ২০%)।
     */
    fun impliedAnnualRate(costPaisa: Long, salvagePaisa: Long, lifeMonths: Int): Double {
        require(lifeMonths > 0 && costPaisa > 0)
        val years = lifeMonths / 12.0
        return if (salvagePaisa > 0) 1.0 - (salvagePaisa.toDouble() / costPaisa).pow(1.0 / years)
        else (2.0 / years).coerceAtMost(1.0)
    }

    /** হ্রাসমান জের: প্রারম্ভিক বুক ভ্যালু × (বার্ষিক হার/১২); স্ক্র্যাপ মূল্যের নিচে নামে না। [annualRate] দশমিক (০.১৫ = ১৫%)। */
    fun reducingBalanceCharge(costPaisa: Long, salvagePaisa: Long, annualRate: Double, accumulatedPaisa: Long): Long {
        require(costPaisa >= 0 && salvagePaisa in 0..costPaisa) { "স্ক্র্যাপ মূল্য ০ থেকে ক্রয়মূল্যের মধ্যে হতে হবে" }
        require(annualRate in 0.0..1.0) { "হার ০ থেকে ১ এর মধ্যে (দশমিকে) হতে হবে" }
        val bookValue = costPaisa - accumulatedPaisa
        val room = bookValue - salvagePaisa
        if (room <= 0) return 0L
        val charge = (bookValue * (annualRate / 12.0)).roundToLong()
        return minOf(charge, room)
    }

    /** একটি পদ্ধতিতে নির্দিষ্ট মাসের চার্জ (accumulated = ঐ মাসের আগ পর্যন্ত জমা)। */
    fun monthlyCharge(
        method: DepreciationMethod, costPaisa: Long, salvagePaisa: Long, lifeMonths: Int,
        month: Int, accumulatedPaisa: Long, annualRate: Double? = null,
    ): Long = when (method) {
        DepreciationMethod.STRAIGHT_LINE ->
            straightLineCharge(costPaisa, salvagePaisa, lifeMonths, month, accumulatedPaisa)
        DepreciationMethod.REDUCING_BALANCE -> {
            if (month < 1 || month > lifeMonths) 0L
            else reducingBalanceCharge(
                costPaisa, salvagePaisa,
                annualRate ?: impliedAnnualRate(costPaisa, salvagePaisa, lifeMonths),
                accumulatedPaisa,
            )
        }
    }

    /** পুরো আয়ুষ্কালের সময়সূচি। */
    fun schedule(
        method: DepreciationMethod, costPaisa: Long, salvagePaisa: Long, lifeMonths: Int, annualRate: Double? = null,
    ): List<DepreciationRow> {
        val rows = ArrayList<DepreciationRow>(lifeMonths)
        var acc = 0L
        for (m in 1..lifeMonths) {
            val c = monthlyCharge(method, costPaisa, salvagePaisa, lifeMonths, m, acc, annualRate)
            acc += c
            rows += DepreciationRow(m, c, acc, costPaisa - acc)
        }
        return rows
    }
}
