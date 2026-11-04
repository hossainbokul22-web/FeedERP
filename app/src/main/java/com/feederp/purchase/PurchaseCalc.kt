package com.feederp.purchase

import com.feederp.inventory.StockCalc
import kotlin.math.roundToLong

/** PO স্ট্যাটাসের মান (PurchaseOrderEntity.status)। */
object PoStatus {
    const val DRAFT = "DRAFT"
    const val ORDERED = "ORDERED"
    const val PARTIAL = "PARTIAL"
    const val RECEIVED = "RECEIVED"
    const val CANCELLED = "CANCELLED"

    fun labelBn(status: String): String = when (status) {
        DRAFT -> "খসড়া"
        ORDERED -> "অনুমোদিত"
        PARTIAL -> "আংশিক রিসিভ"
        RECEIVED -> "সম্পূর্ণ রিসিভ"
        CANCELLED -> "বাতিল"
        else -> status
    }

    /** এই স্ট্যাটাসে গুডস রিসিভ করা যায়। */
    fun canReceive(status: String): Boolean = status == ORDERED || status == PARTIAL
}

/** পেমেন্ট মাধ্যম। */
object PayMethod {
    val all = listOf("CASH", "BANK", "MOBILE_BANKING")
    fun labelBn(m: String): String = when (m) {
        "CASH" -> "ক্যাশ"
        "BANK" -> "ব্যাংক"
        "MOBILE_BANKING" -> "মোবাইল ব্যাংকিং"
        else -> m
    }
}

/** Android/Room-নিরপেক্ষ বিশুদ্ধ ক্রয়-হিসাব (ইউনিট টেস্টযোগ্য)। */
object PurchaseCalc {
    /** পরিমাণ (গ্রাম) × কেজি-প্রতি দর (পয়সা) = লাইন মোট (পয়সা)। */
    fun lineTotalPaisa(qtyGrams: Double, ratePaisaPerKg: Long): Long =
        (qtyGrams / 1000.0 * ratePaisaPerKg.toDouble()).roundToLong()

    /** বাকি পরিমাণ (ঋণাত্মক হয় না)। */
    fun remainingGrams(orderedGrams: Double, receivedGrams: Double): Double =
        (orderedGrams - receivedGrams).let { if (it <= StockCalc.EPS) 0.0 else it }

    /**
     * রিসিভের পর PO স্ট্যাটাস: সব আইটেম অর্ডারের সমান/বেশি এলে RECEIVED, কিছু এলে PARTIAL, নইলে ORDERED।
     * [ordered]/[received]: itemId -> গ্রাম।
     */
    fun statusAfterReceipt(ordered: Map<String, Double>, received: Map<String, Double>): String {
        if (ordered.isEmpty()) return PoStatus.ORDERED
        val allDone = ordered.all { (item, qty) -> (received[item] ?: 0.0) + StockCalc.EPS >= qty }
        if (allDone) return PoStatus.RECEIVED
        val anyReceived = ordered.keys.any { (received[it] ?: 0.0) > StockCalc.EPS }
        return if (anyReceived) PoStatus.PARTIAL else PoStatus.ORDERED
    }

    /** সাপ্লায়ারকে আমরা কত দেব (লেজার ব্যালেন্স: ঋণাত্মক = আমরা দেব)। অগ্রিম দেওয়া থাকলে 0। */
    fun payableDuePaisa(partyBalancePaisa: Long): Long = if (partyBalancePaisa < 0) -partyBalancePaisa else 0L

    /** সাপ্লায়ারের কাছে আমরা অগ্রিম পাব (লেজার ধনাত্মক)। */
    fun advanceWithSupplierPaisa(partyBalancePaisa: Long): Long = if (partyBalancePaisa > 0) partyBalancePaisa else 0L
}
