package com.feederp.ui.formulation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.newId
import com.feederp.data.ItemDao
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.formulation.FormulaEntity
import com.feederp.data.formulation.FormulaStatus
import com.feederp.formulation.FormulaDraftInput
import com.feederp.formulation.FormulaEvaluation
import com.feederp.formulation.FormulaHeaderInfo
import com.feederp.formulation.FormulaLineInput
import com.feederp.formulation.FormulaRepository
import com.feederp.formulation.NutrientTargets
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class FormulaListViewModel @Inject constructor(
    repo: FormulaRepository,
) : BaseViewModel() {
    val formulas: StateFlow<List<FormulaEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** ফর্মুলার একটি লাইন (ফর্মের অবস্থা): আইটেম ও গ্রাম/টন লেখা। */
data class LineUi(val key: String, val itemId: String?, val gramsText: String)

data class EditorState(
    val loaded: Boolean = false,
    val formulaId: String? = null,
    val status: FormulaStatus = FormulaStatus.DRAFT,
    val version: Int = 1,
    val code: String = "",
    val name: String = "",
    val productItemId: String? = null,
    val isMedicated: Boolean = false,
    val species: String = "",
    val ageDays: String = "",
    val minProtein: String = "",
    val minEnergy: String = "",
    val minFat: String = "",
    val maxFat: String = "",
    val maxFibre: String = "",
    val maxMoisture: String = "",
    val note: String = "",
    val lines: List<LineUi> = emptyList(),
    val ingredientOptions: List<ItemEntity> = emptyList(),
    val productOptions: List<ItemEntity> = emptyList(),
    val evaluation: FormulaEvaluation? = null,
) {
    val editable: Boolean get() = status == FormulaStatus.DRAFT

    /** পূর্ণ (আইটেম + ধনাত্মক পরিমাণ) লাইনগুলো। */
    fun validLines(): List<FormulaLineInput> = lines.mapNotNull { l ->
        val grams = parseNum(l.gramsText)
        if (l.itemId != null && grams != null && grams > 0.0) FormulaLineInput(l.itemId, grams) else null
    }

    /** অর্ধেক-পূরণ লাইন থাকলে বার্তা। */
    fun linesProblem(): String? {
        for (l in lines) {
            val grams = parseNum(l.gramsText)
            val filledGrams = l.gramsText.isNotBlank()
            if (l.itemId == null && filledGrams) return "একটি লাইনে আইটেম বাছাই করা হয়নি"
            if (l.itemId != null && (grams == null || grams <= 0.0)) return "প্রতিটি লাইনে গ্রাম/টন পরিমাণ ধনাত্মক সংখ্যায় দিন"
        }
        return null
    }

    fun targets() = NutrientTargets(
        minProteinPct = parseNum(minProtein), minEnergyKcalPerKg = parseNum(minEnergy),
        minFatPct = parseNum(minFat), maxFatPct = parseNum(maxFat),
        maxFibrePct = parseNum(maxFibre), maxMoisturePct = parseNum(maxMoisture),
    )

    fun headerInfo() = FormulaHeaderInfo(isMedicated, species.trim().ifBlank { null }, targets())

    fun toDraftInput(): FormulaDraftInput = FormulaDraftInput(
        code = code, name = name, productItemId = productItemId ?: "",
        isMedicated = isMedicated, targetSpecies = species.trim().ifBlank { null },
        targetAgeDays = ageDays.trim().toIntOrNull(), targets = targets(), note = note.trim().ifBlank { null },
    )

    /** মোট গ্রাম/টন (১০,০০,০০০ হলে ১০০%)। */
    fun totalGramsPerTon(): Double = validLines().sumOf { it.gramsPerTon }
}

@HiltViewModel
class FormulaEditorViewModel @Inject constructor(
    private val repo: FormulaRepository,
    private val itemDao: ItemDao,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val initialId: String? = savedState.get<String>("id")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _opt = MutableStateFlow<com.feederp.formulation.OptRun?>(null)
    val opt: StateFlow<com.feederp.formulation.OptRun?> = _opt.asStateFlow()

    /** [picked]: আইটেম id → (সর্বনিম্ন %, সর্বোচ্চ %) লেখা; ফাঁকা = ০ / ১০০। */
    fun runOptimizer(picked: Map<String, Pair<String, String>>) = launchSafe {
        require(picked.isNotEmpty()) { "অন্তত একটি উপাদান বাছাই করুন" }
        val options = picked.map { (id, mm) ->
            val lo = if (mm.first.isBlank()) 0.0 else parseNum(mm.first) ?: throw IllegalArgumentException("সর্বনিম্ন % ঠিক নয়")
            val hi = if (mm.second.isBlank()) 100.0 else parseNum(mm.second) ?: throw IllegalArgumentException("সর্বোচ্চ % ঠিক নয়")
            com.feederp.formulation.OptOption(id, lo, hi)
        }
        _opt.value = repo.optimize(options, _state.value.targets())
    }

    fun clearOptimizer() { _opt.value = null }

    /** অপটিমাইজারের মিশ্রণ লাইনে বসানো (আগের লাইন বদলে যায়); সংরক্ষণ আলাদা। */
    fun applyOptimizer() {
        val r = _opt.value?.result ?: return
        if (!r.ok) return
        edit { it.copy(lines = r.lines.map { l -> LineUi(newId(), l.itemId, trimNum(l.gramsPerTon)) }) }
        recompute()
        _opt.value = null
    }

    init { load(initialId) }

    fun load(id: String?) = launchSafe {
        val all = itemDao.all()
        val ingredients = repo.candidateItems()
        val products = all.filter { it.type == ItemType.FINISHED_GOOD }
        if (id == null) {
            _state.value = EditorState(loaded = true, ingredientOptions = ingredients, productOptions = products)
            return@launchSafe
        }
        val f = repo.get(id) ?: throw IllegalArgumentException("ফর্মুলা পাওয়া যায়নি")
        val lines = repo.lines(id).map { LineUi(newId(), it.itemId, trimNum(it.gramsPerTon)) }
        _state.value = EditorState(
            loaded = true, formulaId = f.id, status = f.status, version = f.version,
            code = f.code, name = f.name, productItemId = f.productItemId, isMedicated = f.isMedicated,
            species = f.targetSpecies.orEmpty(), ageDays = f.targetAgeDays?.toString().orEmpty(),
            minProtein = numOrBlank(f.minProteinPct), minEnergy = numOrBlank(f.minEnergyKcalPerKg),
            minFat = numOrBlank(f.minFatPct), maxFat = numOrBlank(f.maxFatPct),
            maxFibre = numOrBlank(f.maxFibrePct), maxMoisture = numOrBlank(f.maxMoisturePct),
            note = f.note.orEmpty(), lines = lines, ingredientOptions = ingredients, productOptions = products,
        )
        recompute()
    }

    private fun edit(block: (EditorState) -> EditorState) {
        if (!_state.value.editable) return
        _state.update(block)
    }

    fun onCode(v: String) = edit { it.copy(code = v) }
    fun onName(v: String) = edit { it.copy(name = v) }
    fun onProduct(item: ItemEntity) = edit { it.copy(productItemId = item.id) }
    fun onMedicated(v: Boolean) { edit { it.copy(isMedicated = v) }; recompute() }
    fun onSpecies(v: String) = edit { it.copy(species = v) }
    fun onAge(v: String) = edit { it.copy(ageDays = v) }
    fun onNote(v: String) = edit { it.copy(note = v) }
    fun onTarget(field: String, v: String) {
        edit {
            when (field) {
                "minProtein" -> it.copy(minProtein = v)
                "minEnergy" -> it.copy(minEnergy = v)
                "minFat" -> it.copy(minFat = v)
                "maxFat" -> it.copy(maxFat = v)
                "maxFibre" -> it.copy(maxFibre = v)
                "maxMoisture" -> it.copy(maxMoisture = v)
                else -> it
            }
        }
        recompute()
    }

    fun addLine() = edit { it.copy(lines = it.lines + LineUi(newId(), null, "")) }
    fun removeLine(key: String) { edit { s -> s.copy(lines = s.lines.filterNot { it.key == key }) }; recompute() }
    fun onLineItem(key: String, item: ItemEntity) {
        edit { s -> s.copy(lines = s.lines.map { if (it.key == key) it.copy(itemId = item.id) else it }) }
        recompute()
    }
    fun onLineGrams(key: String, text: String) {
        edit { s -> s.copy(lines = s.lines.map { if (it.key == key) it.copy(gramsText = text) else it }) }
        recompute()
    }

    /** লাইভ নিউট্রিয়েন্ট/খরচ/যাচাই। লাইন না থাকলে ফাঁকা। */
    private fun recompute() {
        val s = _state.value
        val lines = s.validLines()
        if (lines.isEmpty()) {
            _state.update { it.copy(evaluation = null) }
            return
        }
        launchSafe {
            val eval = repo.evaluate(s.headerInfo(), lines)
            _state.update { it.copy(evaluation = eval) }
        }
    }

    private fun formProblem(s: EditorState): String? = when {
        s.code.isBlank() -> "ফর্মুলার কোড দিন"
        s.name.isBlank() -> "ফর্মুলার নাম দিন"
        s.productItemId == null -> "তৈরি পণ্য (ফিনিশড গুডস আইটেম) বাছাই করুন"
        else -> s.linesProblem()
    }

    /** খসড়া সংরক্ষণ (নতুন হলে তৈরি)। সফল হলে ফর্মুলার id দিয়ে [onSaved] ডাকে। */
    fun save(onSaved: (String) -> Unit = {}) = launchSafe {
        val s = _state.value
        if (!s.editable) throw IllegalStateException("অনুমোদিত ফর্মুলা বদলানো যায় না; নতুন ভার্সন বানান")
        formProblem(s)?.let { throw IllegalArgumentException(it) }
        val input = s.toDraftInput()
        val id = s.formulaId?.also { repo.updateDraftHeader(it, input) } ?: repo.createDraft(input)
        repo.saveLines(id, s.validLines())
        _state.update { it.copy(formulaId = id) }
        onSaved(id)
    }

    /** সংরক্ষণ করে অনুমোদন (Permission.APPROVE; ব্লকার থাকলে রিপোজিটরি থেকে বাংলা ত্রুটি আসে)। */
    fun approve(onDone: () -> Unit) = launchSafe {
        val s = _state.value
        formProblem(s)?.let { throw IllegalArgumentException(it) }
        val input = s.toDraftInput()
        val id = s.formulaId?.also { repo.updateDraftHeader(it, input) } ?: repo.createDraft(input)
        repo.saveLines(id, s.validLines())
        repo.approve(id)
        onDone()
    }

    fun newVersion(onCreated: (String) -> Unit) = launchSafe {
        val id = _state.value.formulaId ?: throw IllegalStateException("আগে ফর্মুলা সংরক্ষণ করুন")
        onCreated(repo.createDraftVersion(id))
    }

    fun deleteDraft(onDone: () -> Unit) = launchSafe {
        val id = _state.value.formulaId ?: return@launchSafe
        repo.deleteDraft(id)
        onDone()
    }

    private fun trimNum(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    private fun numOrBlank(d: Double?): String = d?.let { trimNum(it) }.orEmpty()
}
