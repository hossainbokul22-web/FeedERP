package com.feederp.production

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CostingCalcTest {
    // হাতে হিসাব: ভুট্টা ৬০০ কেজি @৩০৳/কেজি = ১৮,০০,০০০ পয়সা; সয়া ৩০০ কেজি @৫০৳ = ১৫,০০,০০০;
    // প্রিমিক্স ২ কেজি @১,০০০৳ = ২,০০,০০০; প্যাকেজিং ১ কেজি @২০০৳ = ২০,০০০ (ইনপুট গ্রামে ধরা হয় না);
    // লেবার ৫০,০০০; ওভারহেড ৩০,০০০; মেশিন ২ ঘণ্টা @১০০৳ = ২০,০০০ → মোট ৩৬,২০,০০০ পয়সা।
    private val input = BatchCostInput(
        materials = listOf(
            CostMaterialLine("corn", CostCategory.RAW, 600_000.0, 3_000),
            CostMaterialLine("soy", CostCategory.RAW, 300_000.0, 5_000),
            CostMaterialLine("premix", CostCategory.MEDICINE_PREMIX, 2_000.0, 100_000),
            CostMaterialLine("bag", CostCategory.PACKAGING, 1_000.0, 20_000),
        ),
        labourPaisa = 50_000,
        electricityOverheadPaisa = 30_000,
        machineRuns = listOf(MachineRunCost(runHours = 2.0, hourlyCostPaisa = 10_000)),
        outputGoodGrams = 900_000.0,
        bagSizeKg = 50.0,
    )

    @Test fun fullBatchBreakdown() {
        val r = CostingCalc.compute(input)
        assertEquals(3_300_000L, r.rawMaterialPaisa)
        assertEquals(200_000L, r.medicinePremixPaisa)
        assertEquals(20_000L, r.packagingPaisa)
        assertEquals(20_000L, r.machinePaisa)
        assertEquals(3_620_000L, r.totalPaisa)
        assertEquals(3_500_000L, r.materialPaisa)
        assertEquals(50_000L, r.overheadAndMachinePaisa)
    }

    @Test fun yieldWastageAndBags() {
        val r = CostingCalc.compute(input)
        assertEquals(902_000.0, r.inputGrams, 1e-6)          // প্যাকেজিং বাদ
        assertEquals(2_000.0, r.wastageGrams, 1e-6)
        assertEquals(2_000.0 / 902_000.0 * 100.0, r.wastagePct, 1e-9)
        assertEquals(900_000.0 / 902_000.0 * 100.0, r.yieldPct, 1e-9)
        assertEquals(18, r.outputBags)                        // ৯০০ কেজি ÷ ৫০
        assertEquals(0.0, r.looseGrams, 1e-6)
    }

    @Test fun costPerKgAndPerBag() {
        val r = CostingCalc.compute(input)
        assertEquals(4_022L, r.costPerKgPaisa)                // 3,620,000×1000/900,000 = 4022.2
        assertEquals(201_111L, r.costPerBagPaisa)             // 3,620,000×50,000/900,000 = 201,111.1
        assertEquals(4_013L, r.costPerKgAtFullYieldPaisa)     // …/902,000 = 4013.3
        assertEquals(9L, r.wastageImpactPerKgPaisa)
    }

    @Test fun zeroOutputKeepsTotalButNoPerUnitCost() {
        val r = CostingCalc.compute(input.copy(outputGoodGrams = 0.0))
        assertEquals(3_620_000L, r.totalPaisa)
        assertEquals(0L, r.costPerKgPaisa)
        assertEquals(0L, r.costPerBagPaisa)
        assertEquals(0L, r.wastageImpactPerKgPaisa)
        assertEquals(0.0, r.yieldPct, 0.0)
        assertEquals(902_000.0, r.wastageGrams, 1e-6)
    }

    @Test fun emptyBatchIsAllZero() {
        val r = CostingCalc.compute(BatchCostInput(materials = emptyList(), outputGoodGrams = 0.0))
        assertEquals(0L, r.totalPaisa)
        assertEquals(0.0, r.yieldPct, 0.0)
        assertEquals(0.0, r.wastagePct, 0.0)
    }

    @Test fun looseBatchHasNoBags() {
        assertEquals(0, CostingCalc.bagCount(900_000.0, 0.0))
        assertEquals(900_000.0, CostingCalc.looseGrams(900_000.0, 0.0), 0.0)
        assertEquals(0L, CostingCalc.costPerBagPaisa(1_000_000L, 900_000.0, 0.0))
    }

    @Test fun partialBagLeavesLooseGrams() {
        assertEquals(2, CostingCalc.bagCount(120_000.0, 50.0))
        assertEquals(20_000.0, CostingCalc.looseGrams(120_000.0, 50.0), 1e-6)
    }

    @Test fun bagCountToleratesTinyFloatShortfall() {
        // ফ্লোটিং-পয়েন্টে ৫০ কেজি থেকে সামান্য কম হলেও একটি পূর্ণ বস্তা ধরা হয়
        assertEquals(1, CostingCalc.bagCount(49_999.9996, 50.0))
        assertEquals(0, CostingCalc.bagCount(49_990.0, 50.0))
    }

    @Test fun valueRoundsToNearestPaisa() {
        assertEquals(2L, CostingCalc.valuePaisa(500.0, 3))     // 1.5 → 2
        assertEquals(0L, CostingCalc.valuePaisa(1.0, 1))       // 0.001 → 0
        assertEquals(999L, CostingCalc.valuePaisa(333.0, 3_000))
    }

    @Test fun depreciationPerHour() {
        assertEquals(1_000L, CostingCalc.depreciationPerHourPaisa(10_000_000L, 1_000_000L, 9_000.0))
        assertEquals(0L, CostingCalc.depreciationPerHourPaisa(10_000_000L, 1_000_000L, 0.0))
        assertEquals(0L, CostingCalc.depreciationPerHourPaisa(500L, 1_000L, 100.0))   // স্ক্র্যাপ বেশি হলে ঋণাত্মক নয়
    }

    @Test fun machineHourlyRateSumsAndRejectsNegative() {
        assertEquals(350L, CostingCalc.machineHourlyRatePaisa(200, 100, 50))
        assertThrows(IllegalArgumentException::class.java) { CostingCalc.machineHourlyRatePaisa(-1, 0, 0) }
    }

    @Test fun marginAndBatchMargin() {
        val m = CostingCalc.margin(5_000, 4_022)
        assertEquals(978L, m.marginPerKgPaisa)
        assertEquals(978.0 / 5_000.0 * 100.0, m.marginPct, 1e-9)
        assertEquals(0.0, CostingCalc.margin(0, 100).marginPct, 0.0)
        // ৯০০ কেজি × ৫০৳ = ৪৫,০০,০০০ − ৩৬,২০,০০০
        assertEquals(880_000L, CostingCalc.batchMarginPaisa(900_000.0, 5_000, 3_620_000L))
    }

    @Test fun productionValueAggregates() {
        val v = CostingCalc.productionValue(
            listOf(BatchCostSummary(900_000.0, 3_620_000L), BatchCostSummary(100_000.0, 400_000L))
        )
        assertEquals(4_020_000L, v.totalCostPaisa)
        assertEquals(1_000_000.0, v.totalOutputGrams, 0.0)
        assertEquals(4_020L, v.avgCostPerKgPaisa)
        assertEquals(0L, CostingCalc.productionValue(emptyList()).avgCostPerKgPaisa)
    }

    @Test fun fgStockValueIgnoresNegativeStock() {
        assertEquals(0L, CostingCalc.fgStockValuePaisa(-500.0, 4_000))
        assertEquals(4_000L, CostingCalc.fgStockValuePaisa(1_000.0, 4_000))
    }

    @Test fun invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CostingCalc.compute(input.copy(outputGoodGrams = -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { CostingCalc.compute(input.copy(labourPaisa = -1)) }
        assertThrows(IllegalArgumentException::class.java) { CostingCalc.compute(input.copy(bagSizeKg = -5.0)) }
        assertThrows(IllegalArgumentException::class.java) {
            CostingCalc.compute(input.copy(materials = listOf(CostMaterialLine("x", CostCategory.RAW, -1.0, 100))))
        }
    }
}
