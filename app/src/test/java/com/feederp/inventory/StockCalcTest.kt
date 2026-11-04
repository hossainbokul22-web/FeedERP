package com.feederp.inventory

import com.feederp.data.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockCalcTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    // ---------- ওয়েটেড-অ্যাভারেজ ----------

    @Test
    fun weightedAvg_basic() {
        // 100 কেজি @ 40 টাকা + 300 কেজি @ 50 টাকা = 47.50 টাকা/কেজি
        val layers = listOf(CostLayer(100_000.0, 4000), CostLayer(300_000.0, 5000))
        assertEquals(4750L, StockCalc.weightedAvgCostPaisaPerKg(layers))
    }

    @Test
    fun weightedAvg_emptyOrInvalidLayers_isZero() {
        assertEquals(0L, StockCalc.weightedAvgCostPaisaPerKg(emptyList()))
        assertEquals(0L, StockCalc.weightedAvgCostPaisaPerKg(listOf(CostLayer(0.0, 5000), CostLayer(-5.0, 100))))
    }

    @Test
    fun weightedAvg_roundsToNearestPaisa() {
        // (1kg@100 + 2kg@101)/3 = 100.666.. -> 101
        val layers = listOf(CostLayer(1000.0, 100), CostLayer(2000.0, 101))
        assertEquals(101L, StockCalc.weightedAvgCostPaisaPerKg(layers))
    }

    @Test
    fun valuePaisa_isQtyTimesRate() {
        assertEquals(12_500L, StockCalc.valuePaisa(2_500.0, 5000)) // 2.5 কেজি × 50 টাকা = 125 টাকা
        assertEquals(0L, StockCalc.valuePaisa(0.0, 5000))
    }

    // ---------- স্টক যোগফল ও নেগেটিভ ----------

    @Test
    fun sumGrams_insPositiveOutsNegative() {
        assertEquals(700.0, StockCalc.sumGrams(listOf(1000.0, -400.0, 100.0)), 1e-9)
        assertEquals(0.0, StockCalc.sumGrams(emptyList()), 1e-9)
    }

    @Test
    fun isNegative_toleratesFloatNoise() {
        assertFalse(StockCalc.isNegative(0.0))
        assertFalse(StockCalc.isNegative(-0.0001))
        assertTrue(StockCalc.isNegative(-1.0))
    }

    @Test
    fun hasEnough_exactAndShort() {
        assertTrue(StockCalc.hasEnough(1000.0, 1000.0))
        assertTrue(StockCalc.hasEnough(999.9996, 1000.0))
        assertFalse(StockCalc.hasEnough(999.0, 1000.0))
    }

    // ---------- মিনিমাম স্টক ----------

    @Test
    fun lowStock_filtersAndSortsByShortfall() {
        val levels = listOf(
            StockLevel("a", minGrams = 1000.0, currentGrams = 900.0),   // ঘাটতি 100
            StockLevel("b", minGrams = 5000.0, currentGrams = 1000.0),  // ঘাটতি 4000
            StockLevel("c", minGrams = 1000.0, currentGrams = 2000.0),  // ঠিক আছে
            StockLevel("d", minGrams = 0.0, currentGrams = 0.0),        // min=0: অ্যালার্ট নেই
            StockLevel("e", minGrams = 1000.0, currentGrams = 1000.0),  // সমান হলে অ্যালার্ট
        )
        assertEquals(listOf("b", "a", "e"), StockCalc.lowStock(levels).map { it.itemId })
    }

    // ---------- মেয়াদ ----------

    @Test
    fun expiryState_variants() {
        assertEquals(ExpiryState.NONE, StockCalc.expiryState(null, now))
        assertEquals(ExpiryState.EXPIRED, StockCalc.expiryState(now - 1, now))
        assertEquals(ExpiryState.NEAR, StockCalc.expiryState(now + 10 * day, now))
        assertEquals(ExpiryState.NEAR, StockCalc.expiryState(now + 30 * day, now))
        assertEquals(ExpiryState.OK, StockCalc.expiryState(now + 31 * day, now))
    }

    @Test
    fun daysToExpiry_floors() {
        assertEquals(5L, StockCalc.daysToExpiry(now + 5 * day + 1000, now))
        assertEquals(-1L, StockCalc.daysToExpiry(now - 1000, now))
    }

    // ---------- QC / ইস্যুযোগ্যতা ----------

    @Test
    fun isQcIssuable_rules() {
        assertTrue(StockCalc.isQcIssuable(QcStatus.PASSED, itemRequiresQc = true))
        assertTrue(StockCalc.isQcIssuable(QcStatus.PASSED, itemRequiresQc = false))
        assertFalse(StockCalc.isQcIssuable(QcStatus.PENDING, itemRequiresQc = true))
        assertTrue(StockCalc.isQcIssuable(QcStatus.PENDING, itemRequiresQc = false))
        assertFalse(StockCalc.isQcIssuable(QcStatus.HOLD, itemRequiresQc = false))
        assertFalse(StockCalc.isQcIssuable(QcStatus.REJECTED, itemRequiresQc = false))
        assertFalse(StockCalc.isQcIssuable("UNKNOWN", itemRequiresQc = false))
    }

    @Test
    fun isIssuable_expiredLotBlockedEvenIfPassed() {
        assertFalse(StockCalc.isIssuable(QcStatus.PASSED, true, now - 1, now))
        assertTrue(StockCalc.isIssuable(QcStatus.PASSED, true, now, now))       // মেয়াদের দিনেই (expiry == now) চলে
        assertTrue(StockCalc.isIssuable(QcStatus.PASSED, true, null, now))      // মেয়াদহীন
        assertFalse(StockCalc.isIssuable(QcStatus.HOLD, true, now + day, now))
    }

    @Test
    fun qcStatus_allAndLabels() {
        assertEquals(setOf("PENDING", "PASSED", "REJECTED", "HOLD"), QcStatus.all)
        QcStatus.all.forEach { assertTrue(QcStatus.labelBn(it).isNotBlank()) }
    }

    // ---------- মুভমেন্ট চিহ্ন ----------

    @Test
    fun signError_incomingMustBePositive() {
        assertNull(StockCalc.signError(MovementType.PURCHASE_IN, 100.0))
        assertNotNull(StockCalc.signError(MovementType.PURCHASE_IN, -100.0))
        assertNotNull(StockCalc.signError(MovementType.PRODUCTION_IN, -1.0))
        assertNotNull(StockCalc.signError(MovementType.RETURN_IN, -1.0))
    }

    @Test
    fun signError_outgoingMustBeNegative() {
        assertNull(StockCalc.signError(MovementType.SALE_OUT, -100.0))
        assertNotNull(StockCalc.signError(MovementType.SALE_OUT, 100.0))
        assertNotNull(StockCalc.signError(MovementType.PRODUCTION_OUT, 1.0))
    }

    @Test
    fun signError_transferAndAdjustmentEitherSign() {
        assertNull(StockCalc.signError(MovementType.TRANSFER, 5.0))
        assertNull(StockCalc.signError(MovementType.TRANSFER, -5.0))
        assertNull(StockCalc.signError(MovementType.ADJUSTMENT, 5.0))
        assertNull(StockCalc.signError(MovementType.ADJUSTMENT, -5.0))
    }

    @Test
    fun signError_zeroNaNInfiniteRejected() {
        MovementType.values().forEach {
            assertNotNull(StockCalc.signError(it, 0.0))
            assertNotNull(StockCalc.signError(it, Double.NaN))
            assertNotNull(StockCalc.signError(it, Double.POSITIVE_INFINITY))
        }
    }
}
