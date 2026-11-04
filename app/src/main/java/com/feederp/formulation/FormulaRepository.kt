package com.feederp.formulation

import androidx.room.withTransaction
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.ItemDao
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.formulation.FormulaDao
import com.feederp.data.formulation.FormulaEntity
import com.feederp.data.formulation.FormulaLineEntity
import com.feederp.data.formulation.FormulaStatus
import com.feederp.data.formulation.RestrictedItemDao
import com.feederp.data.formulation.RestrictedItemEntity
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.StockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** নতুন ফর্মুলা (ভার্সন ১ খসড়া) বা খসড়ার হেডার বদলের তথ্য। */
data class FormulaDraftInput(
    val code: String,
    val name: String,
    val productItemId: String,
    val isMedicated: Boolean = false,
    val targetSpecies: String? = null,
    val targetAgeDays: Int? = null,
    val targets: NutrientTargets = NutrientTargets(),
    val note: String? = null,
)

/** অপটিমাইজারের প্রার্থী: [minPct]/[maxPct] = মিশ্রণে এই উপাদানের % সীমা। */
data class OptOption(val itemId: String, val minPct: Double = 0.0, val maxPct: Double = 100.0)

/** অপটিমাইজারের ফল + যেসব আইটেম বাদ গেছে (দাম/নিষিদ্ধ তালিকার কারণে)। */
data class OptRun(val result: OptimizerResult, val skipped: List<String>)

/** লাইভ হিসাবের ফল: নিউট্রিয়েন্ট, খরচ ও যাচাইয়ের রিপোর্ট। */
data class FormulaEvaluation(
    val nutrients: NutrientResult,
    val report: ValidationReport,
    val ingredients: List<IngredientData>,
    /** কেজি প্রতি দাম ০ (স্টকে ক্রয়মূল্য নেই) এমন উপাদানের নাম। */
    val itemsWithoutCost: List<String>,
)

fun ItemEntity.toDoseInfo() = DoseItemInfo(
    itemId = id, name = name, type = type, minDoseGPerTon = minDoseGPerTon, maxDoseGPerTon = maxDoseGPerTon,
    concentrationPct = concentrationPct, isControlled = isControlled, withdrawalDays = withdrawalDays,
)

fun FormulaEntity.targets() = NutrientTargets(
    minProteinPct, minEnergyKcalPerKg, minFatPct, maxFatPct, maxFibrePct, maxMoisturePct,
)

fun FormulaEntity.headerInfo() = FormulaHeaderInfo(isMedicated, targetSpecies, targets())

/**
 * ফর্মুলা, ভার্সন ও অনুমোদন। অনুমোদিত ফর্মুলা কখনও বদলানো যায় না; বদলাতে হলে [createDraftVersion] দিয়ে নতুন ভার্সন।
 * শুধু APPROVED ফর্মুলা প্রোডাকশনে ব্যবহারযোগ্য ([approvedFormulas], [getApproved])।
 */
