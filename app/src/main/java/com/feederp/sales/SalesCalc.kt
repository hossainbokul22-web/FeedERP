package com.feederp.sales

import java.math.BigDecimal
import java.math.RoundingMode

/** বাকির বয়স-ভাগ (ইনভয়েসের তারিখ থেকে দিন গুনে)। */
enum class AgingBucket(val labelBn: String) {
    D0_30("০-৩০ দিন"),
    D31_60("৩১-৬০ দিন"),
    D61_90("৬১-৯০ দিন"),
    D90_PLUS("৯০+ দিন"),
}

/** ক্রেডিট লিমিট যাচাইয়ের ফল। [exceededByPaisa] = লিমিটের ওপরে কত (0 হলে ঠিক আছে)। */
data class CreditCheck(
    val allowed: Boolean,
    val limitPaisa: Long,
    val exposureAfterPaisa: Long,
    val exceededByPaisa: Long,
)

/** বস্তা + খোলা (লুজ) ওজন। */
data class BagQty(val bags: Long, val looseGrams: Double)

/** পার্টির বাকির বয়স-ভিত্তিক যোগফল (পয়সা)। */
data class AgingTotals(
    val d0to30: Long = 0,
    val d31to60: Long = 0,
    val d61to90: Long = 0,
    val d90plus: Long = 0,
) {
    val total: Long get() = d0to30 + d31to60 + d61to90 + d90plus
    operator fun plus(o: AgingTotals) = AgingTotals(
        d0to30 + o.d0to30, d31to60 + o.d31to60, d61to90 + o.d61to90, d90plus + o.d90plus,
    )
}

/**
 * Android/Room-নিরপেক্ষ বিশুদ্ধ বিক্রয়-হিসাব (ইউনিট টেস্টযোগ্য)।
 *
 * রাউন্ডিং নিয়ম (সব জায়গায় একই): পয়সার ভগ্নাংশ HALF_UP (০.৫ হলে ওপরে) — [BigDecimal] দিয়ে, Double ত্রুটি ছাড়া।
 * ডিসকাউন্ট ও ভ্যাট বেসিস পয়েন্টে (bps; ১০০ bps = ১%)।
 */
object SalesCalc {
    private const val DAY_MS = 86_400_000L
    private const val EPS = 0.0005

    /** ডিসকাউন্ট অনুমোদন ছাড়া Sales রোল সর্বোচ্চ এতটা (সাবটোটালের ৫%) দিতে পারে; বেশি হলে PRICE_EDIT লাগে। */
    const val MAX_DISCOUNT_BPS_NO_APPROVAL = 500

    private fun roundHalfUp(v: BigDecimal): Long = v.setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** পরিমাণ (গ্রাম) × কেজি-প্রতি দাম (পয়সা) = লাইন মোট (পয়সা), HALF_UP। */
    fun lineTotalPaisa(qtyGrams: Double, unitPricePaisaPerKg: Long): Long {
        require(!qtyGrams.isNaN() && !qtyGrams.isInfinite()) { "পরিমাণ সঠিক নয়" }
        return roundHalfUp(
            BigDecimal.valueOf(qtyGrams).multiply(BigDecimal.valueOf(unitPricePaisaPerKg)).divide(BigDecimal(1000))
        )
    }

    /** [amountPaisa] এর [bps] ভাগ (HALF_UP)। */
    fun percentOf(amountPaisa: Long, bps: Int): Long {
        require(bps >= 0) { "হার ঋণাত্মক হতে পারে না" }
        return roundHalfUp(BigDecimal.valueOf(amountPaisa).multiply(BigDecimal(bps)).divide(BigDecimal(10_000)))
    }

    /** সাবটোটালের ওপর ডিসকাউন্ট (bps) → পয়সা। */
    fun discountFromBps(subtotalPaisa: Long, bps: Int): Long = percentOf(subtotalPaisa, bps)

    /** ডিসকাউন্টের পর করযোগ্য অঙ্কের ওপর ভ্যাট (bps) → পয়সা। */
    fun vatPaisa(taxablePaisa: Long, vatBps: Int): Long = percentOf(taxablePaisa.coerceAtLeast(0), vatBps)

    /**
     * ইনভয়েস মোট = সাবটোটাল − ডিসকাউন্ট + ভ্যাট(সাবটোটাল − ডিসকাউন্ট)।
     * ডিসকাউন্ট সাবটোটালের বেশি বা ঋণাত্মক হলে IllegalArgumentException।
     */
    fun invoiceTotalPaisa(subtotalPaisa: Long, discountPaisa: Long, vatBps: Int = 0): Long {
        require(subtotalPaisa >= 0) { "সাবটোটাল ঋণাত্মক হতে পারে না" }
        require(discountPaisa >= 0) { "ডিসকাউন্ট ঋণাত্মক হতে পারে না" }
        require(discountPaisa <= subtotalPaisa) { "ডিসকাউন্ট মোটের চেয়ে বেশি হতে পারে না" }
        val taxable = subtotalPaisa - discountPaisa
        return taxable + vatPaisa(taxable, vatBps)
    }

