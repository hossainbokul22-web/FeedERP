package com.feederp.ui.master

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feederp.ui.common.formatDate

/** বাংলা সংখ্যা (০-৯) কে ইংরেজি অঙ্কে বদলে ডবল পার্স করে; ফাঁকা বা ভুল হলে null। */
fun parseNum(text: String): Double? {
    val sb = StringBuilder()
    for (c in text.trim()) {
        sb.append(if (c in '০'..'৯') ('0' + (c - '০')) else c)
    }
    return sb.toString().replace(",", "").toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

/** টাকা লেখা (যেমন "1,250.50") থেকে পয়সা; ভুল হলে null। */
fun parseTakaToPaisa(text: String): Long? = parseNum(text)?.let { com.feederp.core.Money.fromTaka(it) }

/** ইউজারের টাইপ করা কেজি থেকে গ্রাম। */
fun parseKgToGrams(text: String): Double? = parseNum(text)?.let { com.feederp.core.Weight.kgToGrams(it) }

/** গ্রামকে ফর্মের ইনপুট বক্সের জন্য কেজি স্ট্রিং (কমা ছাড়া) বানায়। */
fun gramsToKgText(grams: Double): String {
    val kg = grams / 1000.0
    return if (kg == kotlin.math.floor(kg)) kg.toLong().toString() else String.format(java.util.Locale.US, "%.3f", kg).trimEnd('0').trimEnd('.')
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("কিছু নেই") }, onClick = { expanded = false })
            }
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionLabel(opt)) },
                    onClick = { expanded = false; onSelect(opt) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    valueMillis: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    clearable: Boolean = false,
) {
    var show by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = valueMillis?.let { formatDate(it) } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { show = true })
        }
        if (clearable && valueMillis != null) {
            TextButton(onClick = { onChange(null) }) { Text("মুছুন") }
        }
    }
    if (show) {
        val state = rememberDatePickerState(initialSelectedDateMillis = valueMillis ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { onChange(state.selectedDateMillis); show = false }) { Text("ঠিক আছে") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("বাতিল") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(vertical = 4.dp))
    }
}

/** ত্রুটি/বার্তা দেখানোর ডায়ালগ ([message] null হলে কিছু দেখায় না)। */
@Composable
fun MessageDialog(message: String?, onDismiss: () -> Unit, title: String = "বার্তা") {
    if (message != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("ঠিক আছে") } },
        )
    }
}
