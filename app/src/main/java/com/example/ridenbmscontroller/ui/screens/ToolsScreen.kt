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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.ridenbmscontroller.epever.EpeverBatteryProfile
import com.example.ridenbmscontroller.epever.EpeverClock
import com.example.ridenbmscontroller.epever.EpeverExtendedSettings
import com.example.ridenbmscontroller.model.MpptState
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import kotlinx.coroutines.delay

@Composable
fun ToolsScreen(
    unattendedMode: Boolean,
    profile: EpeverBatteryProfile,
    extended: EpeverExtendedSettings,
    epeverConnected: Boolean,
    epeverClock: EpeverClock?,
    mppt: MpptState,
    bmsVolts: Double,
    bmsAmps: Double,
    status: String,
    modifier: Modifier = Modifier,
    onRefreshFromEpever: () -> Unit,
    onUnattendedModeChanged: (Boolean) -> Unit,
    chargeOutputOn: Boolean,
    onChargeOutputChanged: (Boolean) -> Unit,
    onProfileChanged: (EpeverBatteryProfile, writeToEpever: Boolean) -> Unit,
    onExtendedChanged: (EpeverExtendedSettings) -> Unit,
    voltageCal: Double,
    onVoltageCalChanged: (Double) -> Unit,
    onSyncClockFromPhone: () -> Unit,
    onClearGeneratedEnergy: () -> Unit
) {
    var phoneClock by remember { mutableStateOf(EpeverClock.fromPhoneNow()) }
    LaunchedEffect(Unit) {
        onRefreshFromEpever()
        while (true) {
            phoneClock = EpeverClock.fromPhoneNow()
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("EPEVER profile", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Mode", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (unattendedMode) "Unattended" else "Live",
                            color = if (unattendedMode) WarningOrange else BatteryGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Switch(
                        checked = unattendedMode,
                        onCheckedChange = onUnattendedModeChanged
                    )
                }
                Text(
                    if (unattendedMode) {
                        "Phone is monitor-only. EPEVER is using the battery profile below."
                    } else {
                        "Phone is controlling charge. Overlay voltages show the intended unattended set, not the live CV."
                    },
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Text(
                    if (epeverConnected) status else "EPEVER disconnected",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Button(onClick = onRefreshFromEpever, modifier = Modifier.fillMaxWidth(), enabled = epeverConnected) {
                    Text("Read EPEVER now")
                }
            }
        }

        ProfileGroup("Charge output") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MPPT charge output", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (chargeOutputOn) "On" else "Off — live writes paused",
                        color = if (chargeOutputOn) BatteryGreen else WarningOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
                Switch(
                    checked = chargeOutputOn,
                    onCheckedChange = onChargeOutputChanged,
                    enabled = epeverConnected
                )
            }
            Text(
                if (chargeOutputOn) {
                    "On restores the previous voltage block and resumes the live loop."
                } else {
                    "Tracer-AN coil 0x0000 does not stop charge. Off writes the full battery block to 13.00 V and pauses further writes. Watch Boost/Float and MPPT A."
                },
                color = TextMuted,
                fontSize = 13.sp
            )
        }

        ProfileGroup("Controller clock") {
            ClockRow("EPEVER", epeverClock?.takeIf { it.valid }?.format() ?: if (epeverConnected) "Reading…" else "Disconnected")
            ClockRow("Phone", phoneClock.format())
            Text(
                "Daily / monthly / yearly watt-hours reset at EPEVER midnight.",
                color = TextMuted,
                fontSize = 13.sp
            )
            Button(
                onClick = onSyncClockFromPhone,
                modifier = Modifier.fillMaxWidth(),
                enabled = epeverConnected
            ) {
                Text("Sync from phone")
            }
        }

        ProfileGroup("Generated energy") {
            ClockRow("Today", mppt.generatedWhToday.formatWattHoursLong())
            ClockRow("This month", mppt.generatedWhMonth.formatWattHoursLong())
            ClockRow("This year", mppt.generatedWhYear.formatWattHoursLong())
            ClockRow("Total", mppt.generatedWhTotal.formatWattHoursLong())
            ClockRow("Max PV today", mppt.maxPvVoltsToday.formatVolts())
            ClockRow("Min PV today", mppt.minPvVoltsToday.formatVolts())
            ClockRow("Max battery today", mppt.maxBatteryVoltsToday.formatVolts())
            ClockRow("Min battery today", mppt.minBatteryVoltsToday.formatVolts())
            Text(
                if (epeverConnected) status else "EPEVER disconnected",
                color = TextMuted,
                fontSize = 13.sp
            )
            Text(
                "Reset clears today, month, year, and lifetime on the EPEVER.",
                color = TextMuted,
                fontSize = 13.sp
            )
            Button(
                onClick = onClearGeneratedEnergy,
                modifier = Modifier.fillMaxWidth(),
                enabled = epeverConnected
            ) {
                Text("Reset generated energy")
            }
        }

        ProfileGroup("Battery") {
            TypeRow(profile.batteryType) { type ->
                onProfileChanged(profile.copy(batteryType = type), true)
            }
            IntField("Capacity", profile.capacityAh, "Ah", 1, 3000) {
                onProfileChanged(profile.copy(capacityAh = it), true)
            }
            NumberField("Temp compensation", profile.tempCompensationMvv, "mV/°C/2V", 0.0, 9.0) {
                onProfileChanged(profile.copy(tempCompensationMvv = it), true)
            }
            CycleRow("Rated battery V", extended.batteryRatedVoltageLabel) {
                onExtendedChanged(
                    extended.copy(
                        batteryRatedVoltageCode = EpeverExtendedSettings.nextRatedVoltageCode(
                            extended.batteryRatedVoltageCode
                        )
                    )
                )
            }
            NumberField("Batt temp warn high", extended.batteryTempWarnHighC, "°C", 0.0, 90.0) {
                onExtendedChanged(extended.copy(batteryTempWarnHighC = it))
            }
            NumberField("Batt temp warn low", extended.batteryTempWarnLowC, "°C", -40.0, 10.0) {
                onExtendedChanged(extended.copy(batteryTempWarnLowC = it))
            }
            NumberField("Inner temp limit", extended.innerTempLimitC, "°C", 40.0, 100.0) {
                onExtendedChanged(extended.copy(innerTempLimitC = it))
            }
            NumberField("Inner temp recover", extended.innerTempRecoverC, "°C", 30.0, 95.0) {
                onExtendedChanged(extended.copy(innerTempRecoverC = it))
            }
            NumberField("Power temp limit", extended.powerTempLimitC, "°C", 40.0, 100.0) {
                onExtendedChanged(extended.copy(powerTempLimitC = it))
            }
            NumberField("Power temp recover", extended.powerTempRecoverC, "°C", 30.0, 95.0) {
                onExtendedChanged(extended.copy(powerTempRecoverC = it))
            }
            NumberField("Line impedance", extended.lineImpedanceMOhm, "mΩ", 0.0, 200.0) {
                onExtendedChanged(extended.copy(lineImpedanceMOhm = it))
            }
            Text(
                "Live writes 0 mΩ so the commanded CV is not raised. This value is used in Unattended and No BMS.",
                color = TextMuted,
                fontSize = 13.sp
            )
            ClockRow(
                "Live ΔV / A",
                liveLineImpedanceMOhm(mppt.batteryVolts, bmsVolts, mppt.chargeAmps, bmsAmps, voltageCal)
                    ?.let { "%.2f mΩ".format(it) }
                    ?: "— (need charge current)"
            )
        }

        ProfileGroup(if (unattendedMode) "Charge voltages" else "Unattended charge voltages") {
            NumberField("EPEVER voltage cal", voltageCal, "V", -0.50, 0.50, onVoltageCalChanged)
            Text(
                "Positive if MPPT V reads high. Added only when writing the Unattended profile. Live CV, park, and reads stay raw. 0 = off. Read EPEVER does not overwrite these voltages while cal is not 0.",
                color = TextMuted,
                fontSize = 13.sp
            )
            NumberField("High voltage disconnect", profile.highVoltageDisconnect, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(highVoltageDisconnect = it), unattendedMode)
            }
            NumberField("Charging limit", profile.chargingLimitVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(chargingLimitVolts = it), unattendedMode)
            }
            NumberField("Over voltage reconnect", profile.overVoltageReconnect, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(overVoltageReconnect = it), unattendedMode)
            }
            NumberField("Boost", profile.boostVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(boostVolts = it), unattendedMode)
            }
            NumberField("Float", profile.floatVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(floatVolts = it), unattendedMode)
            }
            NumberField("Boost reconnect", profile.boostReconnectVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(boostReconnectVolts = it), unattendedMode)
            }
            IntField("Boost duration", extended.boostDurationMin, "min", 10, 180) {
                onExtendedChanged(extended.copy(boostDurationMin = it))
            }
        }

        ProfileGroup("SOC charging") {
            Text(
                if (extended.chargeManagementSoc) {
                    "SOC mode: Unattended uses Charge percent as the Tracer's own full-charge SOC (Ah count, not BMS). Discharge percent is a load cutoff — unused on this 80A. Live still uses BMS SOC."
                } else {
                    "Voltage mode (in use): Unattended follows the voltage cards. Charge percent and Discharge percent are stored but ignored. Live uses BMS SOC, not these."
                },
                color = TextMuted,
                fontSize = 13.sp
            )
            CycleRow("Charge management", extended.chargeManagementLabel) {
                onExtendedChanged(extended.copy(chargeManagementSoc = !extended.chargeManagementSoc))
            }
            IntField("Charge percent", extended.chargePercent, "%", 0, 100) {
                onExtendedChanged(extended.copy(chargePercent = it))
            }
            IntField("Discharge percent", extended.dischargePercent, "%", 0, 100) {
                onExtendedChanged(extended.copy(dischargePercent = it))
            }
        }

        ProfileGroup("Equalize") {
            Text(
                "Duration 0 min disables Equalize (BMS already balances). Cycle and voltage are unused while duration is 0. Keep voltage at or below Charging limit — do not set it to 0 V.",
                color = TextMuted,
                fontSize = 13.sp
            )
            NumberField("Equalize voltage", profile.equalizeVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(equalizeVolts = it), unattendedMode)
            }
            IntField("Equalize duration", extended.equalizeDurationMin, "min", 0, 180) {
                onExtendedChanged(extended.copy(equalizeDurationMin = it))
            }
            IntField("Equalize cycle", extended.equalizeCycleDays, "d", 0, 255) {
                onExtendedChanged(extended.copy(equalizeCycleDays = it))
            }
        }

        ProfileGroup("Low-voltage protection") {
            NumberField("Low voltage reconnect", profile.lowVoltageReconnect, "V", 10.0, 14.0) {
                onProfileChanged(profile.copy(lowVoltageReconnect = it), true)
            }
            NumberField("Under voltage recover", profile.underVoltageRecover, "V", 10.0, 14.0) {
                onProfileChanged(profile.copy(underVoltageRecover = it), true)
            }
            NumberField("Under voltage warning", profile.underVoltageWarning, "V", 10.0, 14.0) {
                onProfileChanged(profile.copy(underVoltageWarning = it), true)
            }
            NumberField("Low voltage disconnect", profile.lowVoltageDisconnect, "V", 10.0, 14.0) {
                onProfileChanged(profile.copy(lowVoltageDisconnect = it), true)
            }
            NumberField("Discharging limit", profile.dischargingLimitVolts, "V", 10.0, 14.0) {
                onProfileChanged(profile.copy(dischargingLimitVolts = it), true)
            }
        }
    }
}

