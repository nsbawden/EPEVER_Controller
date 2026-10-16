package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.epever.EpeverChargeStage
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.CurrentRose
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.sensors.BarometricPressure
import com.example.ridenbmscontroller.ui.theme.PowerBlue
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.TextPrimary
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun DashboardScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    onSilenceLowSocAlarm: () -> Unit = {},
    onSilenceSocDriftAlarm: () -> Unit = {},
    onPressureTileClick: () -> Unit = {},
    onToggleBalanceToday: () -> Unit = {},
    onManualCvChanged: (enabled: Boolean, volts: Double) -> Unit = { _, _ -> }
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BatteryGaugeCard(state, onSilenceLowSocAlarm, onSilenceSocDriftAlarm)
        MpptPanel(state, onPressureTileClick, onToggleBalanceToday, onManualCvChanged)
    }
}

@Composable
private fun BatteryGaugeCard(
    state: AppState,
    onSilenceLowSocAlarm: () -> Unit,
    onSilenceSocDriftAlarm: () -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Screen position: keep gauge where 20.dp felt right; in-tile top inset
            // clears the thick arc stroke, so outer offset is reduced by the same delta.
            .offset(y = 6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 0.dp)
        ) {
            HalfCircleSocGauge(state)
            if (state.alerts.lowSocAlarmActive) {
                Spacer(Modifier.height(8.dp))
                LowSocAlertRow(state, onSilenceLowSocAlarm)
            }
            if (state.alerts.socDriftAlarmActive) {
                Spacer(Modifier.height(8.dp))
                SocDriftAlertRow(state, onSilenceSocDriftAlarm)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ValueTile("Battery V", "%.2f".format(state.battery.volts), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("Battery A", "%.2f".format(state.battery.amps), "A", CurrentRose, Modifier.weight(1f))
                val bmsWatts = formatBmsWatts(state.battery.watts)
                ValueTile("BMS Watts", bmsWatts.value, bmsWatts.unit, PowerBlue, Modifier.weight(1f))
                ValueTile("Temp", "%.0f".format(state.battery.temperatureF), "F", WarningOrange, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HalfCircleSocGauge(state: AppState) {
    val soc = state.battery.socPercent.coerceIn(0, 100)
    val worstCaseSoc = state.battery.worstCaseSocPercent
    val lowSocActive = state.alerts.lowSocAlarmActive
    val driftAlarmActive = state.alerts.socDriftAlarmActive
    val gaugeColor = when {
        lowSocActive || driftAlarmActive -> WarningOrange
        else -> BatteryGreen
    }
    val deviceIssueText = deviceIssueText(state)
    val capacityText = formatAmpHourProgress(state.battery.remainingAh, state.battery.nominalAh)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 24.dp.toPx()
            val width = size.width
            val gaugeWidth = width * 0.86f
            val arcHeight = gaugeWidth * 0.50f
            val left = (width - gaugeWidth) / 2f
            // Enough inset for half of the 24.dp stroke so Surface does not clip the arc top.
            val top = 19.dp.toPx()
            val rect = Rect(left, top, left + gaugeWidth, top + arcHeight * 2f)
            val arcRadius = rect.width / 2f
            val capAngle = Math.toDegrees((stroke / 2f / arcRadius).toDouble()).toFloat()
            val gaugeStart = 180f + capAngle
            val gaugeSweep = 180f - capAngle * 2f
            val progressSweep = gaugeSweep * (soc / 100f)

            drawArc(
                color = PanelAlt,
                startAngle = gaugeStart,
                sweepAngle = gaugeSweep,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = gaugeColor,
                startAngle = gaugeStart,
                sweepAngle = progressSweep,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            for (tick in 0..10) {
                val angle = Math.toRadians((180.0 + tick * 18.0))
                val center = Offset(rect.center.x, rect.center.y)
                val outer = Offset(
                    x = center.x + cos(angle).toFloat() * rect.width / 2f,
                    y = center.y + sin(angle).toFloat() * rect.height / 2f
                )
                val inner = Offset(
                    x = center.x + cos(angle).toFloat() * (rect.width / 2f - 18.dp.toPx()),
                    y = center.y + sin(angle).toFloat() * (rect.height / 2f - 18.dp.toPx())
                )
                drawLine(
                    color = Color.White.copy(alpha = if (tick % 5 == 0) 0.80f else 0.38f),
                    start = inner,
                    end = outer,
                    strokeWidth = if (tick % 5 == 0) 3.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 56.dp)
        ) {
            Text(
                text = "$soc%",
                color = if (lowSocActive || driftAlarmActive) {
                    WarningOrange
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            val estText = worstCaseSoc?.let { "EST $it%" } ?: "EST --"
            val estColor = when {
                worstCaseSoc == null -> TextMuted
                soc - worstCaseSoc > 20 -> DeviceRed
                soc - worstCaseSoc > 10 -> WarningOrange
                else -> TextMuted
            }
            Text(
                text = estText,
                color = estColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (deviceIssueText != null) {
                Text(
                    text = deviceIssueText,
                    color = DeviceRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = capacityText,
                color = TextMuted,
                fontSize = 12.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (state.battery.balancing) WarningOrange else TextMuted.copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = "BAL ${if (state.battery.balancing) "ON" else "OFF"}",
                        color = if (state.battery.balancing) WarningOrange else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Delta ${state.battery.cellDeltaMv} mV",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun deviceIssueText(state: AppState): String? {
    return when {
        !state.controller.bmsConnected && !state.controller.epeverConnected -> "BMS + MPPT OFFLINE"
        !state.controller.bmsConnected -> "BMS OFFLINE"
        !state.controller.epeverConnected -> "MPPT OFFLINE"
        else -> null
    }
}

@Composable
private fun LowSocAlertRow(state: AppState, onSilenceLowSocAlarm: () -> Unit) {
    Surface(
        color = WarningOrange.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = if (state.alerts.lowSocSilenced) {
                    "LOW SOC ${state.battery.socPercent}% - silenced"
                } else {
                    "LOW SOC ${state.battery.socPercent}%"
                },
                color = WarningOrange,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (!state.alerts.lowSocSilenced) {
                Button(onClick = onSilenceLowSocAlarm) {
                    Text("Silence")
                }
            }
        }
    }
}

@Composable
private fun SocDriftAlertRow(state: AppState, onSilenceSocDriftAlarm: () -> Unit) {
    val worst = state.battery.worstCaseSocPercent ?: state.alerts.socDriftThresholdPercent
    Surface(
        color = WarningOrange.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = if (state.alerts.socDriftSilenced) {
                    "WORST-CASE SOC $worst% - silenced"
                } else {
                    "WORST-CASE SOC $worst%"
                },
                color = WarningOrange,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (!state.alerts.socDriftSilenced) {
                Button(onClick = onSilenceSocDriftAlarm) {
                    Text("Silence")
                }
            }
        }
    }
}

@Composable
private fun MpptPanel(
    state: AppState,
    onPressureTileClick: () -> Unit,
    onToggleBalanceToday: () -> Unit,
    onManualCvChanged: (enabled: Boolean, volts: Double) -> Unit
) {
    var showManualCv by remember { mutableStateOf(false) }
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SolarSplitHeader(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile(
                    "Policy",
                    state.controller.pvMode,
                    "",
                    modeColor(state.controller.pvMode),
                    Modifier.weight(1f)
                )
                ValueTile(
                    "Stage",
                    state.mppt.chargeStage.label,
                    "",
                    if (state.mppt.fault) WarningOrange else TextMuted,
                    Modifier.weight(1f)
                )
                val limit = harvestLimit(state)
                ValueTile("Limit", limit.label, "", limit.color, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("PV V", "%.2f".format(state.mppt.pvVolts), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("PV A", "%.2f".format(state.mppt.pvAmps), "A", CurrentRose, Modifier.weight(1f))
                ValueTile("PV W", "%.0f".format(state.mppt.pvWatts), "W", PowerBlue, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("MPPT V", "%.2f".format(state.mppt.batteryVolts), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("MPPT A", "%.2f".format(state.mppt.chargeAmps), "A", CurrentRose, Modifier.weight(1f))
                ValueTile("MPPT W", "%.0f".format(state.mppt.chargeWatts), "W", PowerBlue, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile(
                    "ΔV",
                    "%+.2f".format(state.controller.voltageOffset),
                    "V",
                    VoltageAmber,
                    Modifier.weight(1f)
                )
                val displayCv = if (state.settings.manualCvEnabled) {
                    state.settings.manualCvVolts
                } else {
                    state.controller.commandCvVolts
                }
                ValueTile(
                    "CV",
                    if (displayCv > 0.0) "%.2f".format(displayCv) else "—",
                    "V",
                    if (state.settings.manualCvEnabled) WarningOrange else VoltageAmber,
                    Modifier.weight(1f),
                    onClick = { showManualCv = true }
                )
                val altitudeM = state.gpsAltitudeMeters
                val stationInHg = state.barometricPressureInHg
                val pressureValue = when {
                    state.gpsAltitudeSampling -> "…"
                    stationInHg == null -> "—"
                    altitudeM != null ->
                        "%.2f".format(BarometricPressure.seaLevelInHg(stationInHg, altitudeM))
                    else -> "%.2f".format(stationInHg)
                }
                val pressureColor = when {
                    state.gpsAltitudeSampling -> WarningOrange
                    altitudeM != null -> TextPrimary
                    else -> WarningOrange
                }
                ValueTile(
                    "Pressure",
                    pressureValue,
                    "inHg",
                    pressureColor,
                    Modifier.weight(1f),
                    onClick = onPressureTileClick
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = state.energy.whToday.formatWattHours()
                ValueTile("PV Today", today.value, today.unit, TextPrimary, Modifier.weight(1f))
                ValueTile(
                    "SOC Target",
                    state.controller.socTargetPercent.toString(),
                    "%",
                    if (state.controller.socTargetPercent >= 100) WarningOrange else BatteryGreen,
                    Modifier.weight(1f),
                    onClick = onToggleBalanceToday
                )
            }
        }
    }
    if (showManualCv) {
        ManualCvDialog(
            currentAutoCv = state.controller.commandCvVolts,
            manualEnabled = state.settings.manualCvEnabled,
            manualVolts = state.settings.manualCvVolts,
            onDismiss = { showManualCv = false },
            onConfirm = { enabled, volts ->
                onManualCvChanged(enabled, volts)
                showManualCv = false
            }
        )
    }
}

@Composable
private fun ManualCvDialog(
    currentAutoCv: Double,
    manualEnabled: Boolean,
    manualVolts: Double,
    onDismiss: () -> Unit,
    onConfirm: (enabled: Boolean, volts: Double) -> Unit
) {
    var enabled by remember { mutableStateOf(manualEnabled) }
    val seed = when {
        manualEnabled -> manualVolts
        currentAutoCv > 0.0 -> currentAutoCv
        else -> manualVolts
    }
    var text by remember { mutableStateOf("%.2f".format(seed)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Charge CV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manual", fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            if (on && !manualEnabled) {
                                val auto = if (currentAutoCv > 0.0) currentAutoCv else manualVolts
                                text = "%.2f".format(auto)
                            }
                        }
                    )
                }
                if (enabled) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("CV") },
                        suffix = { Text("V") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        )
                    )
                    Text(
                        "Sent as Boost, Equalize, and Float. The value you set is what is written.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        "Live loop sets Boost, Equalize, and Float together.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val volts = text.toDoubleOrNull() ?: seed
                    onConfirm(enabled, volts)
                }
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SolarSplitHeader(state: AppState) {
    val solarWatts = state.mppt.chargeWatts.coerceAtLeast(0.0)
    val solarActive = solarWatts > 1.0
    val batteryWatts = if (solarActive) state.battery.watts.coerceAtLeast(0.0).coerceAtMost(solarWatts) else 0.0
    val loadWatts = if (solarActive) (solarWatts - batteryWatts).coerceAtLeast(0.0) else 0.0
    val total = max(solarWatts, batteryWatts + loadWatts)
    val batteryFraction = if (total > 1.0) (batteryWatts / total).toFloat().coerceIn(0f, 1f) else 0f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Solar", fontWeight = FontWeight.SemiBold)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TextMuted)) { append("Batt ") }
                    withStyle(SpanStyle(color = if (solarActive) BatteryGreen else TextMuted)) {
                        append(batteryWatts.formatWholeWatts())
                    }
                    withStyle(SpanStyle(color = TextMuted)) { append("  Load ") }
                    withStyle(SpanStyle(color = if (solarActive) VoltageAmber else TextMuted)) {
                        append(loadWatts.formatWholeWatts())
                    }
                },
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val radius = 4.dp.toPx()
            drawRoundRect(
                color = PanelAlt,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
            )
            if (solarActive && total > 1.0) {
                val batteryWidth = size.width * batteryFraction
                val loadWidth = size.width - batteryWidth
                if (batteryWidth > 0f) {
                    drawRoundRect(
                        color = BatteryGreen,
                        size = Size(batteryWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                }
                if (loadWidth > 0f) {
                    drawRoundRect(
                        color = VoltageAmber,
                        topLeft = Offset(batteryWidth, 0f),
                        size = Size(loadWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                }
            }
        }
    }
}


@Composable
internal fun ValueTile(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        color = PanelAlt,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .height(66.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(
                text = value,
                color = color,
                fontSize = value.tileValueFontSize(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(unit, color = TextMuted, fontSize = 9.sp)
        }
    }
}


private data class CompactEnergy(val value: String, val unit: String)

private val DeviceRed = Color(0xFFFF4D4D)

private fun String.tileValueFontSize() = when {
    length >= 8 -> 20.sp
    length >= 7 -> 21.sp
    length >= 6 -> 22.sp
    else -> 24.sp
}

private data class HarvestLimit(val label: String, val color: Color)

private fun harvestLimit(state: AppState): HarvestLimit {
    val pvWatts = state.mppt.pvWatts
    val pvVolts = state.mppt.pvVolts
    val mpptVolts = state.mppt.batteryVolts
    val mode = state.controller.pvMode
    val commandedCv = when {
        (mode == "Unattended" || mode == "No BMS") && state.mppt.chargeStage == EpeverChargeStage.Float ->
            state.controller.profileFloatVolts
        mode == "Unattended" || mode == "No BMS" -> state.controller.profileBoostVolts
        state.controller.commandCvVolts > 0.0 -> state.controller.commandCvVolts
        else -> state.settings.maxBatteryVolts
    }
    val noSun = pvWatts < 10.0 && pvVolts < mpptVolts + 1.0
    val pushing = state.mppt.chargeAmps > 0.4 || state.mppt.chargeWatts > 8.0
    val holdTarget = state.controller.holdVolts.takeIf { it > 0.0 }
        ?: state.settings.bmsVoltageHoldVolts
    val atHoldVoltage = mode == "Hold" && state.battery.volts >= holdTarget - 0.02
    val atCommandedCv = commandedCv > 0.0 && commandedCv - mpptVolts <= 0.08
    val iMax = state.settings.maxChargeAmps
    val live = mode == "Bulk" || mode == "Hold" || mode == "Top-up"
    val policyCv = if (mode == "Hold" || mode == "Top-up") holdTarget else state.settings.maxBatteryVolts
    val chargeAmps = state.battery.amps.coerceAtLeast(0.0)
    val limiting = live &&
        !state.settings.manualCvEnabled &&
        iMax > 0.0 &&
        (chargeAmps >= iMax || (commandedCv > 0.0 && commandedCv < policyCv - 0.05))
    return when {
        limiting -> HarvestLimit("%.0f A".format(iMax), WarningOrange)
        noSun -> HarvestLimit("Night", TextMuted)
        mode == "Off" && pushing -> HarvestLimit("Array", BatteryGreen)
        mode == "Off" || (mode == "Alarm" && !pushing) -> HarvestLimit("Off", DeviceRed)
        !pushing && !state.mppt.chargingOn -> HarvestLimit("Off", DeviceRed)
        atHoldVoltage -> HarvestLimit("Hold", WarningOrange)
        atCommandedCv && pushing && mode != "Hold" && mode != "Top-up" -> HarvestLimit("CV", VoltageAmber)
        else -> HarvestLimit("Array", BatteryGreen)
    }
}

private fun modeColor(mode: String): Color {
    return when (mode) {
        "Hold" -> BatteryGreen
        "Top-up" -> PowerBlue
        "Bulk" -> PowerBlue
        "Alarm" -> WarningOrange
        "No BMS" -> VoltageAmber
        "Unattended" -> WarningOrange
        "Off" -> DeviceRed
        "Idle" -> TextMuted
        else -> TextMuted
    }
}

private fun formatAmpHourProgress(remainingAh: Double?, nominalAh: Double?): String {
    return when {
        remainingAh != null && nominalAh != null -> {
            "${remainingAh.roundAh()} of ${nominalAh.roundAh()} Ah"
        }
        remainingAh != null -> "${remainingAh.roundAh()} Ah"
        nominalAh != null -> "of ${nominalAh.roundAh()} Ah"
        else -> "-- of -- Ah"
    }
}

private fun Double.roundAh(): String {
    return "%.2f".format(this)
}

private fun Double.formatWholeWatts(): String {
    return "%.0fW".format(this)
}

/** Compact BMS pack watts so large discharge values (e.g. -1234) fit the tile. */
private fun formatBmsWatts(watts: Double): CompactEnergy {
    return if (watts <= -1000.0) {
        CompactEnergy("%.1fK".format(watts / 1000.0), "W")
    } else {
        CompactEnergy("%.0f".format(watts), "W")
    }
}

private fun Double.formatWattHours(): CompactEnergy {
    val absValue = kotlin.math.abs(this)
    return when {
        absValue >= 1_000_000.0 -> CompactEnergy("%.2f".format(this / 1_000_000.0), "MWH")
        absValue >= 10_000.0 -> CompactEnergy("%.1f".format(this / 1_000.0), "kWH")
        absValue >= 1_000.0 -> CompactEnergy("%.0f".format(this), "WH")
        else -> CompactEnergy("%.1f".format(this), "WH")
    }
}
