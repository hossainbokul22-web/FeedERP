package com.feederp.formulation

import java.util.Locale
import kotlin.math.roundToLong

/** অপটিমাইজারের একটি প্রার্থী উপাদান। [minPct]/[maxPct] = মিশ্রণে এই উপাদানের % সীমা (০..১০০)। */
data class OptIngredient(
    val itemId: String,
    val name: String,
    val costPaisaPerKg: Long,
    val proteinPct: Double? = null,
    val energyKcalPerKg: Double? = null,
    val fatPct: Double? = null,
    val fibrePct: Double? = null,
    val moisturePct: Double? = null,
    val minPct: Double = 0.0,
    val maxPct: Double = 100.0,
) {
    internal fun valueOf(key: String): Double = when (key) {
        Nutrient.PROTEIN -> proteinPct
        Nutrient.ENERGY -> energyKcalPerKg
        Nutrient.FAT -> fatPct
        Nutrient.FIBRE -> fibrePct
        Nutrient.MOISTURE -> moisturePct
        else -> null
    } ?: 0.0 // ডাটা নেই = ০ (NutrientCalculator এর সাথে একই নিয়ম)
}

enum class OptStatus { OPTIMAL, INFEASIBLE, UNBOUNDED, INVALID }

data class OptLine(val itemId: String, val name: String, val pct: Double, val gramsPerTon: Double)

/**
 * অপটিমাইজারের ফল। [status] OPTIMAL হলে [lines] এ মিশ্রণ থাকে; অন্যথায় [lines] খালি ও [message] এ কারণ (বাংলা)।
 * INFEASIBLE হলে [relaxHints] এ বলা হয় কোন লক্ষ্যমাত্রা একা শিথিল করলে সমাধান মিলত (যদি তেমন থাকে)।
 */
data class OptimizerResult(
    val status: OptStatus,
    val lines: List<OptLine>,
    val costPerKgPaisa: Double,
    val costPerTonPaisa: Long,
    val nutrients: NutrientResult?,
    val message: String,
    val relaxHints: List<String> = emptyList(),
) {
    val ok: Boolean get() = status == OptStatus.OPTIMAL
    fun toFormulaLines(): List<FormulaLineInput> = lines.map { FormulaLineInput(it.itemId, it.gramsPerTon) }
}

/**
 * ন্যূনতম খরচের ফিড মিশ্রণ (লিনিয়ার প্রোগ্রাম, [Simplex] দিয়ে)। Android নির্ভরতা নেই।
 *
 * ভ্যারিয়েবল x_i = উপাদান i এর % (মোট ১০০)। উদ্দেশ্য: Σ cost_i·x_i/100 (পয়সা/কেজি) ন্যূনতম।
 * সীমা: Σx = ১০০; উপাদানের min/max %; নিউট্রিয়েন্ট [NutrientTargets] (প্রোটিন, শক্তি, ফ্যাট, ফাইবার, আর্দ্রতা)।
 * নিউট্রিয়েন্ট সীমায় [NUTRIENT_MARGIN] পরিমাণ নিরাপত্তা-ব্যবধান রাখা হয়, যাতে গ্রামে রাউন্ড করার পর
 * [NutrientCalculator.checkTargets] সীমা লঙ্ঘনের সতর্কতা না দেয়।
 */
object LeastCostOptimizer {
    const val NUTRIENT_MARGIN = 1e-4
    private const val EPS = 1e-9
    private const val G_PER_PCT = 10_000.0 // ১% = ১০,০০০ গ্রাম/টন

    private class Target(val tag: String, val key: String, val rel: LpRel, val rhs: Double, val label: String)

