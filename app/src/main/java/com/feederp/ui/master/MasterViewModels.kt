package com.feederp.ui.master

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Weight
import com.feederp.core.newId
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.PartyEntity
import com.feederp.data.PartyType
import com.feederp.data.UnitEntity
import com.feederp.data.WarehouseEntity
import com.feederp.master.MasterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

private fun optNum(label: String, text: String): Double? {
    if (text.isBlank()) return null
    return parseNum(text) ?: throw IllegalArgumentException("$label সঠিক সংখ্যা নয়")
}

// ---------------- আইটেম ----------------

data class ItemForm(
    val id: String = newId(),
    val isNew: Boolean = true,
    val code: String = "",
    val name: String = "",
    val nameBn: String = "",
    val type: ItemType = ItemType.RAW_MATERIAL,
    val unitId: String = "",
    val minStockKg: String = "",
    val trackLot: Boolean = true,
    val trackExpiry: Boolean = true,
    val requiresQc: Boolean = true,
    val activeIngredient: String = "",
    val concentrationPct: String = "",
    val minDose: String = "",
    val maxDose: String = "",
    val withdrawalDays: String = "",
    val isControlled: Boolean = false,
    val storageNote: String = "",
    val protein: String = "",
    val energy: String = "",
    val fat: String = "",
    val fibre: String = "",
    val moisture: String = "",
) {
    fun toEntity(): ItemEntity {
        val minKg = optNum("মিনিমাম স্টক", minStockKg) ?: 0.0
        val days = if (withdrawalDays.isBlank()) null
        else withdrawalDays.trim().toIntOrNull() ?: throw IllegalArgumentException("উইথড্রয়াল দিন পূর্ণসংখ্যা হতে হবে")
        val medicine = com.feederp.master.MasterLabels.showsMedicineFields(type)
        return ItemEntity(
            id = id, code = code, name = name, nameBn = nameBn.ifBlank { null }, type = type, unitId = unitId,
            minStockGrams = Weight.kgToGrams(minKg), trackLot = trackLot, trackExpiry = trackExpiry, requiresQc = requiresQc,
            activeIngredient = if (medicine) activeIngredient.ifBlank { null } else null,
            concentrationPct = if (medicine) optNum("ঘনত্ব", concentrationPct) else null,
            minDoseGPerTon = if (medicine) optNum("সর্বনিম্ন ডোজ", minDose) else null,
            maxDoseGPerTon = if (medicine) optNum("সর্বোচ্চ ডোজ", maxDose) else null,
            withdrawalDays = if (medicine) days else null,
            isControlled = isControlled,
            storageNote = storageNote.ifBlank { null },
            proteinPct = optNum("প্রোটিন", protein), energyKcalPerKg = optNum("শক্তি", energy),
            fatPct = optNum("ফ্যাট", fat), fibrePct = optNum("ফাইবার", fibre), moisturePct = optNum("আর্দ্রতা", moisture),
            updatedAt = 0L, deviceId = "",
        )
    }

    companion object {
        private fun s(d: Double?): String = d?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: ""

        fun from(i: ItemEntity) = ItemForm(
            id = i.id, isNew = false, code = i.code, name = i.name, nameBn = i.nameBn ?: "", type = i.type, unitId = i.unitId,
            minStockKg = gramsToKgText(i.minStockGrams), trackLot = i.trackLot, trackExpiry = i.trackExpiry, requiresQc = i.requiresQc,
            activeIngredient = i.activeIngredient ?: "", concentrationPct = s(i.concentrationPct),
            minDose = s(i.minDoseGPerTon), maxDose = s(i.maxDoseGPerTon), withdrawalDays = i.withdrawalDays?.toString() ?: "",
            isControlled = i.isControlled, storageNote = i.storageNote ?: "",
            protein = s(i.proteinPct), energy = s(i.energyKcalPerKg), fat = s(i.fatPct), fibre = s(i.fibrePct), moisture = s(i.moisturePct),
        )
    }
}

@HiltViewModel
class ItemListViewModel @Inject constructor(
    private val repo: MasterRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canEdit: Boolean = session.can(Permission.MASTER_EDIT)
    val items: StateFlow<List<ItemEntity>> =
        repo.observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val units: StateFlow<List<UnitEntity>> =
        repo.observeUnits().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: String) = launchSafe { repo.deleteItem(id) }

    /** স্ক্যান করা মান আইটেম কোডের সাথে মেলে কি না; পেলে [onFound]-এ id। */
    fun findByScan(code: String, onFound: (String) -> Unit) = launchSafe {
        val hit = items.value.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
        if (hit == null) showMessage("'${code.trim()}' কোডের কোনো আইটেম নেই") else onFound(hit.id)
    }
}