@Composable
private fun ProfileGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun Double.formatWattHoursLong(): String {
    val absValue = kotlin.math.abs(this)
    return when {
        absValue >= 1_000_000.0 -> "%.2f MWH".format(this / 1_000_000.0)
        absValue >= 10_000.0 -> "%.1f kWH".format(this / 1_000.0)
        else -> "%.1f WH".format(this)
    }
}

@Composable
private fun ClockRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun Double?.formatVolts(): String = this?.takeIf { it > 0.0 }?.let { "%.2f V".format(it) } ?: "—"

private fun liveLineImpedanceMOhm(
    mpptVolts: Double,
    bmsVolts: Double,
    mpptAmps: Double,
    bmsAmps: Double,
    voltageCal: Double
): Double? {
    if (mpptVolts <= 0.0 || bmsVolts <= 0.0) return null
    val amps = maxOf(mpptAmps, bmsAmps)
    if (amps < 3.0) return null
    val milliOhm = (mpptVolts - voltageCal - bmsVolts) / amps * 1000.0
    return milliOhm.takeIf { it in -50.0..200.0 }
}

@Composable
private fun CycleRow(label: String, value: String, onCycle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted, modifier = Modifier.weight(1f))
        Button(onClick = onCycle) {
            Text(value)
        }
    }
}

