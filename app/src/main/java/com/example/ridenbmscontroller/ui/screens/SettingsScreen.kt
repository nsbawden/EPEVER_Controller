package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.logging.OpsLogger
import com.example.ridenbmscontroller.logging.OpsLogStorageSummary
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.model.MpptState
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: AppSettings,
    mppt: MpptState,
    socDriftAh: Double = 0.0,
    worstCaseSocPercent: Int? = null,
    onSettingsChanged: (AppSettings) -> Unit,
    balanceDayToday: Boolean,
    daysUntilNextBalance: Int,
    onToggleBalanceToday: () -> Unit,
    onResetSocDrift: () -> Unit = {},
    opsLogSummary: OpsLogStorageSummary = OpsLogStorageSummary(0L, emptyList(), ""),
    onDeleteOpsLogsBeforeDate: (String) -> String = { "Unavailable" },
    onRefreshOpsLogSummary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        SettingsGroup("Charge profile") {
            IntFieldRow("Normal SOC ceiling", settings.normalSocCeilingPercent, "%", 50, 100) {
                onSettingsChanged(settings.copy(normalSocCeilingPercent = it))
            }
            NumberFieldRow("Maximum controller voltage", settings.maxBatteryVolts, "V", 12.0, 15.0) {
                onSettingsChanged(settings.copy(maxBatteryVolts = it))
            }
            NumberFieldRow("Ceiling hold voltage", settings.bmsVoltageHoldVolts, "V", 12.0, 15.0) {
                onSettingsChanged(settings.copy(bmsVoltageHoldVolts = it))
            }
            IntFieldRow("Resume SOC", settings.normalSocResumePercent, "%", 50, 99) {
                onSettingsChanged(settings.copy(normalSocResumePercent = it.coerceAtMost(settings.normalSocCeilingPercent - 1)))
            }
            NumberFieldRow("100% hold voltage", settings.fullHoldVolts, "V", 12.0, 15.0) {
                onSettingsChanged(settings.copy(fullHoldVolts = it))
            }
            IntFieldRow("Low SOC alarm", settings.lowSocAlarmPercent, "%", 0, 100) {
                onSettingsChanged(settings.copy(lowSocAlarmPercent = it))
            }
            IntFieldRow("Balance interval", settings.balanceEveryDays, "days", 1, 60) {
                onSettingsChanged(settings.copy(balanceEveryDays = it))
            }
            Button(onClick = onToggleBalanceToday, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (balanceDayToday) {
                        "Cancel Balance Today"
                    } else {
                        "Force Balance Today (next in ${daysUntilNextBalance}d)"
                    }
                )
            }
        }
        SettingsGroup("SOC drift") {
            NumberFieldRow("BMS zero deadband", settings.socDriftDeadbandAmps, "A", 0.0, 5.0) {
                onSettingsChanged(settings.copy(socDriftDeadbandAmps = it))
            }
            IntFieldRow("Worst-case SOC alarm", settings.socDriftAlarmPercent, "%", 0, 100) {
                onSettingsChanged(settings.copy(socDriftAlarmPercent = it))
            }
            SettingRow(
                "Accumulated drift",
                "%.2f Ah".format(socDriftAh)
            )
            SettingRow(
                "Worst-case SOC",
                worstCaseSocPercent?.let { "$it%" } ?: "—"
            )
            Button(onClick = onResetSocDrift, modifier = Modifier.fillMaxWidth()) {
                Text("Reset SOC Drift")
            }
        }
        SettingsGroup("Solar control") {
            IntFieldRow("Controller loop", settings.controllerLoopMs, "ms", 100, 2000) {
                onSettingsChanged(settings.copy(controllerLoopMs = it))
            }
            ToggleRow("Controller enabled", settings.controllerEnabled) {
                onSettingsChanged(settings.copy(controllerEnabled = it))
            }
        }
        SettingsGroup("Devices") {
            SettingRow("MPPT connection", "USB RS485")
            SettingRow("Battery BMS", "Xiaoxiang / JBD BLE")
            SettingRow(
                "MPPT device temp",
                mppt.deviceTempF?.let { "${"%.0f".format(it)} °F" } ?: "—"
            )
            SettingRow(
                "RTS battery temp",
                mppt.rtsTempF?.let { "${"%.0f".format(it)} °F" } ?: "—"
            )
            ToggleRow("Keep screen on", settings.keepScreenOn) {
                onSettingsChanged(settings.copy(keepScreenOn = it))
            }
        }
        SettingsGroup("Logging") {
            ToggleRow("Events log", settings.logEventsEnabled) {
                onSettingsChanged(settings.copy(logEventsEnabled = it))
            }
            ToggleRow("Telemetry log", settings.logTelemetryEnabled) {
                onSettingsChanged(settings.copy(logTelemetryEnabled = it))
            }
            ToggleRow("Controller history log", settings.logHistoryEnabled) {
                onSettingsChanged(settings.copy(logHistoryEnabled = it))
            }
            SettingRow("Storage", "${OpsLogger.formatSize(opsLogSummary.totalBytes)} / ${opsLogSummary.days.size} days")
            if (opsLogSummary.logDirectory.isNotBlank()) {
                Text(opsLogSummary.logDirectory, color = TextMuted, fontSize = 12.sp)
            }
            opsLogSummary.days.take(8).forEach { day ->
                Text(
                    "${day.dateLabel}  tel ${OpsLogger.formatSize(day.telemetryBytes)}  ev ${OpsLogger.formatSize(day.eventsBytes)}",
                    color = TextMuted
                )
            }
            Button(onClick = onRefreshOpsLogSummary, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh Log Summary")
            }
            var deleteDate by remember {
                mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis()))
            }
            var deleteMessage by remember { mutableStateOf("") }
            OutlinedTextField(
                value = deleteDate,
                onValueChange = { deleteDate = it },
                label = { Text("Delete logs before (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    deleteMessage = onDeleteOpsLogsBeforeDate(deleteDate.trim())
                    onRefreshOpsLogSummary()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete Older Logs")
            }
            if (deleteMessage.isNotBlank()) {
                Text(deleteMessage, color = if (opsLogSummary.totalBytes >= OpsLogger.STORAGE_WARN_BYTES) WarningOrange else TextMuted)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, fontWeight = FontWeight.SemiBold, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun NumberFieldRow(
    label: String,
    value: Double,
    unit: String,
    min: Double,
    max: Double,
    onValueChange: (Double) -> Unit
) {
    var text by remember(label) { mutableStateOf(value.formatSetting()) }
    var focused by remember(label) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    fun commitText(currentText: String, fallback: Double) {
        val committed = currentText.toDoubleOrNull()?.coerceIn(min, max) ?: fallback
        text = committed.formatSetting()
        if (kotlin.math.abs(committed - latestValue) > 0.000_5) {
            latestOnValueChange(committed)
        }
    }

    LaunchedEffect(value, focused) {
        if (!focused) text = value.formatSetting()
    }

    DisposableEffect(label) {
        onDispose {
            val committed = latestText.toDoubleOrNull()?.coerceIn(min, max) ?: latestValue
            if (kotlin.math.abs(committed - latestValue) > 0.000_5) {
                latestOnValueChange(committed)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1.15f)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    text = next
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText(text, value)
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) {
                            commitText(text, value)
                        }
                    }
            )
            Text(unit, color = TextMuted)
        }
    }
}

@Composable
private fun IntFieldRow(
    label: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    var focused by remember(label) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    fun commitText(currentText: String, fallback: Int) {
        val committed = currentText.toIntOrNull()?.coerceIn(min, max) ?: fallback
        text = committed.toString()
        if (committed != latestValue) {
            latestOnValueChange(committed)
        }
    }

    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }

    DisposableEffect(label) {
        onDispose {
            val committed = latestText.toIntOrNull()?.coerceIn(min, max) ?: latestValue
            if (committed != latestValue) {
                latestOnValueChange(committed)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1.15f)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    text = next
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText(text, value)
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) {
                            commitText(text, value)
                        }
                    }
            )
            Text(unit, color = TextMuted)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Double.formatSetting(): String {
    return if (this < 1.0) {
        "%.3f".format(this).trimEnd('0').trimEnd('.')
    } else {
        "%.2f".format(this).trimEnd('0').trimEnd('.')
    }
}