@HiltViewModel
class ItemFormViewModel @Inject constructor(
    private val repo: MasterRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel() {
    private val editId: String? = savedStateHandle.get<String>("id")
    val units: StateFlow<List<UnitEntity>> =
        repo.observeUnits().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _form = MutableStateFlow(ItemForm())
    val form: StateFlow<ItemForm> = _form.asStateFlow()

    init {
        if (editId != null) launchSafe {
            val item = repo.getItem(editId) ?: throw IllegalStateException("আইটেম পাওয়া যায়নি")
            _form.value = ItemForm.from(item)
        }
    }

    fun update(f: (ItemForm) -> ItemForm) { _form.update(f) }
    fun save(onSaved: () -> Unit) = launchSafe(onDone = onSaved) { repo.saveItem(_form.value.toEntity()) }
}

// ---------------- পার্টি ----------------

data class PartyForm(
    val id: String = newId(),
    val isNew: Boolean = true,
    val type: PartyType = PartyType.SUPPLIER,
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val creditLimitTaka: String = "",
) {
    fun toEntity(): PartyEntity {
        val credit = if (type == PartyType.SUPPLIER || creditLimitTaka.isBlank()) 0L
        else parseTakaToPaisa(creditLimitTaka) ?: throw IllegalArgumentException("ক্রেডিট লিমিট সঠিক সংখ্যা নয়")
        return PartyEntity(
            id = id, type = type, name = name, phone = phone.ifBlank { null }, address = address.ifBlank { null },
            creditLimitPaisa = credit, updatedAt = 0L, deviceId = "",
        )
    }

    companion object {
        fun from(p: PartyEntity) = PartyForm(
            id = p.id, isNew = false, type = p.type, name = p.name, phone = p.phone ?: "", address = p.address ?: "",
            creditLimitTaka = if (p.creditLimitPaisa == 0L) "" else (p.creditLimitPaisa / 100.0).toString(),
        )
    }
}

@HiltViewModel
class PartyViewModel @Inject constructor(
    private val repo: MasterRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canEdit: Boolean = session.can(Permission.MASTER_EDIT)
    val parties: StateFlow<List<PartyEntity>> =
        repo.observeParties().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _editing = MutableStateFlow<PartyForm?>(null)
    val editing: StateFlow<PartyForm?> = _editing.asStateFlow()

    fun startNew() { clearMessage(); _editing.value = PartyForm() }
    fun startEdit(p: PartyEntity) { clearMessage(); _editing.value = PartyForm.from(p) }
    fun dismiss() { clearMessage(); _editing.value = null }
    fun update(f: (PartyForm) -> PartyForm) { _editing.update { it?.let(f) } }
    fun save() {
        val form = _editing.value ?: return
        launchSafe(onDone = { _editing.value = null }) { repo.saveParty(form.toEntity()) }
    }
    fun delete(id: String) = launchSafe { repo.deleteParty(id) }
}

// ---------------- গুদাম ----------------

data class WarehouseForm(
    val id: String = newId(),
    val isNew: Boolean = true,
    val name: String = "",
    val isMedicineStore: Boolean = false,
) {
    fun toEntity() = WarehouseEntity(id = id, name = name, isMedicineStore = isMedicineStore, updatedAt = 0L, deviceId = "")

    companion object {
        fun from(w: WarehouseEntity) = WarehouseForm(w.id, false, w.name, w.isMedicineStore)
    }
}

@HiltViewModel
class WarehouseViewModel @Inject constructor(
    private val repo: MasterRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canEdit: Boolean = session.can(Permission.MASTER_EDIT)
    val warehouses: StateFlow<List<WarehouseEntity>> =
        repo.observeWarehouses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _editing = MutableStateFlow<WarehouseForm?>(null)
    val editing: StateFlow<WarehouseForm?> = _editing.asStateFlow()

    fun startNew() { clearMessage(); _editing.value = WarehouseForm() }
    fun startEdit(w: WarehouseEntity) { clearMessage(); _editing.value = WarehouseForm.from(w) }
    fun dismiss() { clearMessage(); _editing.value = null }
    fun update(f: (WarehouseForm) -> WarehouseForm) { _editing.update { it?.let(f) } }
    fun save() {
        val form = _editing.value ?: return
        launchSafe(onDone = { _editing.value = null }) { repo.saveWarehouse(form.toEntity()) }
    }
    fun delete(id: String) = launchSafe { repo.deleteWarehouse(id) }
}

// ---------------- ইউনিট ----------------

data class UnitForm(
    val id: String = newId(),
    val isNew: Boolean = true,
    val name: String = "",
    val symbol: String = "",
    val factorGrams: String = "",
) {
    fun toEntity(): UnitEntity {
        val f = parseNum(factorGrams) ?: throw IllegalArgumentException("গ্রামে রূপান্তর গুণক সঠিক সংখ্যা নয়")
        return UnitEntity(id = id, name = name, symbol = symbol, toBaseFactor = f, updatedAt = 0L, deviceId = "")
    }

    companion object {
        fun from(u: UnitEntity) = UnitForm(
            u.id, false, u.name, u.symbol,
            if (u.toBaseFactor == Math.floor(u.toBaseFactor)) u.toBaseFactor.toLong().toString() else u.toBaseFactor.toString(),
        )
    }
}

@HiltViewModel
class UnitViewModel @Inject constructor(
    private val repo: MasterRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canEdit: Boolean = session.can(Permission.MASTER_EDIT)
    val units: StateFlow<List<UnitEntity>> =
        repo.observeUnits().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _editing = MutableStateFlow<UnitForm?>(null)
    val editing: StateFlow<UnitForm?> = _editing.asStateFlow()

    fun startNew() { clearMessage(); _editing.value = UnitForm() }
    fun startEdit(u: UnitEntity) { clearMessage(); _editing.value = UnitForm.from(u) }
    fun dismiss() { clearMessage(); _editing.value = null }
    fun update(f: (UnitForm) -> UnitForm) { _editing.update { it?.let(f) } }
    fun save() {
        val form = _editing.value ?: return
        launchSafe(onDone = { _editing.value = null }) { repo.saveUnit(form.toEntity()) }
    }
    fun delete(id: String) = launchSafe { repo.deleteUnit(id) }
}
