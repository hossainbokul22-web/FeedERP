package com.feederp.production

import com.feederp.inventory.LotStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPlannerTest {
    @Test fun requiredGramsScalesByBatchSize() {
        // ফর্মুলায় ভুট্টা ৬,০০,০০০ গ্রাম/টন; ব্যাচ ২ টন → ১২,০০,০০০ গ্রাম
        assertEquals(1_200_000.0, ProductionPlanner.requiredGrams(600_000.0, 2_000_000.0), 1e-6)
        // প্রিমিক্স ২,০০০ গ্রাম/টন; ব্যাচ ৫০০ কেজি → ১,০০০ গ্রাম
        assertEquals(1_000.0, ProductionPlanner.requiredGrams(2_000.0, 500_000.0), 1e-9)
        assertEquals(0.0, ProductionPlanner.requiredGrams(0.0, 1_000_000.0), 0.0)
        assertThrows(IllegalArgumentException::class.java) { ProductionPlanner.requiredGrams(-1.0, 100.0) }
    }

    private val corn = listOf(
        LotStock("L-late", "B2", expiryAt = 2_000L, qtyGrams = 500_000.0),
        LotStock("L-early", "B1", expiryAt = 1_000L, qtyGrams = 300_000.0),
    )

    @Test fun planAllocatesEarliestExpiryFirst() {
        val p = ProductionPlanner.plan(
            needs = listOf(ProductionPlanner.Need("corn", "ভুট্টা", 600_000.0)),
            lotsByItem = mapOf("corn" to corn),
        ).single()
        assertTrue(p.isCovered)
        assertEquals(listOf("L-early", "L-late"), p.allocations.map { it.lotId })
        assertEquals(300_000.0, p.allocations[0].qtyGrams, 1e-9)
        assertEquals(300_000.0, p.allocations[1].qtyGrams, 1e-9)
        assertEquals(600_000.0, p.allocations.sumOf { it.qtyGrams }, 1e-9)
    }

    @Test fun shortageIsReportedWithBanglaMessage() {
        val plan = ProductionPlanner.plan(
            needs = listOf(
                ProductionPlanner.Need("corn", "ভুট্টা", 900_000.0),     // আছে ৮,০০,০০০
                ProductionPlanner.Need("soy", "সয়া", 100_000.0),         // কোনো লট নেই
            ),
            lotsByItem = mapOf("corn" to corn),
        )
        assertEquals(2, ProductionPlanner.shortages(plan).size)
        assertEquals(100_000.0, plan[0].shortfallGrams, 1e-9)
        assertEquals(100_000.0, plan[1].shortfallGrams, 1e-9)
        val msg = ProductionPlanner.shortageMessage(plan)!!
        assertTrue(msg.contains("ভুট্টা") && msg.contains("সয়া") && msg.contains("100.00 কেজি"))
    }

    @Test fun noShortageMeansNoMessage() {
        val plan = ProductionPlanner.plan(
            listOf(ProductionPlanner.Need("corn", "ভুট্টা", 100_000.0)), mapOf("corn" to corn),
        )
        assertNull(ProductionPlanner.shortageMessage(plan))
    }

    @Test fun lotlessItemsUseTotalStockForShortage() {
        val ok = ProductionPlanner.plan(
            listOf(ProductionPlanner.Need("salt", "লবণ", 5_000.0)), emptyMap(),
            lotless = setOf("salt"), lotlessStock = mapOf("salt" to 5_000.0),
        ).single()
        assertTrue(ok.isCovered); assertTrue(ok.allocations.isEmpty())
        val short = ProductionPlanner.plan(
            listOf(ProductionPlanner.Need("salt", "লবণ", 5_000.0)), emptyMap(),
            lotless = setOf("salt"), lotlessStock = mapOf("salt" to 3_000.0),
        ).single()
        assertFalse(short.isCovered)
        assertEquals(2_000.0, short.shortfallGrams, 1e-9)
    }

    @Test fun expiryAddsShelfLifeDays() {
        assertEquals(1_000L + 90L * 86_400_000L, ProductionPlanner.expiryAt(1_000L, 90))
        assertThrows(IllegalArgumentException::class.java) { ProductionPlanner.expiryAt(0L, 0) }
    }

    @Test fun netRunHoursSubtractsDowntime() {
        val h = 3_600_000L
        assertEquals(2.0, ProductionPlanner.netRunHours(0L, 2 * h, 0), 1e-9)
        assertEquals(1.5, ProductionPlanner.netRunHours(0L, 2 * h, 30), 1e-9)
        assertEquals(0.0, ProductionPlanner.netRunHours(0L, h, 60), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { ProductionPlanner.netRunHours(0L, h, 61) }
        assertThrows(IllegalArgumentException::class.java) { ProductionPlanner.netRunHours(h, h, 0) }
        assertThrows(IllegalArgumentException::class.java) { ProductionPlanner.netRunHours(0L, h, -1) }
    }

    @Test fun cleaningCompleteNeedsAllItemsDone() {
        assertFalse(ProductionPlanner.isCleaningComplete(emptyList()))
        assertFalse(ProductionPlanner.isCleaningComplete(listOf(true, false)))
        assertTrue(ProductionPlanner.isCleaningComplete(listOf(true, true)))
        assertNotNull(ProductionPlanner.defaultCleaningChecklist.firstOrNull())
    }
}
