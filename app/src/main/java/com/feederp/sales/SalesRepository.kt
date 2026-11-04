package com.feederp.sales

import androidx.room.withTransaction
import com.feederp.accounts.ChartOfAccounts
import com.feederp.accounts.LedgerLine
import com.feederp.accounts.LedgerRepository
import com.feederp.core.AccountCodes
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.ItemDao
import com.feederp.data.PartyDao
import com.feederp.data.PartyType
import com.feederp.data.sales.CollectionDao
import com.feederp.data.sales.CollectionEntity
import com.feederp.data.sales.ItemPriceDao
import com.feederp.data.sales.ItemPriceEntity
import com.feederp.data.sales.PayMethods
import com.feederp.data.sales.SalesInvoiceDao
import com.feederp.data.sales.SalesInvoiceEntity
import com.feederp.data.sales.SalesInvoiceLineEntity
import com.feederp.data.sales.SalesReturnDao
import com.feederp.data.sales.SalesStatus
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class SaleLineInput(val itemId: String, val qtyGrams: Double, val unitPricePaisaPerKg: Long)

/**
 * বিক্রয়: ইনভয়েস, আদায়, আদায় রিভার্সাল, ইনভয়েস বাতিল, দামের তালিকা।
 * স্টক এখানে কমে না — ডেলিভারি নোটে (পরের ধাপ) FEFO ও QC-পাস লট থেকে SALE_OUT হয় এবং তখনই COGS পোস্ট হয়।
 * ফেরত (sales return) এখনও লেখা হয়নি।
 */