    fun optimize(ingredients: List<OptIngredient>, targets: NutrientTargets): OptimizerResult {
        validateInput(ingredients, targets)?.let { return failure(OptStatus.INVALID, it) }
        val tgts = buildTargets(targets)

        quickInfeasibility(ingredients)?.let { return failure(OptStatus.INFEASIBLE, it) }

        val sol = solve(ingredients, tgts, skipTag = null)
        return when (sol.status) {
            LpStatus.OPTIMAL -> success(ingredients, sol.x)
            LpStatus.UNBOUNDED -> failure(OptStatus.UNBOUNDED, "সমস্যাটি অসীম (সীমাহীন) — উপাদানের সীমা যাচাই করুন")
            LpStatus.INFEASIBLE -> {
                val hints = tgts.filter { t ->
                    solve(ingredients, tgts, skipTag = t.tag).status == LpStatus.OPTIMAL
                }.map { "\"${it.label}\" শিথিল করলে সমাধান পাওয়া যায়" }
                val msg = "দেওয়া সীমাগুলো একসাথে পূরণ করা সম্ভব নয়" +
                    if (hints.isEmpty()) " (একাধিক সীমা মিলিয়ে বিরোধ; উপাদানের সীমা বা তালিকা বদলান)" else ""
                failure(OptStatus.INFEASIBLE, msg, hints)
            }
        }
    }

    // ---------------- ইনপুট যাচাই ----------------

