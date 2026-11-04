package com.feederp.data.sales

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** ইনভয়েসের স্ট্যাটাস (ডেলিভারির অবস্থা)। ডিস্ট্রিবিউশন মডিউল ডেলিভারি অনুযায়ী [SalesInvoiceDao.updateStatus] দিয়ে বদলায়। */
object SalesStatus {
    const val CONFIRMED = "CONFIRMED"   // নিশ্চিত, ডেলিভারি বাকি
    const val PARTIAL = "PARTIAL"       // আংশিক ডেলিভারি
    const val DELIVERED = "DELIVERED"   // সম্পূর্ণ ডেলিভারি
    const val CANCELLED = "CANCELLED"   // বাতিল (লেজার রিভার্স করা)

    fun labelBn(status: String): String = when (status) {
        CONFIRMED -> "ডেলিভারি বাকি"
        PARTIAL -> "আংশিক ডেলিভারি"
        DELIVERED -> "ডেলিভারি সম্পন্ন"
        CANCELLED -> "বাতিল"
        else -> status
    }
}

/** পেমেন্ট পদ্ধতি (ExpenseRepository এর মতোই)। */
object PayMethods {
    val all = listOf("CASH", "BANK", "MOBILE_BANKING")
    fun labelBn(method: String): String = when (method) {
        "CASH" -> "নগদ"
        "BANK" -> "ব্যাংক"
        "MOBILE_BANKING" -> "মোবাইল ব্যাংকিং"
        else -> method
    }
}

/**
 * বিক্রয় ইনভয়েস (পরিবর্তনযোগ্য নিয়ম)। [paidPaisa] = এই ইনভয়েসে বরাদ্দ কালেকশনের নিট যোগফল।
 * স্টক এখানে কমে না; ডেলিভারি লোডিংয়ে ডিস্ট্রিবিউশন SALE_OUT করে।
 */
@Entity(
    tableName = "sales_invoices",
    indices = [Index("invoiceNo", unique = true), Index("partyId"), Index("invoiceDate"), Index("status"), Index("createdBy")],
)
data class SalesInvoiceEntity(
    @PrimaryKey val id: String,
    val invoiceNo: String,
    val partyId: String,
    val invoiceDate: Long,
    val subtotalPaisa: Long,
    val discountPaisa: Long,
    val totalPaisa: Long,
    val paidPaisa: Long,
    val status: String,
    val warehouseId: String,
    val note: String?,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
    val voucherId: String? = null,     // রাজস্ব ভাউচার (বাতিলে রিভার্স করতে)
)

