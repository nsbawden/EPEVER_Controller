package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.ble.BmsBleDevice
import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.model.EnergyCounters
import com.example.ridenbmscontroller.model.MpptState
import com.example.ridenbmscontroller.epever.EpeverUsbState
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.WarningOrange

@Composable
fun DevicesScreen(
    bleState: BmsBleUiState,
    epeverState: EpeverUsbState,
    mppt: MpptState,
    commandCvVolts: Double,
    energy: EnergyCounters,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BmsBleDevice) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllDevices by remember { mutableStateOf(false) }
    val linked = bleState.connectedDeviceName != null && epeverState.connected
    var setupExpanded by remember { mutableStateOf(false) }
    val batteryLikeDevices = bleState.devices.filter { it.looksLikeBattery }
    val visibleDevices = if (showAllDevices || batteryLikeDevices.isEmpty()) {
        bleState.devices
    } else {
        batteryLikeDevices
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Devices", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        if (linked && !setupExpanded) {
            Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { setupExpanded = true }
                        .padding(12.dp)
                ) {
                    Column {
                        Text("BMS / USB setup", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${bleState.connectedDeviceName} · USB online",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    Text("Show", color = TextMuted)
                }
            }
        }

        if (!linked || setupExpanded) {
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusRow("Bluetooth", if (bleState.bluetoothEnabled) "On" else "Off", bleState.bluetoothEnabled)
                StatusRow("Phone location", if (bleState.locationEnabled) "On" else "Off", bleState.locationEnabled)
                StatusRow("BLE permissions", if (bleState.hasPermissions) "Granted" else "Needed", bleState.hasPermissions)
                StatusRow("BMS scan", bleState.status, bleState.scanning || bleState.devices.isNotEmpty())
                StatusRow(
                    "BMS connection",
                    bleState.connectedDeviceName ?: if (bleState.connecting) "Connecting" else "Disconnected",
                    bleState.connectedDeviceName != null
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (bleState.hasPermissions) onStartScan() else onRequestPermissions()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (bleState.hasPermissions) "Scan BMS" else "Allow BLE")
                    }
                    OutlinedButton(
                        onClick = onStopScan,
                        enabled = bleState.scanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop")
                    }
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = bleState.connectedDeviceName != null || bleState.connecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect BMS")
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Discovered BLE Devices (${visibleDevices.size}/${bleState.devices.size})",
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All", color = TextMuted)
                        Switch(
                            checked = showAllDevices || batteryLikeDevices.isEmpty(),
                            onCheckedChange = { showAllDevices = it },
                            enabled = batteryLikeDevices.isNotEmpty()
                        )
                    }
                }
                if (visibleDevices.isEmpty()) {
                    Text(
                        if (bleState.devices.isEmpty()) {
                            "No devices found yet."
                        } else {
                            "No battery-like devices shown. Turn on All to inspect every BLE device."
                        },
                        color = TextMuted
                    )
                } else {
                    visibleDevices.forEach { device ->
                        Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(device.name, fontWeight = FontWeight.SemiBold)
                                    Button(
                                        onClick = { onConnect(device) },
                                        enabled = !bleState.connecting && bleState.connectedDeviceAddress != device.address
                                    ) {
                                        Text(if (bleState.connectedDeviceAddress == device.address) "Connected" else "Connect")
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(device.address, color = TextMuted)
                                    Text("${device.rssi} dBm", color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("EPEVER USB", fontWeight = FontWeight.SemiBold)
                StatusRow("Connection", if (epeverState.connected) "Online" else "Offline", epeverState.connected)
                StatusRow("Permission", if (epeverState.permissionNeeded) "Needed" else "OK", !epeverState.permissionNeeded)
                TelemetryRow("Status", epeverState.status)
                epeverState.deviceName?.let { TelemetryRow("Device", it) }
                if (epeverState.vendorId != null && epeverState.productId != null) {
                    TelemetryRow("VID/PID", "%04X:%04X".format(epeverState.vendorId, epeverState.productId))
                }
                val telemetry = epeverState.telemetry
                TelemetryRow("PV", "%.2f V / %.2f A / %.0f W".format(telemetry.pvVolts, telemetry.pvAmps, telemetry.pvWatts))
                TelemetryRow("MPPT battery", "%.2f V / %.2f A".format(telemetry.batteryVolts, telemetry.chargeAmps))
                TelemetryRow("Stage", telemetry.chargeStage.label)
                TelemetryRow("Generated today", "%.0f Wh".format(telemetry.generatedWhToday))
            }
        }
        if (linked && setupExpanded) {
            OutlinedButton(onClick = { setupExpanded = false }, modifier = Modifier.fillMaxWidth()) {
                Text("Hide setup")
            }
        }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Controller telemetry", fontWeight = FontWeight.SemiBold)
                Text(
                    "Read-only values from the EPEVER. MPPT V on the dashboard is measured battery voltage, not these setpoints.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                val lastWriteNote = epeverState.lastWriteNote
                if (!lastWriteNote.isNullOrBlank()) {
                    Text(lastWriteNote, color = WarningOrange, fontSize = 13.sp)
                }
                val deviceProfile = epeverState.deviceProfile
                Text("Live voltages", fontWeight = FontWeight.SemiBold)
                TelemetryRow(
                    "MPPT T",
                    mppt.deviceTempF?.let { "%.0f °F".format(it) } ?: "—"
                )
                TelemetryRow(
                    "RTS T",
                    mppt.rtsTempF?.let { "%.0f °F".format(it) } ?: "—"
                )
                if (deviceProfile == null) {
                    Text(if (epeverState.connected) "Reading…" else "Disconnected", color = TextMuted)
                } else {
                    if (commandCvVolts > 0.0) {
                        TelemetryRow("App command CV", "%.2f V".format(commandCvVolts))
                        val deviceCv = deviceProfile.regulationVolts
                        if (kotlin.math.abs(commandCvVolts - deviceCv) >= 0.05) {
                            Text(
                                "EPEVER still at %.2f V — last command did not stick.".format(deviceCv),
                                color = WarningOrange,
                                fontSize = 13.sp
                            )
                        }
                    }
                    TelemetryRow("High voltage disconnect", "%.2f V".format(deviceProfile.highVoltageDisconnect))
                    TelemetryRow("Charging limit", "%.2f V".format(deviceProfile.chargingLimitVolts))
                    TelemetryRow("Over voltage reconnect", "%.2f V".format(deviceProfile.overVoltageReconnect))
                    TelemetryRow("Equalize", "%.2f V".format(deviceProfile.equalizeVolts))
                    TelemetryRow("Boost", "%.2f V".format(deviceProfile.boostVolts))
                    TelemetryRow("Float", "%.2f V".format(deviceProfile.floatVolts))
                    TelemetryRow("Boost reconnect", "%.2f V".format(deviceProfile.boostReconnectVolts))
                    TelemetryRow("Low voltage reconnect", "%.2f V".format(deviceProfile.lowVoltageReconnect))
                    TelemetryRow("Under voltage recover", "%.2f V".format(deviceProfile.underVoltageRecover))
                    TelemetryRow("Under voltage warning", "%.2f V".format(deviceProfile.underVoltageWarning))
                    TelemetryRow("Low voltage disconnect", "%.2f V".format(deviceProfile.lowVoltageDisconnect))
                    TelemetryRow("Discharging limit", "%.2f V".format(deviceProfile.dischargingLimitVolts))
                }

                Text("Status / rated", fontWeight = FontWeight.SemiBold)
                if (deviceProfile != null) {
                    TelemetryRow("Battery type", deviceProfile.batteryTypeLabel)
                    TelemetryRow("Capacity", "${deviceProfile.capacityAh} Ah")
                    TelemetryRow("Temp compensation", "%.2f mV/°C/2V".format(deviceProfile.tempCompensationMvv))
                }
                TelemetryRow("Controller SOC", mppt.controllerSocPercent?.let { "$it %" } ?: "—")
                TelemetryRow("System rated V", mppt.systemRatedVolts.formatVolts())
                TelemetryRow("Charging mode", mppt.chargingModeText ?: "—")
                TelemetryRow("Rated PV", mppt.ratedPvVolts.formatRated(mppt.ratedPvAmps, mppt.ratedPvWatts))
                TelemetryRow("Rated charge", mppt.ratedChargeVolts.formatRated(mppt.ratedChargeAmps, mppt.ratedChargeWatts))
                TelemetryRow("Battery status", mppt.batteryStatusText ?: "—")
                TelemetryRow("Charge status", mppt.chargeStatusText ?: "—")
                TelemetryRow("Net battery current", mppt.netBatteryAmps?.let { "%.2f A".format(it) } ?: "—")

                val deviceExtended = epeverState.extendedSettings
                if (deviceExtended != null) {
                    Text("Extras", fontWeight = FontWeight.SemiBold)
                    TelemetryRow("Rated battery V", deviceExtended.batteryRatedVoltageLabel)
                    TelemetryRow("Equalize cycle", "${deviceExtended.equalizeCycleDays} d")
                    TelemetryRow("Equalize duration", "${deviceExtended.equalizeDurationMin} min")
                    TelemetryRow("Boost duration", "${deviceExtended.boostDurationMin} min")
                    TelemetryRow("Charge %", "${deviceExtended.chargePercent} %")
                    TelemetryRow("Discharge %", "${deviceExtended.dischargePercent} %")
                    TelemetryRow("Charge management", deviceExtended.chargeManagementLabel)
                    TelemetryRow("Batt temp warn high", "%.1f °C".format(deviceExtended.batteryTempWarnHighC))
                    TelemetryRow("Batt temp warn low", "%.1f °C".format(deviceExtended.batteryTempWarnLowC))
                    TelemetryRow("Inner temp limit", "%.1f °C".format(deviceExtended.innerTempLimitC))
                    TelemetryRow("Inner temp recover", "%.1f °C".format(deviceExtended.innerTempRecoverC))
                    TelemetryRow("Power temp limit", "%.1f °C".format(deviceExtended.powerTempLimitC))
                    TelemetryRow("Power temp recover", "%.1f °C".format(deviceExtended.powerTempRecoverC))
                    TelemetryRow("Line impedance", "%.2f mΩ".format(deviceExtended.lineImpedanceMOhm))
                }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BMS Telemetry", fontWeight = FontWeight.SemiBold)
                val telemetry = bleState.telemetry
                if (telemetry.packVoltage == null && telemetry.cellVoltages.isEmpty()) {
                    Text("No live BMS values yet.", color = TextMuted)
                } else {
                    TelemetryRow("BMS Wh today", energy.bmsWhToday.formatWattHoursLong())
                    TelemetryRow("BMS Wh yesterday", energy.bmsWhYesterday.formatWattHoursLong())
                    TelemetryRow("Solar load Wh today", (energy.whToday - energy.bmsWhToday).coerceAtLeast(0.0).formatWattHoursLong())
                    TelemetryRow("Solar load Wh yesterday", (energy.whYesterday - energy.bmsWhYesterday).coerceAtLeast(0.0).formatWattHoursLong())
                    TelemetryRow("Pack voltage", telemetry.packVoltage?.let { "%.2f V".format(it) } ?: "-")
                    TelemetryRow("Pack current", telemetry.packCurrent?.let { "%.2f A".format(it) } ?: "-")
                    TelemetryRow("SOC", telemetry.socPercent?.let { "$it%" } ?: "-")
                    TelemetryRow("Capacity", formatCapacity(telemetry.remainingAh, telemetry.nominalAh))
                    TelemetryRow("Temperature", telemetry.temperatureF?.let { "%.1f F".format(it) } ?: "-")
                    TelemetryRow("Cells", telemetry.cellCount?.toString() ?: telemetry.cellVoltages.size.toString())
                    TelemetryRow("Cell delta", telemetry.cellDeltaMv?.let { "$it mV" } ?: "-")
                    TelemetryRow("Balancing", telemetry.balancingActive?.let { if (it) "On" else "Off" } ?: "-")
                    TelemetryRow("FET status", telemetry.fetStatus ?: "-")
                    TelemetryRow(
                        "Alarm counts",
                        "${telemetry.activeProtectionAlarmCount} active / ${telemetry.monitoredProtectionAlarmCount} monitored"
                    )
                    TelemetryRow(
                        "Charge stops",
                        "${telemetry.activeChargeBlockingAlarmCount} active / ${telemetry.monitoredChargeBlockingAlarmCount} monitored"
                    )
                    TelemetryRow(
                        "Active protection",
                        telemetry.protectionStatus?.let {
                            val hex = "0x%04X".format(it)
                            if (telemetry.protectionAlarmNames.isEmpty()) {
                                "None ($hex)"
                            } else {
                                "${telemetry.protectionAlarmNames.joinToString()} ($hex)"
                            }
                        } ?: "-"
                    )
                    if (telemetry.cellVoltages.isNotEmpty()) {
                        Text("Cell voltages", fontWeight = FontWeight.SemiBold)
                        telemetry.cellVoltages.forEachIndexed { index, volts ->
                            TelemetryRow("Cell ${index + 1}", "%.3f V".format(volts))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatCapacity(remainingAh: Double?, nominalAh: Double?): String {
    return when {
        remainingAh != null && nominalAh != null -> "%.2f / %.2f Ah".format(remainingAh, nominalAh)
        remainingAh != null -> "%.2f Ah".format(remainingAh)
        nominalAh != null -> "%.2f Ah nominal".format(nominalAh)
        else -> "-"
    }
}

private fun Double?.formatVolts(): String = this?.takeIf { it > 0.0 }?.let { "%.2f V".format(it) } ?: "—"

private fun Double?.formatRated(amps: Double?, watts: Double?): String {
    if (this == null && amps == null && watts == null) return "—"
    val parts = buildList {
        this@formatRated?.takeIf { it > 0.0 }?.let { add("%.1f V".format(it)) }
        amps?.takeIf { it > 0.0 }?.let { add("%.1f A".format(it)) }
        watts?.takeIf { it > 0.0 }?.let { add("%.0f W".format(it)) }
    }
    return parts.joinToString(" / ").ifBlank { "—" }
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
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, color = if (good) BatteryGreen else WarningOrange, fontWeight = FontWeight.SemiBold)
    }
}