@Singleton
class FormulaRepository @Inject constructor(
    private val db: AppDatabase,
    private val formulaDao: FormulaDao,
    private val restrictedDao: RestrictedItemDao,
    private val itemDao: ItemDao,
    private val trialDao: com.feederp.data.rnd.FormulaTrialDao,
    private val stock: StockRepository,
    private val audit: AuditRepository,
    private val stamper: Stamper,
    private val session: SessionManager,
) {
    // ---------------- পড়া ----------------

    fun observeAll(): Flow<List<FormulaEntity>> = formulaDao.observeAll()
    fun observeLines(formulaId: String): Flow<List<FormulaLineEntity>> = formulaDao.observeLines(formulaId)

    /** প্রোডাকশন ও অন্য মডিউলের জন্য: শুধু অনুমোদিত (APPROVED) ফর্মুলা। */
    suspend fun approvedFormulas(): List<FormulaEntity> = formulaDao.approved()

    /** অনুমোদিত না হলে (খসড়া/আর্কাইভ/মুছে ফেলা) null। */
    suspend fun getApproved(id: String): FormulaEntity? =
        formulaDao.getById(id)?.takeIf { it.status == FormulaStatus.APPROVED && it.deletedAt == null }

    suspend fun get(id: String): FormulaEntity? = formulaDao.getById(id)?.takeIf { it.deletedAt == null }
    suspend fun lines(formulaId: String): List<FormulaLineEntity> = formulaDao.lines(formulaId)
    suspend fun versions(rootId: String): List<FormulaEntity> = formulaDao.versionsOf(rootId)

    /** ফর্মুলায় ব্যবহারযোগ্য আইটেম (প্যাকেজিং ও তৈরি পণ্য বাদে)। */
    suspend fun candidateItems(): List<ItemEntity> =
        itemDao.all().filter { it.type != ItemType.PACKAGING && it.type != ItemType.FINISHED_GOOD }

    suspend fun restrictedMap(): Map<String, String> = restrictedDao.all().associate { it.itemId to it.level }

    // ---------------- লাইভ হিসাব ও যাচাই ----------------

    /** [lines] এর নিউট্রিয়েন্ট, খরচ (স্টকের ওয়েটেড-অ্যাভারেজ দাম) ও যাচাই। কিছু লেখে না। */
    suspend fun evaluate(header: FormulaHeaderInfo, lines: List<FormulaLineInput>): FormulaEvaluation {
        val itemMap = loadItems(lines)
        val dose = itemMap.mapValues { it.value.toDoseInfo() }
        val ingredients = mutableListOf<IngredientData>()
        val noCost = mutableListOf<String>()
        for (l in lines) {
            val item = itemMap[l.itemId] ?: continue
            val cost = stock.weightedAvgCostPaisaPerKg(item.id)
            if (cost <= 0L) noCost += item.name
            ingredients += IngredientData(
                item.id, item.name, l.gramsPerTon, item.proteinPct, item.energyKcalPerKg, item.fatPct,
                item.fibrePct, item.moisturePct, cost,
            )
        }
        val nutrients = NutrientCalculator.calculate(ingredients)
        val report = FormulaChecker.check(header, lines, dose, restrictedMap(), nutrients)
        return FormulaEvaluation(nutrients, report, ingredients, noCost)
    }

    suspend fun evaluate(formulaId: String): FormulaEvaluation {
        val f = get(formulaId) ?: throw IllegalArgumentException("ফর্মুলা পাওয়া যায়নি")
        return evaluate(f.headerInfo(), lines(formulaId).map { FormulaLineInput(it.itemId, it.gramsPerTon) })
    }

    /** অপটিমাইজারের প্রার্থী: আইটেমের নিউট্রিয়েন্ট ও বর্তমান স্টক-দাম সহ। */
    suspend fun costPaisaPerKg(itemId: String): Long = stock.weightedAvgCostPaisaPerKg(itemId)

    // ---------------- লেখা ----------------

    /** নতুন ফর্মুলা: ভার্সন ১, খসড়া। ফেরত: ফর্মুলার id। */
    suspend fun createDraft(input: FormulaDraftInput): String {
        session.require(Permission.FORMULA_EDIT)
        checkHeader(input)
        val id = newId()
        formulaDao.upsert(
            FormulaEntity(
                id = id, rootId = id, code = input.code.trim(), name = input.name.trim(), version = 1,
                baseFormulaId = null, productItemId = input.productItemId, status = FormulaStatus.DRAFT,
                isMedicated = input.isMedicated, targetSpecies = input.targetSpecies?.trim()?.ifBlank { null },
                targetAgeDays = input.targetAgeDays,
                minProteinPct = input.targets.minProteinPct, minEnergyKcalPerKg = input.targets.minEnergyKcalPerKg,
                minFatPct = input.targets.minFatPct, maxFatPct = input.targets.maxFatPct,
                maxFibrePct = input.targets.maxFibrePct, maxMoisturePct = input.targets.maxMoisturePct,
                note = input.note?.trim()?.ifBlank { null },
                createdBy = session.userId, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("FORMULA_CREATE", "formula", id, "${input.code} ${input.name} v1")
        return id
    }

    /** খসড়ার হেডার বদল। অনুমোদিত/আর্কাইভ ফর্মুলায় ব্যর্থ হয়। */
    suspend fun updateDraftHeader(id: String, input: FormulaDraftInput) {
        session.require(Permission.FORMULA_EDIT)
        checkHeader(input)
        val f = requireDraft(id)
        formulaDao.upsert(
            f.copy(
                code = input.code.trim(), name = input.name.trim(), productItemId = input.productItemId,
                isMedicated = input.isMedicated, targetSpecies = input.targetSpecies?.trim()?.ifBlank { null },
                targetAgeDays = input.targetAgeDays,
                minProteinPct = input.targets.minProteinPct, minEnergyKcalPerKg = input.targets.minEnergyKcalPerKg,
                minFatPct = input.targets.minFatPct, maxFatPct = input.targets.maxFatPct,
                maxFibrePct = input.targets.maxFibrePct, maxMoisturePct = input.targets.maxMoisturePct,
                note = input.note?.trim()?.ifBlank { null },
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
    }

    /**
     * খসড়ার লাইন সংরক্ষণ (পুরনো লাইন সফট-ডিলিট, নতুন লাইন যোগ)। মোট ১০০% না হলেও খসড়া সেভ হয়;
     * অনুমোদনের সময় ব্লক হয়। অনুমোদিত ফর্মুলায় ব্যর্থ হয়।
     */
    suspend fun saveLines(formulaId: String, lines: List<FormulaLineInput>) {
        session.require(Permission.FORMULA_EDIT)
        val f = requireDraft(formulaId)
        sanitize(lines)
        db.withTransaction {
            val now = stamper.now()
            formulaDao.softDeleteLines(formulaId, now, stamper.deviceId)
            formulaDao.upsertLines(lines.map { toLineEntity(formulaId, it, stamper.now()) })
            formulaDao.upsert(f.copy(updatedAt = stamper.now(), deviceId = stamper.deviceId))
        }
    }

    /**
     * [baseFormulaId] থেকে নতুন খসড়া ভার্সন (rootId একই, ভার্সন = সর্বোচ্চ + ১, baseFormulaId = বেস)।
     * [lines] না দিলে বেসের লাইন কপি হয়। বেস যেকোনো অবস্থায় (সাধারণত APPROVED) থাকতে পারে; বেস নিজে বদলায় না।
     * R&D এটি দিয়ে প্রস্তাবিত ফর্মুলা বানায়। ফেরত: নতুন ফর্মুলার id।
     */
    suspend fun createDraftVersion(
        baseFormulaId: String,
        name: String? = null,
        note: String? = null,
        lines: List<FormulaLineInput>? = null,
        targets: NutrientTargets? = null,
    ): String {
        session.require(Permission.FORMULA_EDIT)
        val base = get(baseFormulaId) ?: throw IllegalArgumentException("বেস ফর্মুলা পাওয়া যায়নি")
        val newLines = lines ?: lines(baseFormulaId).map { FormulaLineInput(it.itemId, it.gramsPerTon) }
        sanitize(newLines)
        val t = targets ?: base.targets()
        val id = newId()
        db.withTransaction {
            val version = (formulaDao.maxVersion(base.rootId) ?: base.version) + 1
            val now = stamper.now()
            formulaDao.upsert(
                base.copy(
                    id = id, version = version, baseFormulaId = base.id, status = FormulaStatus.DRAFT,
                    name = name?.trim()?.ifBlank { null } ?: base.name,
                    note = note?.trim()?.ifBlank { null } ?: base.note,
                    minProteinPct = t.minProteinPct, minEnergyKcalPerKg = t.minEnergyKcalPerKg,
                    minFatPct = t.minFatPct, maxFatPct = t.maxFatPct,
                    maxFibrePct = t.maxFibrePct, maxMoisturePct = t.maxMoisturePct,
                    approvedBy = null, approvedAt = null, createdBy = session.userId,
                    updatedAt = now, deviceId = stamper.deviceId, deletedAt = null,
                )
            )
            formulaDao.upsertLines(newLines.map { toLineEntity(id, it, stamper.now()) })
            audit.log("FORMULA_NEW_VERSION", "formula", id, "${base.code} v${base.version} -> v$version")
        }
        return id
    }

    /**
     * অনুমোদন (Permission.APPROVE)। ব্লকার থাকলে ব্যর্থ। একই ফর্মুলার আগের APPROVED ভার্সন ARCHIVED হয়ে যায়।
     */
    suspend fun approve(formulaId: String, trialApproved: Boolean = false) {
        session.require(Permission.APPROVE)
        val f = requireDraft(formulaId)
        // R&D ট্রায়ালের প্রস্তাবিত ফর্মুলা সরাসরি অনুমোদন করা যায় না; ট্রায়ালের অনুমোদন প্রবাহ (R&D → QC → ম্যানেজার) দিয়ে যেতে হবে
        val trial = trialDao.byProposedFormula(formulaId)
        if (trial != null && !trialApproved) {
            throw IllegalStateException("এই ফর্মুলা একটি R&D ট্রায়ালের অংশ (${trial.trialNo}); ট্রায়াল অনুমোদনের মাধ্যমেই এটি অনুমোদিত হবে")
        }
        val eval = evaluate(formulaId)
        if (!eval.report.ok) {
            throw IllegalStateException("অনুমোদন করা যায়নি: " + eval.report.blockers.joinToString("; "))
        }
        db.withTransaction {
            val now = stamper.now()
            for (old in formulaDao.versionsOf(f.rootId)) {
                if (old.id != f.id && old.status == FormulaStatus.APPROVED) {
                    formulaDao.upsert(old.copy(status = FormulaStatus.ARCHIVED, updatedAt = stamper.now(), deviceId = stamper.deviceId))
                }
            }
            formulaDao.upsert(
                f.copy(
                    status = FormulaStatus.APPROVED, approvedBy = session.userId, approvedAt = now,
                    updatedAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
            audit.log("FORMULA_APPROVE", "formula", f.id, "${f.code} v${f.version}")
        }
    }

    /** খসড়া মুছে ফেলা (সফট ডিলিট)। অনুমোদিত ফর্মুলা মোছা যায় না। */
    suspend fun deleteDraft(formulaId: String) {
        session.require(Permission.FORMULA_EDIT)
        val f = requireDraft(formulaId)
        db.withTransaction {
            formulaDao.softDeleteLines(formulaId, stamper.now(), stamper.deviceId)
            formulaDao.upsert(f.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
            audit.log("FORMULA_DELETE_DRAFT", "formula", f.id, "${f.code} v${f.version}")
        }
    }

    /** নিষিদ্ধ/সীমিত তালিকায় যোগ (level = "BANNED" | "CONTROLLED")। */
    suspend fun setRestricted(itemId: String, level: String?, note: String? = null) {
        session.require(Permission.FORMULA_EDIT)
        if (level == null) {
            restrictedDao.delete(itemId)
        } else {
            require(level == DoseValidator.BANNED || level == DoseValidator.CONTROLLED) { "অবৈধ স্তর" }
            restrictedDao.upsert(RestrictedItemEntity(itemId, level, note, stamper.now()))
        }
        audit.log("FORMULA_RESTRICT", "item", itemId, level ?: "REMOVED")
    }

    // ---------------- ভেতরের সহায়ক ----------------

    /**
     * ন্যূনতম খরচের মিশ্রণ ([LeastCostOptimizer]) — কিছু লেখে না, শুধু প্রস্তাব দেয়; ব্যবহারকারী প্রয়োগ করে সংরক্ষণ করে।
     * খরচ = স্টকের ওয়েটেড-অ্যাভারেজ দাম; দাম ০ বা নিষিদ্ধ (BANNED) আইটেম বাদ যায়। নিউট্রিয়েন্ট ডাটা না থাকা = ০ ধরা হয়।
     * ভিটামিন/মিনারেল/প্রিমিক্স যেগুলো অবশ্যই লাগবে সেগুলোর [OptOption.minPct] নিজে দিতে হবে — অপটিমাইজার শুধু দেওয়া সীমা মানে।
     */
    suspend fun optimize(options: List<OptOption>, targets: NutrientTargets): OptRun {
        session.require(Permission.FORMULA_EDIT)
        val items = itemDao.getByIds(options.map { it.itemId }).associateBy { it.id }
        val banned = restrictedMap().filterValues { it == "BANNED" }.keys
        val skipped = ArrayList<String>()
        val ings = ArrayList<OptIngredient>()
        for (o in options) {
            val it = items[o.itemId] ?: continue
            val cost = stock.weightedAvgCostPaisaPerKg(it.id)
            if (it.id in banned) { skipped += "${it.name} (নিষিদ্ধ)"; continue }
            if (cost <= 0) { skipped += "${it.name} (স্টকে দাম নেই)"; continue }
            ings += OptIngredient(
                it.id, it.name, cost, it.proteinPct, it.energyKcalPerKg, it.fatPct, it.fibrePct, it.moisturePct, o.minPct, o.maxPct,
            )
        }
        return OptRun(LeastCostOptimizer.optimize(ings, targets), skipped)
    }

    private suspend fun requireDraft(id: String): FormulaEntity {
        val f = get(id) ?: throw IllegalArgumentException("ফর্মুলা পাওয়া যায়নি")
        check(f.status == FormulaStatus.DRAFT) {
            "অনুমোদিত/আর্কাইভ ফর্মুলা বদলানো যায় না; নতুন ভার্সন (খসড়া) বানিয়ে বদলান"
        }
        return f
    }

    private suspend fun checkHeader(i: FormulaDraftInput) {
        require(i.code.isNotBlank()) { "ফর্মুলা কোড দিন" }
        require(i.name.isNotBlank()) { "ফর্মুলার নাম দিন" }
        require(itemDao.getById(i.productItemId) != null) { "পণ্য (তৈরি ফিড আইটেম) বাছাই করুন" }
        i.targetAgeDays?.let { require(it > 0) { "বয়স (দিন) ০ এর বেশি হতে হবে" } }
        val t = i.targets
        listOfNotNull(
            t.minProteinPct, t.minEnergyKcalPerKg, t.minFatPct, t.maxFatPct, t.maxFibrePct, t.maxMoisturePct,
        ).forEach { require(it.isFinite() && it >= 0.0) { "নিউট্রিয়েন্ট লক্ষ্যমাত্রা ঋণাত্মক/অবৈধ হতে পারে না" } }
        if (t.minFatPct != null && t.maxFatPct != null) {
            require(t.minFatPct <= t.maxFatPct) { "ফ্যাটের সর্বনিম্ন সর্বোচ্চের বেশি হতে পারে না" }
        }
    }

    private fun sanitize(lines: List<FormulaLineInput>) {
        for (l in lines) {
            require(l.gramsPerTon.isFinite() && l.gramsPerTon > 0.0) { "প্রতিটি লাইনের পরিমাণ ০ এর বেশি হতে হবে" }
        }
        require(lines.map { it.itemId }.toSet().size == lines.size) { "একই উপাদান একাধিকবার দেওয়া যাবে না" }
    }

    private fun toLineEntity(formulaId: String, l: FormulaLineInput, now: Long) = FormulaLineEntity(
        id = newId(), formulaId = formulaId, itemId = l.itemId, gramsPerTon = l.gramsPerTon,
        updatedAt = now, deviceId = stamper.deviceId,
    )

    private suspend fun loadItems(lines: List<FormulaLineInput>): Map<String, ItemEntity> {
        val ids = lines.map { it.itemId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return itemDao.getByIds(ids).associateBy { it.id }
    }
}
