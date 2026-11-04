package com.feederp.inventory

/** একটি লটের বর্তমান স্টক (কন্ট্রাক্ট: অন্য মডিউল এটা ব্যবহার করে)। */
data class LotStock(val lotId: String, val batchNo: String, val expiryAt: Long?, val qtyGrams: Double)
