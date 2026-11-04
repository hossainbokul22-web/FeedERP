package com.feederp.master

import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.PartyType

/** বাংলা লেবেল। */
object MasterLabels {
    fun itemType(t: ItemType): String = when (t) {
        ItemType.RAW_MATERIAL -> "কাঁচামাল"
        ItemType.PREMIX -> "প্রিমিক্স"
        ItemType.MEDICINE -> "ওষুধ"
        ItemType.ADDITIVE -> "অ্যাডিটিভ"
        ItemType.PACKAGING -> "প্যাকেজিং"
        ItemType.FINISHED_GOOD -> "ফিনিশড গুডস"
    }

    fun partyType(t: PartyType): String = when (t) {
        PartyType.SUPPLIER -> "সাপ্লায়ার"
        PartyType.CUSTOMER -> "কাস্টমার"
        PartyType.DEALER -> "ডিলার"
    }

    /** ওষুধ/প্রিমিক্স/অ্যাডিটিভের বিশেষ ফিল্ড (সক্রিয় উপাদান, ডোজ, উইথড্রয়াল) দেখানোর শর্ত। */
    fun showsMedicineFields(t: ItemType): Boolean =
        t == ItemType.MEDICINE || t == ItemType.PREMIX || t == ItemType.ADDITIVE
}

/** Android/Room-নিরপেক্ষ মাস্টার ডাটা যাচাই; ভুল থাকলে প্রথম বাংলা ত্রুটি, ঠিক হলে null। */
object MasterValidator {
    private fun badPct(v: Double?): Boolean = v != null && (v.isNaN() || v < 0.0 || v > 100.0)
    private fun bad(v: Double?): Boolean = v != null && (v.isNaN() || v.isInfinite() || v < 0.0)

    fun validateItem(item: ItemEntity): String? {
        if (item.code.isBlank()) return "আইটেম কোড দিন"
        if (item.name.isBlank()) return "আইটেমের নাম দিন"
        if (item.unitId.isBlank()) return "ইউনিট বাছাই করুন"
        if (item.minStockGrams.isNaN() || item.minStockGrams < 0.0) return "মিনিমাম স্টক ঋণাত্মক হতে পারে না"
        if (item.trackExpiry && !item.trackLot) return "মেয়াদ ট্র্যাক করতে লট ট্র্যাক চালু করতে হবে"
        if (item.type == ItemType.MEDICINE && item.activeIngredient.isNullOrBlank()) return "ওষুধের সক্রিয় উপাদান (Active Ingredient) দিন"
        if (badPct(item.concentrationPct)) return "ঘনত্ব ০-১০০% এর মধ্যে হতে হবে"
        if (bad(item.minDoseGPerTon) || bad(item.maxDoseGPerTon)) return "ডোজ ঋণাত্মক হতে পারে না"
        val lo = item.minDoseGPerTon
        val hi = item.maxDoseGPerTon
        if (lo != null && hi != null && lo > hi) return "সর্বনিম্ন ডোজ সর্বোচ্চ ডোজের বেশি হতে পারে না"
        val wd = item.withdrawalDays
        if (wd != null && wd < 0) return "উইথড্রয়াল দিন ঋণাত্মক হতে পারে না"
        if (badPct(item.proteinPct) || badPct(item.fatPct) || badPct(item.fibrePct) || badPct(item.moisturePct)) {
            return "নিউট্রিয়েন্ট শতাংশ ০-১০০ এর মধ্যে হতে হবে"
        }
        if (bad(item.energyKcalPerKg)) return "শক্তি (kcal/kg) ঋণাত্মক হতে পারে না"
        return null
    }

    fun validateUnit(name: String, symbol: String, toBaseFactor: Double): String? {
        if (name.isBlank()) return "ইউনিটের নাম দিন"
        if (symbol.isBlank()) return "ইউনিটের চিহ্ন দিন"
        if (toBaseFactor.isNaN() || toBaseFactor.isInfinite() || toBaseFactor <= 0.0) return "গ্রামে রূপান্তর গুণক ০ এর বেশি হতে হবে"
        return null
    }

    fun validateParty(name: String, phone: String?, type: PartyType, creditLimitPaisa: Long): String? {
        if (name.isBlank()) return "নাম দিন"
        if (!phone.isNullOrBlank() && !phone.trim().matches(Regex("^[+0-9][0-9 \\-]{5,19}$"))) return "ফোন নম্বর সঠিক নয়"
        if (creditLimitPaisa < 0) return "ক্রেডিট লিমিট ঋণাত্মক হতে পারে না"
        if (type == PartyType.SUPPLIER && creditLimitPaisa != 0L) return "সাপ্লায়ারের ক্রেডিট লিমিট থাকে না"
        return null
    }

    fun validateWarehouse(name: String): String? =
        if (name.isBlank()) "গুদামের নাম দিন" else null
}
