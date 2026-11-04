package com.feederp.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class LeaveCalcTest {
    private val fri = setOf(DayOfWeek.FRIDAY)

    @Test fun skipsWeeklyOffAndHolidays() {
        // জুন ২০২৫: ১–৭ তারিখ; ৬ = শুক্রবার, ৩ = সরকারি ছুটি ধরা হলো
        val d = LeaveCalc.workingDays(20250601, 20250607, setOf(20250603), fri)
        assertEquals(listOf(20250601, 20250602, 20250604, 20250605, 20250607), d)
    }

    @Test fun singleDay() {
        assertEquals(listOf(20250602), LeaveCalc.workingDays(20250602, 20250602, emptySet(), fri))
        assertTrue(LeaveCalc.workingDays(20250606, 20250606, emptySet(), fri).isEmpty())   // শুধু শুক্রবার
    }

    @Test fun rejectsBadRanges() {
        assertThrows(IllegalArgumentException::class.java) { LeaveCalc.workingDays(20250610, 20250601, emptySet(), fri) }
        assertThrows(IllegalArgumentException::class.java) { LeaveCalc.workingDays(20240101, 20250601, emptySet(), fri) }
    }

    @Test fun crossMonthAndLeapYear() {
        assertEquals(2, LeaveCalc.workingDays(20240229, 20240301, emptySet(), emptySet()).size)
    }

    @Test fun overlapRules() {
        assertTrue(LeaveCalc.overlaps(1, 5, 5, 9))
        assertFalse(LeaveCalc.overlaps(1, 4, 5, 9))
        assertTrue(LeaveCalc.overlaps(3, 4, 1, 9))
    }

    @Test fun remainingBalance() {
        assertEquals(4.0, LeaveCalc.remaining(10.0, 6.0), 0.0)
        assertEquals(-2.0, LeaveCalc.remaining(10.0, 12.0), 0.0)
    }
}
