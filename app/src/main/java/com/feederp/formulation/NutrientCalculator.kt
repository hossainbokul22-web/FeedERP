package com.feederp.formulation

import kotlin.math.abs
import kotlin.math.roundToLong

/** নিউট্রিয়েন্টের স্থির কী (অপটিমাইজার ও ক্যালকুলেটর উভয়ে ব্যবহৃত)। মান: প্রোটিন/ফ্যাট/ফাইবার/আর্দ্রতা % এ, শক্তি kcal/kg এ। */
object Nutrient {
    const val PROTEIN = "PROTEIN"
    const val ENERGY = "ENERGY"
    const val FAT = "FAT"
    const val FIBRE = "FIBRE"
    const val MOISTURE = "MOISTURE"

    val all = listOf(PROTEIN, ENERGY, FAT, FIBRE, MOISTURE)

    fun labelBn(key: String): String = when (key) {
        PROTEIN -> "প্রোটিন %"
        ENERGY -> "শক্তি (kcal/kg)"
        FAT -> "ফ্যাট %"
        FIBRE -> "ফাইবার %"
        MOISTURE -> "আর্দ্রতা %"
        else -> key
    }
}

/** ফর্মুলার একটি লাইন: আইটেম ও গ্রাম প্রতি টন (১০,০০,০০০ গ্রাম) খাদ্যে। */
data class FormulaLineInput(val itemId: String, val gramsPerTon: Double)

/** হিসাবের জন্য একটি উপাদানের তথ্য (নিউট্রিয়েন্ট null = ডাটা নেই, ০ ধরা হয় ও সতর্কতা দেওয়া হয়)। */
data class IngredientData(
    val itemId: String,
    val name: String,
    val gramsPerTon: Double,
    val proteinPct: Double?,
    val energyKcalPerKg: Double?,
    val fatPct: Double?,
    val fibrePct: Double?,
    val moisturePct: Double?,
    val costPaisaPerKg: Long,
)

data class NutrientResult(
    val totalGrams: Double,
    val proteinPct: Double,
    val energyKcalPerKg: Double,
    val fatPct: Double,
    val fibrePct: Double,
    val moisturePct: Double,
    val costPerKgPaisa: Double,
    val costPerTonPaisa: Long,
    /** যেসব উপাদানের কোনো নিউট্রিয়েন্ট ডাটা নেই (নাম)। */
    val itemsMissingNutrientData: List<String>,
) {
    fun valueOf(key: String): Double = when (key) {
        Nutrient.PROTEIN -> proteinPct
        Nutrient.ENERGY -> energyKcalPerKg
        Nutrient.FAT -> fatPct
        Nutrient.FIBRE -> fibrePct
        Nutrient.MOISTURE -> moisturePct
        else -> 0.0
    }
}

/** ফর্মুলার নিউট্রিয়েন্ট লক্ষ্যমাত্রা (সবগুলো ঐচ্ছিক)। */
data class NutrientTargets(
    val minProteinPct: Double? = null,
    val minEnergyKcalPerKg: Double? = null,
    val minFatPct: Double? = null,
    val maxFatPct: Double? = null,
    val maxFibrePct: Double? = null,
    val maxMoisturePct: Double? = null,
)

object NutrientCalculator {
    const val TON_GRAMS = 1_000_000.0

    /** ওজনভিত্তিক গড়: নিউট্রিয়েন্ট = Σ(গ্রাম × মান) / মোট গ্রাম। মোট ০ হলে সব ০। */
    fun calculate(lines: List<IngredientData>): NutrientResult {
        val total = lines.sumOf { it.gramsPerTon.coerceAtLeast(0.0) }
        fun avg(sel: (IngredientData) -> Double?): Double =
            if (total <= 0.0) 0.0 else lines.sumOf { it.gramsPerTon.coerceAtLeast(0.0) * (sel(it) ?: 0.0) } / total
        val costPerKg = if (total <= 0.0) 0.0 else
            lines.sumOf { it.gramsPerTon.coerceAtLeast(0.0) * it.costPaisaPerKg.toDouble() } / total
        val missing = lines.filter {
            it.proteinPct == null && it.energyKcalPerKg == null && it.fatPct == null &&
                it.fibrePct == null && it.moisturePct == null
        }.map { it.name }
        return NutrientResult(
            totalGrams = total,
            proteinPct = avg { it.proteinPct },
            energyKcalPerKg = avg { it.energyKcalPerKg },
            fatPct = avg { it.fatPct },
            fibrePct = avg { it.fibrePct },
            moisturePct = avg { it.moisturePct },
            costPerKgPaisa = costPerKg,
            costPerTonPaisa = (costPerKg * 1000.0).roundToLong(),
            itemsMissingNutrientData = missing,
        )
    }

    /** লক্ষ্যমাত্রা লঙ্ঘনের বাংলা বার্তা; খালি = সব ঠিক। */
    fun checkTargets(r: NutrientResult, t: NutrientTargets): List<String> {
        val out = mutableListOf<String>()
        fun f(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
        t.minProteinPct?.let { if (r.proteinPct + EPS < it) out += "প্রোটিন ${f(r.proteinPct)}% (সর্বনিম্ন ${f(it)}%)" }
        t.minEnergyKcalPerKg?.let { if (r.energyKcalPerKg + EPS < it) out += "শক্তি ${f(r.energyKcalPerKg)} kcal/kg (সর্বনিম্ন ${f(it)})" }
        t.minFatPct?.let { if (r.fatPct + EPS < it) out += "ফ্যাট ${f(r.fatPct)}% (সর্বনিম্ন ${f(it)}%)" }
        t.maxFatPct?.let { if (r.fatPct - EPS > it) out += "ফ্যাট ${f(r.fatPct)}% (সর্বোচ্চ ${f(it)}%)" }
        t.maxFibrePct?.let { if (r.fibrePct - EPS > it) out += "ফাইবার ${f(r.fibrePct)}% (সর্বোচ্চ ${f(it)}%)" }
        t.maxMoisturePct?.let { if (r.moisturePct - EPS > it) out += "আর্দ্রতা ${f(r.moisturePct)}% (সর্বোচ্চ ${f(it)}%)" }
        return out
    }

    /** মোট গ্রাম এক টনের কতটা কাছে (পার্থক্য, গ্রামে)। */
    fun totalDeviationGrams(lines: List<FormulaLineInput>): Double =
        abs(lines.sumOf { it.gramsPerTon } - TON_GRAMS)

    private const val EPS = 1e-9
}