@Composable
private fun TypeRow(type: Int, onTypeChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Battery type", color = TextMuted)
        Button(onClick = { onTypeChange((type + 1) % 6) }) {
            Text(EpeverBatteryProfile.batteryTypeLabel(type))
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    unit: String,
    min: Double,
    max: Double,
    onValueChange: (Double) -> Unit
) {
    var text by remember(label) { mutableStateOf("%.2f".format(value)) }
    var focused by remember(label) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    fun commit(current: String, fallback: Double) {
        val committed = current.toDoubleOrNull()?.coerceIn(min, max) ?: fallback
        text = "%.2f".format(committed)
        if (kotlin.math.abs(committed - latestValue) > 0.000_5) {
            latestOnValueChange(committed)
        }
    }

    LaunchedEffect(value, focused) {
        if (!focused) text = "%.2f".format(value)
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
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commit(text, value)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) commit(text, value)
                    }
            )
            Text(unit, color = TextMuted)
        }
    }
}

@Composable
private fun IntField(
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

    fun commit(current: String, fallback: Int) {
        val committed = current.toIntOrNull()?.coerceIn(min, max) ?: fallback
        text = committed.toString()
        if (committed != latestValue) latestOnValueChange(committed)
    }

    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }
    DisposableEffect(label) {
        onDispose {
            val committed = latestText.toIntOrNull()?.coerceIn(min, max) ?: latestValue
            if (committed != latestValue) latestOnValueChange(committed)
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
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commit(text, value)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) commit(text, value)
                    }
            )
            Text(unit, color = TextMuted)
        }
    }
}
