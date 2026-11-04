package com.feederp.data.accounts

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.feederp.accounts.AccountType
import kotlinx.coroutines.flow.Flow

/** হিসাবের খাত। id = "acc-<code>" (নির্ধারিত), তাই একাধিক ডিভাইসে সিড করলেও ডুপ্লিকেট হয় না। */
@Entity(tableName = "accounts", indices = [Index("code", unique = true)])
data class AccountEntity(
    @PrimaryKey val id: String,
    val code: String,
    val nameBn: String,
    val nameEn: String,
    val type: AccountType,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** অ্যাপেন্ড-অনলি লেজার এন্ট্রি। এক ভাউচার = একাধিক লাইন (একই voucherId)। */
@Entity(
    tableName = "ledger_entries",
    indices = [Index("voucherId"), Index("accountCode"), Index("partyId"), Index("entryDate"), Index(value = ["refType", "refId"])],
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val voucherId: String,
    val voucherType: String,
    val lineNo: Int,
    val accountCode: String,
    val debitPaisa: Long,
    val creditPaisa: Long,
    val partyId: String?,
    val refType: String?,
    val refId: String?,
    val memo: String?,
    val entryDate: Long,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** খরচ। status: PENDING | APPROVED | REJECTED। method: CASH | BANK | MOBILE_BANKING */
@Entity(tableName = "expenses", indices = [Index("status"), Index("expenseDate"), Index("expenseNo", unique = true)])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val expenseNo: String,
    val accountCode: String,
    val amountPaisa: Long,
    val method: String,
    val description: String,
    val payeeName: String? = null,
    val expenseDate: Long,
    val status: String,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val rejectReason: String? = null,
    val voucherId: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** ট্রায়াল ব্যালেন্স/লাভ-ক্ষতির জন্য খাতভিত্তিক যোগফল। */
data class AccountTotalRow(val accountCode: String, val debitPaisa: Long, val creditPaisa: Long)

/** পার্টি লেজার/এজিংয়ের জন্য সারি। */
data class PartyEntryRow(
    val id: String,
    val voucherId: String,
    val voucherType: String,
    val accountCode: String,
    val partyId: String,
    val debitPaisa: Long,
    val creditPaisa: Long,
    val memo: String?,
    val refType: String?,
    val refId: String?,
    val entryDate: Long,
)

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL ORDER BY code")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL ORDER BY code")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<LedgerEntryEntity>)

    @Query("SELECT * FROM ledger_entries WHERE voucherId = :voucherId ORDER BY lineNo")
    suspend fun byVoucher(voucherId: String): List<LedgerEntryEntity>

    /** ইতিমধ্যে এই ভাউচারের রিভার্সাল হয়েছে কি (refType = REVERSAL, refId = মূল voucherId)। */
    @Query("SELECT COUNT(*) FROM ledger_entries WHERE refType = 'REVERSAL' AND refId = :voucherId")
    suspend fun reversalCount(voucherId: String): Int

    @Query("SELECT * FROM ledger_entries WHERE refType = :refType AND refId = :refId ORDER BY createdAt")
    suspend fun byRef(refType: String, refId: String): List<LedgerEntryEntity>

    @Query(
        "SELECT COALESCE(SUM(debitPaisa - creditPaisa), 0) FROM ledger_entries " +
            "WHERE partyId = :partyId AND accountCode IN ('1100', '2100')"
    )
    suspend fun partyBalance(partyId: String): Long

    @Query(
        "SELECT COALESCE(SUM(debitPaisa - creditPaisa), 0) FROM ledger_entries " +
            "WHERE partyId = :partyId AND accountCode IN ('1100', '2100')"
    )
    fun observePartyBalance(partyId: String): Flow<Long>

    @Query(
        "SELECT id AS id, voucherId AS voucherId, voucherType AS voucherType, accountCode AS accountCode, " +
            "partyId AS partyId, debitPaisa AS debitPaisa, creditPaisa AS creditPaisa, memo AS memo, " +
            "refType AS refType, refId AS refId, entryDate AS entryDate " +
            "FROM ledger_entries WHERE partyId = :partyId AND accountCode IN ('1100', '2100') " +
            "ORDER BY entryDate, createdAt, lineNo"
    )
    fun observePartyEntries(partyId: String): Flow<List<PartyEntryRow>>

    @Query(
        "SELECT id AS id, voucherId AS voucherId, voucherType AS voucherType, accountCode AS accountCode, " +
            "partyId AS partyId, debitPaisa AS debitPaisa, creditPaisa AS creditPaisa, memo AS memo, " +
            "refType AS refType, refId AS refId, entryDate AS entryDate " +
            "FROM ledger_entries WHERE partyId IS NOT NULL AND accountCode = '1100'"
    )
    suspend fun allReceivableEntries(): List<PartyEntryRow>

    /** সব পার্টির নিট ব্যালেন্স (কেবল বকেয়া থাকা পার্টি)। */
    @Query(
        "SELECT partyId AS accountCode, SUM(debitPaisa) AS debitPaisa, SUM(creditPaisa) AS creditPaisa " +
            "FROM ledger_entries WHERE partyId IS NOT NULL AND accountCode IN ('1100', '2100') " +
            "GROUP BY partyId HAVING SUM(debitPaisa) <> SUM(creditPaisa)"
    )
    fun observePartyTotals(): Flow<List<AccountTotalRow>>

    @Query(
        "SELECT accountCode AS accountCode, SUM(debitPaisa) AS debitPaisa, SUM(creditPaisa) AS creditPaisa " +
            "FROM ledger_entries WHERE entryDate >= :from AND entryDate < :to GROUP BY accountCode"
    )
    suspend fun totalsBetween(from: Long, to: Long): List<AccountTotalRow>

    @Query(
        "SELECT accountCode AS accountCode, SUM(debitPaisa) AS debitPaisa, SUM(creditPaisa) AS creditPaisa " +
            "FROM ledger_entries WHERE entryDate < :to GROUP BY accountCode"
    )
    suspend fun totalsUpTo(to: Long): List<AccountTotalRow>

    @Query("SELECT * FROM ledger_entries WHERE accountCode IN (:codes) AND entryDate >= :from AND entryDate < :to ORDER BY entryDate, createdAt, lineNo")
    suspend fun entriesForAccounts(codes: List<String>, from: Long, to: Long): List<LedgerEntryEntity>

    @Query("SELECT COALESCE(SUM(debitPaisa - creditPaisa), 0) FROM ledger_entries WHERE accountCode IN (:codes) AND entryDate < :before")
    suspend fun netBefore(codes: List<String>, before: Long): Long

    @Query("SELECT * FROM ledger_entries ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LedgerEntryEntity>>
}

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE deletedAt IS NULL ORDER BY expenseDate DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>
}
