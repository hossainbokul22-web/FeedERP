package com.feederp.rnd

import kotlin.math.roundToLong

/**
 * ফিড ট্রায়ালের একটি গ্রুপের কাঁচা ইনপুট (বিশুদ্ধ লজিক, Android/Room নির্ভরতা নেই)।
 * ওজন প্রাণী-প্রতি গ্রামে; [feedIntakeGrams] মোট খাদ্য গ্রহণ গ্রামে।
 */
data class FcrInput(
    val animalCount: Int,
    val days: Int,
    val startWeightGrams: Double,
    val endWeightGrams: Double,
    val feedIntakeGrams: Double,
    val mortalityCount: Int = 0,
    val eggCount: Long? = null,
    val milkLiters: Double? = null,
    val feedCostPaisaPerKg: Long? = null,
)

/**
 * হিসাবের ফল। null মানে হিসাব সম্ভব নয় (যেমন ওজন বৃদ্ধি শূন্য বা ঋণাত্মক হলে FCR নেই)।
 * [animalDays] = গড় জীবিত প্রাণী x দিন (মৃত্যু সমানভাবে ঘটেছে ধরে), ডিম/দুধের হারের হর।
 */
data class FcrResult(
    val animalCount: Int,
    val survivors: Int,
    val mortalityPct: Double,
    val gainPerAnimalGrams: Double,
    val totalGainGrams: Double,          // জীবিত প্রাণীর মোট বৃদ্ধি (মৃতের ওজন ধরা হয় না)
    val feedIntakeGrams: Double,
    val animalDays: Double,
    val fcr: Double?,
    val avgDailyGainGrams: Double?,      // প্রাণী-প্রতি দৈনিক গড় বৃদ্ধি
    val feedCostTotalPaisa: Long?,
    val costPerKgGainPaisa: Long?,
    val eggCount: Long?,
    val eggProductionPct: Double?,       // হেন-ডে %
    val milkLiters: Double?,
    val milkPerAnimalPerDayLiters: Double?,
)

object FcrCalculator {

    /** ইনপুটের ভুল বাংলায়। খালি তালিকা = ঠিক আছে। */
    fun validate(i: FcrInput): List<String> {
        val errors = mutableListOf<String>()
        if (i.animalCount <= 0) errors += "প্রাণীর সংখ্যা ০ এর বেশি হতে হবে"
        if (i.days <= 0) errors += "দিনের সংখ্যা ০ এর বেশি হতে হবে"
        if (!i.startWeightGrams.isFinite() || i.startWeightGrams < 0) errors += "শুরুর ওজন ঋণাত্মক বা অবৈধ হতে পারবে না"
        if (!i.endWeightGrams.isFinite() || i.endWeightGrams < 0) errors += "শেষ ওজন ঋণাত্মক বা অবৈধ হতে পারবে না"
        if (!i.feedIntakeGrams.isFinite() || i.feedIntakeGrams < 0) errors += "খাদ্য গ্রহণ ঋণাত্মক বা অবৈধ হতে পারবে না"
        if (i.mortalityCount < 0) errors += "মৃত্যুর সংখ্যা ঋণাত্মক হতে পারবে না"
        if (i.animalCount > 0 && i.mortalityCount > i.animalCount) errors += "মৃত্যুর সংখ্যা মোট প্রাণীর চেয়ে বেশি হতে পারবে না"
        if (i.eggCount != null && i.eggCount < 0) errors += "ডিমের সংখ্যা ঋণাত্মক হতে পারবে না"
        if (i.milkLiters != null && (!i.milkLiters.isFinite() || i.milkLiters < 0)) errors += "দুধের পরিমাণ ঋণাত্মক হতে পারবে না"
        if (i.feedCostPaisaPerKg != null && i.feedCostPaisaPerKg < 0) errors += "খাদ্যের দাম ঋণাত্মক হতে পারবে না"
        return errors
    }

