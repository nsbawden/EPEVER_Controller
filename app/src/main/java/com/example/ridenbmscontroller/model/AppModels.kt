package com.example.ridenbmscontroller.model

import com.example.ridenbmscontroller.epever.EpeverChargeStage
import com.example.ridenbmscontroller.logging.OpsLogStorageSummary

enum class PowerDirection {
    Charging,
    Discharging,
    Idle
}

enum class ChargeMode {
    Bulk,
    Balance,
    Idle
}

data class BatteryState(
    val socPercent: Int,
    val volts: Double,
    val amps: Double,
    val watts: Double,
    val remainingAh: Double?,
    val nominalAh: Double?,
    val temperatureF: Double,
    val direction: PowerDirection,
    val chargeMode: ChargeMode,
    val balancing: Boolean,
    val cellDeltaMv: Int,
    val worstCaseSocPercent: Int? = null,
    val socDriftAh: Double = 0.0
)

data class MpptState(
    val pvVolts: Double,
    val pvAmps: Double,
    val pvWatts: Double,
    val batteryVolts: Double,
    val chargeAmps: Double,
    val chargeWatts: Double,
    val deviceTempF: Double? = null,
    val rtsTempF: Double? = null,
    val chargingOn: Boolean = false,
    val chargeStage: EpeverChargeStage = EpeverChargeStage.NoCharging,
    val fault: Boolean = false,
    val generatedWhToday: Double = 0.0,
    val generatedWhMonth: Double = 0.0,
    val generatedWhYear: Double = 0.0,
    val generatedWhTotal: Double = 0.0,
    val voltageOffset: Double = 0.0,
    val commandCvVolts: Double = 0.0
)

data class EnergyCounters(
    val currentWatts: Double,
    val whToday: Double,
    val whYesterday: Double,
    val whTotal: Double,
    val bmsWhToday: Double,
    val bmsWhYesterday: Double
)

data class ControllerState(
    val enabled: Boolean,
    val pvMode: String,
    val status: String,
    val epeverConnected: Boolean,
    val bmsConnected: Boolean,
    val socTargetPercent: Int,
    val commandCvVolts: Double = 0.0,
    val voltageOffset: Double = 0.0,
    val holdVolts: Double = 0.0,
    val chargingOn: Boolean = false,
    val averagePackCurrentA: Double? = null,
    val profileBoostVolts: Double = 14.20,
    val profileFloatVolts: Double = 13.60
)

data class AlertState(
    val usbAlarmActive: Boolean,
    val lowSocAlarmActive: Boolean,
    val lowSocSilenced: Boolean,
    val lowSocThresholdPercent: Int,
    val socDriftAlarmActive: Boolean = false,
    val socDriftSilenced: Boolean = false,
    val socDriftThresholdPercent: Int = 20
)

fun localEpochDay(nowMs: Long = System.currentTimeMillis()): Long {
    return java.time.Instant.ofEpochMilli(nowMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toEpochDay()
}

data class AppSettings(
    val maxBatteryVolts: Double,
    val bmsOfflineMaxBatteryVolts: Double,
    val balanceEveryDays: Int,
    val lastBalanceEpochDay: Long,
    val controllerEnabled: Boolean,
    val normalSocCeilingPercent: Int,
    val normalSocResumePercent: Int,
    val lowSocAlarmPercent: Int,
    val socDriftDeadbandAmps: Double,
    val socDriftAlarmPercent: Int,
    val bmsVoltageHoldVolts: Double,
    val fullHoldVolts: Double,
    val controllerLoopMs: Int,
    val keepScreenOn: Boolean,
    val logEventsEnabled: Boolean = true,
    val logTelemetryEnabled: Boolean = true,
    val logHistoryEnabled: Boolean = true,
    val unattendedMode: Boolean = false
)

data class HistoryPoint(
    val timestampMs: Long,
    val dayKey: Int,
    val batteryVolts: Double,
    val batteryAmps: Double,
    val batteryWatts: Double,
    val socPercent: Int,
    val temperatureF: Double,
    val pvVolts: Double,
    val mpptBattVolts: Double,
    val chargeAmps: Double,
    val chargeWatts: Double
)

data class AppState(
    val battery: BatteryState,
    val mppt: MpptState,
    val energy: EnergyCounters,
    val dailyHealth: DailyHealthState,
    val controller: ControllerState,
    val alerts: AlertState,
    val settings: AppSettings,
    val history: List<HistoryPoint>,
    val events: List<String>,
    val logs: List<String>,
    val opsLogSummary: OpsLogStorageSummary,
    val barometricPressureInHg: Double? = null,
    val gpsAltitudeMeters: Double? = null,
    val gpsAltitudeSampling: Boolean = false
) {
    companion object {
        val preview = AppState(
            battery = BatteryState(
                socPercent = 0,
                volts = 0.0,
                amps = 0.0,
                watts = 0.0,
                remainingAh = null,
                nominalAh = null,
                temperatureF = 0.0,
                direction = PowerDirection.Idle,
                chargeMode = ChargeMode.Idle,
                balancing = false,
                cellDeltaMv = 0
            ),
            mppt = MpptState(
                pvVolts = 0.0,
                pvAmps = 0.0,
                pvWatts = 0.0,
                batteryVolts = 0.0,
                chargeAmps = 0.0,
                chargeWatts = 0.0
            ),
            energy = EnergyCounters(
                currentWatts = 0.0,
                whToday = 0.0,
                whYesterday = 0.0,
                whTotal = 0.0,
                bmsWhToday = 0.0,
                bmsWhYesterday = 0.0
            ),
            dailyHealth = DailyHealthState(),
            controller = ControllerState(
                enabled = false,
                pvMode = "Idle",
                status = "Waiting for devices",
                epeverConnected = false,
                bmsConnected = false,
                socTargetPercent = 95
            ),
            alerts = AlertState(
                usbAlarmActive = false,
                lowSocAlarmActive = false,
                lowSocSilenced = false,
                lowSocThresholdPercent = 20
            ),
            settings = AppSettings(
                maxBatteryVolts = 14.20,
                bmsOfflineMaxBatteryVolts = 14.20,
                balanceEveryDays = 7,
                lastBalanceEpochDay = 0L,
                controllerEnabled = true,
                normalSocCeilingPercent = 95,
                normalSocResumePercent = 93,
                lowSocAlarmPercent = 20,
                socDriftDeadbandAmps = 1.5,
                socDriftAlarmPercent = 20,
                bmsVoltageHoldVolts = 13.42,
                fullHoldVolts = 14.00,
                controllerLoopMs = 400,
                keepScreenOn = true
            ),
            history = emptyList(),
            events = emptyList(),
            logs = emptyList(),
            opsLogSummary = OpsLogStorageSummary(
                totalBytes = 0L,
                days = emptyList(),
                logDirectory = ""
            )
        )
    }
}
