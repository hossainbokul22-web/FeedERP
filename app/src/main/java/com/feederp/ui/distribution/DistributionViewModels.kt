package com.feederp.ui.distribution

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.PartyDao
import com.feederp.data.distribution.DeliveryLineEntity
import com.feederp.data.distribution.DeliveryNoteEntity
import com.feederp.data.distribution.DistStatus
import com.feederp.data.distribution.DriverEntity
import com.feederp.data.distribution.TransportCostEntity
import com.feederp.data.distribution.TripEntity
import com.feederp.data.distribution.TripStopEntity
import com.feederp.data.distribution.VehicleEntity
import com.feederp.distribution.DeliveryRepository
import com.feederp.distribution.DistributionRepository
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

fun tripStatusBn(s: String) = when (s) {
    DistStatus.TRIP_PLANNED -> "প্ল্যান করা"; DistStatus.TRIP_LOADED -> "লোড হয়েছে"; DistStatus.TRIP_ONROAD -> "রাস্তায়"
    DistStatus.TRIP_COMPLETED -> "সম্পন্ন"; DistStatus.TRIP_CANCELLED -> "বাতিল"; else -> s
}

fun stopStatusBn(s: String) = when (s) {
    DistStatus.STOP_PENDING -> "বাকি"; DistStatus.STOP_DELIVERED -> "ডেলিভারি হয়েছে"; DistStatus.STOP_FAILED -> "ব্যর্থ"; else -> s
}

fun kgText(grams: Double): String = String.format(java.util.Locale.US, "%.3f", grams / 1000.0).trimEnd('0').trimEnd('.')

@HiltViewModel
class TripListViewModel @Inject constructor(repo: DistributionRepository, private val session: SessionManager) : BaseViewModel() {
    val trips: StateFlow<List<TripEntity>> = repo.observeTrips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canManage: Boolean get() = session.can(Permission.DISTRIBUTION)
}

data class NoteOption(val note: DeliveryNoteEntity, val partyName: String)

