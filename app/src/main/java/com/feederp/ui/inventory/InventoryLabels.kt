package com.feederp.ui.inventory

import com.feederp.data.MovementType
import com.feederp.inventory.ExpiryState
import com.feederp.inventory.StockCalc
import com.feederp.ui.common.formatDate

object InventoryLabels {
    fun movementType(t: MovementType): String = when (t) {
        MovementType.PURCHASE_IN -> "ক্রয় (ইন)"
        MovementType.PRODUCTION_OUT -> "উৎপাদনে ব্যবহার"
        MovementType.PRODUCTION_IN -> "উৎপাদন (ইন)"
        MovementType.SALE_OUT -> "বিক্রয় (আউট)"
        MovementType.RETURN_IN -> "ফেরত (ইন)"
        MovementType.TRANSFER -> "ট্রান্সফার"
        MovementType.ADJUSTMENT -> "অ্যাডজাস্টমেন্ট"
    }

    fun expiryText(expiryAt: Long?, now: Long): String {
        if (expiryAt == null) return "মেয়াদ নেই"
        return when (StockCalc.expiryState(expiryAt, now)) {
            ExpiryState.EXPIRED -> "মেয়াদোত্তীর্ণ (${formatDate(expiryAt)})"
            ExpiryState.NEAR -> "${formatDate(expiryAt)} (আর ${StockCalc.daysToExpiry(expiryAt, now)} দিন)"
            else -> formatDate(expiryAt)
        }
    }
}

/** ড্রপডাউনে লট বাছাইয়ের জন্য। */
data class LotChoice(val lotId: String, val label: String, val qtyGrams: Double)
