package com.feederp.accounts

/** ডাবল-এন্ট্রি ভাউচারের একটি লাইন (কন্ট্রাক্ট: সব মডিউল এটা ব্যবহার করে)। */
data class LedgerLine(
    val accountCode: String,
    val debitPaisa: Long = 0,
    val creditPaisa: Long = 0,
    val partyId: String? = null,
)
