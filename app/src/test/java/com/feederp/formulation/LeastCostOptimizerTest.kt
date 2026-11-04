package com.feederp.formulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeastCostOptimizerTest {
    private val corn = OptIngredient("corn", "ভুট্টা", 2500, proteinPct = 8.0, energyKcalPerKg = 3400.0, fibrePct = 2.0)
    private val soy = OptIngredient("soy", "সয়াবিন মিল", 5000, proteinPct = 44.0, energyKcalPerKg = 2800.0, fibrePct = 6.0)
    private val oil = OptIngredient("oil", "তেল", 9000, fatPct = 99.0, energyKcalPerKg = 8800.0)

    private fun pct(r: OptimizerResult, id: String) = r.lines.firstOrNull { it.itemId == id }?.pct ?: 0.0

    @Test
    fun knownAnswer_twoIngredients_proteinFloor() {
        // 8(1-s)+44s = 20 -> s = 1/3 ; খরচ = 2500*2/3 + 5000/3 = 3333.33 পয়সা/কেজি
        val r = LeastCostOptimizer.optimize(listOf(corn, soy), NutrientTargets(minProteinPct = 20.0))
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertEquals(100.0 / 3.0, pct(r, "soy"), 0.01)
        assertEquals(200.0 / 3.0, pct(r, "corn"), 0.01)
        assertEquals(3333.33, r.costPerKgPaisa, 0.5)
        assertEquals(NutrientCalculator.TON_GRAMS, r.lines.sumOf { it.gramsPerTon }, 1e-6)
        assertTrue(NutrientCalculator.checkTargets(r.nutrients!!, NutrientTargets(minProteinPct = 20.0)).isEmpty())
    }

    @Test
    fun noTargets_picksCheapestIngredient() {
        val r = LeastCostOptimizer.optimize(listOf(corn, soy), NutrientTargets())
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertEquals(100.0, pct(r, "corn"), 1e-6)
        assertEquals(1, r.lines.size)
        assertEquals(2_500_000L, r.costPerTonPaisa)
    }

    @Test
    fun ingredientMaxBoundary_fillsWithNextCheapest() {
        val a = OptIngredient("a", "A", 1000, maxPct = 40.0)
        val b = OptIngredient("b", "B", 2000)
        val c = OptIngredient("c", "C", 3000)
        val r = LeastCostOptimizer.optimize(listOf(c, b, a), NutrientTargets())
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertEquals(40.0, pct(r, "a"), 1e-6)
        assertEquals(60.0, pct(r, "b"), 1e-6)
        assertEquals(0.0, pct(r, "c"), 1e-6)
    }

    @Test
    fun ingredientMinBoundary_forcesExpensiveItem() {
        val cheap = OptIngredient("cheap", "সস্তা", 1000)
        val pricey = OptIngredient("pricey", "দামি", 4000, minPct = 5.0)
        val r = LeastCostOptimizer.optimize(listOf(cheap, pricey), NutrientTargets())
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertEquals(5.0, pct(r, "pricey"), 1e-6)
        assertEquals(95.0, pct(r, "cheap"), 1e-6)
    }

    @Test
    fun fixedInclusion_minEqualsMax() {
        val r = LeastCostOptimizer.optimize(
            listOf(corn.copy(minPct = 60.0, maxPct = 60.0), soy), NutrientTargets(),
        )
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertEquals(60.0, pct(r, "corn"), 1e-6)
        assertEquals(40.0, pct(r, "soy"), 1e-6)
    }

    @Test
    fun multipleTargets_resultSatisfiesAllAndBeatsFeasibleMix() {
        val t = NutrientTargets(minProteinPct = 18.0, minEnergyKcalPerKg = 3000.0, maxFibrePct = 4.0, minFatPct = 3.0)
        val r = LeastCostOptimizer.optimize(listOf(corn, soy, oil), t)
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertTrue(NutrientCalculator.checkTargets(r.nutrients!!, t).isEmpty())
        assertEquals(NutrientCalculator.TON_GRAMS, r.lines.sumOf { it.gramsPerTon }, 1e-6)
        // একটি জানা সম্ভাব্য মিশ্রণ: ভুট্টা 60, সয়া 37, তেল 3 -> খরচ ২৫০০*.৬+৫০০০*.৩৭+৯০০০*.০৩
        val feasible = 2500 * 0.60 + 5000 * 0.37 + 9000 * 0.03
        assertTrue(r.costPerKgPaisa <= feasible + 1e-6)
    }

    @Test
    fun infeasible_proteinTooHigh_givesMessageAndHint() {
        val limitedSoy = soy.copy(maxPct = 30.0) // সর্বোচ্চ প্রোটিন = 8*.7+44*.3 = 18.8
        val r = LeastCostOptimizer.optimize(listOf(corn, limitedSoy), NutrientTargets(minProteinPct = 20.0))
        assertEquals(OptStatus.INFEASIBLE, r.status)
        assertTrue(r.lines.isEmpty())
        assertTrue(r.message.isNotBlank())
        assertTrue(r.relaxHints.any { it.contains("প্রোটিন") })
    }

    @Test
    fun infeasible_sumOfMinAbove100() {
        val r = LeastCostOptimizer.optimize(
            listOf(corn.copy(minPct = 60.0), soy.copy(minPct = 50.0)), NutrientTargets(),
        )
        assertEquals(OptStatus.INFEASIBLE, r.status)
    }

    @Test
    fun infeasible_sumOfMaxBelow100() {
        val r = LeastCostOptimizer.optimize(
            listOf(corn.copy(maxPct = 40.0), soy.copy(maxPct = 50.0)), NutrientTargets(),
        )
        assertEquals(OptStatus.INFEASIBLE, r.status)
    }

    @Test
    fun invalidInputs() {
        assertEquals(OptStatus.INVALID, LeastCostOptimizer.optimize(emptyList(), NutrientTargets()).status)
        assertEquals(OptStatus.INVALID, LeastCostOptimizer.optimize(listOf(corn, corn), NutrientTargets()).status)
        assertEquals(
            OptStatus.INVALID,
            LeastCostOptimizer.optimize(listOf(corn.copy(minPct = 50.0, maxPct = 20.0)), NutrientTargets()).status,
        )
        assertEquals(
            OptStatus.INVALID,
            LeastCostOptimizer.optimize(listOf(corn), NutrientTargets(minProteinPct = -1.0)).status,
        )
        assertEquals(
            OptStatus.INVALID,
            LeastCostOptimizer.optimize(listOf(corn), NutrientTargets(minFatPct = 5.0, maxFatPct = 2.0)).status,
        )
    }

    @Test
    fun singleIngredient_fullyFeasibleOrNot() {
        val ok = LeastCostOptimizer.optimize(listOf(corn), NutrientTargets(minProteinPct = 7.0))
        assertEquals(OptStatus.OPTIMAL, ok.status)
        assertEquals(1_000_000.0, ok.lines.single().gramsPerTon, 1e-6)
        val bad = LeastCostOptimizer.optimize(listOf(corn), NutrientTargets(minProteinPct = 9.0))
        assertEquals(OptStatus.INFEASIBLE, bad.status)
    }

    @Test
    fun maxMoistureConstraintRespected() {
        val wet = OptIngredient("wet", "ভেজা", 1000, moisturePct = 25.0)
        val dry = OptIngredient("dry", "শুকনো", 3000, moisturePct = 10.0)
        val t = NutrientTargets(maxMoisturePct = 14.0)
        val r = LeastCostOptimizer.optimize(listOf(wet, dry), t)
        assertEquals(OptStatus.OPTIMAL, r.status)
        assertEquals(14.0, r.nutrients!!.moisturePct, 0.001)
        assertTrue(NutrientCalculator.checkTargets(r.nutrients!!, t).isEmpty())
    }

    @Test
    fun doseConversion() {
        assertEquals(0.05, LeastCostOptimizer.gramsPerTonToPct(500.0), 1e-12)
    }
}
