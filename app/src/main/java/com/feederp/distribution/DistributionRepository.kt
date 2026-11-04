package com.feederp.distribution

import androidx.room.withTransaction
import com.feederp.accounts.LedgerLine
import com.feederp.accounts.LedgerRepository
import com.feederp.core.AccountCodes
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.distribution.CostKinds
import com.feederp.data.distribution.DeliveryDao
import com.feederp.data.distribution.DeliveryPodEntity
import com.feederp.data.distribution.DistStatus
import com.feederp.data.distribution.DriverDao
import com.feederp.data.distribution.DriverEntity
import com.feederp.data.distribution.TransportCostEntity
import com.feederp.data.distribution.TripDao
import com.feederp.data.distribution.TripEntity
import com.feederp.data.distribution.TripStopEntity
import com.feederp.data.distribution.VehicleDao
import com.feederp.data.distribution.VehicleEntity
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * গাড়ি, ড্রাইভার, ট্রিপ (একাধিক ডেলিভারি নোট একটি গাড়িতে), স্টপে ডেলিভারি/ব্যর্থ, পরিবহন খরচ।
 * স্টকের নড়াচড়া ও COGS [DeliveryRepository]-তেই হয়; এখানে শুধু ট্রিপ-পরিচালনা।
 * এখনও নেই: ক্যাশ-মিলানো (cash reconciliation) স্ক্রিন, GPS ট্র্যাকিং, এলাকা (territory) ব্যবস্থাপনা — ডেটা-টেবিল আছে, লজিক/স্ক্রিন নেই।
 * পরিবহন খরচ লেজারে পোস্ট হয় (Dr পরিবহন খরচ, Cr নগদ) কিন্তু খরচ-সারিতে ভাউচার আইডি রাখা হয় না, তাই খরচ এন্ট্রি পরে রিভার্স করা যায় না।
 */
