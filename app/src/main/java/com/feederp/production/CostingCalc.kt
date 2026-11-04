package com.feederp.production

import com.feederp.inventory.CostLayer
import com.feederp.inventory.StockCalc
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/** খরচের শ্রেণি: কাঁচামাল, ওষুধ/প্রিমিক্স/অ্যাডিটিভ, প্যাকেজিং। */
enum class CostCategory { RAW, MEDICINE_PREMIX, PACKAGING }

/** ব্যাচে ব্যবহৃত একটি উপাদান; [costPaisaPerKg] লট বা ওয়েটেড-অ্যাভারেজ খরচ। */
data class CostMaterialLine(
    val itemId: String,
    val category: CostCategory,
    val qtyGrams: Double,
    val costPaisaPerKg: Long,
)

/** মেশিনের একটি রান: নেট রান ঘণ্টা × ঘণ্টা-প্রতি খরচ। */
data class MachineRunCost(val runHours: Double, val hourlyCostPaisa: Long)

data class BatchCostInput(
    val materials: List<CostMaterialLine>,
    val labourPaisa: Long = 0L,
    /** বিদ্যুৎ/জ্বালানি/অন্যান্য ওভারহেড (একত্রে)। */
    val electricityOverheadPaisa: Long = 0L,
    val machineRuns: List<MachineRunCost> = emptyList(),
    val outputGoodGrams: Double,
    /** 0 = লুজ (বস্তা নেই)। */
    val bagSizeKg: Double = 0.0,
)

data class BatchCostResult(
    val rawMaterialPaisa: Long,
    val medicinePremixPaisa: Long,
    val packagingPaisa: Long,
    val labourPaisa: Long,
    val electricityOverheadPaisa: Long,
    val machinePaisa: Long,
    val totalPaisa: Long,
    /** প্যাকেজিং বাদে উপাদানের মোট ইনপুট (গ্রাম)। */
    val inputGrams: Double,
    val outputGoodGrams: Double,
    val wastageGrams: Double,
    val wastagePct: Double,
    val yieldPct: Double,
    val outputBags: Int,
    val looseGrams: Double,
    val costPerKgPaisa: Long,
    val costPerBagPaisa: Long,
    /** ১০০% ইয়িল্ডে (কোনো অপচয় ছাড়া) কেজি-প্রতি খরচ; অপচয়ের প্রভাব বোঝাতে। */
    val costPerKgAtFullYieldPaisa: Long,
    val wastageImpactPerKgPaisa: Long,
) {
    /** UI/এন্টিটিতে ব্যবহারের জন্য: কাঁচামাল + ওষুধ/প্রিমিক্স। */
    val materialPaisa: Long get() = rawMaterialPaisa + medicinePremixPaisa
    /** বিদ্যুৎ/ওভারহেড + মেশিন খরচ। */
    val overheadAndMachinePaisa: Long get() = electricityOverheadPaisa + machinePaisa
}

data class Margin(val marginPerKgPaisa: Long, val marginPct: Double)

data class BatchCostSummary(val outputGrams: Double, val totalCostPaisa: Long)

data class ProductionValue(val totalCostPaisa: Long, val totalOutputGrams: Double, val avgCostPerKgPaisa: Long)

/** Android/Room-নিরপেক্ষ বিশুদ্ধ ব্যাচ কস্টিং। টাকা Long পয়সা, ওজন Double গ্রাম। */
object CostingCalc {
    const val EPS = 1e-9
    private const val BAG_EPS = 0.0005

    /** পরিমাণ (গ্রাম) × কেজি-প্রতি দাম = মূল্য (পয়সা), নিকটতম পয়সায় রাউন্ড। */
    fun valuePaisa(qtyGrams: Double, costPaisaPerKg: Long): Long =
        (qtyGrams / 1000.0 * costPaisaPerKg.toDouble()).roundToLong()

    /**
     * লটের খরচ: ওই লটের ইনকামিং স্তর থেকে ওয়েটেড-অ্যাভারেজ; স্তর না থাকলে (বা খরচ শূন্য) আইটেমের সামগ্রিক
     * ওয়েটেড-অ্যাভারেজ [itemWaFallbackPaisaPerKg]।
     */
    fun lotCostPaisaPerKg(lotLayers: List<CostLayer>, itemWaFallbackPaisaPerKg: Long): Long {
        val wa = StockCalc.weightedAvgCostPaisaPerKg(lotLayers)
        return if (wa > 0L) wa else max(0L, itemWaFallbackPaisaPerKg)
    }

