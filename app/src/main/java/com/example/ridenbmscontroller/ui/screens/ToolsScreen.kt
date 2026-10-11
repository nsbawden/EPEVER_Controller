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
    epeverConnected: Boolean,
    epeverClock: EpeverClock?,
    deviceProfile: EpeverBatteryProfile?,
    commandCvVolts: Double,
    lastWriteNote: String?,
    mppt: MpptState,
    status: String,
    modifier: Modifier = Modifier,
    onRefreshFromEpever: () -> Unit,
    onUnattendedModeChanged: (Boolean) -> Unit,
    chargeOutputOn: Boolean,
    onChargeOutputChanged: (Boolean) -> Unit,
    onProfileChanged: (EpeverBatteryProfile, writeToEpever: Boolean) -> Unit,
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

        ProfileGroup("Controller settings (live)") {
            Text(
                "Read-only values from the EPEVER. MPPT V on the dashboard is measured battery voltage, not these setpoints.",
                color = TextMuted,
                fontSize = 13.sp
            )
            if (!lastWriteNote.isNullOrBlank()) {
                Text(lastWriteNote, color = WarningOrange, fontSize = 13.sp)
            }
            if (deviceProfile == null) {
                Text(if (epeverConnected) "Reading…" else "Disconnected", color = TextMuted)
            } else {
                ClockRow("Battery type", deviceProfile.batteryTypeLabel)
                ClockRow("Capacity", "${deviceProfile.capacityAh} Ah")
                ClockRow("Temp compensation", "%.2f mV/°C/2V".format(deviceProfile.tempCompensationMvv))
                if (commandCvVolts > 0.0) {
                    ClockRow("App command CV", "%.2f V".format(commandCvVolts))
                    val deviceCv = deviceProfile.regulationVolts
                    if (kotlin.math.abs(commandCvVolts - deviceCv) >= 0.05) {
                        Text(
                            "EPEVER still at %.2f V — last command did not stick.".format(deviceCv),
                            color = WarningOrange,
                            fontSize = 13.sp
                        )
                    }
                }
                ClockRow("High voltage disconnect", "%.2f V".format(deviceProfile.highVoltageDisconnect))
                ClockRow("Charging limit", "%.2f V".format(deviceProfile.chargingLimitVolts))
                ClockRow("Over voltage reconnect", "%.2f V".format(deviceProfile.overVoltageReconnect))
                ClockRow("Equalize", "%.2f V".format(deviceProfile.equalizeVolts))
                ClockRow("Boost", "%.2f V".format(deviceProfile.boostVolts))
                ClockRow("Float", "%.2f V".format(deviceProfile.floatVolts))
                ClockRow("Boost reconnect", "%.2f V".format(deviceProfile.boostReconnectVolts))
                ClockRow("Low voltage reconnect", "%.2f V".format(deviceProfile.lowVoltageReconnect))
                ClockRow("Under voltage recover", "%.2f V".format(deviceProfile.underVoltageRecover))
                ClockRow("Under voltage warning", "%.2f V".format(deviceProfile.underVoltageWarning))
                ClockRow("Low voltage disconnect", "%.2f V".format(deviceProfile.lowVoltageDisconnect))
                ClockRow("Discharging limit", "%.2f V".format(deviceProfile.dischargingLimitVolts))
            }
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
        }

        ProfileGroup(if (unattendedMode) "Charge voltages" else "Unattended charge voltages") {
            NumberField("High voltage disconnect", profile.highVoltageDisconnect, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(highVoltageDisconnect = it), unattendedMode)
            }
            NumberField("Charging limit", profile.chargingLimitVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(chargingLimitVolts = it), unattendedMode)
            }
            NumberField("Over voltage reconnect", profile.overVoltageReconnect, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(overVoltageReconnect = it), unattendedMode)
            }
            NumberField("Equalize", profile.equalizeVolts, "V", 12.0, 15.0) {
                onProfileChanged(profile.copy(equalizeVolts = it), unattendedMode)
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
