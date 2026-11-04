package com.feederp.ui.rnd

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.formulation.FormulaEntity
import com.feederp.data.rnd.FeedTrialRecordEntity
import com.feederp.data.rnd.FieldFeedbackEntity
import com.feederp.data.rnd.FormulaTrialEntity
import com.feederp.formulation.FormulaRepository
import com.feederp.rnd.FcrCalculator
import com.feederp.rnd.FcrInput
import com.feederp.rnd.FcrResult
import com.feederp.rnd.RndRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

fun fmt(v: Double?, digits: Int = 2): String = if (v == null) "—" else String.format(java.util.Locale.US, "%.${digits}f", v)

@HiltViewModel
class TrialListViewModel @Inject constructor(repo: RndRepository, private val session: SessionManager) : BaseViewModel() {
    val trials: StateFlow<List<FormulaTrialEntity>> = repo.observeTrials().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canStart: Boolean get() = session.can(Permission.RND)
}

@HiltViewModel
class NewTrialViewModel @Inject constructor(private val repo: RndRepository, private val formulas: FormulaRepository) : BaseViewModel() {
    private val _approved = MutableStateFlow<List<FormulaEntity>>(emptyList())
    val approved: StateFlow<List<FormulaEntity>> = _approved.asStateFlow()

    init { viewModelScope.launch { _approved.value = formulas.approvedFormulas() } }

    fun start(baseId: String?, changes: String, objective: String, onCreated: (String) -> Unit) = launchSafe {
        val id = repo.startTrial(baseId ?: throw IllegalArgumentException("বেস ফর্মুলা বাছাই করুন"), changes, objective, System.currentTimeMillis())
        onCreated(id)
    }
}

data class GroupRow(val record: FeedTrialRecordEntity, val result: FcrResult)

data class TrialState(
    val trial: FormulaTrialEntity? = null,
    val baseName: String = "",
    val proposedName: String = "",
    val groups: List<GroupRow> = emptyList(),
    val total: FcrResult? = null,
)

@HiltViewModel
class TrialDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: RndRepository,
    formulas: FormulaRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    private val trialId: String = requireNotNull(handle.get<String>("id"))
    val canRnd: Boolean get() = session.can(Permission.RND)
    val canQc: Boolean get() = session.can(Permission.QC)
    val canApprove: Boolean get() = session.can(Permission.APPROVE)

    val state: StateFlow<TrialState> = combine(repo.observeTrial(trialId), repo.observeRecords(trialId), formulas.observeAll()) { t, recs, fs ->
        val names = fs.associate { it.id to "${it.name} v${it.version}" }
        val groups = recs.map { GroupRow(it, FcrCalculator.calculate(repo.toInput(it))) }
        TrialState(t, t?.let { names[it.baseFormulaId] }.orEmpty(), t?.let { names[it.proposedFormulaId] }.orEmpty(), groups, FcrCalculator.aggregate(groups.map { it.result }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrialState())

    fun addRecord(
        group: String, species: String, count: String, days: String, startG: String, endG: String, feedKg: String,
        dead: String, eggs: String, milk: String, costPerKg: String, note: String, onDone: () -> Unit,
    ) = launchSafe(onDone) {
        fun num(t: String, label: String): Double = parseNum(t) ?: throw IllegalArgumentException("$label: সংখ্যা ঠিক নয়")
        val input = FcrInput(
            animalCount = count.toIntOrNull() ?: throw IllegalArgumentException("প্রাণীর সংখ্যা ঠিক নয়"),
            days = days.toIntOrNull() ?: throw IllegalArgumentException("দিন ঠিক নয়"),
            startWeightGrams = num(startG, "শুরুর ওজন"), endWeightGrams = num(endG, "শেষ ওজন"),
            feedIntakeGrams = num(feedKg, "খাদ্য গ্রহণ") * 1000.0,
            mortalityCount = if (dead.isBlank()) 0 else dead.toIntOrNull() ?: throw IllegalArgumentException("মৃত্যু সংখ্যা ঠিক নয়"),
            eggCount = if (eggs.isBlank()) null else eggs.toLongOrNull() ?: throw IllegalArgumentException("ডিমের সংখ্যা ঠিক নয়"),
            milkLiters = if (milk.isBlank()) null else num(milk, "দুধ"),
            feedCostPaisaPerKg = if (costPerKg.isBlank()) null else parseTakaToPaisa(costPerKg) ?: throw IllegalArgumentException("খাদ্যের দাম ঠিক নয়"),
        )
        repo.addRecord(trialId, group, species, input, note)
    }

    fun removeRecord(id: String) = launchSafe { repo.removeRecord(id) }
    fun submit() = launchSafe { repo.submit(trialId) }
    fun qcVerify(note: String) = launchSafe { repo.qcVerify(trialId, note) }
    fun approve(note: String) = launchSafe { repo.approve(trialId, note) }
    fun reject(note: String) = launchSafe { repo.reject(trialId, note) }
}

@HiltViewModel
class FeedbackViewModel @Inject constructor(private val repo: RndRepository, private val session: SessionManager) : BaseViewModel() {
    val items: StateFlow<List<FieldFeedbackEntity>> = repo.observeFeedback().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canEdit: Boolean get() = session.can(Permission.RND)

    fun add(batchNo: String, farm: String, issue: String, severity: Int, animals: String, desc: String, onDone: () -> Unit) = launchSafe(onDone) {
        repo.addFeedback(
            batchNo, farm, System.currentTimeMillis(), issue, severity,
            if (animals.isBlank()) null else animals.toIntOrNull() ?: throw IllegalArgumentException("প্রাণীর সংখ্যা ঠিক নয়"), desc,
        )
    }

    fun resolve(id: String, action: String, onDone: () -> Unit) = launchSafe(onDone) { repo.resolveFeedback(id, action) }
}
