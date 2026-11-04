package com.feederp.ui.maintenance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.maintenance.MachineEntity
import com.feederp.data.maintenance.MachineStatus
import com.feederp.data.maintenance.MachineType
import com.feederp.data.maintenance.ScheduleBasis
import com.feederp.data.maintenance.WorkOrderChecklistItemEntity
import com.feederp.data.maintenance.WorkOrderEntity
import com.feederp.data.maintenance.WorkOrderPriority
import com.feederp.data.maintenance.WorkOrderStatus
import com.feederp.data.maintenance.WorkOrderType
import com.feederp.maintenance.MaintenanceRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

fun machineStatusBn(s: MachineStatus) = when (s) {
    MachineStatus.ACTIVE -> "চালু"; MachineStatus.BREAKDOWN -> "ব্রেকডাউন"
    MachineStatus.UNDER_MAINTENANCE -> "মেরামত চলছে"; MachineStatus.RETIRED -> "অবসরপ্রাপ্ত"
}

fun woStatusBn(s: WorkOrderStatus) = when (s) {
    WorkOrderStatus.OPEN -> "নতুন"; WorkOrderStatus.IN_PROGRESS -> "চলমান"; WorkOrderStatus.WAITING_PARTS -> "যন্ত্রাংশের অপেক্ষা"
    WorkOrderStatus.DONE -> "সম্পন্ন (বন্ধ বাকি)"; WorkOrderStatus.CLOSED -> "বন্ধ"
}

fun woTypeBn(t: WorkOrderType) = when (t) {
    WorkOrderType.PREVENTIVE -> "প্রিভেন্টিভ"; WorkOrderType.BREAKDOWN -> "ব্রেকডাউন"
    WorkOrderType.CALIBRATION -> "ক্যালিব্রেশন"; WorkOrderType.IMPROVEMENT -> "উন্নয়ন"
}

fun priorityBn(p: WorkOrderPriority) = when (p) {
    WorkOrderPriority.LOW -> "কম"; WorkOrderPriority.NORMAL -> "স্বাভাবিক"; WorkOrderPriority.HIGH -> "জরুরি"; WorkOrderPriority.CRITICAL -> "অতি জরুরি"
}

fun machineTypeBn(t: MachineType) = when (t) {
    MachineType.GRINDER -> "গ্রাইন্ডার"; MachineType.MIXER -> "মিক্সার"; MachineType.PELLET_MILL -> "পেলেট মিল"; MachineType.COOLER -> "কুলার"
    MachineType.DRYER -> "ড্রায়ার"; MachineType.BOILER -> "বয়লার"; MachineType.PACKING -> "প্যাকিং"; MachineType.GENERATOR -> "জেনারেটর"
    MachineType.WEIGHING_SCALE -> "ওজন স্কেল"; MachineType.DOSING_SYSTEM -> "ডোজিং সিস্টেম"; MachineType.OTHER -> "অন্যান্য"
}

