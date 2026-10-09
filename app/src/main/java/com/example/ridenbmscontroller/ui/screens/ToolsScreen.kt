package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.logging.OpsLogger
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.CurrentRose
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.ui.theme.PowerBlue
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

private val toolTabs = listOf("Events", "SOC Hold", "Logs", "Telemetry")

@Composable
fun ToolsScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    onDeleteOpsLogsBeforeDate: (String) -> String = { "Unavailable" },
    onRefreshOpsLogSummary: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Tools", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            TabRow(selectedTabIndex = selectedTab) {
                toolTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                0 -> EventsTool(state)
                1 -> TuningTool(state)
                2 -> OpsLogsTool(state, onDeleteOpsLogsBeforeDate, onRefreshOpsLogSummary)
                else -> TelemetryTool(state)
            }
        }
    }
}

@Composable
private fun EventsTool(state: AppState) {
    ToolCard("Controller Events") {
        Text(
            "Recent mode changes and alerts. Full timeline is in daily ops_logs/*_events.csv.",
            color = TextMuted,
            fontSize = 13.sp
        )
        if (state.events.isEmpty()) {
            Text("No controller events yet.", color = TextMuted)
        } else {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                state.events.forEach {
                    Text(
                        text = it,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun TuningTool(state: AppState) {
    val target = state.settings.bmsVoltageHoldVolts
    val actual = state.battery.volts
    val error = actual - target
    val targetReached = state.battery.socPercent >= state.controller.socTargetPercent
    val guidance = when {
        !state.controller.enabled -> "Controller is off."
        !state.controller.epeverConnected -> "Waiting for EPEVER USB telemetry."
        !state.controller.bmsConnected -> "BMS offline: MPPT uses its own voltage setpoints."
        state.controller.pvMode == "Alarm" -> "Charging is inhibited by BMS alarm handling."
        targetReached && abs(error) <= 0.03 -> "Hold is on target. BMS voltage is within 0.03 V of ${"%.2f".format(target)} V."
        targetReached && error > 0.03 -> "BMS is above hold. The app should lower EPEVER CV using the MPPT-minus-BMS offset."
        targetReached && error < -0.03 -> "BMS is below hold. The app should raise EPEVER CV, or solar may be short of load."
        else -> "SOC target has not been reached; EPEVER is in native bulk."
    }

    ToolCard("BMS Voltage Hold") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniTile(
                label = "BMS V",
                value = "%.2f".format(actual),
                unit = "V",
                color = CurrentRose,
                modifier = Modifier.weight(1f)
            )
            MiniTile("Hold Target", "%.2f".format(target), "V", BatteryGreen, Modifier.weight(1f))
            MiniTile(
                label = "Error",
                value = "%+.2f".format(error),
                unit = "V",
                color = if (abs(error) <= 0.03) BatteryGreen else WarningOrange,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniTile(
                label = "SOC Target",
                value = state.controller.socTargetPercent.toString(),
                unit = "%",
                color = if (state.controller.socTargetPercent >= 100) WarningOrange else BatteryGreen,
                modifier = Modifier.weight(1f)
            )
            MiniTile("Command CV", "%.2f".format(state.controller.commandCvVolts), "V", VoltageAmber, Modifier.weight(1f))
            MiniTile("Offset", "%+.2f".format(state.controller.voltageOffset), "V", VoltageAmber, Modifier.weight(1f))
        }
        MiniTile("Mode", state.controller.pvMode, "", modeColor(state.controller.pvMode), Modifier.fillMaxWidth())
        Text(guidance, color = TextMuted, fontSize = 13.sp)
        Text(state.controller.status, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun OpsLogsTool(
    state: AppState,
    onDeleteOpsLogsBeforeDate: (String) -> String,
    onRefreshOpsLogSummary: () -> Unit
) {
    var deleteDate by rememberSaveable {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis()))
    }
    var actionMessage by rememberSaveable { mutableStateOf("") }
    val summary = state.opsLogSummary

    ToolCard("Operational Logging") {
        Text(
            "Telemetry is written more often during Hold and Alarm, then slows down in Bulk. " +
                "Events are timestamped on every policy and charge-coil change.",
            color = TextMuted,
            fontSize = 13.sp
        )
        MiniTile(
            "Live",
            state.controller.pvMode,
            "CV ${"%.2f".format(state.controller.commandCvVolts)}V | Offset ${"%+.2f".format(state.controller.voltageOffset)}V",
            modeColor(state.controller.pvMode),
            Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniTile(
                "Charge",
                if (state.controller.chargingOn) "ON" else "OFF",
                "SOC ${state.controller.socTargetPercent}%",
                if (state.controller.chargingOn) BatteryGreen else TextMuted,
                Modifier.weight(1f)
            )
            MiniTile(
                "Total Logs",
                OpsLogger.formatSize(summary.totalBytes),
                "${summary.days.size} days",
                if (summary.totalBytes >= OpsLogger.STORAGE_WARN_BYTES) WarningOrange else PowerBlue,
                Modifier.weight(1f)
            )
        }
        if (summary.logDirectory.isNotBlank()) {
            Text("Path: ${summary.logDirectory}", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Button(onClick = { onRefreshOpsLogSummary() }, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh Log Summary")
        }
    }

    ToolCard("Daily Log Files") {
        if (summary.days.isEmpty()) {
            Text("No log files yet.", color = TextMuted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.days.forEach { day ->
                    Text(
                        text = "${day.dateLabel}  telemetry ${OpsLogger.formatSize(day.telemetryBytes)}  " +
                            "events ${OpsLogger.formatSize(day.eventsBytes)}  " +
                            "total ${OpsLogger.formatSize(day.totalBytes)}",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    ToolCard("Delete Old Logs") {
        Text(
            "Remove all daily log files strictly before the date below. Today's logs are kept.",
            color = TextMuted,
            fontSize = 13.sp
        )
        OutlinedTextField(
            value = deleteDate,
            onValueChange = { deleteDate = it },
            label = { Text("Delete logs before (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                actionMessage = onDeleteOpsLogsBeforeDate(deleteDate.trim())
                onRefreshOpsLogSummary()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Delete Older Logs")
        }
        if (actionMessage.isNotBlank()) {
            Text(actionMessage, color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TelemetryTool(state: AppState) {
    ToolCard("Live Telemetry") {
        state.logs.forEach {
            Text(it, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ToolCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MiniTile(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(unit, color = TextMuted, fontSize = 9.sp)
            }
        }
    }
}

private fun modeColor(mode: String): Color {
    return when (mode) {
        "Hold" -> BatteryGreen
        "Bulk" -> PowerBlue
        "Alarm" -> WarningOrange
        "Voltage" -> VoltageAmber
        else -> TextMuted
    }
}