    /** মেশিনের ঘণ্টা-খরচ = বিদ্যুৎ + রক্ষণাবেক্ষণ + অবচয় (প্রতি ঘণ্টা, পয়সা)। */
    fun machineHourlyRatePaisa(energyPerHour: Long, maintenancePerHour: Long, depreciationPerHour: Long): Long {
        require(energyPerHour >= 0 && maintenancePerHour >= 0 && depreciationPerHour >= 0) { "খরচ ঋণাত্মক হতে পারে না" }
        return energyPerHour + maintenancePerHour + depreciationPerHour
    }

    /** প্রতি রান ঘণ্টায় অবচয় = (ক্রয়মূল্য − স্ক্র্যাপ) ÷ আয়ুষ্কাল ঘণ্টা; আয়ু ≤ ০ হলে ০। */
    fun depreciationPerHourPaisa(purchaseCostPaisa: Long, salvagePaisa: Long, usefulLifeHours: Double): Long {
        if (usefulLifeHours <= 0.0) return 0L
        val base = max(0L, purchaseCostPaisa - salvagePaisa)
        return (base.toDouble() / usefulLifeHours).roundToLong()
    }

    fun machineCostPaisa(runs: List<MachineRunCost>): Long = runs.sumOf { r ->
        require(r.runHours >= 0.0 && r.hourlyCostPaisa >= 0L) { "মেশিন রান ঘণ্টা/খরচ ঋণাত্মক হতে পারে না" }
        (r.runHours * r.hourlyCostPaisa.toDouble()).roundToLong()
    }

    /** ইয়িল্ড % = ভালো আউটপুট ÷ ইনপুট × ১০০; ইনপুট ≤ ০ হলে ০। */
    fun yieldPct(inputGrams: Double, outputGrams: Double): Double =
        if (inputGrams <= EPS) 0.0 else outputGrams / inputGrams * 100.0

    /** অপচয় (গ্রাম) = ইনপুট − আউটপুট, ঋণাত্মক হলে ০। */
    fun wastageGrams(inputGrams: Double, outputGrams: Double): Double = max(0.0, inputGrams - outputGrams)

    fun wastagePct(inputGrams: Double, outputGrams: Double): Double =
        if (inputGrams <= EPS) 0.0 else wastageGrams(inputGrams, outputGrams) / inputGrams * 100.0

    /** পূর্ণ বস্তা সংখ্যা; লুজ (bagSizeKg ≤ ০) হলে ০। */
    fun bagCount(outputGrams: Double, bagSizeKg: Double): Int {
        if (bagSizeKg <= 0.0 || outputGrams <= 0.0) return 0
        return floor((outputGrams + BAG_EPS) / (bagSizeKg * 1000.0)).toInt()
    }

    /** পূর্ণ বস্তার পরে বাকি লুজ (গ্রাম)। */
    fun looseGrams(outputGrams: Double, bagSizeKg: Double): Double {
        if (bagSizeKg <= 0.0) return max(0.0, outputGrams)
        return max(0.0, outputGrams - bagCount(outputGrams, bagSizeKg) * bagSizeKg * 1000.0)
    }

    /** কেজি-প্রতি খরচ (পয়সা); আউটপুট শূন্য হলে ০ (ভাগ নয়)। */
    fun costPerKgPaisa(totalPaisa: Long, outputGrams: Double): Long =
        if (outputGrams <= EPS) 0L else (totalPaisa.toDouble() * 1000.0 / outputGrams).roundToLong()

    /** বস্তা-প্রতি খরচ মোট খরচ থেকে সরাসরি (দুইবার রাউন্ডিং এড়াতে); লুজ বা আউটপুট শূন্য হলে ০। */
    fun costPerBagPaisa(totalPaisa: Long, outputGrams: Double, bagSizeKg: Double): Long =
        if (outputGrams <= EPS || bagSizeKg <= 0.0) 0L
        else (totalPaisa.toDouble() * (bagSizeKg * 1000.0) / outputGrams).roundToLong()

    /** ফিনিশড গুডস স্টকের মূল্য = স্টক × ইউনিট খরচ। */
    fun fgStockValuePaisa(stockGrams: Double, costPerKgPaisa: Long): Long = valuePaisa(max(0.0, stockGrams), costPerKgPaisa)

