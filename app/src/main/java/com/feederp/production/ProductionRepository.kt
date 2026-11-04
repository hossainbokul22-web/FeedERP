package com.feederp.production

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
import com.feederp.data.ItemDao
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.MovementType
import com.feederp.data.production.BatchConsumptionEntity
import com.feederp.data.production.BatchMachineRunEntity
import com.feederp.data.production.BatchStatus
import com.feederp.data.production.CleaningChecklistItemEntity
import com.feederp.data.production.ProductionBatchEntity
import com.feederp.data.production.ProductionDao
import com.feederp.formulation.FormulaRepository
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.LotStock
import com.feederp.inventory.QcStatus
import com.feederp.inventory.StockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** নতুন ব্যাচের ইনপুট। ওজন গ্রামে। */
data class NewBatchInput(
    val formulaId: String,
    val plannedQtyGrams: Double,
    val sourceWarehouseId: String,
    val fgWarehouseId: String,
    val bagSizeKg: Double = 0.0,
    val shelfLifeDays: Int = 90,
    val note: String? = null,
)

/** ব্যাচ শুরুর আগের পূর্বরূপ: কোন লট থেকে কত যাবে, ঘাটতি আছে কি না। */
data class BatchPreview(
    val lines: List<ProductionPlanner.PlanLine>,
    val shortageMessage: String?,
    /** lotId → লটের ব্যাচ নম্বর (UI তে দেখানোর জন্য)। */
    val lotBatchNo: Map<String, String>,
)

/**
 * প্রোডাকশন ব্যাচ: অনুমোদিত ফর্মুলা থেকে অর্ডার → FEFO কনজাম্পশন (এক ট্রানজ্যাকশনে) → মেশিন রান →
 * সম্পূর্ণ (FG লট, স্টক-ইন, কস্টিং, লেজার)। ওষুধযুক্ত ব্যাচের পর লাইন-ক্লিনিং শেষ না হলে নতুন ব্যাচ বানানো যায় না।
 *
 * সীমাবদ্ধতা: কনজাম্পশনের ইউনিট খরচ আইটেমের সামগ্রিক ওয়েটেড-অ্যাভারেজ (লট-ভিত্তিক খরচ নয়)।
 */
