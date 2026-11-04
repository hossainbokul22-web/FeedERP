package com.feederp.formulation

import com.feederp.data.ItemType

/** ফর্মুলার হেডারের যে তথ্য যাচাইয়ে লাগে (FormulaEntity থেকে, Room নির্ভরতা ছাড়া)। */
data class FormulaHeaderInfo(
    val isMedicated: Boolean = false,
    val targetSpecies: String? = null,
    val targets: NutrientTargets = NutrientTargets(),
)

/**
 * সম্পূর্ণ যাচাই: [FormulaValidator] (মোট ১০০%, ডোজ সীমা, নিষিদ্ধ/সীমিত, লক্ষ্যমাত্রা) + ওষুধযুক্ত ফর্মুলার নিয়ম।
 * বিশুদ্ধ লজিক; অনুমোদনের আগে ও এডিটরে লাইভ সতর্কতার জন্য একই ফাংশন ব্যবহৃত হয়।
 */
object FormulaChecker {
    fun check(
        header: FormulaHeaderInfo,
        lines: List<FormulaLineInput>,
        items: Map<String, DoseItemInfo>,
        restricted: Map<String, String>,
        nutrients: NutrientResult,
    ): ValidationReport {
        val base = FormulaValidator.validate(lines, items, restricted, nutrients, header.targets)
        val blockers = base.blockers.toMutableList()
        val warnings = base.warnings.toMutableList()

        val medicineLines = lines.filter { items[it.itemId]?.type == ItemType.MEDICINE }
        if (header.isMedicated) {
            if (header.targetSpecies.isNullOrBlank()) blockers += "ওষুধযুক্ত ফর্মুলায় লক্ষ্য প্রজাতি (targetSpecies) দিতে হবে"
            if (medicineLines.isEmpty()) warnings += "ওষুধযুক্ত চিহ্নিত, কিন্তু ফর্মুলায় কোনো ওষুধ (MEDICINE) উপাদান নেই"
            for (l in medicineLines) {
                val w = items[l.itemId]?.withdrawalDays
                if (w != null && w > 0) warnings += "${items[l.itemId]?.name}: উইথড্রয়াল পিরিয়ড $w দিন (লেবেল/ইনভয়েসে দিতে হবে)"
            }
        } else {
            for (l in medicineLines) {
                blockers += "${items[l.itemId]?.name}: ওষুধ আছে, অথচ ফর্মুলা \"ওষুধযুক্ত\" চিহ্নিত নয়"
            }
        }
        return ValidationReport(blockers, warnings)
    }
}