@HiltViewModel
class NewTripViewModel @Inject constructor(
    private val repo: DistributionRepository,
    partyDao: PartyDao,
) : BaseViewModel() {
    val vehicles: StateFlow<List<VehicleEntity>> = repo.observeVehicles().map { l -> l.filter { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val drivers: StateFlow<List<DriverEntity>> = repo.observeDrivers().map { l -> l.filter { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** ট্রিপে যোগ করার প্রার্থী: প্ল্যান করা কিন্তু কোনো ট্রিপে/লোডে নেই। */
    val notes: StateFlow<List<NoteOption>> = combine(repo.observeNotes(), partyDao.observeAll()) { notes, parties ->
        val names = parties.associate { it.id to it.name }
        notes.filter { it.tripId == null && it.loadedAt == null && it.deliveredAt == null }
            .map { NoteOption(it, names[it.partyId] ?: "?") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    fun toggle(id: String) = _selected.update { if (id in it) it - id else it + id }

    fun create(vehicleId: String?, driverId: String?, route: String, note: String, onCreated: (String) -> Unit) = launchSafe {
        val id = repo.createTrip(
            vehicleId ?: throw IllegalArgumentException("গাড়ি বাছাই করুন"),
            driverId ?: throw IllegalArgumentException("ড্রাইভার বাছাই করুন"),
            route, System.currentTimeMillis(), _selected.value.toList(), note,
        )
        onCreated(id)
    }
}

data class StopRow(val stop: TripStopEntity, val note: DeliveryNoteEntity?, val partyName: String, val lines: List<DeliveryLineEntity>)

data class TripDetailState(
    val trip: TripEntity? = null,
    val vehicle: String = "",
    val driver: String = "",
    val stops: List<StopRow> = emptyList(),
    val itemNames: Map<String, String> = emptyMap(),
    val costs: List<TransportCostEntity> = emptyList(),
)

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    private val repo: DistributionRepository,
    private val delivery: DeliveryRepository,
    partyDao: PartyDao,
    itemDao: com.feederp.data.ItemDao,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val tripId: String = requireNotNull(savedState.get<String>("id")) { "ট্রিপ id নেই" }
    private val refresh = MutableStateFlow(0)
    val canManage: Boolean get() = session.can(Permission.DISTRIBUTION)
    val canDispatch: Boolean get() = session.can(Permission.DELIVERY_DISPATCH)

    val state: StateFlow<TripDetailState> = combine(
        repo.observeTrips().map { l -> l.firstOrNull { it.id == tripId } },
        repo.observeStops(tripId), repo.observeNotes(), repo.observeCosts(tripId),
        combine(repo.observeVehicles(), repo.observeDrivers(), partyDao.observeAll(), itemDao.observeAll(), refresh) { v, d, p, i, _ -> arrayOf<Any>(v, d, p, i) },
    ) { trip, stops, notes, costs, ref ->
        @Suppress("UNCHECKED_CAST") val vehicles = ref[0] as List<VehicleEntity>
        @Suppress("UNCHECKED_CAST") val drivers = ref[1] as List<DriverEntity>
        @Suppress("UNCHECKED_CAST") val parties = ref[2] as List<com.feederp.data.PartyEntity>
        @Suppress("UNCHECKED_CAST") val items = ref[3] as List<com.feederp.data.ItemEntity>
        val byId = notes.associateBy { it.id }
        TripDetailState(
            trip = trip,
            vehicle = vehicles.firstOrNull { it.id == trip?.vehicleId }?.regNo.orEmpty(),
            driver = drivers.firstOrNull { it.id == trip?.driverId }?.name.orEmpty(),
            stops = stops.map { s ->
                val n = byId[s.deliveryNoteId]
                StopRow(s, n, parties.firstOrNull { it.id == n?.partyId }?.name ?: "?", delivery.lines(s.deliveryNoteId))
            },
            itemNames = items.associate { it.id to it.name },
            costs = costs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailState())

    private fun reload() { refresh.value += 1 }

    fun load() = launchSafe { repo.loadTrip(tripId); reload() }

    fun start(startKm: String) = launchSafe {
        val km = if (startKm.isBlank()) null else parseNum(startKm) ?: throw IllegalArgumentException("কিমি সংখ্যায় দিন")
        repo.startTrip(tripId, km); reload()
    }

    fun complete(endKm: String, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        val km = if (endKm.isBlank()) null else parseNum(endKm) ?: throw IllegalArgumentException("কিমি সংখ্যায় দিন")
        repo.completeTrip(tripId, km); reload()
    }

    /** [deliveredKg]: লাইন আইডি → কেজি (ফাঁকা = পুরোটা)। */
    fun deliver(stopId: String, receiver: String, phone: String, deliveredKg: Map<String, String>, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        val grams = HashMap<String, Double>()
        for ((id, t) in deliveredKg) {
            if (t.isBlank()) continue
            grams[id] = (parseNum(t) ?: throw IllegalArgumentException("ডেলিভারির পরিমাণ সংখ্যায় দিন")) * 1000.0
        }
        repo.deliverStop(stopId, receiver, phone, grams); reload()
    }

    fun fail(stopId: String, reason: String, onDone: () -> Unit) = launchSafe(onDone = onDone) { repo.failStop(stopId, reason); reload() }

    fun addCost(kind: String, amountTaka: String, litres: String, note: String, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        val amount = parseTakaToPaisa(amountTaka)?.takeIf { it > 0 } ?: throw IllegalArgumentException("অঙ্ক সংখ্যায় দিন")
        val l = if (litres.isBlank()) null else parseNum(litres) ?: throw IllegalArgumentException("লিটার সংখ্যায় দিন")
        repo.addCost(tripId, kind, amount, l, note)
    }
}

@HiltViewModel
class FleetViewModel @Inject constructor(private val repo: DistributionRepository, private val session: SessionManager) : BaseViewModel() {
    val vehicles: StateFlow<List<VehicleEntity>> = repo.observeVehicles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val drivers: StateFlow<List<DriverEntity>> = repo.observeDrivers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canManage: Boolean get() = session.can(Permission.DISTRIBUTION)

    fun saveVehicle(id: String?, reg: String, type: String, capKg: String, hired: Boolean, owner: String, active: Boolean, onDone: () -> Unit) =
        launchSafe(onDone = onDone) {
            val cap = if (capKg.isBlank()) 0.0 else parseNum(capKg) ?: throw IllegalArgumentException("ক্ষমতা সংখ্যায় দিন")
            repo.saveVehicle(id, reg, type, cap, if (hired) DistStatus.HIRED else DistStatus.OWN, owner, active)
        }

    fun saveDriver(id: String?, name: String, license: String, phone: String, active: Boolean, onDone: () -> Unit) =
        launchSafe(onDone = onDone) { repo.saveDriver(id, name, license, phone, active) }
}
