package com.feederp.data.purchase

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** PO স্ট্যাটাস: DRAFT (খসড়া) | ORDERED (অনুমোদিত) | PARTIAL | RECEIVED | CANCELLED */
@Entity(tableName = "purchase_orders", indices = [Index("supplierId"), Index("poNo", unique = true), Index("status")])
data class PurchaseOrderEntity(
    @PrimaryKey val id: String,
    val poNo: String,
    val supplierId: String,
    val orderDate: Long,
    val expectedDate: Long? = null,
    val status: String = "ORDERED",
    val totalPaisa: Long,
    val note: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "purchase_order_lines", indices = [Index("poId"), Index("itemId")])
data class PurchaseOrderLineEntity(
    @PrimaryKey val id: String,
    val poId: String,
    val itemId: String,
    val qtyGrams: Double,
    val ratePaisaPerKg: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "goods_receipts", indices = [Index("supplierId"), Index("poId"), Index("grnNo", unique = true)])
data class GoodsReceiptEntity(
    @PrimaryKey val id: String,
    val grnNo: String,
    val poId: String? = null,
    val supplierId: String,
    val warehouseId: String,
    val receivedAt: Long,
    val totalPaisa: Long,
    val note: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "goods_receipt_lines", indices = [Index("grnId"), Index("itemId"), Index("lotId")])
data class GoodsReceiptLineEntity(
    @PrimaryKey val id: String,
    val grnId: String,
    val itemId: String,
    val lotId: String,
    val batchNo: String,
    val qtyGrams: Double,
    val ratePaisaPerKg: Long,
    val mfgAt: Long? = null,
    val expiryAt: Long? = null,
    val moisturePct: Double? = null,
    /** PO এর বিপরীতে এই ডেলিভারিতে যত আসার কথা ছিল (বাকি পরিমাণ); সরাসরি রিসিভে null। ভ্যারিয়েন্স = qtyGrams - expectedGrams */
    val expectedGrams: Double? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** সাপ্লায়ারকে পেমেন্ট (অ্যাপেন্ড-অনলি)। method: CASH | BANK | MOBILE_BANKING */
@Entity(tableName = "supplier_payments", indices = [Index("supplierId")])
data class SupplierPaymentEntity(
    @PrimaryKey val id: String,
    val supplierId: String,
    val amountPaisa: Long,
    val method: String,
    val grnId: String? = null,
    val note: String? = null,
    val paidAt: Long,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

@Dao
interface PurchaseOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(po: PurchaseOrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<PurchaseOrderLineEntity>)

    @Query("SELECT * FROM purchase_orders WHERE deletedAt IS NULL ORDER BY orderDate DESC")
    fun observeAll(): Flow<List<PurchaseOrderEntity>>

    @Query("SELECT * FROM purchase_orders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PurchaseOrderEntity?

    @Query("SELECT * FROM purchase_orders WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PurchaseOrderEntity?>

    @Query("SELECT * FROM purchase_order_lines WHERE poId = :poId AND deletedAt IS NULL")
    fun observeLines(poId: String): Flow<List<PurchaseOrderLineEntity>>

    @Query("SELECT * FROM purchase_order_lines WHERE poId = :poId AND deletedAt IS NULL")
    suspend fun lines(poId: String): List<PurchaseOrderLineEntity>
}

@Dao
interface GoodsReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grn: GoodsReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<GoodsReceiptLineEntity>)

    @Query("SELECT * FROM goods_receipts WHERE deletedAt IS NULL ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<GoodsReceiptEntity>>

    @Query("SELECT * FROM goods_receipts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): GoodsReceiptEntity?

    @Query("SELECT * FROM goods_receipts WHERE supplierId = :supplierId AND deletedAt IS NULL ORDER BY receivedAt DESC")
    suspend fun forSupplier(supplierId: String): List<GoodsReceiptEntity>

    @Query("SELECT * FROM goods_receipts WHERE poId = :poId AND deletedAt IS NULL ORDER BY receivedAt DESC")
    fun observeForPo(poId: String): Flow<List<GoodsReceiptEntity>>

    @Query("SELECT * FROM goods_receipt_lines WHERE grnId = :grnId AND deletedAt IS NULL")
    suspend fun linesOf(grnId: String): List<GoodsReceiptLineEntity>

    @Query("SELECT * FROM goods_receipt_lines WHERE grnId = :grnId AND deletedAt IS NULL")
    fun observeLines(grnId: String): Flow<List<GoodsReceiptLineEntity>>

    /** একটি PO এর বিপরীতে এ পর্যন্ত রিসিভ হওয়া সব লাইন। */
    @Query(
        "SELECT l.* FROM goods_receipt_lines l INNER JOIN goods_receipts g ON g.id = l.grnId " +
            "WHERE g.poId = :poId AND g.deletedAt IS NULL AND l.deletedAt IS NULL"
    )
    suspend fun linesForPo(poId: String): List<GoodsReceiptLineEntity>

    /** [linesForPo] এর লাইভ রূপ (PO বিস্তারিত স্ক্রিনের জন্য)। */
    @Query(
        "SELECT l.* FROM goods_receipt_lines l INNER JOIN goods_receipts g ON g.id = l.grnId " +
            "WHERE g.poId = :poId AND g.deletedAt IS NULL AND l.deletedAt IS NULL"
    )
    fun observeLinesForPo(poId: String): Flow<List<GoodsReceiptLineEntity>>
}

@Dao
interface SupplierPaymentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: SupplierPaymentEntity)

    @Query("SELECT * FROM supplier_payments WHERE supplierId = :supplierId ORDER BY paidAt DESC")
    fun observeForSupplier(supplierId: String): Flow<List<SupplierPaymentEntity>>

    @Query("SELECT * FROM supplier_payments WHERE supplierId = :supplierId ORDER BY paidAt DESC")
    suspend fun forSupplier(supplierId: String): List<SupplierPaymentEntity>
}
