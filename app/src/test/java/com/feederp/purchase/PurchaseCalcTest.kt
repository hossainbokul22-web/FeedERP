package com.feederp.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseCalcTest {
    @Test
    fun lineTotal_qtyTimesRate() {
        assertEquals(125_000L, PurchaseCalc.lineTotalPaisa(2_500.0, 50_000)) // 2.5 কেজি × 500 টাকা
        assertEquals(0L, PurchaseCalc.lineTotalPaisa(0.0, 5000))
    }

    @Test
    fun remaining_neverNegative() {
        assertEquals(400.0, PurchaseCalc.remainingGrams(1000.0, 600.0), 1e-9)
        assertEquals(0.0, PurchaseCalc.remainingGrams(1000.0, 1200.0), 1e-9)
        assertEquals(0.0, PurchaseCalc.remainingGrams(1000.0, 999.9999), 1e-9)
    }

    @Test
    fun statusAfterReceipt_variants() {
        val ordered = mapOf("a" to 1000.0, "b" to 500.0)
        assertEquals(PoStatus.ORDERED, PurchaseCalc.statusAfterReceipt(ordered, emptyMap()))
        assertEquals(PoStatus.PARTIAL, PurchaseCalc.statusAfterReceipt(ordered, mapOf("a" to 1000.0)))
        assertEquals(PoStatus.PARTIAL, PurchaseCalc.statusAfterReceipt(ordered, mapOf("a" to 400.0, "b" to 100.0)))
        assertEquals(PoStatus.RECEIVED, PurchaseCalc.statusAfterReceipt(ordered, mapOf("a" to 1000.0, "b" to 700.0)))
        assertEquals(PoStatus.ORDERED, PurchaseCalc.statusAfterReceipt(emptyMap(), mapOf("a" to 5.0)))
    }

    @Test
    fun poStatus_canReceiveOnlyWhenOrderedOrPartial() {
        assertTrue(PoStatus.canReceive(PoStatus.ORDERED))
        assertTrue(PoStatus.canReceive(PoStatus.PARTIAL))
        assertFalse(PoStatus.canReceive(PoStatus.DRAFT))
        assertFalse(PoStatus.canReceive(PoStatus.RECEIVED))
        assertFalse(PoStatus.canReceive(PoStatus.CANCELLED))
    }

    @Test
    fun payableAndAdvance_fromLedgerBalance() {
        assertEquals(5000L, PurchaseCalc.payableDuePaisa(-5000))
        assertEquals(0L, PurchaseCalc.payableDuePaisa(300))
        assertEquals(300L, PurchaseCalc.advanceWithSupplierPaisa(300))
        assertEquals(0L, PurchaseCalc.advanceWithSupplierPaisa(-5000))
    }
}
