package com.feederp.ui.sales

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import com.feederp.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.data.ItemDao
import com.feederp.sales.ReturnLineInput
import com.feederp.sales.ReturnableLine
import com.feederp.sales.SalesReturnRepository
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SwitchRow
import com.feederp.ui.master.parseKgToGrams
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReturnState(val lines: List<ReturnableLine> = emptyList(), val itemNames: Map<String, String> = emptyMap())

@HiltViewModel
class SalesReturnViewModel @Inject constructor(
    private val repo: SalesReturnRepository,
    itemDao: ItemDao,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val invoiceId: String = requireNotNull(savedState.get<String>("id")) { "ইনভয়েস id নেই" }
    private val _state = MutableStateFlow(ReturnState())
    val state: StateFlow<ReturnState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val names = itemDao.all().associate { it.id to it.name }
                _state.value = ReturnState(repo.returnable(invoiceId), names)
            } catch (e: Exception) { showMessage(e.message ?: "লোড ব্যর্থ") }
        }
    }

    fun submit(qtyKg: Map<String, String>, reason: String, restock: Boolean, onDone: () -> Unit) = launchSafe(onDone) {
        val inputs = qtyKg.mapNotNull { (lineId, t) ->
            if (t.isBlank()) null
            else ReturnLineInput(lineId, parseKgToGrams(t) ?: throw IllegalArgumentException("পরিমাণ ঠিক নয়"))
        }
        repo.createReturn(invoiceId, inputs, reason, restock, System.currentTimeMillis())
    }
}

@Composable
fun SalesReturnScreen(onBack: () -> Unit, vm: SalesReturnViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    val qty = remember { mutableStateMapOf<String, String>() }
    var reason by remember { mutableStateOf("") }
    var restock by remember { mutableStateOf(true) }
    ScreenScaffold(title = "বিক্রয় ফেরত", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("ফেরত মাল ভালো অবস্থায় না থাকলে 'স্টকে ফেরত' বন্ধ রাখুন — তখন মাল স্টকে ঢুকবে না, শুধু ক্রেতার ক্রেডিট হবে।", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            st.lines.forEach { l ->
                Text("${st.itemNames[l.itemId] ?: "?"} — ডেলিভারি ${kg(l.deliveredGrams)} কেজি, আগে ফেরত ${kg(l.returnedGrams)}, ফেরতযোগ্য ${kg(l.returnableGrams)}", style = MaterialTheme.typography.bodyMedium)
                if (l.returnableGrams > 0) LabeledField("ফেরত (কেজি)", qty[l.invoiceLineId].orEmpty(), { qty[l.invoiceLineId] = it }, numeric = true)
                Spacer(Modifier.height(6.dp))
            }
            LabeledField("কারণ", reason, { reason = it })
            SwitchRow("স্টকে ফেরত (ভালো অবস্থায়)", restock, { restock = it })
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.submit(qty.toMap(), reason, restock, onBack) }, modifier = Modifier.fillMaxWidth()) { Text("ফেরত নিশ্চিত করুন") }
        }
    }
}
