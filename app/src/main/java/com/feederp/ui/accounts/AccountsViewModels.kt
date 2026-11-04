package com.feederp.ui.accounts

import androidx.lifecycle.viewModelScope
import com.feederp.accounts.AccountTotal
import com.feederp.accounts.AccountingCalc
import com.feederp.accounts.ExpenseRepository
import com.feederp.accounts.ProfitAndLoss
import com.feederp.accounts.TrialBalance
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.accounts.ExpenseEntity
import com.feederp.data.accounts.LedgerDao
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

fun taka(paisa: Long): String = String.format(java.util.Locale.US, "%.2f", paisa / 100.0)

enum class Period(val labelBn: String) { THIS_MONTH("এই মাস"), LAST_MONTH("গত মাস"), THIS_YEAR("এই বছর"), ALL("সব সময়") }

/** [from, to) মিলিসেকেন্ড; ALL = সব। */
fun periodRange(p: Period, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    fun cal(): Calendar = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (p) {
        Period.ALL -> 0L to Long.MAX_VALUE
        Period.THIS_MONTH -> cal().apply { set(Calendar.DAY_OF_MONTH, 1) }.let { s ->
            s.timeInMillis to (s.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        }
        Period.LAST_MONTH -> cal().apply { set(Calendar.DAY_OF_MONTH, 1) }.let { s ->
            (s.clone() as Calendar).apply { add(Calendar.MONTH, -1) }.timeInMillis to s.timeInMillis
        }
        Period.THIS_YEAR -> cal().apply { set(Calendar.DAY_OF_YEAR, 1) }.let { s ->
            s.timeInMillis to (s.clone() as Calendar).apply { add(Calendar.YEAR, 1) }.timeInMillis
        }
    }
}

data class ReportState(
    val period: Period = Period.THIS_MONTH,
    val trial: TrialBalance? = null,
    val pnl: ProfitAndLoss? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(private val ledgerDao: LedgerDao) : BaseViewModel() {
    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    init { load(Period.THIS_MONTH) }

    fun load(period: Period) = launchSafe {
        val (from, to) = periodRange(period)
        val rows = if (period == Period.ALL) ledgerDao.totalsUpTo(to) else ledgerDao.totalsBetween(from, to)
        val totals = rows.map { AccountTotal(it.accountCode, it.debitPaisa, it.creditPaisa) }
        _state.value = ReportState(period, AccountingCalc.trialBalance(totals), AccountingCalc.profitAndLoss(totals))
    }
}

@HiltViewModel
class ExpenseViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    val expenses: StateFlow<List<ExpenseEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canApprove: Boolean get() = session.can(Permission.APPROVE)

    fun create(accountCode: String?, amountTaka: String, method: String, description: String, payee: String, onDone: () -> Unit) =
        launchSafe(onDone = onDone) {
            val code = accountCode ?: throw IllegalArgumentException("খরচের খাত বাছাই করুন")
            val amount = parseTakaToPaisa(amountTaka)?.takeIf { it > 0 } ?: throw IllegalArgumentException("অঙ্ক সংখ্যায় দিন")
            repo.create(code, amount, method, description, payee, System.currentTimeMillis())
        }

    fun approve(id: String) = launchSafe { repo.approve(id) }
    fun reject(id: String, reason: String) = launchSafe { repo.reject(id, reason) }
    fun void(id: String, reason: String) = launchSafe { repo.voidApproved(id, reason) }
}