    /** ডিসকাউন্ট কি অনুমোদন-সীমার (সাবটোটালের [MAX_DISCOUNT_BPS_NO_APPROVAL] bps) ভেতরে। */
    fun isDiscountWithinSalesLimit(subtotalPaisa: Long, discountPaisa: Long): Boolean =
        discountPaisa <= percentOf(subtotalPaisa, MAX_DISCOUNT_BPS_NO_APPROVAL)

    /**
     * ইনভয়েস-ডিসকাউন্ট লাইনগুলোর মধ্যে আনুপাতিক ভাগ। ভগ্নাংশ HALF_UP; সব লাইনের যোগফল ঠিক [discountPaisa] রাখতে
     * সর্বশেষ লাইন বাকিটা নেয়। ফেরত: প্রতিটি লাইনের ডিসকাউন্ট ভাগ।
     */
    fun allocateDiscount(lineTotals: List<Long>, discountPaisa: Long): List<Long> {
        if (lineTotals.isEmpty()) return emptyList()
        val sum = lineTotals.sum()
        if (discountPaisa == 0L || sum == 0L) return lineTotals.map { 0L }
        var used = 0L
        return lineTotals.mapIndexed { i, t ->
            if (i == lineTotals.lastIndex) discountPaisa - used
            else roundHalfUp(
                BigDecimal.valueOf(discountPaisa).multiply(BigDecimal.valueOf(t)).divide(BigDecimal.valueOf(sum), 10, RoundingMode.HALF_UP)
            ).also { used += it }
        }
    }

    /**
     * একটি লাইনের [returnGrams] ফেরতে ক্রেডিট (ডিসকাউন্ট-সমন্বিত, পয়সা)।
     * [lineNetPaisa] = লাইন মোট − তার ডিসকাউন্ট ভাগ; [alreadyCreditedPaisa] = এই লাইনে আগের ফেরতের ক্রেডিট।
     * লাইনের বাকি পুরোটা ফেরত গেলে ক্রেডিট = বাকি নেট অঙ্ক (পয়সার রাউন্ডিং জমে না)।
     */
    fun returnCreditPaisa(
        lineQtyGrams: Double,
        lineNetPaisa: Long,
        alreadyReturnedGrams: Double,
        alreadyCreditedPaisa: Long,
        returnGrams: Double,
    ): Long {
        require(returnGrams > EPS) { "ফেরতের পরিমাণ শূন্যের বেশি হতে হবে" }
        require(lineQtyGrams > EPS) { "লাইনের পরিমাণ সঠিক নয়" }
        val remaining = lineQtyGrams - alreadyReturnedGrams
        require(returnGrams <= remaining + EPS) { "ফেরত বিক্রীত পরিমাণের বেশি হতে পারে না" }
        if (returnGrams + EPS >= remaining) return (lineNetPaisa - alreadyCreditedPaisa).coerceAtLeast(0)
        return roundHalfUp(
            BigDecimal.valueOf(lineNetPaisa).multiply(BigDecimal.valueOf(returnGrams))
                .divide(BigDecimal.valueOf(lineQtyGrams), 10, RoundingMode.HALF_UP)
        ).coerceAtMost((lineNetPaisa - alreadyCreditedPaisa).coerceAtLeast(0))
    }

    // ---------------- ক্রেডিট লিমিট ----------------

    /**
     * ক্রেডিট লিমিট যাচাই। [currentBalancePaisa] = পার্টির বর্তমান পাওনা (লেজার; + = আমরা পাব),
     * [newUnpaidPaisa] = এই ইনভয়েসের যেটুকু বাকি থাকবে। এক্সপোজার = পাওনা + নতুন বাকি।
     * লিমিট ০ = বাকিতে বিক্রি নিষিদ্ধ (শুধু নগদ)। নতুন বাকি ≤ ০ (পুরো নগদ) হলে সবসময় অনুমোদিত।
     * অগ্রিম জমা (ঋণাত্মক পাওনা) এক্সপোজার কমায়।
     */
    fun checkCredit(limitPaisa: Long, currentBalancePaisa: Long, newUnpaidPaisa: Long): CreditCheck {
        val exposure = currentBalancePaisa + newUnpaidPaisa.coerceAtLeast(0)
        if (newUnpaidPaisa <= 0) return CreditCheck(true, limitPaisa, exposure, 0)
        val over = (exposure - limitPaisa.coerceAtLeast(0)).coerceAtLeast(0)
        return CreditCheck(over == 0L, limitPaisa, exposure, over)
    }

    // ---------------- বাকি ও এজিং ----------------

    /** ইনভয়েসের বকেয়া = মোট − আদায় − ফেরত ক্রেডিট (ঋণাত্মক হয় না)। */
    fun outstandingPaisa(totalPaisa: Long, paidPaisa: Long, returnedPaisa: Long): Long =
        (totalPaisa - paidPaisa - returnedPaisa).coerceAtLeast(0)

