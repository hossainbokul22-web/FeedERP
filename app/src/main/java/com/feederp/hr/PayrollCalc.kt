package com.feederp.hr

import com.feederp.data.hr.AttendanceStatus
import com.feederp.data.hr.PayBasis
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * বেতন হিসাবের বিশুদ্ধ নিয়ম (Android ছাড়া, টেস্টযোগ্য)। টাকা সবসময় পয়সায় (Long)।
 *
 * নিয়ম (আপনার প্রতিষ্ঠানের নীতি/বাংলাদেশ শ্রম আইন অনুযায়ী যাচাই করে নিন — এগুলো ডিফল্ট ধরা হয়েছে):
 *  - মাসিক: মোট মাসিক বেতন = মূল + ভাতা; অনুপস্থিতির কর্তন = মোট মাসিক বেতন ÷ মাসের দিন × অনুপস্থিত দিন।
 *  - দৈনিক: মজুরি × (উপস্থিত + বেতনসহ ছুটি) দিন; অনুপস্থিতি কর্তন নেই (না এলে মজুরি নেই)।
 *  - পিস-রেট: শুধু পিস-কাজের মজুরি।
 *  - ওভারটাইম: রেট দেওয়া থাকলে সেটা; নইলে ডিফল্ট = মূল বেতন × ২ ÷ ২০৮ ঘণ্টা (শ্রম আইনের সাধারণ নিয়ম; দৈনিক হলে মজুরি ÷ ৮ × ২)।
 *  - কর্তনের ক্রম: অনুপস্থিতি → জরিমানা (মোটের বেশি হয় না) → ঋণ কিস্তি → অগ্রিম কিস্তি। ঋণ/অগ্রিম এমনভাবে ছাঁটা হয় যাতে নিট ঋণাত্মক না হয়।
 */
data class PayInput(
    val basis: PayBasis,
    val basicPaisa: Long = 0,
    val allowancesPaisa: Long = 0,
    val overtimeRatePaisaPerHour: Long = 0,
    val dailyWagePaisa: Long = 0,
    val daysInMonth: Int,
    val presentDays: Double = 0.0,
    val paidLeaveDays: Double = 0.0,
    val absentDays: Double = 0.0,
    val overtimeMinutes: Int = 0,
    val bonusPaisa: Long = 0,
    val pieceWagePaisa: Long = 0,
    val fineDeductionPaisa: Long = 0,
    val loanInstallmentDuePaisa: Long = 0,
    val advanceInstallmentDuePaisa: Long = 0,
)

data class PayFigures(
    val basicPaisa: Long,
    val allowancesPaisa: Long,
    val overtimePaisa: Long,
    val bonusPaisa: Long,
    val pieceWagePaisa: Long,
    val grossPaisa: Long,
    val absentDeductionPaisa: Long,
    val fineDeductionPaisa: Long,
    val loanDeductionPaisa: Long,
    val advanceDeductionPaisa: Long,
) {
    val totalDeductionPaisa: Long get() = absentDeductionPaisa + fineDeductionPaisa + loanDeductionPaisa + advanceDeductionPaisa
    val netPaisa: Long get() = grossPaisa - totalDeductionPaisa
}

object PayrollCalc {
    const val OT_DIVISOR_HOURS = 208

    private fun round(v: BigDecimal): Long = v.setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** ডিফল্ট ওভারটাইম রেট (পয়সা/ঘণ্টা) = মূল × ২ ÷ ২০৮। */
    fun defaultOvertimeRate(basicPaisa: Long): Long =
        round(BigDecimal.valueOf(basicPaisa).multiply(BigDecimal(2)).divide(BigDecimal(OT_DIVISOR_HOURS), 10, RoundingMode.HALF_UP))

    fun compute(i: PayInput): PayFigures {
        require(i.daysInMonth in 28..31) { "মাসের দিন সংখ্যা ঠিক নয়" }
        require(listOf(i.presentDays, i.paidLeaveDays, i.absentDays).all { it.isFinite() && it >= 0 }) { "দিনের সংখ্যা ঋণাত্মক হতে পারে না" }
        require(i.presentDays + i.paidLeaveDays + i.absentDays <= i.daysInMonth + 0.001) { "উপস্থিত+ছুটি+অনুপস্থিত মাসের দিনের বেশি হতে পারে না" }
        require(listOf(i.basicPaisa, i.allowancesPaisa, i.overtimeRatePaisaPerHour, i.dailyWagePaisa, i.bonusPaisa, i.pieceWagePaisa,
            i.fineDeductionPaisa, i.loanInstallmentDuePaisa, i.advanceInstallmentDuePaisa).all { it >= 0 } && i.overtimeMinutes >= 0) { "টাকা/সময় ঋণাত্মক হতে পারে না" }

        val basic: Long; val allowances: Long; val absentDeduction: Long; val defaultOtBase: Long
        when (i.basis) {
            PayBasis.MONTHLY -> {
                basic = i.basicPaisa; allowances = i.allowancesPaisa
                absentDeduction = round(
                    BigDecimal.valueOf(basic + allowances).multiply(BigDecimal.valueOf(i.absentDays))
                        .divide(BigDecimal(i.daysInMonth), 10, RoundingMode.HALF_UP)
                ).coerceAtMost(basic + allowances)
                defaultOtBase = defaultOvertimeRate(basic)
            }
            PayBasis.DAILY -> {
                basic = round(BigDecimal.valueOf(i.dailyWagePaisa).multiply(BigDecimal.valueOf(i.presentDays + i.paidLeaveDays)))
                allowances = 0; absentDeduction = 0
                defaultOtBase = round(BigDecimal.valueOf(i.dailyWagePaisa).multiply(BigDecimal(2)).divide(BigDecimal(8), 10, RoundingMode.HALF_UP))
            }
            PayBasis.PIECE -> { basic = 0; allowances = 0; absentDeduction = 0; defaultOtBase = 0 }
        }
        val otRate = if (i.overtimeRatePaisaPerHour > 0) i.overtimeRatePaisaPerHour else defaultOtBase
        val overtime = round(BigDecimal.valueOf(otRate).multiply(BigDecimal(i.overtimeMinutes)).divide(BigDecimal(60), 10, RoundingMode.HALF_UP))
        val gross = basic + allowances + overtime + i.bonusPaisa + i.pieceWagePaisa

        var room = gross - absentDeduction
        val fine = minOf(i.fineDeductionPaisa, room.coerceAtLeast(0)); room -= fine
        val loan = minOf(i.loanInstallmentDuePaisa, room.coerceAtLeast(0)); room -= loan
        val adv = minOf(i.advanceInstallmentDuePaisa, room.coerceAtLeast(0))
        return PayFigures(basic, allowances, overtime, i.bonusPaisa, i.pieceWagePaisa, gross, absentDeduction, fine, loan, adv)
    }

