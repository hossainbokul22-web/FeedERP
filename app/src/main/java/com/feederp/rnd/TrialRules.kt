package com.feederp.rnd

import com.feederp.core.Permission
import com.feederp.data.rnd.TrialStatus

/** ট্রায়াল অনুমোদন প্রবাহের বিশুদ্ধ নিয়ম: RUNNING -> SUBMITTED -> QC_VERIFIED -> APPROVED | REJECTED। */
object TrialRules {

    private val allowed: Map<TrialStatus, Set<TrialStatus>> = mapOf(
        TrialStatus.RUNNING to setOf(TrialStatus.SUBMITTED, TrialStatus.REJECTED),
        TrialStatus.SUBMITTED to setOf(TrialStatus.QC_VERIFIED, TrialStatus.REJECTED),
        TrialStatus.QC_VERIFIED to setOf(TrialStatus.APPROVED, TrialStatus.REJECTED),
        TrialStatus.APPROVED to emptySet(),
        TrialStatus.REJECTED to emptySet(),
    )

    fun canTransition(from: TrialStatus, to: TrialStatus): Boolean = to in allowed[from].orEmpty()

    fun isTerminal(status: TrialStatus): Boolean = allowed[status].orEmpty().isEmpty()

    /** ফল/ডাটা এন্ট্রি শুধু চলমান ট্রায়ালে। */
    fun canEnterData(status: TrialStatus): Boolean = status == TrialStatus.RUNNING

    /** যে ধাপে যেতে যে অনুমতি লাগে। বাতিল (REJECTED) এর অনুমতি নির্ভর করে কোন ধাপ থেকে বাতিল হচ্ছে তার ওপর। */
    fun requiredPermission(from: TrialStatus, to: TrialStatus): Permission? = when (to) {
        TrialStatus.SUBMITTED -> Permission.RND
        TrialStatus.QC_VERIFIED -> Permission.QC
        TrialStatus.APPROVED -> Permission.APPROVE
        TrialStatus.REJECTED -> when (from) {
            TrialStatus.RUNNING -> Permission.RND
            TrialStatus.SUBMITTED -> Permission.QC
            TrialStatus.QC_VERIFIED -> Permission.APPROVE
            else -> null
        }
        TrialStatus.RUNNING -> null
    }

    /** জমা দেওয়ার আগে বাধা; খালি = জমা দেওয়া যাবে। */
    fun submitBlockers(status: TrialStatus, recordCount: Int, formulaErrors: List<String>): List<String> {
        val out = mutableListOf<String>()
        if (status != TrialStatus.RUNNING) out += "শুধু চলমান ট্রায়াল জমা দেওয়া যায়"
        if (recordCount <= 0) out += "অন্তত একটি ফিড ট্রায়াল ফল এন্ট্রি দিন"
        out += formulaErrors
        return out
    }

    /** বাতিলের সময় কারণ বাধ্যতামূলক। */
    fun rejectionBlockers(note: String?): List<String> =
        if (note.isNullOrBlank()) listOf("বাতিলের কারণ লিখুন") else emptyList()

    /** শুধু APPROVED ট্রায়ালের প্রস্তাবিত ফর্মুলা প্রোডাকশনে যেতে পারে। */
    fun formulaMayEnterProduction(status: TrialStatus): Boolean = status == TrialStatus.APPROVED

    fun labelBn(status: TrialStatus): String = when (status) {
        TrialStatus.RUNNING -> "চলমান"
        TrialStatus.SUBMITTED -> "জমা দেওয়া (QC অপেক্ষমান)"
        TrialStatus.QC_VERIFIED -> "QC যাচাইকৃত (অনুমোদন অপেক্ষমান)"
        TrialStatus.APPROVED -> "অনুমোদিত"
        TrialStatus.REJECTED -> "বাতিল"
    }
}
