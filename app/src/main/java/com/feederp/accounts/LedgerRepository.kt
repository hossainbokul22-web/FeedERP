package com.feederp.accounts

import androidx.room.withTransaction
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.accounts.AccountDao
import com.feederp.data.accounts.AccountEntity
import com.feederp.data.accounts.LedgerDao
import com.feederp.data.accounts.LedgerEntryEntity
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * কেন্দ্রীয় অ্যাপেন্ড-অনলি ডাবল-এন্ট্রি লেজার। সব মডিউল শুধু [post] ডাকে।
 * [post] এ কোনো নির্দিষ্ট পারমিশন চাওয়া হয় না (ক্রেতা মডিউলের নিজের পারমিশনই যথেষ্ট) — শুধু লগইন লাগে।
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val db: AppDatabase,
    private val accountDao: AccountDao,
    private val ledgerDao: LedgerDao,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    @Volatile private var seeded = false

    /** প্রথম চালুতে (বা খাত কম থাকলে) হিসাবের খাত তৈরি; বারবার ডাকলে ক্ষতি নেই। */
    suspend fun ensureSeeded() {
        if (seeded) return
        if (accountDao.count() < ChartOfAccounts.defaults.size) {
            val now = stamper.now()
            accountDao.insertIgnore(
                ChartOfAccounts.defaults.map {
                    AccountEntity(
                        id = "acc-" + it.code, code = it.code, nameBn = it.nameBn, nameEn = it.nameEn,
                        type = it.type, updatedAt = now, deviceId = stamper.deviceId,
                    )
                }
            )
        }
        seeded = true
    }

    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    /** একটি ডাবল-এন্ট্রি ভাউচার; ডেবিট যোগফল ≠ ক্রেডিট যোগফল হলে IllegalArgumentException। ফেরত: voucherId। */
    suspend fun post(
        voucherType: String,
        refType: String?,
        refId: String?,
        memo: String?,
        lines: List<LedgerLine>,
        entryDate: Long? = null,
    ): String {
        val userId = session.userId
        ensureSeeded()
        val known = accountDao.all().map { it.code }.toSet()
        VoucherValidator.validate(lines) { it in known }?.let { throw IllegalArgumentException(it) }
        val now = stamper.now()
        val voucherId = newId()
        val date = entryDate ?: now
        val entries = lines.mapIndexed { i, l ->
            LedgerEntryEntity(
                id = newId(), voucherId = voucherId, voucherType = voucherType, lineNo = i,
                accountCode = l.accountCode, debitPaisa = l.debitPaisa, creditPaisa = l.creditPaisa,
                partyId = l.partyId, refType = refType, refId = refId, memo = memo,
                entryDate = date, createdBy = userId, createdAt = now, deviceId = stamper.deviceId,
            )
        }
        db.withTransaction { ledgerDao.insertAll(entries) }
        return voucherId
    }

    /** + = আমরা পাব (receivable), - = আমরা দেব। কেবল ১১০০ (বাকি পাওনা) ও ২১০০ (বাকি দেনা) খাতের পার্টি-লাইন গোনা হয়। */
    suspend fun partyBalancePaisa(partyId: String): Long = ledgerDao.partyBalance(partyId)

    fun observePartyBalancePaisa(partyId: String): Flow<Long> = ledgerDao.observePartyBalance(partyId)

    /**
     * ভুল শুধরাতে ভাউচারের হুবহু বিপরীত এন্ট্রি যোগ করা হয় (মূল এন্ট্রি অপরিবর্তিত)।
     * [requiredPermission] কলার-মডিউলের বৈধ পারমিশন (ডিফল্ট ACCOUNTS)। একই ভাউচার দুইবার রিভার্স হয় না।
     */
    suspend fun reverseVoucher(
        voucherId: String,
        reason: String,
        requiredPermission: Permission = Permission.ACCOUNTS,
    ): String {
        session.require(requiredPermission)
        require(reason.isNotBlank()) { "রিভার্সালের কারণ লিখতে হবে" }
        val newVoucher = db.withTransaction {
            val original = ledgerDao.byVoucher(voucherId)
            require(original.isNotEmpty()) { "ভাউচার পাওয়া যায়নি" }
            require(original.first().voucherType != "REVERSAL") { "রিভার্সাল ভাউচার আবার রিভার্স করা যায় না" }
            check(ledgerDao.reversalCount(voucherId) == 0) { "এই ভাউচার ইতিমধ্যে রিভার্স করা হয়েছে" }
            val lines = VoucherValidator.reverse(
                original.map { LedgerLine(it.accountCode, it.debitPaisa, it.creditPaisa, it.partyId) }
            )
            post("REVERSAL", "REVERSAL", voucherId, "রিভার্সাল: $reason", lines)
        }
        audit.log("REVERSE_VOUCHER", "ledger_entries", voucherId, reason)
        return newVoucher
    }

    // ---- অন্য মডিউলের জন্য সহায়ক (HR / রক্ষণাবেক্ষণ) ----

    /** বেতন অনুমোদন: ডেবিট বেতন খরচ; ক্রেডিট অগ্রিম কর্তন ও বেতন বাকি। refId = payroll run/slip id। */
    suspend fun postPayrollAccrual(refId: String, memo: String?, grossPaisa: Long, advanceRecoveryPaisa: Long = 0L): String =
        post("PAYROLL", "PAYROLL", refId, memo, PayrollPosting.accrualLines(grossPaisa, advanceRecoveryPaisa))

    /** বেতন পরিশোধ (method: CASH | BANK | MOBILE_BANKING)। */
    suspend fun postSalaryPayment(refId: String, memo: String?, amountPaisa: Long, method: String): String =
        post("SALARY_PAY", "PAYROLL", refId, memo, PayrollPosting.paymentLines(amountPaisa, method))

    /** কর্মচারীকে অগ্রিম প্রদান। */
    suspend fun postEmployeeAdvance(refId: String, memo: String?, amountPaisa: Long, method: String): String =
        post("EMP_ADVANCE", "EMPLOYEE_ADVANCE", refId, memo, PayrollPosting.advanceLines(amountPaisa, method))

    /** মাসিক অবচয় এন্ট্রি (পরিমাণ [DepreciationCalculator] থেকে)। refId = asset id। */
    suspend fun postDepreciation(assetId: String, memo: String?, amountPaisa: Long): String =
        post("DEPRECIATION", "ASSET", assetId, memo, PayrollPosting.depreciationLines(amountPaisa))
}