@Singleton
class ProductionRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: ProductionDao,
    private val itemDao: ItemDao,
    private val formulas: FormulaRepository,
    private val stock: StockRepository,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val audit: AuditRepository,
    private val stamper: Stamper,
    private val session: SessionManager,
) {
    // ---------------- পড়া ----------------
    fun observeBatches(): Flow<List<ProductionBatchEntity>> = dao.observeBatches()
    fun observeBatch(id: String): Flow<ProductionBatchEntity?> = dao.observeById(id)
    fun observeConsumption(batchId: String) = dao.observeConsumption(batchId)
    fun observeRuns(batchId: String) = dao.observeRuns(batchId)
    fun observeCleaning(batchId: String) = dao.observeCleaningItems(batchId)
    suspend fun getBatch(id: String): ProductionBatchEntity? = dao.getById(id)?.takeIf { it.deletedAt == null }

    // ---------------- ব্যাচ অর্ডার ----------------

    suspend fun createBatch(input: NewBatchInput): String {
        session.require(Permission.PRODUCTION)
        require(input.plannedQtyGrams > 0.0) { "পরিকল্পিত পরিমাণ ০ এর বেশি হতে হবে" }
        require(input.bagSizeKg >= 0.0) { "বস্তার সাইজ ঋণাত্মক হতে পারে না" }
        require(input.shelfLifeDays > 0) { "শেলফ-লাইফ কমপক্ষে ১ দিন হতে হবে" }
        require(input.sourceWarehouseId.isNotBlank() && input.fgWarehouseId.isNotBlank()) { "গুদাম বাছাই করুন" }
        val formula = formulas.getApproved(input.formulaId)
            ?: throw IllegalArgumentException("শুধু অনুমোদিত ফর্মুলা দিয়ে ব্যাচ বানানো যায়")
        if (dao.pendingCleaningCount() > 0) {
            throw IllegalStateException("আগের ওষুধযুক্ত ব্যাচের লাইন-ক্লিনিং বাকি। আগে ক্লিনিং চেকলিস্ট শেষ করুন।")
        }
        val id = newId()
        val now = stamper.now()
        val batchNo = docNumbers.next("BATCH")
        dao.upsertBatch(
            ProductionBatchEntity(
                id = id, batchNo = batchNo, formulaId = formula.id, formulaVersion = formula.version,
                productItemId = formula.productItemId, isMedicated = formula.isMedicated,
                plannedQtyGrams = input.plannedQtyGrams, sourceWarehouseId = input.sourceWarehouseId,
                fgWarehouseId = input.fgWarehouseId, bagSizeKg = input.bagSizeKg, shelfLifeDays = input.shelfLifeDays,
                status = BatchStatus.PLANNED, qcStatus = QcStatus.PENDING,
                cleaningRequired = formula.isMedicated, orderedAt = now, note = input.note?.trim()?.ifBlank { null },
                createdBy = session.userId, updatedAt = now, deviceId = stamper.deviceId,
            )
        )
        audit.log("BATCH_CREATE", "production_batch", id, "$batchNo ফর্মুলা ${formula.code} v${formula.version}")
        return id
    }

    /** শুরুর আগে কোন লট থেকে কত যাবে ও ঘাটতি আছে কি না (কিছু লেখে না)। */
    suspend fun preview(batchId: String): BatchPreview {
        val b = requireBatch(batchId)
        val ctx = buildPlan(b)
        return BatchPreview(ctx.plan, ProductionPlanner.shortageMessage(ctx.plan), ctx.lotBatchNo)
    }

    /**
     * ব্যাচ শুরু: সব উপাদান FEFO অনুযায়ী কনজাম্পশন (PRODUCTION_OUT) ও ট্রেসেবিলিটি রেকর্ড। ঘাটতি থাকলে কিছুই লেখা হয় না।
     * ওষুধ/নিয়ন্ত্রিত আইটেমে [Permission.INVENTORY_ISSUE_MEDICINE] লাগে (স্টক রিপোজিটরি যাচাই করে)।
     */
    suspend fun start(batchId: String) {
        session.require(Permission.PRODUCTION)
        db.withTransaction {
            val b = requireBatch(batchId)
            check(b.status == BatchStatus.PLANNED) { "শুধু পরিকল্পিত ব্যাচ শুরু করা যায়" }
            val ctx = buildPlan(b)
            ProductionPlanner.shortageMessage(ctx.plan)?.let { throw IllegalStateException(it) }
            val now = stamper.now()
            var inputGrams = 0.0
            for (line in ctx.plan) {
                val item = ctx.items[line.itemId]
                val avgCost = stock.weightedAvgCostPaisaPerKg(line.itemId)
                val traceable = item != null && isTraceable(item)
                if (line.allocations.isEmpty()) {
                    val mid = stock.record(
                        line.itemId, null, b.sourceWarehouseId, MovementType.PRODUCTION_OUT,
                        -line.requiredGrams, avgCost, "BATCH", b.id, "ব্যাচ ${b.batchNo}",
                    )
                    insertConsumption(b.id, line.itemId, null, null, line.requiredGrams, avgCost, traceable, mid, now)
                    inputGrams += line.requiredGrams
                } else {
                    for (a in line.allocations) {
                        val mid = stock.record(
                            line.itemId, a.lotId, b.sourceWarehouseId, MovementType.PRODUCTION_OUT,
                            -a.qtyGrams, avgCost, "BATCH", b.id, "ব্যাচ ${b.batchNo}",
                        )
                        insertConsumption(b.id, line.itemId, a.lotId, ctx.lotBatchNo[a.lotId], a.qtyGrams, avgCost, traceable, mid, now)
                        inputGrams += a.qtyGrams
                    }
                }
            }
            dao.upsertBatch(
                b.copy(
                    status = BatchStatus.IN_PROGRESS, startedAt = now, actualInputGrams = inputGrams,
                    updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            audit.log("BATCH_START", "production_batch", b.id, b.batchNo)
        }
    }

    /** মেশিন রান লগ (অ্যাপেন্ড-অনলি)। [hourlyCostPaisa] ব্যাচ-কস্টে ঘণ্টা × খরচ হিসেবে যোগ হয়। */
    suspend fun addMachineRun(
        batchId: String,
        machineId: String,
        machineName: String?,
        startAt: Long,
        endAt: Long,
        downtimeMinutes: Int = 0,
        downtimeReason: String? = null,
        hourlyCostPaisa: Long = 0L,
        note: String? = null,
    ) {
        session.require(Permission.PRODUCTION)
        require(machineId.isNotBlank()) { "মেশিন বাছাই করুন" }
        require(hourlyCostPaisa >= 0L) { "ঘণ্টা-খরচ ঋণাত্মক হতে পারে না" }
        val b = requireBatch(batchId)
        check(b.status == BatchStatus.IN_PROGRESS) { "চলমান ব্যাচে মেশিন রান যোগ করা যায়" }
        val hours = ProductionPlanner.netRunHours(startAt, endAt, downtimeMinutes)
        if (downtimeMinutes > 0) require(!downtimeReason.isNullOrBlank()) { "ডাউনটাইমের কারণ লিখুন" }
        dao.insertRun(
            BatchMachineRunEntity(
                id = newId(), batchId = batchId, machineId = machineId, machineName = machineName,
                startAt = startAt, endAt = endAt, runHours = hours, downtimeMinutes = downtimeMinutes,
                downtimeReason = downtimeReason?.trim()?.ifBlank { null }, hourlyCostPaisa = hourlyCostPaisa,
                note = note?.trim()?.ifBlank { null }, createdBy = session.userId,
                createdAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
    }

    /**
     * ব্যাচ সম্পূর্ণ: আউটপুটের FG লট তৈরি (আইটেমে QC লাগলে PENDING = বিক্রি বন্ধ), PRODUCTION_IN, ব্যাচ কস্টিং ও লেজার
     * (ডেবিট FG মজুদ; ক্রেডিট কাঁচামাল, লেবার, ওভারহেড)। ওষুধযুক্ত হলে ক্লিনিং চেকলিস্ট তৈরি হয়।
     */
    suspend fun complete(
        batchId: String,
        outputGoodGrams: Double,
        labourPaisa: Long = 0L,
        overheadPaisa: Long = 0L,
        packagingPaisa: Long = 0L,
    ) {
        session.require(Permission.PRODUCTION)
        require(outputGoodGrams > 0.0) { "আউটপুট ০ এর বেশি হতে হবে" }
        require(labourPaisa >= 0 && overheadPaisa >= 0 && packagingPaisa >= 0) { "খরচ ঋণাত্মক হতে পারে না" }
        db.withTransaction {
            val b = requireBatch(batchId)
            check(b.status == BatchStatus.IN_PROGRESS) { "শুধু চলমান ব্যাচ সম্পূর্ণ করা যায়" }
            val cons = dao.consumptionOf(b.id)
            val runs = dao.runsOf(b.id)
            val inputGrams = cons.filter { !it.isPackaging }.sumOf { it.qtyGrams }
            require(outputGoodGrams <= inputGrams * MAX_OUTPUT_OVER_INPUT) {
                "আউটপুট ইনপুটের চেয়ে ৫% এর বেশি হতে পারে না (ইনপুট ${"%.1f".format(inputGrams / 1000.0)} কেজি)। কেজি/গ্রাম ঠিক আছে কি দেখুন।"
            }

            val consumed: List<CostMaterialLine> = cons.map {
                CostMaterialLine(
                    it.itemId,
                    when {
                        it.isPackaging -> CostCategory.PACKAGING
                        it.isTraceable -> CostCategory.MEDICINE_PREMIX
                        else -> CostCategory.RAW
                    },
                    it.qtyGrams, it.unitCostPaisaPerKg,
                )
            }
            // ১ কেজি × packagingPaisa পয়সা/কেজি = ঠিক packagingPaisa পয়সা
            val packagingLine: List<CostMaterialLine> =
                if (packagingPaisa > 0) listOf(CostMaterialLine("packaging", CostCategory.PACKAGING, 1000.0, packagingPaisa))
                else emptyList()
            val materials: List<CostMaterialLine> = consumed + packagingLine

            val r = CostingCalc.compute(
                BatchCostInput(
                    materials = materials, labourPaisa = labourPaisa, electricityOverheadPaisa = overheadPaisa,
                    machineRuns = runs.map { MachineRunCost(it.runHours, it.hourlyCostPaisa) },
                    outputGoodGrams = outputGoodGrams, bagSizeKg = b.bagSizeKg,
                )
            )

            val fgItem = itemDao.getById(b.productItemId)
            val now = stamper.now()
            val expiry = ProductionPlanner.expiryAt(now, b.shelfLifeDays)
            val qc = if (fgItem?.requiresQc == false) QcStatus.PASSED else QcStatus.PENDING
            val lotId = stock.createLot(b.productItemId, b.batchNo, now, expiry, null, null, qc)
            stock.record(
                b.productItemId, lotId, b.fgWarehouseId, MovementType.PRODUCTION_IN,
                outputGoodGrams, r.costPerKgPaisa, "BATCH", b.id, "ব্যাচ ${b.batchNo}",
            )

            var voucherId: String? = null
            if (r.totalPaisa > 0L) {
                val lines = buildList {
                    add(LedgerLine(AccountCodes.INVENTORY_FG, debitPaisa = r.totalPaisa))
                    val rawCredit = r.materialPaisa + r.packagingPaisa
                    if (rawCredit > 0) add(LedgerLine(AccountCodes.INVENTORY_RAW, creditPaisa = rawCredit))
                    if (r.labourPaisa > 0) add(LedgerLine(AccountCodes.PRODUCTION_LABOR, creditPaisa = r.labourPaisa))
                    if (r.overheadAndMachinePaisa > 0) {
                        add(LedgerLine(AccountCodes.PRODUCTION_OVERHEAD, creditPaisa = r.overheadAndMachinePaisa))
                    }
                }
                voucherId = ledger.post("PRODUCTION", "BATCH", b.id, "ব্যাচ ${b.batchNo} সম্পূর্ণ", lines)
            }

            dao.upsertBatch(
                b.copy(
                    status = BatchStatus.COMPLETED, qcStatus = qc, actualInputGrams = r.inputGrams,
                    outputGoodGrams = outputGoodGrams, outputBags = r.outputBags, wastageGrams = r.wastageGrams,
                    yieldPct = r.yieldPct, fgLotId = lotId, materialCostPaisa = r.materialPaisa,
                    packagingCostPaisa = r.packagingPaisa, labourCostPaisa = r.labourPaisa,
                    overheadCostPaisa = r.electricityOverheadPaisa, machineCostPaisa = r.machinePaisa,
                    totalCostPaisa = r.totalPaisa, costPerKgPaisa = r.costPerKgPaisa, ledgerVoucherId = voucherId,
                    completedAt = now, mfgAt = now, expiryAt = expiry, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            if (b.cleaningRequired) {
                dao.upsertCleaningItems(
                    ProductionPlanner.defaultCleaningChecklist.mapIndexed { i, text ->
                        CleaningChecklistItemEntity(
                            id = newId(), batchId = b.id, sortOrder = i, text = text,
                            updatedAt = now, deviceId = stamper.deviceId,
                        )
                    }
                )
            }
            audit.log(
                "BATCH_COMPLETE", "production_batch", b.id,
                "${b.batchNo} আউটপুট ${"%.1f".format(outputGoodGrams / 1000.0)} কেজি, মোট খরচ ${r.totalPaisa} পয়সা",
            )
        }
    }

    /** লাইন-ক্লিনিং চেকলিস্টের একটি আইটেম টিক/আনটিক। সব আইটেম টিক হলে ব্যাচের cleaningDone = true। */
    suspend fun setCleaningItem(itemId: String, done: Boolean) {
        session.require(Permission.PRODUCTION)
        db.withTransaction {
            val item = dao.getCleaningItem(itemId) ?: throw IllegalArgumentException("চেকলিস্ট আইটেম পাওয়া যায়নি")
            val now = stamper.now()
            dao.upsertCleaningItem(
                item.copy(
                    done = done, doneBy = if (done) session.userId else null, doneAt = if (done) now else null,
                    updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            val all = dao.cleaningItems(item.batchId)
            val complete = ProductionPlanner.isCleaningComplete(all.map { it.done })
            val b = requireBatch(item.batchId)
            if (b.cleaningDone != complete) {
                dao.upsertBatch(b.copy(cleaningDone = complete, updatedAt = now, deviceId = stamper.deviceId))
                if (complete) audit.log("BATCH_CLEANING_DONE", "production_batch", b.id, b.batchNo)
            }
        }
    }

    /** শুধু পরিকল্পিত (এখনও শুরু হয়নি) ব্যাচ বাতিল করা যায়। */
    suspend fun cancel(batchId: String) {
        session.require(Permission.PRODUCTION)
        val b = requireBatch(batchId)
        check(b.status == BatchStatus.PLANNED) { "শুরু হয়ে যাওয়া ব্যাচ বাতিল করা যায় না" }
        dao.upsertBatch(b.copy(status = BatchStatus.CANCELLED, updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("BATCH_CANCEL", "production_batch", b.id, b.batchNo)
    }

    // ---------------- ভেতরের সহায়ক ----------------

    private class PlanContext(
        val plan: List<ProductionPlanner.PlanLine>,
        val items: Map<String, ItemEntity>,
        val lotBatchNo: Map<String, String>,
    )

    private suspend fun requireBatch(id: String): ProductionBatchEntity =
        dao.getById(id)?.takeIf { it.deletedAt == null } ?: throw IllegalArgumentException("ব্যাচ পাওয়া যায়নি")

    private fun isTraceable(item: ItemEntity) =
        item.type == ItemType.MEDICINE || item.type == ItemType.PREMIX || item.type == ItemType.ADDITIVE

    private suspend fun buildPlan(b: ProductionBatchEntity): PlanContext {
        // একই আইটেম একাধিক লাইনে থাকলে যোগ করে নিই, নইলে একই লট দুইবার বরাদ্দ হবে
        val grouped = formulas.lines(b.formulaId).filter { it.gramsPerTon > 0.0 }
            .groupBy { it.itemId }.mapValues { (_, ls) -> ls.sumOf { it.gramsPerTon } }
        require(grouped.isNotEmpty()) { "ফর্মুলায় কোনো উপাদান নেই" }
        val items = itemDao.getByIds(grouped.keys.toList()).associateBy { it.id }
        val needs = grouped.map { (itemId, gpt) ->
            ProductionPlanner.Need(itemId, items[itemId]?.name ?: itemId, ProductionPlanner.requiredGrams(gpt, b.plannedQtyGrams))
        }
        val lotsByItem = HashMap<String, List<LotStock>>()
        val lotBatchNo = HashMap<String, String>()
        val lotless = HashSet<String>()
        val lotlessStock = HashMap<String, Double>()
        for (n in needs) {
            val item = items[n.itemId]
            if (item == null || item.trackLot) {
                val lots = stock.lotsFefo(n.itemId, b.sourceWarehouseId)
                lotsByItem[n.itemId] = lots
                lots.forEach { lotBatchNo[it.lotId] = it.batchNo }
            } else {
                lotless += n.itemId
                lotlessStock[n.itemId] = stock.stockGrams(n.itemId, b.sourceWarehouseId)
            }
        }
        return PlanContext(ProductionPlanner.plan(needs, lotsByItem, lotless, lotlessStock), items, lotBatchNo)
    }

    private suspend fun insertConsumption(
        batchId: String, itemId: String, lotId: String?, lotBatchNo: String?, qtyGrams: Double,
        unitCostPaisaPerKg: Long, traceable: Boolean, movementId: String, now: Long,
    ) {
        dao.insertConsumption(
            BatchConsumptionEntity(
                id = newId(), batchId = batchId, itemId = itemId, lotId = lotId, lotBatchNo = lotBatchNo,
                qtyGrams = qtyGrams, unitCostPaisaPerKg = unitCostPaisaPerKg, isTraceable = traceable,
                isPackaging = false, stockMovementId = movementId, createdBy = session.userId,
                createdAt = now, deviceId = stamper.deviceId,
            )
        )
    }

    private companion object {
        /** আউটপুট ইনপুটের এর চেয়ে বেশি হলে সম্ভবত একক ভুল (কেজি/গ্রাম)। স্টিম/আর্দ্রতার জন্য সামান্য ছাড়। */
        const val MAX_OUTPUT_OVER_INPUT = 1.05
    }
}
