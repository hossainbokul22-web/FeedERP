package com.feederp.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.feederp.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.feederp.core.Money
import com.feederp.dashboard.DashboardData
import com.feederp.ui.theme.tr

/** হোমের ওপরের ড্যাশবোর্ড: আজকের বিক্রি + ৭ দিনের বার-চার্ট + সংক্ষিপ্ত KPI কার্ড। */
@Composable
fun DashboardHeader(data: DashboardData?, modifier: Modifier = Modifier) {
    if (data == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        data.todaySalesPaisa?.let { today ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("আজকের বিক্রি", "Today's Sales"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        Money.format(today), style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    data.last7DaysSalesPaisa?.let { week ->
                        Spacer(Modifier.height(12.dp))
                        WeekBars(week)
                        Text(
                            tr("শেষ ৭ দিন: ${Money.format(week.sum())}", "Last 7 days: ${Money.format(week.sum())}"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        val kpis = listOfNotNull(
            data.duePaisa?.let { Kpi(tr("বাকি আদায়", "Receivables"), Money.format(it), false) },
            data.lowStockCount?.let { Kpi(tr("কম স্টক আইটেম", "Low-stock items"), "$it", it > 0) },
            data.pendingQcLots?.let { Kpi(tr("QC অপেক্ষমাণ লট", "QC pending lots"), "$it", it > 0) },
            data.activeBatches?.let { Kpi(tr("চলমান ব্যাচ", "Batches running"), "$it", false) },
        )
        kpis.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k -> KpiCard(k, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class Kpi(val label: String, val value: String, val warn: Boolean)

@Composable
private fun KpiCard(k: Kpi, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (k.warn) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(k.label, style = MaterialTheme.typography.bodySmall)
            Text(k.value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WeekBars(values: List<Long>) {
    val color = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val n = values.size
        val gap = 8.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val h = (size.height * (v.toFloat() / max.toFloat())).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                color = if (i == n - 1) color else faint,
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
        }
    }
}

/** রিপোর্ট/ইনভয়েসের মাথায় যে কোম্পানির নাম ছাপা হবে সেটা বদলানোর কার্ড (শুধু মালিক)। */
@Composable
fun CompanyNameCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.feederp.ui.common.CompanyProfile.name(context)) }
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { draft = name; editing = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("রিপোর্টে কোম্পানির নাম (বদলাতে চাপুন)", "Company name on reports (tap to change)"), style = MaterialTheme.typography.bodySmall)
            Text(name, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (editing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(tr("কোম্পানির নাম", "Company name")) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (draft.isNotBlank()) {
                        com.feederp.ui.common.CompanyProfile.setName(context, draft)
                        name = com.feederp.ui.common.CompanyProfile.name(context)
                    }
                    editing = false
                }) { Text(tr("সংরক্ষণ", "Save")) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editing = false }) { Text(tr("বাতিল", "Cancel")) }
            },
        )
    }
}
