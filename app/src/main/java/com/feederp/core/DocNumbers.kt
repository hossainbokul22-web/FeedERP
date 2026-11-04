package com.feederp.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import javax.inject.Inject
import javax.inject.Singleton

/** ডিভাইস-লোকাল কাউন্টার (সিঙ্ক হয় না)। কী = "<deviceCode>-<series>"। */
@Entity(tableName = "number_counters")
data class NumberCounterEntity(@PrimaryKey val key: String, val last: Long)

@Dao
interface NumberCounterDao {
    @Query("SELECT last FROM number_counters WHERE key = :key")
    suspend fun get(key: String): Long?

    @Query("INSERT OR REPLACE INTO number_counters(key, last) VALUES(:key, :last)")
    suspend fun put(key: String, last: Long)

    @Transaction
    suspend fun nextValue(key: String): Long {
        val n = (get(key) ?: 0L) + 1
        put(key, n)
        return n
    }
}

/**
 * ডকুমেন্ট নম্বর: ডিভাইস কোড সহ, তাই দুই ফোনে কখনও একই নম্বর হয় না।
 * উদাহরণ: next("INV") -> "S1-INV-0001"
 */
@Singleton
class DocNumbers @Inject constructor(
    private val dao: NumberCounterDao,
    private val stamper: Stamper,
) {
    suspend fun next(series: String): String {
        val code = stamper.deviceCode
        val n = dao.nextValue("$code-$series")
        return "$code-$series-" + n.toString().padStart(4, '0')
    }
}
