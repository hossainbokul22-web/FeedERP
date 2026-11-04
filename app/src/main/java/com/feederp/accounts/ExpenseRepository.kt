package com.feederp.accounts

import androidx.room.withTransaction
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.Role
import com.feederp.data.accounts.ExpenseDao
import com.feederp.data.accounts.ExpenseEntity
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** খরচ অনুমোদনের পূর্ণ নীতি (বিশুদ্ধ, টেস্টযোগ্য)। */
object ExpensePolicy {
    /** এর বেশি খরচ APPROVE পারমিশনহীন ইউজার দিলে অনুমোদনের অপেক্ষায় থাকে (৫,০০০ টাকা)। */
    const val AUTO_APPROVE_LIMIT_PAISA = 500_000L

    fun initialStatus(amountPaisa: Long, canApprove: Boolean): String =
        if (canApprove || amountPaisa <= AUTO_APPROVE_LIMIT_PAISA) "APPROVED" else "PENDING"

    /** নিজের খরচ নিজে অনুমোদন করা যাবে না (OWNER বাদে)। */
    fun canApproveOwn(role: Role?): Boolean = role == Role.OWNER
}

@Singleton
class ExpenseRepository @Inject constructor(
    private val db: AppDatabase,
    private val expenseDao: ExpenseDao,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    fun observeAll(): Flow<List<ExpenseEntity>> = expenseDao.observeAll()

    /** নতুন খরচ। অনুমোদন-সীমা পেরোলে PENDING থাকে, নইলে সাথে সাথে লেজারে পোস্ট হয়। ফেরত: expenseId। */
    suspend fun create(
        accountCode: String,
        amountPaisa: Long,
        method: String,
        description: String,
        payeeName: String?,
        expenseDate: Long,
    ): String {
        session.require(Permission.ACCOUNTS)
        require(amountPaisa > 0) { "খরচের অঙ্ক ০ এর বেশি হতে হবে" }
        require(description.isNotBlank()) { "খরচের বিবরণ লিখুন" }
        require(ChartOfAccounts.expenseChoices.any { it.code == accountCode }) { "খরচের খাত সঠিক নয়" }
        require(method in listOf("CASH", "BANK", "MOBILE_BANKING")) { "পেমেন্ট পদ্ধতি সঠিক নয়" }
        val status = ExpensePolicy.initialStatus(amountPaisa, session.can(Permission.APPROVE))
        val id = newId()
        val no = docNumbers.next("EXP")
        val base = ExpenseEntity(
            id = id, expenseNo = no, accountCode = accountCode, amountPaisa = amountPaisa, method = method,
            description = description.trim(), payeeName = payeeName?.trim()?.ifBlank { null },
            expenseDate = expenseDate, status = status, createdBy = session.userId,
            updatedAt = stamper.now(), deviceId = stamper.deviceId,
        )
        db.withTransaction {
            if (status == "APPROVED") {
                val v = postExpense(base)
                expenseDao.upsert(
                    base.copy(
                        voucherId = v, approvedBy = session.userId, approvedAt = stamper.now(),
                        updatedAt = stamper.now(),
                    )
                )
            } else expenseDao.upsert(base)
        }
        audit.log("EXPENSE_CREATE", "expenses", id, "$no ${amountPaisa} $status")
        return id
    }

    suspend fun approve(expenseId: String) {
        session.require(Permission.APPROVE)
        db.withTransaction {
            val e = requireNotNull(expenseDao.getById(expenseId)) { "খরচ পাওয়া যায়নি" }
            check(e.status == "PENDING") { "শুধু অপেক্ষমাণ খরচ অনুমোদন করা যায়" }
            check(e.createdBy != session.userId || ExpensePolicy.canApproveOwn(session.role)) {
                "নিজের তোলা খরচ নিজে অনুমোদন করা যাবে না"
            }
            val v = postExpense(e)
            expenseDao.upsert(
                e.copy(
                    status = "APPROVED", voucherId = v, approvedBy = session.userId, approvedAt = stamper.now(),
                    updatedAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
        }
        audit.log("EXPENSE_APPROVE", "expenses", expenseId)
    }

    suspend fun reject(expenseId: String, reason: String) {
        session.require(Permission.APPROVE)
        require(reason.isNotBlank()) { "বাতিলের কারণ লিখুন" }
        db.withTransaction {
            val e = requireNotNull(expenseDao.getById(expenseId)) { "খরচ পাওয়া যায়নি" }
            check(e.status == "PENDING") { "শুধু অপেক্ষমাণ খরচ বাতিল করা যায়" }
            expenseDao.upsert(
                e.copy(
                    status = "REJECTED", rejectReason = reason.trim(), approvedBy = session.userId,
                    approvedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
        }
        audit.log("EXPENSE_REJECT", "expenses", expenseId, reason)
    }

    /** অনুমোদিত খরচের ভুল সংশোধন: লেজার রিভার্স + খরচ সফট-ডিলিট। */
    suspend fun voidApproved(expenseId: String, reason: String) {
        session.require(Permission.APPROVE)
        val e = requireNotNull(expenseDao.getById(expenseId)) { "খরচ পাওয়া যায়নি" }
        check(e.status == "APPROVED" && e.voucherId != null) { "শুধু অনুমোদিত খরচ বাতিল করা যায়" }
        db.withTransaction {
            ledger.reverseVoucher(e.voucherId!!, reason, Permission.APPROVE)
            expenseDao.upsert(
                e.copy(status = "VOID", deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId)
            )
        }
        audit.log("EXPENSE_VOID", "expenses", expenseId, reason)
    }

    private suspend fun postExpense(e: ExpenseEntity): String = ledger.post(
        voucherType = "EXPENSE", refType = "EXPENSE", refId = e.id, memo = "${e.expenseNo}: ${e.description}",
        lines = listOf(
            LedgerLine(e.accountCode, debitPaisa = e.amountPaisa),
            LedgerLine(ChartOfAccounts.cashAccountForMethod(e.method), creditPaisa = e.amountPaisa),
        ),
        entryDate = e.expenseDate,
    )
}