    /** ঋণ/অগ্রিমের এই মাসের কিস্তি = min(নির্ধারিত কিস্তি, বাকি ব্যালেন্স)। ব্যালেন্স ≤ ০ হলে ০। */
    fun installmentDue(installmentPaisa: Long, balancePaisa: Long): Long =
        if (balancePaisa <= 0 || installmentPaisa <= 0) 0 else minOf(installmentPaisa, balancePaisa)

    /** মোট কর্তিত [total]-কে একাধিক ঋণের নির্ধারিত কিস্তি ([dues]) অনুযায়ী ক্রমানুসারে ভাগ। যোগফল সবসময় min(total, dues যোগফল)। */
    fun allocateSequential(dues: List<Long>, total: Long): List<Long> {
        var left = total.coerceAtLeast(0)
        return dues.map { d -> val x = minOf(d.coerceAtLeast(0), left); left -= x; x }
    }
}

data class DayRecord(val dayKey: Int, val status: AttendanceStatus, val lateMinutes: Int = 0, val overtimeMinutes: Int = 0)

data class AttendanceSummary(
    val presentDays: Double,
    val paidLeaveDays: Double,
    val absentDays: Double,
    val lateCount: Int,
    val overtimeMinutes: Int,
    /** হাজিরা না তোলা (ছুটি নয় এমন) দিন; এগুলো উপস্থিত ধরা হয়েছে — পে-রোলে সতর্কবার্তা দেখানোর জন্য। */
    val unmarkedDays: Int,
)

object AttendanceCalc {
    /** মাস yyyyMM → ওই মাসের সব dayKey (yyyyMMdd)। */
    fun dayKeys(month: Int): List<Int> {
        val ym = YearMonth.of(month / 100, month % 100)
        return (1..ym.lengthOfMonth()).map { month * 100 + it }
    }

    fun daysInMonth(month: Int): Int = YearMonth.of(month / 100, month % 100).lengthOfMonth()

    /**
     * [records] ওই কর্মীর মাসের হাজিরা, [holidayDayKeys] সরকারি/প্রতিষ্ঠানের ছুটি, [weeklyOff] সাপ্তাহিক ছুটি (যেমন শুক্রবার)।
     * ছুটির দিনে কর্মী না এলে উপস্থিত/অনুপস্থিত কিছুই গোনা হয় না (মাসিক বেতন কাটে না); ছুটির দিনে PRESENT রেকর্ড থাকলে উপস্থিত গোনা হয়।
     * ছুটি নয় এমন দিনে হাজিরা না তোলা থাকলে: [unmarkedAsPresent] সত্য হলে উপস্থিত ধরা হয় (মাসিক বেতনভোগীর জন্য নিরাপদ);
     * মিথ্যা হলে উপস্থিত ধরা হয় না (দৈনিক মজুরির জন্য — নইলে না আসা দিনেরও মজুরি যেত)। দুই ক্ষেত্রেই [AttendanceSummary.unmarkedDays]-এ গোনা হয়।
     * একই দিনে একাধিক রেকর্ড থাকলে সর্বশেষটি (তালিকার শেষ) ধরা হয়।
     */
    fun summarize(
        month: Int, records: List<DayRecord>, holidayDayKeys: Set<Int>,
        weeklyOff: Set<DayOfWeek> = emptySet(), unmarkedAsPresent: Boolean = true,
    ): AttendanceSummary {
        val byDay = records.associateBy { it.dayKey }
        var present = 0.0; var leave = 0.0; var absent = 0.0; var late = 0; var ot = 0; var unmarked = 0
        for (d in dayKeys(month)) {
            val r = byDay[d]
            if (r != null) { ot += r.overtimeMinutes; if (r.lateMinutes > 0) late++ }
            val isHoliday = d in holidayDayKeys || LocalDate.of(d / 10000, d / 100 % 100, d % 100).dayOfWeek in weeklyOff
            if (isHoliday) { if (r?.status == AttendanceStatus.PRESENT) present += 1; continue }
            when (r?.status) {
                AttendanceStatus.PRESENT -> present += 1
                AttendanceStatus.LEAVE -> leave += 1
                AttendanceStatus.ABSENT -> absent += 1
                AttendanceStatus.HOLIDAY -> Unit
                null -> { if (unmarkedAsPresent) present += 1; unmarked += 1 }
            }
        }
        return AttendanceSummary(present, leave, absent, late, ot, unmarked)
    }
}