@Singleton
class DistributionRepository @Inject constructor(
    private val db: AppDatabase,
    private val vehicleDao: VehicleDao,
    private val driverDao: DriverDao,
    private val tripDao: TripDao,
    private val deliveryDao: DeliveryDao,
    private val delivery: DeliveryRepository,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    fun observeVehicles(): Flow<List<VehicleEntity>> = vehicleDao.observeAll()
    fun observeDrivers(): Flow<List<DriverEntity>> = driverDao.observeAll()
    fun observeTrips(): Flow<List<TripEntity>> = tripDao.observeTrips()
    fun observeStops(tripId: String): Flow<List<TripStopEntity>> = tripDao.observeStopsOf(tripId)
    fun observeCosts(tripId: String): Flow<List<TransportCostEntity>> = tripDao.observeCosts(tripId)
    fun observeNotes() = delivery.observeNotes()
    suspend fun trip(id: String): TripEntity? = tripDao.getTrip(id)

    // ---------------- গাড়ি ও ড্রাইভার ----------------

    suspend fun saveVehicle(
        id: String?, regNo: String, type: String, capacityKg: Double, ownership: String, ownerName: String?, active: Boolean,
    ): String {
        session.require(Permission.DISTRIBUTION)
        val reg = regNo.trim()
        require(reg.isNotEmpty()) { "গাড়ির নম্বর লিখুন" }
        require(type.isNotBlank()) { "গাড়ির ধরন লিখুন" }
        require(capacityKg.isFinite() && capacityKg >= 0) { "ধারণক্ষমতা ঠিক নয়" }
        require(ownership == DistStatus.OWN || ownership == DistStatus.HIRED) { "মালিকানা ঠিক নয়" }
        val dup = vehicleDao.getByRegNo(reg)
        require(dup == null || dup.id == id) { "এই নম্বরের গাড়ি আগেই আছে" }
        val old = id?.let { vehicleDao.getById(it) }
        val vid = id ?: newId()
        vehicleDao.upsert(
            (old ?: VehicleEntity(id = vid, regNo = reg, type = type, capacityGrams = 0.0, updatedAt = 0, deviceId = "")).copy(
                regNo = reg, type = type.trim(), capacityGrams = capacityKg * 1000.0, ownership = ownership,
                ownerName = ownerName?.trim()?.ifBlank { null }, active = active,
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("SAVE_VEHICLE", "vehicles", vid, reg)
        return vid
    }

    suspend fun saveDriver(id: String?, name: String, licenseNo: String?, phone: String?, active: Boolean): String {
        session.require(Permission.DISTRIBUTION)
        require(name.isNotBlank()) { "ড্রাইভারের নাম লিখুন" }
        val old = id?.let { driverDao.getById(it) }
        val did = id ?: newId()
        driverDao.upsert(
            (old ?: DriverEntity(id = did, name = name, updatedAt = 0, deviceId = "")).copy(
                name = name.trim(), licenseNo = licenseNo?.trim()?.ifBlank { null }, phone = phone?.trim()?.ifBlank { null },
                active = active, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("SAVE_DRIVER", "drivers", did, name)
        return did
    }

    // ---------------- ট্রিপ ----------------

    /** প্ল্যান করা (এখনও কোনো ট্রিপে নেই, লোড হয়নি) নোট দিয়ে নতুন ট্রিপ। গাড়ির ক্ষমতার বেশি মাল ঢোকানো যায় না। */
    suspend fun createTrip(
        vehicleId: String, driverId: String, route: String, plannedDate: Long, noteIds: List<String>, note: String?,
    ): String {
        session.require(Permission.DISTRIBUTION)
        val userId = session.userId
        require(noteIds.isNotEmpty()) { "কমপক্ষে একটি ডেলিভারি নোট বাছাই করুন" }
        require(route.isNotBlank()) { "রুট/এলাকার নাম লিখুন" }
        val vehicle = vehicleDao.getById(vehicleId)
        require(vehicle != null && vehicle.active && vehicle.deletedAt == null) { "চালু গাড়ি বাছাই করুন" }
        val driver = driverDao.getById(driverId)
        require(driver != null && driver.active && driver.deletedAt == null) { "চালু ড্রাইভার বাছাই করুন" }
        val id = newId()
        val tripNo = docNumbers.next("TRP")
        db.withTransaction {
            val notes = deliveryDao.getNotes(noteIds.distinct())
            require(notes.size == noteIds.distinct().size) { "কোনো ডেলিভারি নোট পাওয়া যায়নি" }
            notes.forEach {
                require(it.tripId == null && it.loadedAt == null && it.deliveredAt == null) { "${it.challanNo}: এই নোট আগেই ট্রিপে/লোডে আছে" }
            }
            require(notes.map { it.warehouseId }.distinct().size == 1) { "একটি ট্রিপে একই গুদামের নোট থাকতে হবে" }
            val totalGrams = deliveryDao.linesOfNotes(notes.map { it.id }).sumOf { it.plannedGrams }
            if (vehicle.capacityGrams > 0) {
                require(totalGrams <= vehicle.capacityGrams + 0.5) {
                    "মোট মাল ${kg(totalGrams)} কেজি, গাড়ির ক্ষমতা ${kg(vehicle.capacityGrams)} কেজি — কিছু নোট বাদ দিন"
                }
            }
            val now = stamper.now()
            tripDao.upsertTrip(
                TripEntity(
                    id = id, tripNo = tripNo, vehicleId = vehicleId, driverId = driverId, warehouseId = notes.first().warehouseId,
                    route = route.trim(), plannedDate = plannedDate, note = note?.trim()?.ifBlank { null },
                    createdBy = userId, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            tripDao.upsertStops(
                notes.sortedBy { it.dueDate ?: Long.MAX_VALUE }.mapIndexed { i, n ->
                    TripStopEntity(id = newId(), tripId = id, deliveryNoteId = n.id, sequence = i + 1, updatedAt = now, deviceId = stamper.deviceId)
                }
            )
            notes.forEach {
                deliveryDao.upsertNote(it.copy(tripId = id, vehicleNo = vehicle.regNo, driverName = driver.name, updatedAt = now, deviceId = stamper.deviceId))
            }
        }
        audit.log("CREATE_TRIP", "trips", id, "$tripNo ${route.trim()}")
        return id
    }

    /** ট্রিপের সব নোটের মাল লোড (প্রতি নোটে FEFO, স্টক কমে)। মাঝে ঘাটতি হলে আগের নোটগুলো লোডেই থাকে; ঘাটতি মিটিয়ে আবার চাপলে বাকিগুলো হয়। */
    suspend fun loadTrip(tripId: String) {
        session.require(Permission.DELIVERY_DISPATCH)
        val trip = requireNotNull(tripDao.getTrip(tripId)) { "ট্রিপ পাওয়া যায়নি" }
        require(trip.status == DistStatus.TRIP_PLANNED || trip.status == DistStatus.TRIP_LOADED) { "এই ট্রিপে আর লোড করা যায় না" }
        for (stop in tripDao.stopsOf(tripId)) {
            val n = deliveryDao.getNote(stop.deliveryNoteId) ?: continue
            if (n.loadedAt == null) delivery.loadNote(n.id)
        }
        val now = stamper.now()
        tripDao.upsertTrip(trip.copy(status = DistStatus.TRIP_LOADED, loadedAt = now, updatedAt = now, deviceId = stamper.deviceId))
        audit.log("LOAD_TRIP", "trips", tripId, trip.tripNo)
    }

    suspend fun startTrip(tripId: String, startKm: Double?) {
        session.require(Permission.DISTRIBUTION)
        val trip = requireNotNull(tripDao.getTrip(tripId)) { "ট্রিপ পাওয়া যায়নি" }
        require(trip.status == DistStatus.TRIP_LOADED) { "আগে মাল লোড করুন" }
        require(startKm == null || (startKm.isFinite() && startKm >= 0)) { "শুরুর কিমি ঠিক নয়" }
        val now = stamper.now()
        tripDao.upsertTrip(trip.copy(status = DistStatus.TRIP_ONROAD, startKm = startKm, startedAt = now, updatedAt = now, deviceId = stamper.deviceId))
        audit.log("START_TRIP", "trips", tripId, trip.tripNo)
    }

    /** স্টপে ডেলিভারি নিশ্চিত + ডেলিভারির প্রমাণ (গ্রহীতা)। [deliveredGrams]: লাইন আইডি → প্রকৃত; ফাঁকা = পুরোটা। */
    suspend fun deliverStop(stopId: String, receiverName: String, receiverPhone: String?, deliveredGrams: Map<String, Double>) {
        session.require(Permission.DELIVERY_DISPATCH)
        val userId = session.userId
        val stop = requireNotNull(tripDao.getStop(stopId)) { "স্টপ পাওয়া যায়নি" }
        val trip = requireNotNull(tripDao.getTrip(stop.tripId)) { "ট্রিপ পাওয়া যায়নি" }
        require(trip.status == DistStatus.TRIP_ONROAD) { "ট্রিপ রাস্তায় নেই" }
        require(stop.status != DistStatus.STOP_DELIVERED) { "এই স্টপের ডেলিভারি আগেই নিশ্চিত হয়েছে" }
        delivery.confirmDelivery(stop.deliveryNoteId, receiverName, deliveredGrams)
        val now = stamper.now()
        db.withTransaction {
            deliveryDao.insertPod(
                DeliveryPodEntity(
                    id = newId(), deliveryNoteId = stop.deliveryNoteId, tripId = trip.id, receiverName = receiverName.trim(),
                    receiverPhone = receiverPhone?.trim()?.ifBlank { null }, deliveredAt = now,
                    createdBy = userId, createdAt = now, deviceId = stamper.deviceId,
                )
            )
            tripDao.upsertStops(listOf(stop.copy(status = DistStatus.STOP_DELIVERED, arrivedAt = now, failReason = null, updatedAt = now, deviceId = stamper.deviceId)))
        }
    }

    suspend fun failStop(stopId: String, reason: String) {
        session.require(Permission.DELIVERY_DISPATCH)
        require(reason.isNotBlank()) { "ব্যর্থতার কারণ লিখুন" }
        val stop = requireNotNull(tripDao.getStop(stopId)) { "স্টপ পাওয়া যায়নি" }
        require(stop.status != DistStatus.STOP_DELIVERED) { "ডেলিভারি হয়ে যাওয়া স্টপ ব্যর্থ করা যায় না" }
        tripDao.upsertStops(listOf(stop.copy(status = DistStatus.STOP_FAILED, failReason = reason.trim(), updatedAt = stamper.now(), deviceId = stamper.deviceId)))
    }

    /**
     * ট্রিপ শেষ। ডেলিভারি না হওয়া নোটের মাল (লোড হয়ে গাড়িতে থাকা) স্টকে ফেরে (ডেলিভারি ০ ধরে নিশ্চিত); ইনভয়েসে আবার প্ল্যান করা যায়।
     */
    suspend fun completeTrip(tripId: String, endKm: Double?) {
        session.require(Permission.DISTRIBUTION)
        val trip = requireNotNull(tripDao.getTrip(tripId)) { "ট্রিপ পাওয়া যায়নি" }
        require(trip.status == DistStatus.TRIP_ONROAD) { "ট্রিপ রাস্তায় নেই" }
        require(endKm == null || (endKm.isFinite() && endKm >= (trip.startKm ?: 0.0))) { "শেষের কিমি শুরুর কিমির কম হতে পারে না" }
        for (stop in tripDao.stopsOf(tripId)) {
            if (stop.status == DistStatus.STOP_DELIVERED) continue
            val n = deliveryDao.getNote(stop.deliveryNoteId) ?: continue
            if (n.loadedAt != null && n.deliveredAt == null) {
                val zero = deliveryDao.linesOf(n.id).associate { it.id to 0.0 }
                delivery.confirmDelivery(n.id, "ডেলিভারি হয়নি — স্টকে ফেরত", zero)
            }
            if (stop.status == DistStatus.STOP_PENDING) {
                tripDao.upsertStops(listOf(stop.copy(status = DistStatus.STOP_FAILED, failReason = "ট্রিপ শেষে ডেলিভারি হয়নি", updatedAt = stamper.now(), deviceId = stamper.deviceId)))
            }
        }
        val now = stamper.now()
        tripDao.upsertTrip(trip.copy(status = DistStatus.TRIP_COMPLETED, endKm = endKm, completedAt = now, updatedAt = now, deviceId = stamper.deviceId))
        audit.log("COMPLETE_TRIP", "trips", tripId, trip.tripNo)
    }

    // ---------------- খরচ ----------------

    /** ট্রিপের পরিবহন খরচ (নগদ)। লেজারে Dr পরিবহন খরচ / Cr নগদ। */
    suspend fun addCost(tripId: String, kind: String, amountPaisa: Long, litres: Double?, note: String?) {
        session.require(Permission.DISTRIBUTION)
        val userId = session.userId
        require(kind in CostKinds.all) { "খরচের ধরন ঠিক নয়" }
        require(amountPaisa > 0) { "অঙ্ক ০ এর বেশি হতে হবে" }
        require(litres == null || (litres.isFinite() && litres > 0)) { "লিটার ঠিক নয়" }
        val trip = requireNotNull(tripDao.getTrip(tripId)) { "ট্রিপ পাওয়া যায়নি" }
        require(trip.status != DistStatus.TRIP_CANCELLED) { "বাতিল ট্রিপে খরচ হয় না" }
        val id = newId()
        db.withTransaction {
            val now = stamper.now()
            tripDao.upsertCost(
                TransportCostEntity(
                    id = id, tripId = tripId, kind = kind, amountPaisa = amountPaisa, litres = litres,
                    note = note?.trim()?.ifBlank { null }, incurredAt = now, createdBy = userId, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            ledger.post(
                voucherType = "TRANSPORT_COST", refType = "TRANSPORT_COST", refId = id,
                memo = "পরিবহন খরচ ${CostKinds.labelBn(kind)}, ট্রিপ ${trip.tripNo}",
                lines = listOf(
                    LedgerLine(AccountCodes.TRANSPORT_EXPENSE, debitPaisa = amountPaisa),
                    LedgerLine(AccountCodes.CASH, creditPaisa = amountPaisa),
                ),
                entryDate = now,
            )
        }
        audit.log("TRANSPORT_COST", "transport_costs", id, "${trip.tripNo} $kind $amountPaisa")
    }

    private fun kg(grams: Double): String = String.format(java.util.Locale.US, "%.1f", grams / 1000.0)
}
