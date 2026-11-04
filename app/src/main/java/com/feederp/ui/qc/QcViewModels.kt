package com.feederp.ui.qc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.data.ItemDao
import com.feederp.data.inventory.LotDao
import com.feederp.data.inventory.LotEntity
import com.feederp.data.qc.QcTestEntity
import com.feederp.inventory.QcStatus
import com.feederp.qc.QcCalc
import com.feederp.qc.QcParameters
import com.feederp.qc.QcRecommendation
import com.feederp.qc.QcRepository
import com.feederp.qc.TestPoint
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** QC তালিকার একটি সারি: লট + আইটেমের নাম। */
data class QcQueueRow(val lot: LotEntity, val itemName: String)

@HiltViewModel
class QcQueueViewModel @Inject constructor(
    lotDao: LotDao,
    itemDao: ItemDao,
) : BaseViewModel() {
    private val _showAll = MutableStateFlow(false)
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    /** ডিফল্টে শুধু QC-র অপেক্ষায় (PENDING) বা হোল্ডে থাকা লট; আগে মেয়াদ শেষ হবে যেটার। */
    val rows: StateFlow<List<QcQueueRow>> = combine(lotDao.observeAll(), itemDao.observeAll(), _showAll) { lots, items, all ->
        val names = items.associate { it.id to it.name }
        lots.filter { all || it.qcStatus == QcStatus.PENDING || it.qcStatus == QcStatus.HOLD }
            .sortedWith(compareBy({ it.expiryAt == null }, { it.expiryAt ?: Long.MAX_VALUE }))
            .map { QcQueueRow(it, names[it.itemId] ?: "অজানা আইটেম") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setShowAll(v: Boolean) { _showAll.value = v }
}

data class QcForm(
    val param: String = "MOISTURE",
    val customName: String = "",
    val unit: String = "%",
    val value: String = "",
    val min: String = "",
    val max: String = "",
    val note: String = "",
) {
    /** ডাটাবেসে যাওয়া প্যারামিটারের নাম ("অন্যান্য" হলে ব্যবহারকারীর লেখা নাম)। */
    val parameterName: String get() = if (param == QcParameters.OTHER) customName.trim() else param
}

data class LotQcState(
    val lot: LotEntity? = null,
    val itemName: String = "",
    val tests: List<QcTestEntity> = emptyList(),
) {
    val recommendation: QcRecommendation
        get() = QcCalc.recommendation(tests.map { TestPoint(it.parameter, it.passed, it.createdAt) })
    val failedParameters: List<String>
        get() = QcCalc.failedParameters(tests.map { TestPoint(it.parameter, it.passed, it.createdAt) })
}

@HiltViewModel
class QcLotViewModel @Inject constructor(
    private val repo: QcRepository,
    private val lotDao: LotDao,
    itemDao: ItemDao,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val lotId: String = requireNotNull(savedState.get<String>("id")) { "লট id নেই" }

    val state: StateFlow<LotQcState> = combine(lotDao.observeAll(), itemDao.observeAll(), repo.observeTests(lotId)) { lots, items, tests ->
        val lot = lots.firstOrNull { it.id == lotId }
        LotQcState(lot, items.firstOrNull { it.id == lot?.itemId }?.name.orEmpty(), tests)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LotQcState())

    private val _form = MutableStateFlow(QcForm())
    val form: StateFlow<QcForm> = _form.asStateFlow()

    init { prefillSpec() }

    fun selectParam(key: String) {
        _form.update { it.copy(param = key, unit = QcParameters.unitOf(key) ?: "", min = "", max = "") }
        prefillSpec()
    }

    fun onCustomName(v: String) { _form.update { it.copy(customName = v) }; prefillSpec() }
    fun onUnit(v: String) = _form.update { it.copy(unit = v) }
    fun onValue(v: String) = _form.update { it.copy(value = v) }
    fun onMin(v: String) = _form.update { it.copy(min = v) }
    fun onMax(v: String) = _form.update { it.copy(max = v) }
    fun onNote(v: String) = _form.update { it.copy(note = v) }

    /** একই আইটেম ও প্যারামিটারের সর্বশেষ সীমা আগে থেকে বসায় (বারবার টাইপ এড়াতে)। */
    private fun prefillSpec() = launchSafe {
        val name = _form.value.parameterName
        if (name.isBlank()) return@launchSafe
        val lot = lotDao.getById(lotId) ?: return@launchSafe
        val last = repo.lastSpec(lot.itemId, name) ?: return@launchSafe
        _form.update {
            if (it.parameterName != name) it
            else it.copy(
                min = last.specMin?.let(::numText).orEmpty(), max = last.specMax?.let(::numText).orEmpty(),
                unit = last.unit ?: it.unit,
            )
        }
    }

    fun addTest() = launchSafe {
        val f = _form.value
        val value = parseNum(f.value)
        if (f.value.isBlank() || value == null) throw IllegalArgumentException("টেস্টের মান সংখ্যায় দিন")
        fun optional(label: String, text: String): Double? =
            if (text.isBlank()) null else parseNum(text) ?: throw IllegalArgumentException("$label সংখ্যায় দিন")
        repo.recordTest(
            lotId = lotId, parameter = f.parameterName, unit = f.unit, value = value,
            specMin = optional("সর্বনিম্ন সীমা", f.min), specMax = optional("সর্বোচ্চ সীমা", f.max), note = f.note,
        )
        _form.update { it.copy(value = "", note = "") }
    }

    /** [status]: PASSED | HOLD | REJECTED। নিয়ম ও ত্রুটির বার্তা রিপোজিটরি থেকে আসে। */
    fun decide(status: String, note: String, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        repo.decide(lotId, status, note)
    }

    private fun numText(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}
