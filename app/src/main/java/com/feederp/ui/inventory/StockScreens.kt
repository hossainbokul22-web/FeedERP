package com.feederp.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.feederp.ui.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.core.Money
import com.feederp.core.Weight
import com.feederp.inventory.ExpiryState
import com.feederp.inventory.QcStatus
import com.feederp.master.MasterLabels
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDateTime
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog

private const val ALL_WH = "সব গুদাম"

@Composable
fun StockListScreen(
    onBack: () -> Unit,
    onItemMovements: (String) -> Unit,
    vm: StockListViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val selected by vm.selectedWarehouse.collectAsStateWithLifecycle()
    val labels = remember(warehouses) { listOf(ALL_WH) + warehouses.map { it.name } }

    val whName = warehouses.firstOrNull { it.id == selected }?.name ?: ALL_WH
    val stockTable = remember(rows, whName) {
        if (rows.isEmpty()) null else com.feederp.ui.common.ReportTable(
            "স্টক রিপোর্ট", "গুদাম: $whName",
            listOf(
                com.feederp.ui.common.ReportSection(
                    null, listOf("কোড", "আইটেম", "ধরন", "পরিমাণ", "অবস্থা"),
                    rows.map { r ->
                        com.feederp.ui.common.ReportRow(
                            listOf(
                                r.item.code, r.item.name, MasterLabels.itemType(r.item.type), Weight.formatKg(r.qtyGrams),
                                if (r.isNegative) "নেগেটিভ স্টক" else if (r.isLow) "কম স্টক" else "ঠিক আছে",
                            )
                        )
                    },
                    setOf(3),
                )
            ),
        )
    }
    ScreenScaffold(title = "স্টক", onBack = onBack, actions = { com.feederp.ui.common.ReportActions(stockTable) }) { pad ->
        Column(Modifier.padding(pad)) {
            DropdownField(
                label = "গুদাম",
                options = labels,
                selected = warehouses.firstOrNull { it.id == selected }?.name ?: ALL_WH,
                optionLabel = { it },
                onSelect = { l -> vm.selectWarehouse(warehouses.firstOrNull { it.name == l }?.id) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SearchableList(
                items = rows,
                modifier = Modifier.weight(1f),
                searchText = { it.item.name + " " + it.item.code + " " + (it.item.nameBn ?: "") },
                emptyText = "কোনো আইটেম নেই",
                onClick = { onItemMovements(it.item.id) },
            ) { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        Weight.formatKg(r.qtyGrams),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "${r.item.code} · ${MasterLabels.itemType(r.item.type)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (r.isNegative) {
                    Text("নেগেটিভ স্টক! গণনা/অ্যাডজাস্টমেন্ট যাচাই করুন", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                } else if (r.isLow) {
                    Text(
                        "মিনিমাম স্টকের নিচে (সর্বনিম্ন ${Weight.formatKg(r.item.minStockGrams)})",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun LotListScreen(onBack: () -> Unit, vm: LotListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var qcTarget by remember { mutableStateOf<LotRow?>(null) }
    val now = System.currentTimeMillis()

    ScreenScaffold(title = "লট ও মেয়াদ", onBack = onBack) { pad ->
        SearchableList(
            items = rows,
            modifier = Modifier.padding(pad),
            searchText = { it.itemName + " " + it.itemCode + " " + it.batchNo + " " + it.warehouseName },
            emptyText = "কোনো লট স্টকে নেই",
        ) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${r.itemName} · ${r.batchNo}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(Weight.formatKg(r.qtyGrams), style = MaterialTheme.typography.titleMedium)
            }
            Text("গুদাম: ${r.warehouseName}", style = MaterialTheme.typography.bodySmall)
            val expColor = when (r.expiry) {
                ExpiryState.EXPIRED -> MaterialTheme.colorScheme.error
                ExpiryState.NEAR -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text("মেয়াদ: ${InventoryLabels.expiryText(r.expiryAt, now)}", color = expColor, style = MaterialTheme.typography.bodySmall)
            Text(
                "QC: ${QcStatus.labelBn(r.qcStatus)}" + (r.moisturePct?.let { " · আর্দ্রতা $it%" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.canQc) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { qcTarget = r }) { Text("QC স্ট্যাটাস বদলান") }
                }
            }
        }
    }

    qcTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { qcTarget = null },
            title = { Text("QC: ${r.itemName} · ${r.batchNo}") },
            text = {
                Column {
                    QcStatus.all.sorted().forEach { s ->
                        TextButton(onClick = { vm.setQc(r.lotId, s); qcTarget = null }) {
                            Text(QcStatus.labelBn(s) + if (s == r.qcStatus) " (বর্তমান)" else "")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { qcTarget = null }) { Text("বাতিল") } },
        )
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

@Composable
fun MovementListScreen(onBack: () -> Unit, vm: MovementListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val itemName by vm.itemFilterName.collectAsStateWithLifecycle()

    ScreenScaffold(title = if (itemName != null) "মুভমেন্ট: $itemName" else "স্টক মুভমেন্ট", onBack = onBack) { pad ->
        SearchableList(
            items = rows,
            modifier = Modifier.padding(pad),
            searchText = { it.itemName + " " + InventoryLabels.movementType(it.type) + " " + (it.batchNo ?: "") + " " + (it.note ?: "") },
            emptyText = "কোনো মুভমেন্ট নেই",
        ) { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(m.itemName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    (if (m.qtyGrams > 0) "+" else "") + Weight.formatKg(m.qtyGrams),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (m.qtyGrams < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            KeyValueRow(InventoryLabels.movementType(m.type), formatDateTime(m.createdAt))
            Text(
                "গুদাম: ${m.warehouseName}" + (m.batchNo?.let { " · ব্যাচ: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            m.unitCostPaisaPerKg?.let { Text("কেজি প্রতি খরচ: ${Money.format(it)}", style = MaterialTheme.typography.bodySmall) }
            m.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