    /** ইনপুট অবৈধ হলেও ক্র্যাশ করে না; ০-ভাগ এড়ায়। FCR = মোট খাদ্য / মোট ওজন বৃদ্ধি (বৃদ্ধি <= 0 হলে null)। */
    fun calculate(i: FcrInput): FcrResult {
        val count = i.animalCount.coerceAtLeast(0)
        val dead = i.mortalityCount.coerceIn(0, count)
        val survivors = count - dead
        val mortalityPct = if (count > 0) dead * 100.0 / count else 0.0
        val gainPer = i.endWeightGrams - i.startWeightGrams
        val totalGain = survivors * gainPer
        val days = i.days.coerceAtLeast(0)
        val avgAlive = survivors + dead / 2.0
        val animalDays = avgAlive * days
        val fcr = if (totalGain > 0.0) i.feedIntakeGrams / totalGain else null
        val adg = if (days > 0 && survivors > 0) gainPer / days else null
        val feedCost = i.feedCostPaisaPerKg?.let { (i.feedIntakeGrams / 1000.0 * it).roundToLong() }
        val costPerKgGain = if (feedCost != null && totalGain > 0.0) (feedCost / (totalGain / 1000.0)).roundToLong() else null
        return FcrResult(
            animalCount = count,
            survivors = survivors,
            mortalityPct = mortalityPct,
            gainPerAnimalGrams = gainPer,
            totalGainGrams = totalGain,
            feedIntakeGrams = i.feedIntakeGrams,
            animalDays = animalDays,
            fcr = fcr,
            avgDailyGainGrams = adg,
            feedCostTotalPaisa = feedCost,
            costPerKgGainPaisa = costPerKgGain,
            eggCount = i.eggCount,
            eggProductionPct = if (i.eggCount != null && animalDays > 0.0) i.eggCount * 100.0 / animalDays else null,
            milkLiters = i.milkLiters,
            milkPerAnimalPerDayLiters = if (i.milkLiters != null && animalDays > 0.0) i.milkLiters / animalDays else null,
        )
    }

    /** একাধিক গ্রুপের যোগফল থেকে সমষ্টিগত ফল। খালি তালিকায় null। */
    fun aggregate(results: List<FcrResult>): FcrResult? {
        if (results.isEmpty()) return null
        val count = results.sumOf { it.animalCount }
        val survivors = results.sumOf { it.survivors }
        val dead = count - survivors
        val totalGain = results.sumOf { it.totalGainGrams }
        val feed = results.sumOf { it.feedIntakeGrams }
        val animalDays = results.sumOf { it.animalDays }
        val anyCost = results.any { it.feedCostTotalPaisa != null }
        val cost = if (anyCost) results.sumOf { it.feedCostTotalPaisa ?: 0L } else null
        val anyEgg = results.any { it.eggCount != null }
        val eggs = if (anyEgg) results.sumOf { it.eggCount ?: 0L } else null
        val anyMilk = results.any { it.milkLiters != null }
        val milk = if (anyMilk) results.sumOf { it.milkLiters ?: 0.0 } else null
        val gainPer = if (survivors > 0) totalGain / survivors else 0.0
        // দিনের গড় ওজন করা হয় প্রাণী-দিন / গড় প্রাণী দিয়ে
        val avgAlive = survivors + dead / 2.0
        val days = if (avgAlive > 0) animalDays / avgAlive else 0.0
        return FcrResult(
            animalCount = count,
            survivors = survivors,
            mortalityPct = if (count > 0) dead * 100.0 / count else 0.0,
            gainPerAnimalGrams = gainPer,
            totalGainGrams = totalGain,
            feedIntakeGrams = feed,
            animalDays = animalDays,
            fcr = if (totalGain > 0.0) feed / totalGain else null,
            avgDailyGainGrams = if (days > 0.0 && survivors > 0) gainPer / days else null,
            feedCostTotalPaisa = cost,
            costPerKgGainPaisa = if (cost != null && totalGain > 0.0) (cost / (totalGain / 1000.0)).roundToLong() else null,
            eggCount = eggs,
            eggProductionPct = if (eggs != null && animalDays > 0.0) eggs * 100.0 / animalDays else null,
            milkLiters = milk,
            milkPerAnimalPerDayLiters = if (milk != null && animalDays > 0.0) milk / animalDays else null,
        )
    }
}
