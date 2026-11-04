package com.feederp.formulation

import com.feederp.data.ItemType
import java.util.Locale

enum class Severity { WARN, BLOCK }

data class DoseIssue(val itemId: String, val severity: Severity, val message: String)

/** ডোজ যাচাইয়ের জন্য আইটেমের তথ্য (ItemEntity থেকে নেওয়া)। */
data class DoseItemInfo(
    val itemId: String,
    val name: String,
    val type: ItemType,
    val minDoseGPerTon: Double? = null,
    val maxDoseGPerTon: Double? = null,
    val concentrationPct: Double? = null,
    val isControlled: Boolean = false,
    val withdrawalDays: Int? = null,
)

/** যাচাইয়ের ফল: [blockers] থাকলে অনুমোদন/প্রোডাকশন চলবে না; [warnings] শুধু সতর্কতা। */
data class ValidationReport(val blockers: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = blockers.isEmpty()
}

object DoseValidator {
    const val BANNED = "BANNED"
    const val CONTROLLED = "CONTROLLED"

    /**
     * লাইনের পরিমাণ গ্রাম/টন। সর্বোচ্চ ডোজ পেরোলে BLOCK, সর্বনিম্নের নিচে হলে WARN।
     * [restricted]: itemId -> "BANNED" | "CONTROLLED" (ব্যবহারকারী-কনফিগার করা তালিকা)।
     */
    fun validate(
        lines: List<FormulaLineInput>,
        items: Map<String, DoseItemInfo>,
        restricted: Map<String, String> = emptyMap(),
    ): List<DoseIssue> {
        val out = mutableListOf<DoseIssue>()
        fun g(v: Double) = String.format(Locale.US, "%.1f", v)
        for (l in lines) {
            val info = items[l.itemId]
            if (info == null) {
                out += DoseIssue(l.itemId, Severity.BLOCK, "আইটেম পাওয়া যায়নি")
                continue
            }
            val n = info.name
            if (!l.gramsPerTon.isFinite() || l.gramsPerTon <= 0.0) {
                out += DoseIssue(l.itemId, Severity.BLOCK, "$n: পরিমাণ ০ এর বেশি হতে হবে")
                continue
            }
            when (restricted[l.itemId]) {
                BANNED -> out += DoseIssue(l.itemId, Severity.BLOCK, "$n: নিষিদ্ধ উপাদানের তালিকায় আছে")
                CONTROLLED -> out += DoseIssue(l.itemId, Severity.WARN, "$n: নিয়ন্ত্রিত/সীমিত উপাদানের তালিকায় আছে")
            }
            info.maxDoseGPerTon?.let {
                if (l.gramsPerTon > it + 1e-9) {
                    out += DoseIssue(l.itemId, Severity.BLOCK, "$n: ডোজ ${g(l.gramsPerTon)} গ্রাম/টন, সর্বোচ্চ সীমা ${g(it)} গ্রাম/টন পেরিয়েছে")
                }
            }
            info.minDoseGPerTon?.let {
                if (l.gramsPerTon < it - 1e-9) {
                    out += DoseIssue(l.itemId, Severity.WARN, "$n: ডোজ ${g(l.gramsPerTon)} গ্রাম/টন, সর্বনিম্ন কার্যকর ডোজ ${g(it)} গ্রাম/টনের কম")
                }
            }
            if (info.type == ItemType.MEDICINE && info.maxDoseGPerTon == null) {
                out += DoseIssue(l.itemId, Severity.WARN, "$n: ওষুধের সর্বোচ্চ ডোজ সীমা সেট করা নেই")
            }
            if (info.isControlled) {
                out += DoseIssue(l.itemId, Severity.WARN, "$n: নিয়ন্ত্রিত আইটেম (ইস্যুতে Manager অনুমোদন লাগবে)")
            }
        }
        return out
    }
}

object FormulaValidator {
    /** মোট গ্রামের সহনশীলতা: এক টনের ০.১% (১০০০ গ্রাম)। */
    const val TOTAL_TOLERANCE_GRAMS = 1000.0

    fun validate(
        lines: List<FormulaLineInput>,
        items: Map<String, DoseItemInfo>,
        restricted: Map<String, String>,
        nutrients: NutrientResult,
        targets: NutrientTargets,
    ): ValidationReport {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (lines.isEmpty()) blockers += "ফর্মুলায় কোনো উপাদান নেই"
        val dup = lines.groupBy { it.itemId }.filter { it.value.size > 1 }.keys
        for (d in dup) blockers += "একই উপাদান একাধিকবার আছে: ${items[d]?.name ?: d}"
        if (lines.isNotEmpty() && NutrientCalculator.totalDeviationGrams(lines) > TOTAL_TOLERANCE_GRAMS) {
            blockers += "মোট পরিমাণ " + String.format(Locale.US, "%,.0f", lines.sumOf { it.gramsPerTon }) +
                " গ্রাম; এক টন (১০,০০,০০০ গ্রাম) হতে হবে"
        }
        for (i in DoseValidator.validate(lines, items, restricted)) {
            if (i.severity == Severity.BLOCK) blockers += i.message else warnings += i.message
        }
        for (m in NutrientCalculator.checkTargets(nutrients, targets)) warnings += "লক্ষ্যমাত্রা পূরণ হয়নি: $m"
        for (n in nutrients.itemsMissingNutrientData) warnings += "$n: নিউট্রিয়েন্ট ডাটা নেই (হিসাবে ০ ধরা হয়েছে)"
        return ValidationReport(blockers, warnings)
    }
}
