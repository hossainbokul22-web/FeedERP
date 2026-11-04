package com.feederp.sales

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SalesCalcTest {
    private val day = 86_400_000L

    // ---- মোট / ডিসকাউন্ট / ভ্যাট ----

    @Test
    fun lineTotal_roundsHalfUp() {
        assertEquals(125_000L, SalesCalc.lineTotalPaisa(2_500.0, 50_000))   // 2.5 কেজি × ৫০০ টাকা
        assertEquals(0L, SalesCalc.lineTotalPaisa(0.0, 50_000))
        // 1 গ্রাম × 5 পয়সা/কেজি = 0.005 → 0
        assertEquals(0L, SalesCalc.lineTotalPaisa(1.0, 5))
        // 100 গ্রাম × 5 পয়সা/কেজি = 0.5 → 1 (HALF_UP)
        assertEquals(1L, SalesCalc.lineTotalPaisa(100.0, 5))
        // 300 গ্রাম × 5 = 1.5 → 2
        assertEquals(2L, SalesCalc.lineTotalPaisa(300.0, 5))
        // 25 কেজি × 4,250 টাকা/কেজি
        assertEquals(10_625_000L, SalesCalc.lineTotalPaisa(25_000.0, 425_000))
    }

    @Test
    fun discount_andVat_percentages() {
        assertEquals(500L, SalesCalc.discountFromBps(10_000, 500))      // ৫%
        assertEquals(0L, SalesCalc.discountFromBps(10_000, 0))
        assertEquals(1L, SalesCalc.percentOf(10, 500))                  // 0.5 → 1
        assertEquals(0L, SalesCalc.percentOf(9, 500))                   // 0.45 → 0
        assertEquals(750L, SalesCalc.vatPaisa(5_000, 1_500))            // ১৫%
        assertEquals(0L, SalesCalc.vatPaisa(-100, 1_500))               // ঋণাত্মক করযোগ্য = ০
    }

    @Test
    fun invoiceTotal_subtotalMinusDiscountPlusVat() {
        assertEquals(9_500L, SalesCalc.invoiceTotalPaisa(10_000, 500))
        assertEquals(10_925L, SalesCalc.invoiceTotalPaisa(10_000, 500, 1_500)) // 9500 + 15% (1425)
        assertEquals(0L, SalesCalc.invoiceTotalPaisa(10_000, 10_000, 1_500))
        assertEquals(0L, SalesCalc.invoiceTotalPaisa(0, 0))
    }

    @Test
    fun invoiceTotal_rejectsBadDiscount() {
        try { SalesCalc.invoiceTotalPaisa(1_000, 1_001); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.invoiceTotalPaisa(1_000, -1); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.invoiceTotalPaisa(-1, 0); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun discountLimit_forSalesRole() {
        assertTrue(SalesCalc.isDiscountWithinSalesLimit(100_000, 5_000))
        assertFalse(SalesCalc.isDiscountWithinSalesLimit(100_000, 5_001))
        assertTrue(SalesCalc.isDiscountWithinSalesLimit(100_000, 0))
    }

    @Test
    fun allocateDiscount_sumsExactly() {
        val parts = SalesCalc.allocateDiscount(listOf(3_333L, 3_333L, 3_334L), 100)
        assertEquals(100L, parts.sum())
        assertEquals(listOf(0L, 0L, 0L), SalesCalc.allocateDiscount(listOf(100L, 200L, 300L), 0))
        assertEquals(emptyList<Long>(), SalesCalc.allocateDiscount(emptyList(), 50))
        assertEquals(listOf(10L, 20L), SalesCalc.allocateDiscount(listOf(1_000L, 2_000L), 30))
    }

    // ---- ফেরত ----

    @Test
    fun returnCredit_partialAndFullRemainderHasNoRoundingLeak() {
        // লাইন: 3 কেজি, নেট 1000 পয়সা। ১ কেজি ফেরত = 333, আবার ১ কেজি = 333, শেষ ১ কেজি = বাকি 334।
        val c1 = SalesCalc.returnCreditPaisa(3000.0, 1000, 0.0, 0, 1000.0)
        assertEquals(333L, c1)
        val c2 = SalesCalc.returnCreditPaisa(3000.0, 1000, 1000.0, c1, 1000.0)
        assertEquals(333L, c2)
        val c3 = SalesCalc.returnCreditPaisa(3000.0, 1000, 2000.0, c1 + c2, 1000.0)
        assertEquals(334L, c3)
        assertEquals(1000L, c1 + c2 + c3)
    }

    @Test
    fun returnCredit_rejectsOverReturnAndZero() {
        try { SalesCalc.returnCreditPaisa(1000.0, 500, 0.0, 0, 1001.0); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.returnCreditPaisa(1000.0, 500, 600.0, 300, 500.0); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.returnCreditPaisa(1000.0, 500, 0.0, 0, 0.0); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- ক্রেডিট লিমিট ----

    @Test
    fun credit_withinLimit_allowed() {
        val r = SalesCalc.checkCredit(limitPaisa = 100_000, currentBalancePaisa = 40_000, newUnpaidPaisa = 60_000)
        assertTrue(r.allowed)
        assertEquals(100_000L, r.exposureAfterPaisa)
        assertEquals(0L, r.exceededByPaisa)
    }

    @Test
    fun credit_overLimit_blockedWithExcess() {
        val r = SalesCalc.checkCredit(100_000, 40_000, 60_001)
        assertFalse(r.allowed)
        assertEquals(1L, r.exceededByPaisa)
    }

    @Test
    fun credit_zeroLimit_meansCashOnly_butFullyPaidAlwaysOk() {
        assertFalse(SalesCalc.checkCredit(0, 0, 1).allowed)
        assertTrue(SalesCalc.checkCredit(0, 0, 0).allowed)
        // পুরো নগদ: পুরনো বকেয়া লিমিটের ওপরে থাকলেও এই বিক্রি আটকায় না
        assertTrue(SalesCalc.checkCredit(100, 5_000, 0).allowed)
    }

    @Test
    fun credit_advanceReducesExposure() {
        val r = SalesCalc.checkCredit(50_000, -20_000, 60_000)   // অগ্রিম ২০,০০০ জমা আছে
        assertTrue(r.allowed)
        assertEquals(40_000L, r.exposureAfterPaisa)
    }

    // ---- এজিং ----

    @Test
    fun bucket_boundaries() {
        assertEquals(AgingBucket.D0_30, SalesCalc.bucketOf(0))
        assertEquals(AgingBucket.D0_30, SalesCalc.bucketOf(30))
        assertEquals(AgingBucket.D31_60, SalesCalc.bucketOf(31))
        assertEquals(AgingBucket.D31_60, SalesCalc.bucketOf(60))
        assertEquals(AgingBucket.D61_90, SalesCalc.bucketOf(61))
        assertEquals(AgingBucket.D61_90, SalesCalc.bucketOf(90))
        assertEquals(AgingBucket.D90_PLUS, SalesCalc.bucketOf(91))
        assertEquals(AgingBucket.D90_PLUS, SalesCalc.bucketOf(1000))
    }

    @Test
    fun ageDays_floorAndFutureDate() {
        val now = 100 * day
        assertEquals(0L, SalesCalc.ageDays(now, now))
        assertEquals(0L, SalesCalc.ageDays(now - day + 1, now))
        assertEquals(1L, SalesCalc.ageDays(now - day, now))
        assertEquals(0L, SalesCalc.ageDays(now + 5 * day, now))
    }

    @Test
    fun aging_sumsPerBucket_ignoresPaidInvoices() {
        val now = 200 * day
        val totals = SalesCalc.aging(
            listOf(
                (now - 5 * day) to 100L,
                (now - 30 * day) to 200L,
                (now - 31 * day) to 400L,
                (now - 75 * day) to 800L,
                (now - 120 * day) to 1_600L,
                (now - 10 * day) to 0L,       // পরিশোধিত
                (now - 10 * day) to -50L,     // ঋণাত্মক বাদ
            ),
            now,
        )
        assertEquals(AgingTotals(300, 400, 800, 1_600), totals)
        assertEquals(3_100L, totals.total)
    }

    @Test
    fun outstanding_neverNegative() {
        assertEquals(700L, SalesCalc.outstandingPaisa(1_000, 200, 100))
        assertEquals(0L, SalesCalc.outstandingPaisa(1_000, 900, 200))
    }

    // ---- বস্তা ↔ কেজি ----

    @Test
    fun bags_toGrams_andBack() {
        assertEquals(125_000.0, SalesCalc.bagsToGrams(5, 25.0), 1e-9)
        assertEquals(BagQty(5, 0.0), SalesCalc.gramsToBags(125_000.0, 25.0))
        assertEquals(BagQty(2, 10_000.0), SalesCalc.gramsToBags(60_000.0, 25.0))
        assertEquals(BagQty(0, 0.0), SalesCalc.gramsToBags(0.0, 50.0))
        assertEquals(BagQty(0, 24_000.0), SalesCalc.gramsToBags(24_000.0, 25.0))
    }

    @Test
    fun bags_floatingPointNoiseDoesNotLoseABag() {
        assertEquals(BagQty(3, 0.0), SalesCalc.gramsToBags(75_000.0 - 1e-7, 25.0))
        assertEquals(BagQty(3, 0.0), SalesCalc.gramsToBags(75_000.0 + 1e-7, 25.0))
    }

    @Test
    fun bags_rejectInvalid() {
        try { SalesCalc.bagsToGrams(1, 0.0); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.bagsToGrams(-1, 25.0); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.gramsToBags(-1.0, 25.0); fail() } catch (_: IllegalArgumentException) {}
        try { SalesCalc.gramsToBags(10.0, 0.0); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- ডেলিভারি ----

    @Test
    fun deliveryStatus_variants() {
        assertEquals(SalesCalc.DELIVERY_PENDING, SalesCalc.deliveryStatus(emptyList()))
        assertEquals(SalesCalc.DELIVERY_PENDING, SalesCalc.deliveryStatus(listOf(1000.0 to 0.0, 500.0 to 0.0)))
        assertEquals(SalesCalc.DELIVERY_PARTIAL, SalesCalc.deliveryStatus(listOf(1000.0 to 400.0, 500.0 to 0.0)))
        assertEquals(SalesCalc.DELIVERY_PARTIAL, SalesCalc.deliveryStatus(listOf(1000.0 to 1000.0, 500.0 to 0.0)))
        assertEquals(SalesCalc.DELIVERY_DELIVERED, SalesCalc.deliveryStatus(listOf(1000.0 to 1000.0, 500.0 to 500.0)))
        assertEquals(SalesCalc.DELIVERY_DELIVERED, SalesCalc.deliveryStatus(listOf(1000.0 to 999.99999)))
    }

    @Test
    fun spreadAcrossLots_inOrder_overflowGoesToLast() {
        val lots = listOf("A" to 300.0, "B" to 200.0)
        assertEquals(listOf("A" to 100.0), SalesCalc.spreadAcrossLots(lots, 100.0))
        assertEquals(listOf("A" to 300.0, "B" to 50.0), SalesCalc.spreadAcrossLots(lots, 350.0))
        assertEquals(listOf("A" to 300.0, "B" to 200.0), SalesCalc.spreadAcrossLots(lots, 500.0))
        assertEquals(emptyList<Pair<String, Double>>(), SalesCalc.spreadAcrossLots(emptyList(), 10.0))
        assertEquals(emptyList<Pair<String, Double>>(), SalesCalc.spreadAcrossLots(lots, 0.0))
    }
}
