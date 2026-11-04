package com.feederp.hr

import java.time.DayOfWeek
import java.time.LocalDate

/** ছুটির বিশুদ্ধ হিসাব (Android ছাড়া, টেস্টযোগ্য)। তারিখ = dayKey (yyyyMMdd)। */
object LeaveCalc {
    const val MAX_SPAN_DAYS = 366

    fun toDate(dayKey: Int): LocalDate = LocalDate.of(dayKey / 10000, dayKey / 100 % 100, dayKey % 100)
    fun toKey(d: LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    /** [fromDay]..[toDay] (দুই প্রান্তসহ) থেকে সাপ্তাহিক ও সরকারি ছুটি বাদ দিয়ে কার্যদিবসের তালিকা। */
    fun workingDays(fromDay: Int, toDay: Int, holidays: Set<Int>, weeklyOff: Set<DayOfWeek>): List<Int> {
        require(fromDay <= toDay) { "শুরুর তারিখ শেষের তারিখের পরে হতে পারে না" }
        val a = toDate(fromDay); val b = toDate(toDay)
        require(java.time.temporal.ChronoUnit.DAYS.between(a, b) < MAX_SPAN_DAYS) { "এক আবেদনে এক বছরের বেশি ছুটি হয় না" }
        val out = ArrayList<Int>()
        var d = a
        while (!d.isAfter(b)) {
            val k = toKey(d)
            if (d.dayOfWeek !in weeklyOff && k !in holidays) out += k
            d = d.plusDays(1)
        }
        return out
    }

    fun overlaps(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int): Boolean = aFrom <= bTo && bFrom <= aTo

    /** বাকি ছুটি = প্রাপ্য − (অনুমোদিত + অপেক্ষমান)। ঋণাত্মক হতে পারে (বেশি নেওয়া হয়ে গেলে)। */
    fun remaining(entitledDays: Double, committedDays: Double): Double = entitledDays - committedDays
}
