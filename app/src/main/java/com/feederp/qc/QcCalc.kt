package com.feederp.qc

/** সাধারণ QC প্যারামিটার (বাছাইয়ের সুবিধার জন্য)। সীমা ইচ্ছে করেই বসানো নেই: সেটা আইন/স্পেক অনুযায়ী ব্যবহারকারী দেবেন। */
object QcParameters {
    /** তালিকার "অন্যান্য" বিকল্প; এটা নিজে প্যারামিটার নাম নয়, ব্যবহারকারীকে নিজের নাম লিখতে হবে। */
    const val OTHER = "__OTHER__"

    data class Def(val key: String, val nameBn: String, val unit: String)

    val standard: List<Def> = listOf(
        Def("MOISTURE", "আর্দ্রতা", "%"),
        Def("AFLATOXIN", "অ্যাফ্লাটক্সিন", "ppb"),
        Def("PROTEIN", "প্রোটিন", "%"),
        Def("FAT", "ফ্যাট", "%"),
        Def("FIBRE", "ফাইবার", "%"),
        Def("ASH", "ছাই (অ্যাশ)", "%"),
        Def("SAND_SILICA", "বালি/সিলিকা", "%"),
        Def("SALT", "লবণ", "%"),
        Def("CALCIUM", "ক্যালসিয়াম", "%"),
        Def("PHOSPHORUS", "ফসফরাস", "%"),
    )

    fun nameBn(key: String): String = standard.firstOrNull { it.key == key }?.nameBn ?: key
    fun unitOf(key: String): String? = standard.firstOrNull { it.key == key }?.unit
}

/** একটি টেস্ট ফলের সংক্ষেপ (সর্বশেষ-ফল বাছাইয়ের জন্য)। */
data class TestPoint(val parameter: String, val passed: Boolean, val createdAt: Long)

enum class QcRecommendation { NO_TESTS, ALL_PASS, SOME_FAILED }

/** QC হিসাবের বিশুদ্ধ লজিক (Android/Room নির্ভরতা নেই)। */
object QcCalc {
    const val PASSED = "PASSED"
    const val HOLD = "HOLD"
    const val REJECTED = "REJECTED"
    private val DECISIONS = setOf(PASSED, HOLD, REJECTED)

    /** টেস্ট এন্ট্রির ইনপুট ঠিক আছে কি না; সমস্যা থাকলে বাংলা বার্তা। */
    fun inputError(value: Double?, specMin: Double?, specMax: Double?): String? {
        if (value == null) return "টেস্টের মান দিন"
        if (value.isNaN() || value.isInfinite()) return "টেস্টের মান সঠিক সংখ্যা নয়"
        if (value < 0.0) return "টেস্টের মান ঋণাত্মক হতে পারে না"
        if (specMin == null && specMax == null) return "কমপক্ষে একটি সীমা (সর্বনিম্ন বা সর্বোচ্চ) দিন"
        if (specMin != null && (specMin.isNaN() || specMin.isInfinite())) return "সর্বনিম্ন সীমা সঠিক সংখ্যা নয়"
        if (specMax != null && (specMax.isNaN() || specMax.isInfinite())) return "সর্বোচ্চ সীমা সঠিক সংখ্যা নয়"
        if (specMin != null && specMax != null && specMin > specMax) return "সর্বনিম্ন সীমা সর্বোচ্চের চেয়ে বড় হতে পারে না"
        return null
    }

    /** মান সীমার মধ্যে (সীমা সহ) হলে পাস। NaN/অসীম মান কখনও পাস নয়। */
    fun passed(value: Double, specMin: Double?, specMax: Double?): Boolean {
        if (value.isNaN() || value.isInfinite()) return false
        if (specMin != null && value < specMin) return false
        if (specMax != null && value > specMax) return false
        return true
    }

    /** প্রতিটি প্যারামিটারের সর্বশেষ (createdAt সবচেয়ে বড়) ফল; সমান সময়ে প্রথমটি থাকে। */
    fun latestPerParameter(points: List<TestPoint>): List<TestPoint> =
        points.groupBy { it.parameter }
            .map { (_, ps) -> ps.reduce { best, p -> if (p.createdAt > best.createdAt) p else best } }
            .sortedBy { it.parameter }

    fun failedParameters(points: List<TestPoint>): List<String> =
        latestPerParameter(points).filter { !it.passed }.map { it.parameter }

    fun recommendation(points: List<TestPoint>): QcRecommendation = when {
        points.isEmpty() -> QcRecommendation.NO_TESTS
        failedParameters(points).isEmpty() -> QcRecommendation.ALL_PASS
        else -> QcRecommendation.SOME_FAILED
    }

    /**
     * সিদ্ধান্তের নিয়ম; ত্রুটি থাকলে বাংলা বার্তা, না থাকলে null।
     *  - [status] PASSED/HOLD/REJECTED ছাড়া হলে ত্রুটি।
     *  - HOLD ও REJECTED এ কারণ (note) বাধ্যতামূলক।
     *  - PASSED: সব প্যারামিটার পাস হলে সরাসরি; ফেল বা টেস্টহীন হলে কেবল ওভাররাইট-অনুমতিধারী ([canOverride]) কারণসহ।
     */
    fun decisionError(status: String, rec: QcRecommendation, canOverride: Boolean, note: String?): String? {
        if (status !in DECISIONS) return "সিদ্ধান্ত সঠিক নয়: $status"
        val hasNote = !note.isNullOrBlank()
        return when (status) {
            HOLD, REJECTED -> if (hasNote) null else "হোল্ড বা বাতিলের কারণ লিখুন"
            else -> when {
                rec == QcRecommendation.ALL_PASS -> null
                !canOverride -> when (rec) {
                    QcRecommendation.NO_TESTS -> "কোনো টেস্ট নেই; আগে টেস্ট এন্ট্রি দিন (ম্যানেজার ওভাররাইট করতে পারেন)"
                    else -> "ব্যর্থ টেস্ট আছে; পাস করতে ম্যানেজারের অনুমতি লাগে"
                }
                !hasNote -> "ওভাররাইডের কারণ লিখুন"
                else -> null
            }
        }
    }
}