    private fun validateInput(ings: List<OptIngredient>, t: NutrientTargets): String? {
        if (ings.isEmpty()) return "কোনো উপাদান বাছাই করা হয়নি"
        val dup = ings.groupBy { it.itemId }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) return "একই উপাদান একাধিকবার আছে"
        for (i in ings) {
            if (!i.minPct.isFinite() || !i.maxPct.isFinite() || i.minPct < 0.0 || i.maxPct > 100.0 + EPS) {
                return "${i.name}: সীমা ০ থেকে ১০০% এর মধ্যে হতে হবে"
            }
            if (i.minPct > i.maxPct + EPS) return "${i.name}: সর্বনিম্ন % সর্বোচ্চ % এর বেশি"
            if (i.costPaisaPerKg < 0) return "${i.name}: দাম ঋণাত্মক হতে পারে না"
            for (k in Nutrient.all) {
                val v = i.valueOf(k)
                if (!v.isFinite() || v < 0.0) return "${i.name}: নিউট্রিয়েন্ট মান সঠিক নয়"
            }
        }
        val all = listOfNotNull(
            t.minProteinPct, t.minEnergyKcalPerKg, t.minFatPct, t.maxFatPct, t.maxFibrePct, t.maxMoisturePct,
        )
        if (all.any { !it.isFinite() || it < 0.0 }) return "নিউট্রিয়েন্ট লক্ষ্যমাত্রা সঠিক নয়"
        if (t.minFatPct != null && t.maxFatPct != null && t.minFatPct > t.maxFatPct + EPS) {
            return "ফ্যাটের সর্বনিম্ন সর্বোচ্চের বেশি"
        }
        return null
    }

    /** মোট ১০০% মেলার মৌলিক অসম্ভাব্যতা (সিমপ্লেক্স ছাড়াই স্পষ্ট বার্তা)। */
    private fun quickInfeasibility(ings: List<OptIngredient>): String? {
        val sumMin = ings.sumOf { it.minPct }
        val sumMax = ings.sumOf { it.maxPct }
        if (sumMin > 100.0 + 1e-7) {
            return "উপাদানগুলোর সর্বনিম্ন % এর যোগফল " + String.format(Locale.US, "%.2f", sumMin) + "% — ১০০% এর বেশি"
        }
        if (sumMax < 100.0 - 1e-7) {
            return "উপাদানগুলোর সর্বোচ্চ % এর যোগফল " + String.format(Locale.US, "%.2f", sumMax) + "% — ১০০% পূরণ হয় না; আরও উপাদান যোগ করুন বা সীমা বাড়ান"
        }
        return null
    }

    // ---------------- LP গঠন ----------------

    private fun buildTargets(t: NutrientTargets): List<Target> {
        val out = mutableListOf<Target>()
        val m = NUTRIENT_MARGIN
        t.minProteinPct?.let { out += Target("minProtein", Nutrient.PROTEIN, LpRel.GE, it + m, "প্রোটিন সর্বনিম্ন") }
        t.minEnergyKcalPerKg?.let { out += Target("minEnergy", Nutrient.ENERGY, LpRel.GE, it + m, "শক্তি সর্বনিম্ন") }
        t.minFatPct?.let { out += Target("minFat", Nutrient.FAT, LpRel.GE, it + m, "ফ্যাট সর্বনিম্ন") }
        t.maxFatPct?.let { out += Target("maxFat", Nutrient.FAT, LpRel.LE, it - m, "ফ্যাট সর্বোচ্চ") }
        t.maxFibrePct?.let { out += Target("maxFibre", Nutrient.FIBRE, LpRel.LE, it - m, "ফাইবার সর্বোচ্চ") }
        t.maxMoisturePct?.let { out += Target("maxMoisture", Nutrient.MOISTURE, LpRel.LE, it - m, "আর্দ্রতা সর্বোচ্চ") }
        return out
    }

    private fun solve(ings: List<OptIngredient>, tgts: List<Target>, skipTag: String?): LpSolution {
        val n = ings.size
        val c = DoubleArray(n) { ings[it].costPaisaPerKg.toDouble() / 100.0 } // x % এ, তাই /100 = পয়সা/কেজি
        val rows = mutableListOf<LpRow>()
        rows += LpRow(DoubleArray(n) { 1.0 }, LpRel.EQ, 100.0)
        for ((i, ing) in ings.withIndex()) {
            if (ing.minPct > EPS) rows += LpRow(unit(n, i), LpRel.GE, ing.minPct)
            if (ing.maxPct < 100.0 - EPS) rows += LpRow(unit(n, i), LpRel.LE, ing.maxPct)
        }
        for (t in tgts) {
            if (t.tag == skipTag) continue
            rows += LpRow(DoubleArray(n) { ings[it].valueOf(t.key) / 100.0 }, t.rel, t.rhs)
        }
        return Simplex.minimize(c, rows)
    }

    private fun unit(n: Int, i: Int): DoubleArray = DoubleArray(n).also { it[i] = 1.0 }

    // ---------------- ফল ----------------

    private fun success(ings: List<OptIngredient>, x: DoubleArray): OptimizerResult {
        val grams = DoubleArray(ings.size) { (x[it] * G_PER_PCT * 1000.0).roundToLong() / 1000.0 }
        // রাউন্ডিংয়ের পর মোট ঠিক এক টন করতে সবচেয়ে বড় লাইনে অবশিষ্ট ব্যবধান বসানো
        var big = 0
        for (i in grams.indices) if (grams[i] > grams[big]) big = i
        grams[big] += NutrientCalculator.TON_GRAMS - grams.sum()

        val lines = ings.indices.filter { grams[it] > 0.0005 }.map {
            OptLine(ings[it].itemId, ings[it].name, grams[it] / G_PER_PCT, grams[it])
        }
        val data = lines.map { l ->
            val ing = ings.first { it.itemId == l.itemId }
            IngredientData(
                ing.itemId, ing.name, l.gramsPerTon, ing.proteinPct, ing.energyKcalPerKg, ing.fatPct,
                ing.fibrePct, ing.moisturePct, ing.costPaisaPerKg,
            )
        }
        val nut = NutrientCalculator.calculate(data)
        return OptimizerResult(
            OptStatus.OPTIMAL, lines, nut.costPerKgPaisa, nut.costPerTonPaisa, nut,
            "সর্বনিম্ন খরচের মিশ্রণ পাওয়া গেছে",
        )
    }

    private fun failure(status: OptStatus, msg: String, hints: List<String> = emptyList()) =
        OptimizerResult(status, emptyList(), 0.0, 0L, null, msg, hints)

    /** ডোজ সীমা (গ্রাম/টন) → মিশ্রণে % সীমা, যাতে ওষুধ/প্রিমিক্সের ডোজ সীমা অপটিমাইজারে বসানো যায়। */
    fun gramsPerTonToPct(gramsPerTon: Double): Double = gramsPerTon / G_PER_PCT
}
