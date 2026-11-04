package com.feederp.core

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** হোম স্ক্রিনের মেনু আইটেম; [permission] না থাকলে ইউজার এটা দেখবে না। */
data class MenuItem(
    val route: String,
    val titleBn: String,
    val titleEn: String,
    val permission: Permission,
    val group: String,
)

/**
 * প্রতিটি মডিউল এই ইন্টারফেস ইমপ্লিমেন্ট করে একটি `object` দেয় (যেমন `PurchaseModule`)।
 * AppNav সব মডিউলের [menu] হোম স্ক্রিনে দেখায় এবং [register] দিয়ে রুট যোগ করে।
 */
interface ModuleEntry {
    val menu: List<MenuItem>
    fun register(builder: NavGraphBuilder, nav: NavHostController)
}
