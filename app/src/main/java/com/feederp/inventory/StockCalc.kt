package com.feederp.inventory

import kotlin.math.abs
import kotlin.math.roundToLong

/** লটের QC স্ট্যাটাসের অনুমোদিত মান। */
object QcStatus {
    const val PENDING = "PENDING"
    const val PASSED = "PASSED"
    const val REJECTED = "REJECTED"
    const val HOLD = "HOLD"
    val all: Set<String> = setOf(PENDING, PASSED, REJECTED, HOLD)

    fun labelBn(status: String): String = when (status) {
        PENDING -> "অপেক্ষমাণ"
        PASSED -> "পাস"
        REJECTED -> "বাতিল"
        HOLD -> "হোল্ড"
        else -> status
    }
}

/** FEFO বরাদ্দে একটি লট থেকে কতটা নেওয়া হবে। */
data class LotAllocation(val lotId: String, val qtyGrams: Double)

/** [StockCalc.allocateFefo] এর ফল; [shortfallGrams] > 0 মানে লটের মোট স্টক চাহিদার কম। */
data class FefoAllocation(val allocations: List<LotAllocation>, val shortfallGrams: Double) {
    val isFullyCovered: Boolean get() = shortfallGrams <= StockCalc.EPS
}

/** ওয়েটেড-অ্যাভারেজের জন্য একটি ইনকামিং স্তর। */
data class CostLayer(val qtyGrams: Double, val costPaisaPerKg: Long)

/** মিনিমাম স্টক হিসাবের ইনপুট। */
data class StockLevel(val itemId: String, val minGrams: Double, val currentGrams: Double)

enum class ExpiryState { NONE, OK, NEAR, EXPIRED }

/** Android/Room-নিরপেক্ষ বিশুদ্ধ স্টক লজিক (ইউনিট টেস্টযোগ্য)। */
object StockCalc {
    /** গ্রামে তুলনার সহনশীলতা (ফ্লোটিং-পয়েন্ট জঞ্জাল এড়াতে)। */
    const val EPS = 0.0005
    const val NEAR_EXPIRY_DAYS = 30
    private const val DAY_MS = 86_400_000L

    /** লট বিক্রি/ব্যবহারের জন্য উপযুক্ত কিনা: PASSED সবসময়; PENDING কেবল যদি আইটেমে QC লাগে না; HOLD/REJECTED কখনও না; মেয়াদোত্তীর্ণ নয়। */
    fun isIssuable(qcStatus: String, itemRequiresQc: Boolean, expiryAt: Long?, now: Long): Boolean {
        if (expiryAt != null && expiryAt < now) return false
        return isQcIssuable(qcStatus, itemRequiresQc)
    }

    /** শুধু QC অবস্থার ভিত্তিতে (মেয়াদ বাদে)। */
    fun isQcIssuable(qcStatus: String, itemRequiresQc: Boolean): Boolean = when (qcStatus) {
        QcStatus.PASSED -> true
        QcStatus.PENDING -> !itemRequiresQc
        else -> false
    }

    /** মেয়াদ অনুযায়ী আগে-শেষ-আগে; মেয়াদহীন লট সবশেষে; সমান হলে ব্যাচ নম্বর অনুযায়ী (স্থির ক্রম)। */
    fun fefoSort(lots: List<LotStock>): List<LotStock> =
        lots.sortedWith(
            compareBy<LotStock> { it.expiryAt == null }
                .thenBy { it.expiryAt ?: Long.MAX_VALUE }
                .thenBy { it.batchNo }
                .thenBy { it.lotId }
        )

