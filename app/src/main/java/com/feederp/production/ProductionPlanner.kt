package com.feederp.production

import com.feederp.inventory.LotAllocation
import com.feederp.inventory.LotStock
import com.feederp.inventory.StockCalc

/** ব্যাচ পরিকল্পনার বিশুদ্ধ লজিক (Android/Room নির্ভরতা নেই, টেস্টযোগ্য)। ওজন গ্রামে। */
object ProductionPlanner {
    const val GRAMS_PER_TON = 1_000_000.0
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** ফর্মুলার একটি উপাদানের চাহিদা (এই ব্যাচের জন্য)। */
    data class Need(val itemId: String, val name: String, val requiredGrams: Double)

    /** একটি উপাদানের বরাদ্দ: কোন লট থেকে কত; [shortfallGrams] > 0 মানে স্টকে কম। */
    data class PlanLine(
        val itemId: String,
        val name: String,
        val requiredGrams: Double,
        val allocations: List<LotAllocation>,
        val shortfallGrams: Double,
    ) {
        val isCovered: Boolean get() = shortfallGrams <= StockCalc.EPS
    }

    /** গ্রাম/টন ফর্মুলা-লাইন থেকে [batchGrams] আকারের ব্যাচের জন্য প্রয়োজনীয় গ্রাম। */
    fun requiredGrams(gramsPerTon: Double, batchGrams: Double): Double {
        require(gramsPerTon >= 0.0 && batchGrams >= 0.0) { "পরিমাণ ঋণাত্মক হতে পারে না" }
        return gramsPerTon * batchGrams / GRAMS_PER_TON
    }

    /**
     * প্রতিটি উপাদানের জন্য FEFO অনুযায়ী লট বরাদ্দ। [lotsByItem] এ শুধু ব্যবহারযোগ্য (QC-উপযুক্ত, মেয়াদোত্তীর্ণ নয়)
     * লট দিতে হবে (স্টক রিপোজিটরির `lotsFefo` ঠিক এটাই দেয়)। লটহীন আইটেমের (trackLot=false) জন্য [lotless] তে
     * আইটেমের id দিলে একটি বরাদ্দহীন লাইন ফেরত আসে (লট ছাড়া খরচ), ঘাটতি হিসাব হয় [lotlessStock] থেকে।
     */
    fun plan(
        needs: List<Need>,
        lotsByItem: Map<String, List<LotStock>>,
        lotless: Set<String> = emptySet(),
        lotlessStock: Map<String, Double> = emptyMap(),
    ): List<PlanLine> = needs.map { n ->
        if (n.itemId in lotless) {
            val have = lotlessStock[n.itemId] ?: 0.0
            val short = if (have + StockCalc.EPS >= n.requiredGrams) 0.0 else n.requiredGrams - have
            PlanLine(n.itemId, n.name, n.requiredGrams, emptyList(), short)
        } else {
            val a = StockCalc.allocateFefo(lotsByItem[n.itemId].orEmpty(), n.requiredGrams)
            PlanLine(n.itemId, n.name, n.requiredGrams, a.allocations, a.shortfallGrams)
        }
    }

    fun shortages(plan: List<PlanLine>): List<PlanLine> = plan.filter { !it.isCovered }

    /** ঘাটতির বাংলা বার্তা (খালি = ঘাটতি নেই)। */
    fun shortageMessage(plan: List<PlanLine>): String? {
        val s = shortages(plan)
        if (s.isEmpty()) return null
        return "স্টক অপর্যাপ্ত: " + s.joinToString("; ") {
            "${it.name} (ঘাটতি ${String.format(java.util.Locale.US, "%.2f", it.shortfallGrams / 1000.0)} কেজি)"
        }
    }

    /** ফর্মুলার মোট গ্রাম/টন (১০,০০,০০০ হলে পূর্ণ ১০০%)। */
    fun totalGramsPerTon(lines: List<Double>): Double = lines.sum()

    /** মেয়াদ = উৎপাদনের তারিখ + শেলফ-লাইফ দিন। */
    fun expiryAt(mfgAt: Long, shelfLifeDays: Int): Long {
        require(shelfLifeDays > 0) { "শেলফ-লাইফ কমপক্ষে ১ দিন হতে হবে" }
        return mfgAt + shelfLifeDays * DAY_MILLIS
    }

    /** মেশিন রানের নেট ঘণ্টা = (শেষ − শুরু) − ডাউনটাইম; ঋণাত্মক হলে ব্যর্থ। */
    fun netRunHours(startAt: Long, endAt: Long, downtimeMinutes: Int): Double {
        require(endAt > startAt) { "রানের শেষ সময় শুরুর পরে হতে হবে" }
        require(downtimeMinutes >= 0) { "ডাউনটাইম ঋণাত্মক হতে পারে না" }
        val gross = (endAt - startAt) / 3_600_000.0
        val net = gross - downtimeMinutes / 60.0
        require(net >= 0.0) { "ডাউনটাইম রানের সময়ের চেয়ে বেশি হতে পারে না" }
        return net
    }

    /** ওষুধযুক্ত ব্যাচের পর লাইন-ক্লিনিংয়ের ডিফল্ট চেকলিস্ট (ক্রম অনুযায়ী)। */
    val defaultCleaningChecklist: List<String> = listOf(
        "মিক্সার খালি করে সম্পূর্ণ পরিষ্কার করা হয়েছে",
        "কনভেয়র, এলিভেটর ও হপার ঝাড়া/ভ্যাকুয়াম করা হয়েছে",
        "গ্রাইন্ডার/পেলেট মিলের ডাই ও চেম্বার পরিষ্কার",
        "ওষুধযুক্ত ব্যাচের অবশিষ্ট ফিড আলাদা করে চিহ্নিত/নষ্ট করা হয়েছে",
        "পরবর্তী ব্যাচের আগে ফ্লাশ ব্যাচ (ফাঁকা/সাধারণ ফিড) চালানো হয়েছে",
        "সুপারভাইজার পরিদর্শন করে নিশ্চিত করেছেন",
    )

    /** ব্যাচ ক্লিনিং সম্পূর্ণ কি না (সব আইটেম done এবং অন্তত একটি আইটেম আছে)। */
    fun isCleaningComplete(done: List<Boolean>): Boolean = done.isNotEmpty() && done.all { it }
}
