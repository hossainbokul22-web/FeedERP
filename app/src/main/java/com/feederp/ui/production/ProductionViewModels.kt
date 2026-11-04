package com.feederp.ui.production

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.data.ItemDao
import com.feederp.data.WarehouseDao
import com.feederp.data.WarehouseEntity
import com.feederp.data.formulation.FormulaEntity
import com.feederp.data.production.BatchConsumptionEntity
import com.feederp.data.production.BatchMachineRunEntity
import com.feederp.data.production.CleaningChecklistItemEntity
import com.feederp.data.production.ProductionBatchEntity
import com.feederp.formulation.FormulaRepository
import com.feederp.production.BatchPreview
import com.feederp.production.NewBatchInput
import com.feederp.production.ProductionRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class BatchListViewModel @Inject constructor(repo: ProductionRepository) : BaseViewModel() {
    val batches: StateFlow<List<ProductionBatchEntity>> = repo.observeBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class NewBatchState(
    val formulas: List<FormulaEntity> = emptyList(),
    val warehouses: List<WarehouseEntity> = emptyList(),
    val formulaId: String? = null,
    val plannedKg: String = "",
    val sourceWarehouseId: String? = null,
    val fgWarehouseId: String? = null,
    val bagKg: String = "50",
    val shelfDays: String = "90",
    val note: String = "",
)

@HiltViewModel
class NewBatchViewModel @Inject constructor(
    private val repo: ProductionRepository,
    private val formulaRepo: FormulaRepository,
    private val warehouseDao: WarehouseDao,
) : BaseViewModel() {
    private val _state = MutableStateFlow(NewBatchState())
    val state: StateFlow<NewBatchState> = _state.asStateFlow()

    init {
        launchSafe {
            val formulas = formulaRepo.approvedFormulas()
            val wh = warehouseDao.all()
            _state.update {
                it.copy(
                    formulas = formulas, warehouses = wh,
                    // সিড করা গুদাম থাকলে ডিফল্ট হিসেবে বসাই
                    sourceWarehouseId = wh.firstOrNull { w -> w.id == "wh-main" }?.id ?: wh.firstOrNull { w -> !w.isMedicineStore }?.id,
                    fgWarehouseId = wh.firstOrNull { w -> w.id == "wh-fg" }?.id ?: wh.firstOrNull { w -> !w.isMedicineStore }?.id,
                )
            }
        }
    }

    fun onFormula(f: FormulaEntity) = _state.update { it.copy(formulaId = f.id) }
    fun onPlannedKg(v: String) = _state.update { it.copy(plannedKg = v) }
    fun onSource(w: WarehouseEntity) = _state.update { it.copy(sourceWarehouseId = w.id) }
    fun onFg(w: WarehouseEntity) = _state.update { it.copy(fgWarehouseId = w.id) }
    fun onBagKg(v: String) = _state.update { it.copy(bagKg = v) }
    fun onShelfDays(v: String) = _state.update { it.copy(shelfDays = v) }
    fun onNote(v: String) = _state.update { it.copy(note = v) }

    fun create(onCreated: (String) -> Unit) = launchSafe {
        val s = _state.value
        val formulaId = s.formulaId ?: throw IllegalArgumentException("অনুমোদিত ফর্মুলা বাছাই করুন")
        val kg = parseNum(s.plannedKg)
        if (kg == null || kg <= 0.0) throw IllegalArgumentException("পরিকল্পিত পরিমাণ (কেজি) ধনাত্মক সংখ্যায় দিন")
        val bag = if (s.bagKg.isBlank()) 0.0 else parseNum(s.bagKg) ?: throw IllegalArgumentException("বস্তার সাইজ সংখ্যায় দিন (লুজ হলে ০)")
        val shelf = s.shelfDays.trim().toIntOrNull()
        if (shelf == null || shelf <= 0) throw IllegalArgumentException("শেলফ-লাইফ (দিন) পূর্ণসংখ্যায় দিন")
        val id = repo.createBatch(
            NewBatchInput(
                formulaId = formulaId, plannedQtyGrams = kg * 1000.0,
                sourceWarehouseId = s.sourceWarehouseId.orEmpty(), fgWarehouseId = s.fgWarehouseId.orEmpty(),
                bagSizeKg = bag, shelfLifeDays = shelf, note = s.note,
            )
        )
        onCreated(id)
    }
}

data class BatchDetailState(
    val batch: ProductionBatchEntity? = null,
    val consumption: List<BatchConsumptionEntity> = emptyList(),
    val runs: List<BatchMachineRunEntity> = emptyList(),
    val cleaning: List<CleaningChecklistItemEntity> = emptyList(),
)

@HiltViewModel
class BatchDetailViewModel @Inject constructor(
    private val repo: ProductionRepository,
    itemDao: ItemDao,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val batchId: String = requireNotNull(savedState.get<String>("id")) { "ব্যাচ id নেই" }

    /** আইটেম id → নাম (ব্যবহৃত উপাদানের তালিকায় নাম দেখানোর জন্য)। */
    val itemNames: StateFlow<Map<String, String>> = itemDao.observeAll()
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val state: StateFlow<BatchDetailState> = combine(
        repo.observeBatch(batchId), repo.observeConsumption(batchId),
        repo.observeRuns(batchId), repo.observeCleaning(batchId),
    ) { b, c, r, cl -> BatchDetailState(b, c, r, cl) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatchDetailState())

    private val _preview = MutableStateFlow<BatchPreview?>(null)
    val preview: StateFlow<BatchPreview?> = _preview.asStateFlow()

    init { refreshPreview() }

    fun refreshPreview() = launchSafe {
        val b = repo.getBatch(batchId)
        _preview.value = if (b != null && b.status == com.feederp.data.production.BatchStatus.PLANNED) repo.preview(batchId) else null
    }

    fun start() = launchSafe(onDone = { _preview.value = null }) { repo.start(batchId) }

    fun cancel(onDone: () -> Unit) = launchSafe(onDone = onDone) { repo.cancel(batchId) }

    /** ঘণ্টা-ভিত্তিক সরল রান: এখন থেকে [hoursText] ঘণ্টা আগে শুরু, এখন শেষ। */
    fun addRun(machineName: String, hoursText: String, downtimeText: String, reason: String, hourlyCostTaka: String) = launchSafe {
        val name = machineName.trim()
        if (name.isEmpty()) throw IllegalArgumentException("মেশিনের নাম দিন")
        val hours = parseNum(hoursText)
        if (hours == null || hours <= 0.0) throw IllegalArgumentException("চালু থাকার ঘণ্টা ধনাত্মক সংখ্যায় দিন")
        val down = if (downtimeText.isBlank()) 0 else downtimeText.trim().toIntOrNull()
            ?: throw IllegalArgumentException("ডাউনটাইম (মিনিট) পূর্ণসংখ্যায় দিন")
        val cost = if (hourlyCostTaka.isBlank()) 0L else parseTakaToPaisa(hourlyCostTaka)
            ?: throw IllegalArgumentException("ঘণ্টা-খরচ টাকায় সংখ্যা দিন")
        val end = System.currentTimeMillis()
        val start = end - (hours * 3_600_000.0).toLong()
        repo.addMachineRun(
            batchId = batchId, machineId = "manual:" + name.lowercase(), machineName = name,
            startAt = start, endAt = end, downtimeMinutes = down, downtimeReason = reason, hourlyCostPaisa = cost,
        )
    }

    fun complete(outputKg: String, labourTaka: String, overheadTaka: String, packagingTaka: String, onDone: () -> Unit) =
        launchSafe(onDone = onDone) {
            val kg = parseNum(outputKg)
            if (kg == null || kg <= 0.0) throw IllegalArgumentException("আউটপুট (কেজি) ধনাত্মক সংখ্যায় দিন")
            fun taka(label: String, text: String): Long =
                if (text.isBlank()) 0L else parseTakaToPaisa(text) ?: throw IllegalArgumentException("$label টাকায় সংখ্যা দিন")
            repo.complete(
                batchId = batchId, outputGoodGrams = kg * 1000.0,
                labourPaisa = taka("লেবার খরচ", labourTaka), overheadPaisa = taka("ওভারহেড খরচ", overheadTaka),
                packagingPaisa = taka("প্যাকেজিং খরচ", packagingTaka),
            )
        }

    fun setCleaning(itemId: String, done: Boolean) = launchSafe { repo.setCleaningItem(itemId, done) }
}
