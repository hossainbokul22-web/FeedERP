package com.feederp.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FefoTest {
    private val day = 86_400_000L
    private val base = 1_000 * day

    private fun lot(id: String, batch: String, expiryDays: Long?, qty: Double) =
        LotStock(id, batch, expiryDays?.let { base + it * day }, qty)

    @Test
    fun sort_earliestExpiryFirst_noExpiryLast() {
        val sorted = StockCalc.fefoSort(
            listOf(
                lot("c", "B3", null, 10.0),
                lot("a", "B1", 90, 10.0),
                lot("b", "B2", 30, 10.0),
            )
        )
        assertEquals(listOf("b", "a", "c"), sorted.map { it.lotId })
    }

    @Test
    fun sort_tieBreaksByBatchThenLotId() {
        val sorted = StockCalc.fefoSort(
            listOf(
                lot("z", "B2", 30, 1.0),
                lot("y", "B1", 30, 1.0),
                lot("x", "B1", 30, 1.0),
            )
        )
        assertEquals(listOf("x", "y", "z"), sorted.map { it.lotId })
    }

    @Test
    fun allocate_takesFromEarliestLotFirst() {
        val lots = listOf(lot("late", "B2", 90, 500.0), lot("early", "B1", 10, 300.0))
        val r = StockCalc.allocateFefo(lots, 400.0)
        assertEquals(listOf(LotAllocation("early", 300.0), LotAllocation("late", 100.0)), r.allocations)
        assertEquals(0.0, r.shortfallGrams, 1e-9)
        assertTrue(r.isFullyCovered)
    }

    @Test
    fun allocate_singleLotPartial() {
        val r = StockCalc.allocateFefo(listOf(lot("a", "B1", 10, 1000.0)), 250.0)
        assertEquals(listOf(LotAllocation("a", 250.0)), r.allocations)
        assertTrue(r.isFullyCovered)
    }

    @Test
    fun allocate_reportsShortfall() {
        val r = StockCalc.allocateFefo(listOf(lot("a", "B1", 10, 300.0), lot("b", "B2", 20, 200.0)), 700.0)
        assertEquals(500.0, r.allocations.sumOf { it.qtyGrams }, 1e-9)
        assertEquals(200.0, r.shortfallGrams, 1e-9)
        assertFalse(r.isFullyCovered)
    }

    @Test
    fun allocate_skipsEmptyLots() {
        val r = StockCalc.allocateFefo(listOf(lot("empty", "B0", 1, 0.0), lot("a", "B1", 10, 100.0)), 50.0)
        assertEquals(listOf(LotAllocation("a", 50.0)), r.allocations)
    }

    @Test
    fun allocate_zeroRequired_noAllocations() {
        val r = StockCalc.allocateFefo(listOf(lot("a", "B1", 10, 100.0)), 0.0)
        assertTrue(r.allocations.isEmpty())
        assertTrue(r.isFullyCovered)
    }

    @Test
    fun allocate_noLots_fullShortfall() {
        val r = StockCalc.allocateFefo(emptyList(), 100.0)
        assertTrue(r.allocations.isEmpty())
        assertEquals(100.0, r.shortfallGrams, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun allocate_negativeRequired_throws() {
        StockCalc.allocateFefo(emptyList(), -1.0)
    }

    @Test
    fun allocate_floatNoiseDoesNotCreateShortfall() {
        // 0.1 + 0.2 জাতীয় জঞ্জাল: ০.০০০৫ গ্রামের কম ঘাটতি উপেক্ষা
        val r = StockCalc.allocateFefo(listOf(lot("a", "B1", 10, 0.1), lot("b", "B2", 20, 0.2)), 0.30000000000000004)
        assertTrue(r.isFullyCovered)
    }

    @Test
    fun allocate_doesNotMutateInput_andKeepsExpiryOrderAcrossManyLots() {
        val lots = (1..5).map { lot("l$it", "B$it", (60 - it * 10).toLong(), 100.0) } // l5 সবচেয়ে আগে
        val copy = lots.toList()
        val r = StockCalc.allocateFefo(lots, 250.0)
        assertEquals(copy, lots)
        assertEquals(listOf("l5", "l4", "l3"), r.allocations.map { it.lotId })
        assertEquals(listOf(100.0, 100.0, 50.0), r.allocations.map { it.qtyGrams })
    }
}