@Singleton
class SalesRepository @Inject constructor(
    private val db: AppDatabase,
    private val invoiceDao: SalesInvoiceDao,
    private val collectionDao: CollectionDao,
    private val returnDao: SalesReturnDao,
    private val priceDao: ItemPriceDao,
    private val partyDao: PartyDao,
    private val itemDao: ItemDao,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    fun observeInvoices(): Flow<List<SalesInvoiceEntity>> =
        if (session.can(Permission.SALES_OWN_ONLY) && !session.can(Permission.APPROVE)) invoiceDao.observeByCreator(session.userId)
        else invoiceDao.observeAll()

    fun observeInvoice(id: String): Flow<SalesInvoiceEntity?> = invoiceDao.observeById(id)
    suspend fun lines(invoiceId: String): List<SalesInvoiceLineEntity> = invoiceDao.linesFor(invoiceId)
    suspend fun collections(invoiceId: String) = collectionDao.forInvoice(invoiceId)
    fun observePrices(): Flow<List<ItemPriceEntity>> = priceDao.observeAll()
    suspend fun partyBalancePaisa(partyId: String): Long = ledger.partyBalancePaisa(partyId)

    /** পার্টির ধরন অনুযায়ী দাম: নির্দিষ্ট (DEALER/CUSTOMER) আগে, না থাকলে সবার জন্য (ALL)। তালিকায় না থাকলে null। */
    suspend fun listPricePaisaPerKg(itemId: String, partyType: PartyType): Long? {
        priceDao.getById(ItemPriceEntity.idFor(itemId, partyType.name))?.takeIf { it.deletedAt == null }?.let { return it.pricePaisaPerKg }
        return priceDao.getById(ItemPriceEntity.idFor(itemId, null))?.takeIf { it.deletedAt == null }?.pricePaisaPerKg
    }

    /** দামের তালিকা বদল: PRICE_EDIT লাগে। [partyType] null = সবার জন্য। */
    suspend fun setPrice(itemId: String, partyType: PartyType?, pricePaisaPerKg: Long) {
        session.require(Permission.PRICE_EDIT)
        require(partyType != PartyType.SUPPLIER) { "সাপ্লায়ারের জন্য বিক্রয়মূল্য হয় না" }
        require(pricePaisaPerKg > 0) { "দাম ০ এর বেশি হতে হবে" }
        val item = itemDao.getById(itemId)
        require(item != null && item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
        priceDao.upsert(
            ItemPriceEntity(
                id = ItemPriceEntity.idFor(itemId, partyType?.name), itemId = itemId, partyType = partyType?.name,
                pricePaisaPerKg = pricePaisaPerKg, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("SET_PRICE", "item_prices", itemId, "${item.name} ${partyType?.name ?: "ALL"} $pricePaisaPerKg পয়সা/কেজি")
    }

    /**
     * নতুন ইনভয়েস (+ ঐচ্ছিক সঙ্গে সঙ্গে আদায়)।
     * নিয়ম: তালিকার দামের বাইরে দাম দিতে PRICE_EDIT; ডিসকাউন্ট সীমার (৫%) বেশি হলে বা ক্রেডিট লিমিট ছাড়ালে APPROVE ও কারণ লাগে।
     * ফেরত: ইনভয়েস আইডি।
     */
    suspend fun createInvoice(
        partyId: String,
        lines: List<SaleLineInput>,
        discountPaisa: Long,
        paidNowPaisa: Long,
        method: String,
        invoiceDate: Long,
        warehouseId: String,
        note: String?,
        overrideReason: String? = null,
    ): String {
        session.require(Permission.SALES)
        val userId = session.userId
        val party = partyDao.getById(partyId)
        require(party != null && party.deletedAt == null && party.type != PartyType.SUPPLIER) { "ক্রেতা (কাস্টমার/ডিলার) বাছাই করুন" }
        require(lines.isNotEmpty()) { "কমপক্ষে একটি আইটেম যোগ করুন" }
        require(discountPaisa >= 0) { "ডিসকাউন্ট ঋণাত্মক হতে পারে না" }
        require(paidNowPaisa >= 0) { "আদায়ের অঙ্ক ঋণাত্মক হতে পারে না" }
        if (paidNowPaisa > 0) require(method in PayMethods.all) { "পেমেন্ট মাধ্যম সঠিক নয়" }
        require(warehouseId.isNotBlank()) { "গুদাম বাছাই করুন" }

        val canEditPrice = session.can(Permission.PRICE_EDIT)
        val lineTotals = lines.mapIndexed { i, l ->
            val n = i + 1
            require(l.qtyGrams.isFinite() && l.qtyGrams > 0) { "লাইন $n: পরিমাণ ০ এর বেশি হতে হবে" }
            require(l.unitPricePaisaPerKg > 0) { "লাইন $n: দাম ০ এর বেশি হতে হবে" }
            val item = itemDao.getById(l.itemId)
            require(item != null && item.deletedAt == null) { "লাইন $n: আইটেম পাওয়া যায়নি" }
            val listed = listPricePaisaPerKg(l.itemId, party.type)
            if (!canEditPrice) {
                require(listed != null) { "${item.name}: দামের তালিকায় দাম নেই; ম্যানেজার দাম নির্ধারণ করুন" }
                require(listed == l.unitPricePaisaPerKg) { "${item.name}: তালিকার দামের বাইরে বিক্রির অনুমতি নেই" }
            }
            SalesCalc.lineTotalPaisa(l.qtyGrams, l.unitPricePaisaPerKg)
        }
        val subtotal = lineTotals.sum()
        require(discountPaisa <= subtotal) { "ডিসকাউন্ট মোটের বেশি হতে পারে না" }
        val total = SalesCalc.invoiceTotalPaisa(subtotal, discountPaisa, 0)
        require(paidNowPaisa <= total) { "আদায় ইনভয়েসের মোটের বেশি হতে পারে না" }

        val reasons = mutableListOf<String>()
        if (!SalesCalc.isDiscountWithinSalesLimit(subtotal, discountPaisa)) reasons += "ডিসকাউন্ট সীমার বেশি"
        val balance = ledger.partyBalancePaisa(partyId)
        val credit = SalesCalc.checkCredit(party.creditLimitPaisa, balance, total - paidNowPaisa)
        if (!credit.allowed) reasons += "ক্রেডিট লিমিট ছাড়িয়ে যাবে (অতিরিক্ত ${credit.exceededByPaisa / 100} টাকা)"
        if (reasons.isNotEmpty()) {
            val why = reasons.joinToString("; ")
            check(session.can(Permission.APPROVE)) { "ম্যানেজারের অনুমোদন লাগবে: $why" }
            require(!overrideReason.isNullOrBlank()) { "অনুমোদনের কারণ লিখুন ($why)" }
        }

        val invoiceNo = docNumbers.next("INV")
        val id = newId()
        db.withTransaction {
            val now = stamper.now()
            val allocated = SalesCalc.allocateDiscount(lineTotals, discountPaisa)
            check(allocated.sum() == discountPaisa) { "ডিসকাউন্ট বণ্টনে গরমিল" }
            val voucherId = ledger.post(
                voucherType = "SALES_INVOICE", refType = "SALES_INVOICE", refId = id,
                memo = "বিক্রয় $invoiceNo: ${party.name}",
                lines = listOf(
                    LedgerLine(AccountCodes.RECEIVABLE, debitPaisa = total, partyId = partyId),
                    LedgerLine(AccountCodes.SALES_REVENUE, creditPaisa = total),
                ),
                entryDate = invoiceDate,
            )
            invoiceDao.upsert(
                SalesInvoiceEntity(
                    id = id, invoiceNo = invoiceNo, partyId = partyId, invoiceDate = invoiceDate,
                    subtotalPaisa = subtotal, discountPaisa = discountPaisa, totalPaisa = total, paidPaisa = 0,
                    status = SalesStatus.CONFIRMED, warehouseId = warehouseId, note = note?.trim()?.ifBlank { null },
                    createdBy = userId, updatedAt = now, deviceId = stamper.deviceId, voucherId = voucherId,
                )
            )
            invoiceDao.upsertLines(
                lines.mapIndexed { i, l ->
                    SalesInvoiceLineEntity(
                        id = newId(), invoiceId = id, itemId = l.itemId, qtyGrams = l.qtyGrams,
                        unitPricePaisaPerKg = l.unitPricePaisaPerKg, lineTotalPaisa = lineTotals[i],
                        updatedAt = now, deviceId = stamper.deviceId,
                    )
                }
            )
        }
        audit.log(
            "CREATE_INVOICE", "sales_invoices", id,
            "$invoiceNo ${party.name} $total পয়সা" + if (reasons.isNotEmpty()) " [অনুমোদিত: ${reasons.joinToString("; ")} — $overrideReason]" else "",
        )
        if (paidNowPaisa > 0) collect(partyId, id, paidNowPaisa, method, invoiceDate, "ইনভয়েসের সময় আদায়")
        return id
    }

    /** আদায়। [invoiceId] না দিলে অগ্রিম/হিসাবে জমা। ইনভয়েস দিলে বকেয়ার বেশি নেওয়া যায় না। ফেরত: আদায়ের আইডি। */
    suspend fun collect(
        partyId: String,
        invoiceId: String?,
        amountPaisa: Long,
        method: String,
        collectionDate: Long,
        note: String?,
    ): String {
        session.require(Permission.SALES)
        val userId = session.userId
        require(amountPaisa > 0) { "আদায়ের অঙ্ক ০ এর বেশি হতে হবে" }
        require(method in PayMethods.all) { "পেমেন্ট মাধ্যম সঠিক নয়" }
        val party = partyDao.getById(partyId)
        require(party != null && party.deletedAt == null && party.type != PartyType.SUPPLIER) { "ক্রেতা বাছাই করুন" }
        val id = newId()
        val collectionNo = docNumbers.next("COL")
        db.withTransaction {
            if (invoiceId != null) {
                val inv = invoiceDao.getById(invoiceId)
                require(inv != null && inv.partyId == partyId) { "ইনভয়েসটি এই ক্রেতার নয়" }
                require(inv.status != SalesStatus.CANCELLED) { "বাতিল ইনভয়েসে আদায় হয় না" }
                val returned = returnDao.returnedTotalForInvoice(invoiceId)
                val due = SalesCalc.outstandingPaisa(inv.totalPaisa, collectionDao.sumForInvoice(invoiceId), returned)
                require(amountPaisa <= due) { "আদায় বকেয়ার (${due / 100} টাকা) বেশি হতে পারে না; বাড়তি অংশ অগ্রিম হিসেবে আলাদা জমা করুন" }
            }
            val voucherId = ledger.post(
                voucherType = "COLLECTION", refType = "COLLECTION", refId = id,
                memo = "আদায় $collectionNo: ${party.name}",
                lines = listOf(
                    LedgerLine(ChartOfAccounts.cashAccountForMethod(method), debitPaisa = amountPaisa),
                    LedgerLine(AccountCodes.RECEIVABLE, creditPaisa = amountPaisa, partyId = partyId),
                ),
                entryDate = collectionDate,
            )
            collectionDao.insert(
                CollectionEntity(
                    id = id, collectionNo = collectionNo, partyId = partyId, invoiceId = invoiceId, amountPaisa = amountPaisa,
                    method = method, collectionDate = collectionDate, note = note?.trim()?.ifBlank { null },
                    voucherId = voucherId, createdBy = userId, createdAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
            if (invoiceId != null) refreshPaid(invoiceId)
        }
        audit.log("COLLECTION", "collections", id, "$collectionNo ${party.name} $amountPaisa পয়সা ($method)")
        return id
    }

    /** ভুল আদায় শুধরানো: ঋণাত্মক অঙ্কের সারি + লেজার রিভার্স। মূল সারি অপরিবর্তিত। একবারই করা যায়। */
    suspend fun reverseCollection(collectionId: String, reason: String): String {
        session.require(Permission.APPROVE)
        val userId = session.userId
        require(reason.isNotBlank()) { "রিভার্সালের কারণ লিখুন" }
        val reversalId = newId()
        db.withTransaction {
            val orig = collectionDao.getById(collectionId)
            require(orig != null) { "আদায় পাওয়া যায়নি" }
            require(orig.amountPaisa > 0 && orig.reversesId == null) { "রিভার্সাল সারি আবার রিভার্স করা যায় না" }
            check(collectionDao.reversalCount(collectionId) == 0) { "এই আদায় ইতিমধ্যে রিভার্স হয়েছে" }
            val voucherId = ledger.reverseVoucher(orig.voucherId, reason, Permission.APPROVE)
            collectionDao.insert(
                CollectionEntity(
                    id = reversalId, collectionNo = docNumbers.next("COL"), partyId = orig.partyId, invoiceId = orig.invoiceId,
                    amountPaisa = -orig.amountPaisa, method = orig.method, collectionDate = stamper.now(),
                    note = "রিভার্সাল: $reason", voucherId = voucherId, reversesId = collectionId,
                    createdBy = userId, createdAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
            orig.invoiceId?.let { refreshPaid(it) }
        }
        audit.log("REVERSE_COLLECTION", "collections", collectionId, reason)
        return reversalId
    }

    /** ইনভয়েস বাতিল: শুধু ম্যানেজার, শুধু যদি কিছু ডেলিভারি/আদায়/ফেরত না হয়ে থাকে। লেজার রিভার্স হয়। */
    suspend fun cancelInvoice(invoiceId: String, reason: String) {
        session.require(Permission.APPROVE)
        require(reason.isNotBlank()) { "বাতিলের কারণ লিখুন" }
        db.withTransaction {
            val inv = invoiceDao.getById(invoiceId)
            require(inv != null && inv.deletedAt == null) { "ইনভয়েস পাওয়া যায়নি" }
            require(inv.status == SalesStatus.CONFIRMED) { "ডেলিভারি শুরু হয়ে গেলে বা ইতিমধ্যে বাতিল হলে ইনভয়েস বাতিল করা যায় না" }
            require(collectionDao.sumForInvoice(invoiceId) == 0L) { "আদায় হয়ে থাকলে আগে আদায় রিভার্স করুন" }
            require(returnDao.forInvoice(invoiceId).isEmpty()) { "ফেরত আছে এমন ইনভয়েস বাতিল করা যায় না" }
            inv.voucherId?.let { ledger.reverseVoucher(it, "ইনভয়েস ${inv.invoiceNo} বাতিল: $reason", Permission.APPROVE) }
            invoiceDao.updateStatus(invoiceId, SalesStatus.CANCELLED, stamper.now(), stamper.deviceId)
        }
        audit.log("CANCEL_INVOICE", "sales_invoices", invoiceId, reason)
    }

    private suspend fun refreshPaid(invoiceId: String) {
        invoiceDao.updatePaid(invoiceId, collectionDao.sumForInvoice(invoiceId), stamper.now(), stamper.deviceId)
    }
}
