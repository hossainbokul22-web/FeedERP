package com.feederp.ui.home

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.feederp.core.Money
import com.feederp.dashboard.DashboardData

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
                    Text("আজকের বিক্রি", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        Money.format(today), style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    data.last7DaysSalesPaisa?.let { week ->
                        Spacer(Modifier.height(12.dp))
                        WeekBars(week)
                        Text(
                            "শেষ ৭ দিন: ${Money.format(week.sum())}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        val kpis = listOfNotNull(
            data.duePaisa?.let { Kpi("বাকি আদায়", Money.format(it), false) },
            data.lowStockCount?.let { Kpi("কম স্টক আইটেম", "$it টি", it > 0) },
            data.pendingQcLots?.let { Kpi("QC অপেক্ষমাণ লট", "$it টি", it > 0) },
            data.activeBatches?.let { Kpi("চলমান ব্যাচ", "$it টি", false) },
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