    /** বিক্রয়মূল্য (কেজি-প্রতি) বনাম খরচ; মার্জিন % = মার্জিন ÷ বিক্রয়মূল্য × ১০০ (বিক্রয়মূল্য ≤ ০ হলে ০)। */
    fun margin(sellPricePaisaPerKg: Long, costPerKgPaisa: Long): Margin {
        val m = sellPricePaisaPerKg - costPerKgPaisa
        val pct = if (sellPricePaisaPerKg <= 0L) 0.0 else m.toDouble() / sellPricePaisaPerKg.toDouble() * 100.0
        return Margin(m, pct)
    }

    /** পুরো ব্যাচের সম্ভাব্য মুনাফা = আউটপুট × বিক্রয়মূল্য − মোট খরচ। */
    fun batchMarginPaisa(outputGrams: Double, sellPricePaisaPerKg: Long, totalCostPaisa: Long): Long =
        valuePaisa(max(0.0, outputGrams), sellPricePaisaPerKg) - totalCostPaisa

    /** একাধিক ব্যাচের মোট প্রোডাকশন ভ্যালু ও গড় খরচ। */
    fun productionValue(batches: List<BatchCostSummary>): ProductionValue {
        val cost = batches.sumOf { it.totalCostPaisa }
        val out = batches.sumOf { it.outputGrams }
        return ProductionValue(cost, out, costPerKgPaisa(cost, out))
    }

    /** পূর্ণ ব্যাচ কস্টিং। আউটপুট শূন্য হলে মোট খরচ ঠিকই থাকে, কিন্তু কেজি/বস্তা খরচ ০। */
    fun compute(input: BatchCostInput): BatchCostResult {
        require(input.outputGoodGrams >= 0.0 && !input.outputGoodGrams.isNaN()) { "আউটপুট ঋণাত্মক হতে পারে না" }
        require(input.labourPaisa >= 0 && input.electricityOverheadPaisa >= 0) { "লেবার/ওভারহেড ঋণাত্মক হতে পারে না" }
        require(input.bagSizeKg >= 0.0) { "বস্তার সাইজ ঋণাত্মক হতে পারে না" }
        var raw = 0L
        var med = 0L
        var pack = 0L
        var inputGrams = 0.0
        for (m in input.materials) {
            require(m.qtyGrams >= 0.0 && !m.qtyGrams.isNaN()) { "উপাদানের পরিমাণ ঋণাত্মক হতে পারে না" }
            require(m.costPaisaPerKg >= 0L) { "উপাদানের খরচ ঋণাত্মক হতে পারে না" }
            val v = valuePaisa(m.qtyGrams, m.costPaisaPerKg)
            when (m.category) {
                CostCategory.RAW -> { raw += v; inputGrams += m.qtyGrams }
                CostCategory.MEDICINE_PREMIX -> { med += v; inputGrams += m.qtyGrams }
                CostCategory.PACKAGING -> pack += v
            }
        }
        val machine = machineCostPaisa(input.machineRuns)
        val total = raw + med + pack + input.labourPaisa + input.electricityOverheadPaisa + machine
        val out = input.outputGoodGrams
        val perKg = costPerKgPaisa(total, out)
        val perKgFull = costPerKgPaisa(total, inputGrams)
        return BatchCostResult(
            rawMaterialPaisa = raw,
            medicinePremixPaisa = med,
            packagingPaisa = pack,
            labourPaisa = input.labourPaisa,
            electricityOverheadPaisa = input.electricityOverheadPaisa,
            machinePaisa = machine,
            totalPaisa = total,
            inputGrams = inputGrams,
            outputGoodGrams = out,
            wastageGrams = wastageGrams(inputGrams, out),
            wastagePct = wastagePct(inputGrams, out),
            yieldPct = yieldPct(inputGrams, out),
            outputBags = bagCount(out, input.bagSizeKg),
            looseGrams = looseGrams(out, input.bagSizeKg),
            costPerKgPaisa = perKg,
            costPerBagPaisa = costPerBagPaisa(total, out, input.bagSizeKg),
            costPerKgAtFullYieldPaisa = perKgFull,
            wastageImpactPerKgPaisa = if (out <= EPS || inputGrams <= EPS) 0L else max(0L, perKg - perKgFull),
        )
    }
}
