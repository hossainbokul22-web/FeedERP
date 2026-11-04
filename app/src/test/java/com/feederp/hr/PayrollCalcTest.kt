package com.feederp.hr

import com.feederp.data.hr.AttendanceStatus
import com.feederp.data.hr.PayBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollCalcTest {
    private fun monthly(
        absent: Double = 0.0, otMin: Int = 0, bonus: Long = 0, fine: Long = 0, loan: Long = 0, adv: Long = 0,
        basic: Long = 1_500_000, allow: Long = 500_000, rate: Long = 0, days: Int = 30,
    ) = PayInput(
        PayBasis.MONTHLY, basicPaisa = basic, allowancesPaisa = allow, overtimeRatePaisaPerHour = rate, daysInMonth = days,
        presentDays = days - absent, absentDays = absent, overtimeMinutes = otMin, bonusPaisa = bonus,
        fineDeductionPaisa = fine, loanInstallmentDuePaisa = loan, advanceInstallmentDuePaisa = adv,
    )

    @Test fun plainMonthlyNoDeductions() {
        val f = PayrollCalc.compute(monthly())
        assertEquals(2_000_000L, f.grossPaisa)
        assertEquals(0L, f.totalDeductionPaisa)
        assertEquals(2_000_000L, f.netPaisa)
    }

    @Test fun absentDeductionIsProRataOnGrossMonthly() {
        // ২০,০০০ ÷ ৩০ × ৩ = ২,০০০ টাকা
        val f = PayrollCalc.compute(monthly(absent = 3.0))
        assertEquals(200_000L, f.absentDeductionPaisa)
        assertEquals(1_800_000L, f.netPaisa)
    }

    @Test fun defaultOvertimeRateIsDoubleBasicOver208() {
        // ১৫,০০০ × ২ ÷ ২০৮ = ১৪৪.২৩ টাকা = ১৪,৪২৩ পয়সা
        assertEquals(14_423L, PayrollCalc.defaultOvertimeRate(1_500_000))
        val f = PayrollCalc.compute(monthly(otMin = 120))
        assertEquals(28_846L, f.overtimePaisa)
    }

    @Test fun explicitOvertimeRateWins() {
        val f = PayrollCalc.compute(monthly(otMin = 90, rate = 20_000))
        assertEquals(30_000L, f.overtimePaisa)
    }

    @Test fun bonusAddsToGross() {
        val f = PayrollCalc.compute(monthly(bonus = 500_000))
        assertEquals(2_500_000L, f.grossPaisa)
    }

    @Test fun deductionsAreCappedSoNetNeverNegative() {
        val f = PayrollCalc.compute(monthly(basic = 100_000, allow = 0, fine = 30_000, loan = 50_000, adv = 80_000))
        assertEquals(30_000L, f.fineDeductionPaisa)
        assertEquals(50_000L, f.loanDeductionPaisa)
        assertEquals(20_000L, f.advanceDeductionPaisa)   // বাকি জায়গায় যতটুকু
        assertEquals(0L, f.netPaisa)
    }

    @Test fun fineCannotExceedPayAfterAbsence() {
        val f = PayrollCalc.compute(monthly(basic = 100_000, allow = 0, absent = 30.0, fine = 50_000))
        assertEquals(100_000L, f.absentDeductionPaisa)
        assertEquals(0L, f.fineDeductionPaisa)
        assertEquals(0L, f.netPaisa)
    }

    @Test fun dailyWorkerPaidForPresentAndPaidLeaveOnly() {
        val i = PayInput(PayBasis.DAILY, dailyWagePaisa = 60_000, daysInMonth = 30, presentDays = 20.0, paidLeaveDays = 2.0, absentDays = 8.0)
        val f = PayrollCalc.compute(i)
        assertEquals(22 * 60_000L, f.basicPaisa)
        assertEquals(0L, f.absentDeductionPaisa)
        assertEquals(22 * 60_000L, f.netPaisa)
    }

    @Test fun dailyDefaultOvertimeIsDailyWageOver8Times2() {
        val i = PayInput(PayBasis.DAILY, dailyWagePaisa = 80_000, daysInMonth = 30, presentDays = 1.0, overtimeMinutes = 60)
        assertEquals(20_000L, PayrollCalc.compute(i).overtimePaisa)
    }

    @Test fun pieceRatePaysOnlyPieceWork() {
        val i = PayInput(PayBasis.PIECE, daysInMonth = 30, pieceWagePaisa = 345_000, presentDays = 10.0)
        val f = PayrollCalc.compute(i)
        assertEquals(0L, f.basicPaisa)
        assertEquals(345_000L, f.netPaisa)
    }

    @Test fun installmentDueIsCappedByBalance() {
        assertEquals(500L, PayrollCalc.installmentDue(500, 2000))
        assertEquals(300L, PayrollCalc.installmentDue(500, 300))
        assertEquals(0L, PayrollCalc.installmentDue(500, 0))
        assertEquals(0L, PayrollCalc.installmentDue(0, 900))
    }

    @Test fun invalidInputsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PayrollCalc.compute(monthly(days = 27)) }
        assertThrows(IllegalArgumentException::class.java) { PayrollCalc.compute(monthly(absent = -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { PayrollCalc.compute(monthly(bonus = -5)) }
        assertThrows(IllegalArgumentException::class.java) {
            PayrollCalc.compute(PayInput(PayBasis.MONTHLY, basicPaisa = 1, daysInMonth = 30, presentDays = 25.0, absentDays = 10.0))
        }
    }

    @Test fun attendanceSummary() {
        val m = 202506   // জুন, ৩০ দিন
        val recs = listOf(
            DayRecord(20250601, AttendanceStatus.PRESENT, lateMinutes = 10, overtimeMinutes = 60),
            DayRecord(20250602, AttendanceStatus.ABSENT),
            DayRecord(20250603, AttendanceStatus.LEAVE),
            DayRecord(20250604, AttendanceStatus.PRESENT, overtimeMinutes = 30),
        )
        val s = AttendanceCalc.summarize(m, recs, holidayDayKeys = setOf(20250606, 20250607))
        assertEquals(1.0, s.absentDays, 0.0)
        assertEquals(1.0, s.paidLeaveDays, 0.0)
        assertEquals(1, s.lateCount)
        assertEquals(90, s.overtimeMinutes)
        // ৩০ দিন − ২ ছুটি − ১ অনুপস্থিত − ১ ছুটি = ২৬ উপস্থিত (হাজিরা না তোলা ২৪ দিনসহ)
        assertEquals(2.0 + 24.0, s.presentDays, 0.0)
        assertEquals(24, s.unmarkedDays)
        assertTrue(s.presentDays + s.paidLeaveDays + s.absentDays <= 30)
    }

    @Test fun holidayWithRecordDoesNotCountAsAbsent() {
        val s = AttendanceCalc.summarize(202506, listOf(DayRecord(20250606, AttendanceStatus.ABSENT)), setOf(20250606))
        assertEquals(0.0, s.absentDays, 0.0)
    }

    @Test fun monthHelpers() {
        assertEquals(29, AttendanceCalc.daysInMonth(202402))
        assertEquals(28, AttendanceCalc.daysInMonth(202502))
        assertEquals(20250601, AttendanceCalc.dayKeys(202506).first())
        assertEquals(30, AttendanceCalc.dayKeys(202506).size)
    }

    @Test fun unmarkedDaysNotPaidForDailyWorkers() {
        val s = AttendanceCalc.summarize(202506, listOf(DayRecord(20250601, AttendanceStatus.PRESENT)), emptySet(), unmarkedAsPresent = false)
        assertEquals(1.0, s.presentDays, 0.0)
        assertEquals(29, s.unmarkedDays)
    }

    @Test fun weeklyOffFridaysAreSkippedButWorkedFridayCounts() {
        // জুন ২০২৫: শুক্রবার ৬, ১৩, ২০, ২৭
        val s = AttendanceCalc.summarize(
            202506, listOf(DayRecord(20250606, AttendanceStatus.ABSENT), DayRecord(20250613, AttendanceStatus.PRESENT)),
            emptySet(), weeklyOff = setOf(java.time.DayOfWeek.FRIDAY), unmarkedAsPresent = false,
        )
        assertEquals(0.0, s.absentDays, 0.0)
        assertEquals(1.0, s.presentDays, 0.0)           // ১৩ তারিখে কাজ করেছে
        assertEquals(30 - 4, s.unmarkedDays)             // শুক্রবার ৪টি বাদে ২৬ দিন হাজিরা তোলা নেই
    }

    @Test fun sequentialAllocationOfInstallments() {
        assertEquals(listOf(500L, 300L, 0L), PayrollCalc.allocateSequential(listOf(500, 400, 200), 800))
        assertEquals(listOf(500L, 400L, 200L), PayrollCalc.allocateSequential(listOf(500, 400, 200), 5000))
        assertEquals(listOf(0L, 0L), PayrollCalc.allocateSequential(listOf(500, 400), 0))
    }
}