    /** [lots] FEFO ক্রমে সাজিয়ে [requiredGrams] পর্যন্ত বরাদ্দ করে। */
    fun allocateFefo(lots: List<LotStock>, requiredGrams: Double): FefoAllocation {
        require(requiredGrams >= 0.0) { "পরিমাণ ঋণাত্মক হতে পারে না" }
        var remaining = requiredGrams
        val out = ArrayList<LotAllocation>()
        for (lot in fefoSort(lots)) {
            if (remaining <= EPS) break
            if (lot.qtyGrams <= EPS) continue
            val take = minOf(lot.qtyGrams, remaining)
            out += LotAllocation(lot.lotId, take)
            remaining -= take
        }
        return FefoAllocation(out, if (remaining <= EPS) 0.0 else remaining)
    }

    /** মুভমেন্টের যোগফল = স্টক। */
    fun sumGrams(movementQty: List<Double>): Double = movementQty.sum()

    /** কেজি প্রতি ওয়েটেড-অ্যাভারেজ খরচ (পয়সা)। ইনকামিং স্তর না থাকলে 0। */
    fun weightedAvgCostPaisaPerKg(layers: List<CostLayer>): Long {
        val valid = layers.filter { it.qtyGrams > 0.0 && it.costPaisaPerKg >= 0 }
        val totalQty = valid.sumOf { it.qtyGrams }
        if (totalQty <= 0.0) return 0L
        val weighted = valid.sumOf { it.qtyGrams * it.costPaisaPerKg.toDouble() }
        return (weighted / totalQty).roundToLong()
    }

    /** পরিমাণ (গ্রাম) × কেজি-প্রতি দাম = মূল্য (পয়সা)। */
    fun valuePaisa(qtyGrams: Double, costPaisaPerKg: Long): Long =
        (qtyGrams / 1000.0 * costPaisaPerKg.toDouble()).roundToLong()

    /** মিনিমাম স্টকের নিচে (বা সমান) থাকা আইটেম; min = 0 হলে অ্যালার্ট নেই। ঘাটতি বেশি আগে। */
    fun lowStock(levels: List<StockLevel>): List<StockLevel> =
        levels.filter { it.minGrams > 0.0 && it.currentGrams <= it.minGrams + EPS }
            .sortedByDescending { it.minGrams - it.currentGrams }

    fun isNegative(grams: Double): Boolean = grams < -EPS

    fun expiryState(expiryAt: Long?, now: Long): ExpiryState = when {
        expiryAt == null -> ExpiryState.NONE
        expiryAt < now -> ExpiryState.EXPIRED
        expiryAt - now <= NEAR_EXPIRY_DAYS * DAY_MS -> ExpiryState.NEAR
        else -> ExpiryState.OK
    }

    fun daysToExpiry(expiryAt: Long, now: Long): Long = Math.floorDiv(expiryAt - now, DAY_MS)

    /** মুভমেন্ট টাইপ অনুযায়ী পরিমাণের চিহ্ন ঠিক কিনা; ভুল হলে বাংলা ত্রুটি, ঠিক হলে null। */
    fun signError(type: com.feederp.data.MovementType, qtyGrams: Double): String? {
        if (qtyGrams.isNaN() || qtyGrams.isInfinite()) return "পরিমাণ সঠিক নয়"
        if (abs(qtyGrams) <= EPS) return "পরিমাণ শূন্য হতে পারে না"
        return when (type) {
            com.feederp.data.MovementType.PURCHASE_IN,
            com.feederp.data.MovementType.PRODUCTION_IN,
            com.feederp.data.MovementType.RETURN_IN ->
                if (qtyGrams < 0) "ইনকামিং মুভমেন্টে পরিমাণ ধনাত্মক হতে হবে" else null
            com.feederp.data.MovementType.PRODUCTION_OUT,
            com.feederp.data.MovementType.SALE_OUT ->
                if (qtyGrams > 0) "আউটগোয়িং মুভমেন্টে পরিমাণ ঋণাত্মক হতে হবে" else null
            com.feederp.data.MovementType.TRANSFER,
            com.feederp.data.MovementType.ADJUSTMENT -> null
        }
    }

    /** [available] থেকে [needed] তোলা যায় কিনা। */
    fun hasEnough(available: Double, needed: Double): Boolean = available + EPS >= needed
}
