package com.feederp.core

/**
 * হিসাবের খাতের স্থির কোড। সব মডিউল `LedgerRepository.post(...)` কলে এই কোড ব্যবহার করে।
 * (Chart of accounts এ এগুলো প্রথম চালুতে স্বয়ংক্রিয়ভাবে তৈরি হয়।)
 */
object AccountCodes {
    const val CASH = "1010"
    const val BANK = "1020"
    const val MOBILE_BANKING = "1030"
    const val RECEIVABLE = "1100"          // কাস্টমার/ডিলার বাকি (partyId সহ)
    const val INVENTORY_RAW = "1200"       // কাঁচামাল, প্রিমিক্স, ওষুধ
    const val INVENTORY_FG = "1210"        // ফিনিশড গুডস
    const val INVENTORY_SPARE = "1220"     // স্পেয়ার পার্টস
    const val FIXED_ASSETS = "1500"        // মেশিন ও যন্ত্রপাতি
    const val ACCUM_DEPRECIATION = "1590"
    const val PAYABLE = "2100"             // সাপ্লায়ার বাকি (partyId সহ)
    const val SALARY_PAYABLE = "2200"
    const val EMPLOYEE_ADVANCE = "1150"
    const val CAPITAL = "3000"
    const val SALES_REVENUE = "4000"
    const val SALES_RETURN = "4010"
    const val COGS = "5000"                // বিক্রীত পণ্যের খরচ
    const val PRODUCTION_LABOR = "5100"
    const val PRODUCTION_OVERHEAD = "5200"
    const val WASTAGE_LOSS = "5300"
    const val SALARY_EXPENSE = "6000"
    const val MAINTENANCE_EXPENSE = "6100"
    const val TRANSPORT_EXPENSE = "6200"
    const val DEPRECIATION_EXPENSE = "6300"
    const val OTHER_EXPENSE = "6900"
}