@Entity(tableName = "sales_invoice_lines", indices = [Index("invoiceId"), Index("itemId")])
data class SalesInvoiceLineEntity(
    @PrimaryKey val id: String,
    val invoiceId: String,
    val itemId: String,
    val qtyGrams: Double,
    val unitPricePaisaPerKg: Long,
    val lineTotalPaisa: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/**
 * আদায় (অ্যাপেন্ড-অনলি)। invoiceId না থাকলে অগ্রিম/হিসাবে জমা। ভুল হলে ঋণাত্মক অঙ্কের রিভার্সাল সারি
 * ([reversesId] = মূল আদায়) যোগ হয়।
 */
@Entity(
    tableName = "collections",
    indices = [Index("collectionNo", unique = true), Index("partyId"), Index("invoiceId"), Index("collectionDate"), Index("createdBy"), Index("reversesId")],
)
data class CollectionEntity(
    @PrimaryKey val id: String,
    val collectionNo: String,
    val partyId: String,
    val invoiceId: String?,
    val amountPaisa: Long,
    val method: String,
    val collectionDate: Long,
    val note: String?,
    val voucherId: String,
    val reversesId: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** বিক্রয় ফেরত / ক্রেডিট নোট (অ্যাপেন্ড-অনলি হেডার)। [totalPaisa] = ডিসকাউন্ট-সমন্বিত ক্রেডিট। */
@Entity(
    tableName = "sales_returns",
    indices = [Index("returnNo", unique = true), Index("invoiceId"), Index("partyId"), Index("returnDate"), Index("createdBy")],
)
data class SalesReturnEntity(
    @PrimaryKey val id: String,
    val returnNo: String,
    val invoiceId: String,
    val partyId: String,
    val returnDate: Long,
    val totalPaisa: Long,
    val reason: String,
    val voucherId: String,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

@Entity(tableName = "sales_return_lines", indices = [Index("returnId"), Index("invoiceLineId"), Index("itemId")])
data class SalesReturnLineEntity(
    @PrimaryKey val id: String,
    val returnId: String,
    val invoiceLineId: String,
    val itemId: String,
    val qtyGrams: Double,
    val unitPricePaisaPerKg: Long,
    val lineTotalPaisa: Long,          // ডিসকাউন্ট-সমন্বিত ক্রেডিট
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/**
 * পণ্যের দামের তালিকা (কেজি প্রতি পয়সা)। partyType = null হলে সবার জন্য; নির্দিষ্ট হলে (DEALER/CUSTOMER) সেটাই অগ্রাধিকার পায়।
 * id নির্ধারিত ("price-<itemId>-<ALL|DEALER|CUSTOMER>") তাই সম্পাদনা ওভাররাইট করে, ডুপ্লিকেট হয় না।
 */
@Entity(tableName = "item_prices", indices = [Index("itemId")])
data class ItemPriceEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val partyType: String?,
    val pricePaisaPerKg: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
) {
    companion object {
        fun idFor(itemId: String, partyType: String?): String = "price-$itemId-${partyType ?: "ALL"}"
    }
}

@Dao
interface SalesInvoiceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(invoice: SalesInvoiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<SalesInvoiceLineEntity>)

    @Query("SELECT * FROM sales_invoices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SalesInvoiceEntity?

    @Query("SELECT * FROM sales_invoices WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<SalesInvoiceEntity?>

    @Query("SELECT * FROM sales_invoice_lines WHERE invoiceId = :invoiceId AND deletedAt IS NULL ORDER BY rowid")
    suspend fun linesFor(invoiceId: String): List<SalesInvoiceLineEntity>

    @Query("SELECT * FROM sales_invoice_lines WHERE id = :id LIMIT 1")
    suspend fun getLine(id: String): SalesInvoiceLineEntity?

    /** ডেলিভারি বাকি (নিশ্চিত বা আংশিক) ইনভয়েস, পুরনো আগে। */
    @Query(
        "SELECT * FROM sales_invoices WHERE status IN ('CONFIRMED', 'PARTIAL') AND deletedAt IS NULL " +
            "ORDER BY invoiceDate, invoiceNo"
    )
    fun observePendingDelivery(): Flow<List<SalesInvoiceEntity>>

    @Query("SELECT * FROM sales_invoices WHERE deletedAt IS NULL ORDER BY invoiceDate DESC, invoiceNo DESC")
    fun observeAll(): Flow<List<SalesInvoiceEntity>>

    @Query("SELECT * FROM sales_invoices WHERE deletedAt IS NULL AND createdBy = :createdBy ORDER BY invoiceDate DESC, invoiceNo DESC")
    fun observeByCreator(createdBy: String): Flow<List<SalesInvoiceEntity>>

    /** পার্টির বকেয়া থাকা (বাতিল নয়) ইনভয়েস, পুরনো আগে। */
    @Query(
        "SELECT * FROM sales_invoices WHERE partyId = :partyId AND status <> 'CANCELLED' AND deletedAt IS NULL " +
            "AND paidPaisa < totalPaisa ORDER BY invoiceDate, invoiceNo"
    )
    suspend fun openForParty(partyId: String): List<SalesInvoiceEntity>

    @Query("UPDATE sales_invoices SET status = :status, updatedAt = :updatedAt, deviceId = :deviceId WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long, deviceId: String)

    @Query("UPDATE sales_invoices SET paidPaisa = :paidPaisa, updatedAt = :updatedAt, deviceId = :deviceId WHERE id = :id")
    suspend fun updatePaid(id: String, paidPaisa: Long, updatedAt: Long, deviceId: String)
}

@Dao
interface CollectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity)

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CollectionEntity?

    @Query("SELECT * FROM collections ORDER BY collectionDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE createdBy = :createdBy ORDER BY collectionDate DESC, createdAt DESC")
    fun observeByCreator(createdBy: String): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE invoiceId = :invoiceId ORDER BY collectionDate, createdAt")
    suspend fun forInvoice(invoiceId: String): List<CollectionEntity>

    @Query("SELECT COALESCE(SUM(amountPaisa), 0) FROM collections WHERE invoiceId = :invoiceId")
    suspend fun sumForInvoice(invoiceId: String): Long

    /** এই আদায় ইতিমধ্যে রিভার্স হয়েছে কি। */
    @Query("SELECT COUNT(*) FROM collections WHERE reversesId = :collectionId")
    suspend fun reversalCount(collectionId: String): Int
}

@Dao
interface SalesReturnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(salesReturn: SalesReturnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<SalesReturnLineEntity>)

    @Query("SELECT * FROM sales_returns ORDER BY returnDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<SalesReturnEntity>>

    @Query("SELECT * FROM sales_returns WHERE createdBy = :createdBy ORDER BY returnDate DESC, createdAt DESC")
    fun observeByCreator(createdBy: String): Flow<List<SalesReturnEntity>>

    @Query("SELECT * FROM sales_returns WHERE invoiceId = :invoiceId ORDER BY returnDate, createdAt")
    suspend fun forInvoice(invoiceId: String): List<SalesReturnEntity>

    @Query("SELECT * FROM sales_return_lines WHERE returnId = :returnId AND deletedAt IS NULL ORDER BY rowid")
    suspend fun linesFor(returnId: String): List<SalesReturnLineEntity>

    @Query("SELECT COALESCE(SUM(qtyGrams), 0.0) FROM sales_return_lines WHERE invoiceLineId = :invoiceLineId AND deletedAt IS NULL")
    suspend fun returnedGramsForLine(invoiceLineId: String): Double

    @Query("SELECT COALESCE(SUM(totalPaisa), 0) FROM sales_returns WHERE invoiceId = :invoiceId")
    suspend fun returnedTotalForInvoice(invoiceId: String): Long
}

@Dao
interface ItemPriceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(price: ItemPriceEntity)

    @Query("SELECT * FROM item_prices WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<ItemPriceEntity>>

    @Query("SELECT * FROM item_prices WHERE deletedAt IS NULL")
    suspend fun all(): List<ItemPriceEntity>

    @Query("SELECT * FROM item_prices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ItemPriceEntity?
}
