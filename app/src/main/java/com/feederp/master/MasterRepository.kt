package com.feederp.master

import com.feederp.accounts.LedgerRepository
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.data.ItemDao
import com.feederp.data.ItemEntity
import com.feederp.data.PartyDao
import com.feederp.data.PartyEntity
import com.feederp.data.StockMovementDao
import com.feederp.data.UnitDao
import com.feederp.data.UnitEntity
import com.feederp.data.WarehouseDao
import com.feederp.data.WarehouseEntity
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.StockCalc
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** আইটেম, পার্টি, গুদাম ও ইউনিটের তৈরি/সম্পাদনা/সফট-ডিলিট। লিখতে MASTER_EDIT লাগে। */
@Singleton
class MasterRepository @Inject constructor(
    private val unitDao: UnitDao,
    private val itemDao: ItemDao,
    private val partyDao: PartyDao,
    private val warehouseDao: WarehouseDao,
    private val movementDao: StockMovementDao,
    private val ledger: LedgerRepository,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    fun observeUnits(): Flow<List<UnitEntity>> = unitDao.observeAll()
    fun observeItems(): Flow<List<ItemEntity>> = itemDao.observeAll()
    fun observeParties(): Flow<List<PartyEntity>> = partyDao.observeAll()
    fun observeWarehouses(): Flow<List<WarehouseEntity>> = warehouseDao.observeAll()

    suspend fun getItem(id: String): ItemEntity? = itemDao.getById(id)?.takeIf { it.deletedAt == null }
    suspend fun getParty(id: String): PartyEntity? = partyDao.getById(id)?.takeIf { it.deletedAt == null }
    suspend fun getWarehouse(id: String): WarehouseEntity? = warehouseDao.getById(id)?.takeIf { it.deletedAt == null }
    suspend fun getUnit(id: String): UnitEntity? = unitDao.getById(id)?.takeIf { it.deletedAt == null }
    suspend fun units(): List<UnitEntity> = unitDao.all()

    // ---------------- আইটেম ----------------

    /** নতুন (নতুন id) বা বিদ্যমান আইটেম সংরক্ষণ; `updatedAt`/`deviceId` এখানে বসে। */
    suspend fun saveItem(item: ItemEntity) {
        session.require(Permission.MASTER_EDIT)
        val clean = item.copy(
            code = item.code.trim().uppercase(), name = item.name.trim(),
            nameBn = item.nameBn?.trim()?.ifBlank { null },
            activeIngredient = item.activeIngredient?.trim()?.ifBlank { null },
            storageNote = item.storageNote?.trim()?.ifBlank { null },
        )
        MasterValidator.validateItem(clean)?.let { throw IllegalArgumentException(it) }
        val unit = unitDao.getById(clean.unitId)
        require(unit != null && unit.deletedAt == null) { "ইউনিট পাওয়া যায়নি" }
        val sameCode = itemDao.getByCode(clean.code)
        require(sameCode == null || sameCode.id == clean.id) { "এই কোড (${clean.code}) আগেই ব্যবহার করা হয়েছে" }
        val existing = itemDao.getById(clean.id)
        itemDao.upsert(clean.copy(updatedAt = stamper.now(), deviceId = stamper.deviceId, deletedAt = null))
        audit.log(if (existing == null) "ITEM_CREATE" else "ITEM_UPDATE", "items", clean.id, "${clean.code} ${clean.name}")
    }

    /** সফট ডিলিট; স্টক থাকলে ব্লক। */
    suspend fun deleteItem(id: String) {
        session.require(Permission.MASTER_EDIT)
        val item = itemDao.getById(id)
        require(item != null && item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
        val stock = movementDao.sumGrams(id, null, null)
        require(kotlin.math.abs(stock) <= StockCalc.EPS) { "স্টক থাকা আইটেম মোছা যাবে না" }
        itemDao.upsert(item.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("ITEM_DELETE", "items", id, "${item.code} ${item.name}")
    }

    // ---------------- পার্টি ----------------

    suspend fun saveParty(party: PartyEntity) {
        session.require(Permission.MASTER_EDIT)
        val clean = party.copy(
            name = party.name.trim(), phone = party.phone?.trim()?.ifBlank { null },
            address = party.address?.trim()?.ifBlank { null },
        )
        MasterValidator.validateParty(clean.name, clean.phone, clean.type, clean.creditLimitPaisa)
            ?.let { throw IllegalArgumentException(it) }
        val existing = partyDao.getById(clean.id)
        if (existing != null && existing.creditLimitPaisa != clean.creditLimitPaisa) {
            audit.log("CREDIT_LIMIT_CHANGE", "parties", clean.id, "${existing.creditLimitPaisa} -> ${clean.creditLimitPaisa} পয়সা")
        }
        partyDao.upsert(clean.copy(updatedAt = stamper.now(), deviceId = stamper.deviceId, deletedAt = null))
        audit.log(if (existing == null) "PARTY_CREATE" else "PARTY_UPDATE", "parties", clean.id, "${clean.type} ${clean.name}")
    }

    /** সফট ডিলিট; লেজার ব্যালেন্স বাকি থাকলে ব্লক। */
    suspend fun deleteParty(id: String) {
        session.require(Permission.MASTER_EDIT)
        val p = partyDao.getById(id)
        require(p != null && p.deletedAt == null) { "পার্টি পাওয়া যায়নি" }
        require(ledger.partyBalancePaisa(id) == 0L) { "বাকি/পাওনা থাকা পার্টি মোছা যাবে না" }
        partyDao.upsert(p.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("PARTY_DELETE", "parties", id, p.name)
    }

    // ---------------- গুদাম ----------------

    suspend fun saveWarehouse(w: WarehouseEntity) {
        session.require(Permission.MASTER_EDIT)
        val clean = w.copy(name = w.name.trim())
        MasterValidator.validateWarehouse(clean.name)?.let { throw IllegalArgumentException(it) }
        val existing = warehouseDao.getById(clean.id)
        warehouseDao.upsert(clean.copy(updatedAt = stamper.now(), deviceId = stamper.deviceId, deletedAt = null))
        audit.log(if (existing == null) "WAREHOUSE_CREATE" else "WAREHOUSE_UPDATE", "warehouses", clean.id, clean.name)
    }

    /** সফট ডিলিট; স্টক থাকলে ব্লক। */
    suspend fun deleteWarehouse(id: String) {
        session.require(Permission.MASTER_EDIT)
        val w = warehouseDao.getById(id)
        require(w != null && w.deletedAt == null) { "গুদাম পাওয়া যায়নি" }
        require(movementDao.nonZeroItemCountForWarehouse(id) == 0) { "স্টক থাকা গুদাম মোছা যাবে না" }
        warehouseDao.upsert(w.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("WAREHOUSE_DELETE", "warehouses", id, w.name)
    }

    // ---------------- ইউনিট ----------------

    suspend fun saveUnit(u: UnitEntity) {
        session.require(Permission.MASTER_EDIT)
        val clean = u.copy(name = u.name.trim(), symbol = u.symbol.trim())
        MasterValidator.validateUnit(clean.name, clean.symbol, clean.toBaseFactor)?.let { throw IllegalArgumentException(it) }
        val existing = unitDao.getById(clean.id)
        unitDao.upsert(clean.copy(updatedAt = stamper.now(), deviceId = stamper.deviceId, deletedAt = null))
        audit.log(if (existing == null) "UNIT_CREATE" else "UNIT_UPDATE", "units", clean.id, clean.name)
    }

    /** সফট ডিলিট; কোনো আইটেম ব্যবহার করলে ব্লক। */
    suspend fun deleteUnit(id: String) {
        session.require(Permission.MASTER_EDIT)
        val u = unitDao.getById(id)
        require(u != null && u.deletedAt == null) { "ইউনিট পাওয়া যায়নি" }
        require(itemDao.countByUnit(id) == 0) { "আইটেমে ব্যবহৃত ইউনিট মোছা যাবে না" }
        unitDao.upsert(u.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("UNIT_DELETE", "units", id, u.name)
    }
}