@HiltViewModel
class MachineListViewModel @Inject constructor(
    private val repo: MaintenanceRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    val machines: StateFlow<List<MachineEntity>> = repo.observeMachines()
        .map { l -> l.filter { it.deletedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canManage: Boolean get() = session.can(Permission.MAINTENANCE)

    fun save(
        id: String?, code: String, name: String, type: MachineType, capacity: String, costTaka: String, lifeMonths: String,
        location: String, onDone: () -> Unit,
    ) = launchSafe(onDone = onDone) {
        val cap = if (capacity.isBlank()) 0.0 else parseNum(capacity) ?: throw IllegalArgumentException("ক্ষমতা সংখ্যায় দিন")
        val cost = if (costTaka.isBlank()) 0L else parseTakaToPaisa(costTaka) ?: throw IllegalArgumentException("ক্রয়মূল্য সংখ্যায় দিন")
        val life = lifeMonths.trim().toIntOrNull() ?: throw IllegalArgumentException("আয়ুষ্কাল (মাস) সংখ্যায় দিন")
        repo.saveMachine(id, code, name, type, cap, System.currentTimeMillis(), cost, life, location)
    }

    fun addSchedule(machineId: String, title: String, byHours: Boolean, interval: String, checklist: String, onDone: () -> Unit) =
        launchSafe(onDone = onDone) {
            val n = parseNum(interval) ?: throw IllegalArgumentException("ব্যবধান সংখ্যায় দিন")
            repo.saveSchedule(
                machineId, title, if (byHours) ScheduleBasis.HOURS else ScheduleBasis.DATE,
                if (byHours) n else 0.0, if (byHours) 0 else n.toInt(), checklist,
            )
        }

    fun retire(id: String) = launchSafe { repo.retireMachine(id) }
}

data class WoRow(val wo: WorkOrderEntity, val machineName: String)

@HiltViewModel
class WorkOrderListViewModel @Inject constructor(
    private val repo: MaintenanceRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    val machines: StateFlow<List<MachineEntity>> = repo.observeMachines()
        .map { l -> l.filter { it.deletedAt == null && it.status != MachineStatus.RETIRED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<WoRow>> = combine(repo.observeWorkOrders(), repo.observeMachines()) { wos, ms ->
        val names = ms.associate { it.id to it.name }
        wos.filter { it.deletedAt == null }.map { WoRow(it, names[it.machineId] ?: "অজানা মেশিন") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val canManage: Boolean get() = session.can(Permission.MAINTENANCE)

    fun report(machineId: String?, title: String, desc: String, priority: WorkOrderPriority, onDone: () -> Unit) =
        launchSafe(onDone = onDone) {
            repo.reportBreakdown(machineId ?: throw IllegalArgumentException("মেশিন বাছাই করুন"), title, desc, priority)
        }

    fun generatePm() = launchSafe {
        val n = repo.generateDuePreventive()
        showMessage(if (n == 0) "এখন কোনো প্রিভেন্টিভ কাজ হয়ে ওঠেনি" else "$n টি প্রিভেন্টিভ ওয়ার্ক অর্ডার তৈরি হয়েছে")
    }
}

data class WoDetailState(
    val wo: WorkOrderEntity? = null,
    val machineName: String = "",
    val checklist: List<WorkOrderChecklistItemEntity> = emptyList(),
)

@HiltViewModel
class WorkOrderDetailViewModel @Inject constructor(
    private val repo: MaintenanceRepository,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val woId: String = requireNotNull(savedState.get<String>("id")) { "ওয়ার্ক অর্ডার id নেই" }
    val canManage: Boolean get() = session.can(Permission.MAINTENANCE)
    val canApprove: Boolean get() = session.can(Permission.APPROVE)

    val state: StateFlow<WoDetailState> = combine(repo.observeWorkOrder(woId), repo.observeMachines(), repo.observeChecklist(woId)) { wo, ms, cl ->
        WoDetailState(wo, ms.firstOrNull { it.id == wo?.machineId }?.name.orEmpty(), cl.filter { it.deletedAt == null }.sortedBy { it.sortOrder })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WoDetailState())

    fun move(to: WorkOrderStatus) = launchSafe { repo.changeStatus(woId, to) }
    fun toggle(itemId: String) = launchSafe { repo.toggleChecklist(itemId) }

    fun complete(rootCause: String, action: String, partsTaka: String, laborTaka: String, otherTaka: String, onDone: () -> Unit) =
        launchSafe(onDone = onDone) {
            fun money(label: String, t: String): Long =
                if (t.isBlank()) 0L else parseTakaToPaisa(t) ?: throw IllegalArgumentException("$label সংখ্যায় দিন")
            repo.complete(woId, rootCause, action, money("যন্ত্রাংশ খরচ", partsTaka), money("মজুরি", laborTaka), money("অন্যান্য খরচ", otherTaka))
        }

    fun approve() = launchSafe { repo.approve(woId) }
    fun reject(reason: String) = launchSafe { repo.reject(woId, reason) }
    fun close() = launchSafe { repo.close(woId) }
}