    /** তারিখ থেকে এখন পর্যন্ত পূর্ণ দিন (ভবিষ্যতের তারিখ হলে ০)। */
    fun ageDays(invoiceDate: Long, now: Long): Long = Math.floorDiv(now - invoiceDate, DAY_MS).coerceAtLeast(0)

    /** ০-৩০ / ৩১-৬০ / ৬১-৯০ / ৯০+ (৩০ দিন পূর্ণ = প্রথম ভাগ, ৩১তম দিন থেকে দ্বিতীয়)। */
    fun bucketOf(ageDays: Long): AgingBucket = when {
        ageDays <= 30 -> AgingBucket.D0_30
        ageDays <= 60 -> AgingBucket.D31_60
        ageDays <= 90 -> AgingBucket.D61_90
        else -> AgingBucket.D90_PLUS
    }

    /** ইনভয়েসের (তারিখ, বকেয়া) জোড়া থেকে বয়স-ভিত্তিক যোগফল। বকেয়া ≤ ০ বাদ। */
    fun aging(invoices: List<Pair<Long, Long>>, now: Long): AgingTotals {
        var a = 0L; var b = 0L; var c = 0L; var d = 0L
        for ((date, due) in invoices) {
            if (due <= 0) continue
            when (bucketOf(ageDays(date, now))) {
                AgingBucket.D0_30 -> a += due
                AgingBucket.D31_60 -> b += due
                AgingBucket.D61_90 -> c += due
                AgingBucket.D90_PLUS -> d += due
            }
        }
        return AgingTotals(a, b, c, d)
    }

    // ---------------- বস্তা ↔ কেজি ----------------

    /** [bags] বস্তা × [bagSizeKg] কেজি = গ্রাম। বস্তার সাইজ ≤ ০ বা বস্তা ঋণাত্মক হলে IllegalArgumentException। */
    fun bagsToGrams(bags: Long, bagSizeKg: Double): Double {
        require(bags >= 0) { "বস্তা ঋণাত্মক হতে পারে না" }
        require(bagSizeKg > 0) { "বস্তার সাইজ শূন্যের বেশি হতে হবে" }
        return bags * bagSizeKg * 1000.0
    }

    /** গ্রামকে পূর্ণ বস্তা + খোলা ওজনে ভাঙে (ফ্লোটিং ত্রুটি সহনশীল)। */
    fun gramsToBags(grams: Double, bagSizeKg: Double): BagQty {
        require(grams >= 0) { "ওজন ঋণাত্মক হতে পারে না" }
        require(bagSizeKg > 0) { "বস্তার সাইজ শূন্যের বেশি হতে হবে" }
        val bagGrams = bagSizeKg * 1000.0
        var bags = Math.floor((grams + EPS) / bagGrams).toLong()
        var loose = grams - bags * bagGrams
        if (loose < EPS) loose = 0.0
        if (bagGrams - loose < EPS) { bags += 1; loose = 0.0 }
        return BagQty(bags, loose)
    }

    // ---------------- ডেলিভারি ----------------

    /** ডেলিভারির অবস্থা: কিছুই না = PENDING, সব লাইন পূর্ণ = DELIVERED, নইলে PARTIAL। [lines]: (পরিকল্পিত, ডেলিভারি) গ্রাম। */
    fun deliveryStatus(lines: List<Pair<Double, Double>>): String {
        val active = lines.filter { it.first > EPS }
        if (active.isEmpty()) return DELIVERY_PENDING
        if (active.all { it.second + EPS >= it.first }) return DELIVERY_DELIVERED
        return if (active.any { it.second > EPS }) DELIVERY_PARTIAL else DELIVERY_PENDING
    }

    const val DELIVERY_PENDING = "PENDING"
    const val DELIVERY_PARTIAL = "PARTIAL"
    const val DELIVERY_DELIVERED = "DELIVERED"

    /**
     * ফেরত পরিমাণ লটে ভাগ: [lots] = (লট, মূল বরাদ্দ) ডেলিভারির ক্রমে। প্রতিটি লটে তার বরাদ্দ পর্যন্ত, বাকি (যদি থাকে) শেষ লটে।
     * [lots] ফাঁকা হলে ফাঁকা তালিকা।
     */
    fun spreadAcrossLots(lots: List<Pair<String, Double>>, qtyGrams: Double): List<Pair<String, Double>> {
        if (lots.isEmpty() || qtyGrams <= EPS) return emptyList()
        var remaining = qtyGrams
        val out = ArrayList<Pair<String, Double>>()
        for ((i, l) in lots.withIndex()) {
            if (remaining <= EPS) break
            val take = if (i == lots.lastIndex) remaining else minOf(l.second, remaining)
            if (take > EPS) { out += l.first to take; remaining -= take }
        }
        return out
    }
}
