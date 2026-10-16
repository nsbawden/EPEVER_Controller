package com.example.ridenbmscontroller

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.ridenbmscontroller.sensors.BarometricPressure
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import com.example.ridenbmscontroller.ble.BmsBleDevice
import com.example.ridenbmscontroller.ble.BmsBleScanner
import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.controller.MpptControlState
import com.example.ridenbmscontroller.controller.SolarMpptController
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.model.AlertState
import com.example.ridenbmscontroller.model.BatteryState
import com.example.ridenbmscontroller.model.ChargeMode
import com.example.ridenbmscontroller.model.ControllerState
import com.example.ridenbmscontroller.model.DailyHealthState
import com.example.ridenbmscontroller.model.EnergyCounters
import com.example.ridenbmscontroller.model.HistoryPoint
import com.example.ridenbmscontroller.model.localEpochDay
import com.example.ridenbmscontroller.health.DailyHealthStore
import com.example.ridenbmscontroller.health.SocDriftTracker
import com.example.ridenbmscontroller.logging.OpsLogger
import com.example.ridenbmscontroller.logging.OpsLogStorageSummary
import com.example.ridenbmscontroller.logging.OpsTelemetrySample
import com.example.ridenbmscontroller.model.PowerDirection
import com.example.ridenbmscontroller.model.MpptState
import com.example.ridenbmscontroller.epever.EpeverBatteryProfile
import com.example.ridenbmscontroller.epever.EpeverExtendedSettings
import com.example.ridenbmscontroller.epever.EpeverUsbMonitor
import com.example.ridenbmscontroller.epever.EpeverUsbState
import com.example.ridenbmscontroller.ui.screens.DashboardScreen
import com.example.ridenbmscontroller.ui.screens.DevicesScreen
import com.example.ridenbmscontroller.ui.screens.HistoryScreen
import com.example.ridenbmscontroller.ui.screens.SettingsScreen
import com.example.ridenbmscontroller.ui.screens.ToolsScreen
import com.example.ridenbmscontroller.ui.theme.EpeverTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalLayoutDirection

class MainActivity : ComponentActivity() {
    private lateinit var bmsBleScanner: BmsBleScanner
    private lateinit var epeverUsbMonitor: EpeverUsbMonitor
    private lateinit var mpptController: SolarMpptController
    private var bmsBleState by mutableStateOf(BmsBleUiState())
    private var epeverUsbState by mutableStateOf(EpeverUsbState())
    private var appSettings by mutableStateOf(AppState.preview.settings)
    private var unattendedProfile by mutableStateOf(EpeverBatteryProfile())
    private var extendedSettings by mutableStateOf(EpeverExtendedSettings())
    private var batteryProfileStatus by mutableStateOf("Not read yet")
    private var chargeOutputHeldOff by mutableStateOf(false)
    private var mpptControlState by mutableStateOf(MpptControlState())
    private var energyCounters by mutableStateOf(AppState.preview.energy)
    private var energyDayKey = 0
    private var lastEnergyMs = 0L
    private var lastBmsEnergyMs = 0L
    private var lastPersistEnergyMs = 0L
    private var lastEnergyWatts: Double? = null
    private var lastBmsEnergyWatts: Double? = null
    private var historyPoints by mutableStateOf(emptyList<HistoryPoint>())
    private var controllerEvents by mutableStateOf(emptyList<String>())
    private var alertState by mutableStateOf(AppState.preview.alerts)
    private var lastControllerEventSignature = ""
    private var lastHistorySampleMs = 0L
    private var lastHistoryPruneMs = 0L
    private var lastOpsSampleMs = 0L
    private var lastOpsStorageCheckMs = 0L
    private var opsLogSummary by mutableStateOf(OpsLogStorageSummary(0L, emptyList(), ""))
    private var logStorageWarning by mutableStateOf<String?>(null)
    private var logStorageWarningDismissed = false
    private var lastLoggedKneeOffset = Double.NaN
    private var lastLoggedRecoveryCycle = -1
    private var lastLoggedRecoveryPhase = ""
    private var lastOpsTelemetryBand = ""
    private var lastOpsTelemetryPvMode = ""
    private var opsBurstLogging = false
    private var opsBurstStableSinceMs = 0L
    private var lastLoggedKneeProbeFast = false
    private var lastLoggedVtuneProbePhase = "--"
    private var lastLoggedVtuneDescentBlocked = false
    private lateinit var opsLogger: OpsLogger
    private lateinit var dailyHealthStore: DailyHealthStore
    private lateinit var socDriftTracker: SocDriftTracker
    private var dailyHealth by mutableStateOf(DailyHealthState())
    private var batteryCurrentAverage = RollingAverageWindow(60_000L)
    private var historyAccumulator = HistoryAccumulator()
    private var hadGoodEpeverConnection = false
    private var usbAlarmJob: Job? = null
    private var lowSocAlarmJob: Job? = null
    private var socDriftAlarmJob: Job? = null
    private var lowSocSilenced = false
    private var socDriftSilenced = false
    private var socDriftAh by mutableStateOf(0.0)
    private var worstCaseSocPercent by mutableStateOf<Int?>(null)
    private var barometricPressureInHg by mutableStateOf<Double?>(null)
    private var gpsAltitudeMeters by mutableStateOf<Double?>(null)
    /** True from cold start until the first altitude sample finishes (or permission blocks it). */
    private var gpsAltitudeSampling by mutableStateOf(true)
    private var gpsAltitudeSampleJob: Job? = null
    private var pendingGpsAltitudeSampleAfterPermission = false
    private var sensorManager: SensorManager? = null
    private var pressureSensor: Sensor? = null
    private var lastPressureUiUpdateElapsedMs = 0L
    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PRESSURE) return
            val now = SystemClock.elapsedRealtime()
            if (barometricPressureInHg != null &&
                now - lastPressureUiUpdateElapsedMs < PRESSURE_UI_UPDATE_INTERVAL_MS
            ) {
                return
            }
            lastPressureUiUpdateElapsedMs = now
            val inHg = event.values[0] * BarometricPressure.HPA_TO_INHG
            val rounded = (kotlin.math.round(inHg * 100.0) / 100.0)
            if (barometricPressureInHg != rounded) {
                barometricPressureInHg = rounded
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bmsBleScanner.refresh()
        if (bmsBleScanner.hasPermissions()) {
            bmsBleScanner.startScan()
        }
        if (pendingGpsAltitudeSampleAfterPermission && hasFineLocationPermission()) {
            startGpsAltitudeSample()
        } else if (pendingGpsAltitudeSampleAfterPermission) {
            pendingGpsAltitudeSampleAfterPermission = false
            gpsAltitudeSampling = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = loadSettings()
        unattendedProfile = loadUnattendedProfile()
        extendedSettings = loadExtendedSettings()
        opsLogger = OpsLogger(filesDir)
        refreshOpsLogSummary()
        if (appSettings.logEventsEnabled) {
            opsLogger.logEvent(System.currentTimeMillis(), "App started")
        }
        loadEnergy()
        dailyHealthStore = DailyHealthStore(this)
        dailyHealthStore.load(nowDayKey())
        refreshDailyHealth()
        socDriftTracker = SocDriftTracker(this)
        socDriftAh = socDriftTracker.driftAh
        historyPoints = loadHistory()
        lastHistorySampleMs = System.currentTimeMillis()
        applyKeepScreenOn(appSettings.keepScreenOn)
        applyControllerKeepAlive(appSettings.controllerEnabled)
        epeverUsbMonitor = EpeverUsbMonitor(this, lifecycleScope) {
            epeverUsbState = it
            historyAccumulator.addEpever(it)
            updateDailyHealthFromMppt(it.telemetry.chargeAmps, it.telemetry.fault)
            if (it.connected) {
                energyCounters = energyCounters.copy(
                    currentWatts = it.telemetry.chargeWatts,
                    whToday = it.telemetry.generatedWhToday,
                    whTotal = it.telemetry.generatedWhTotal
                )
            } else {
                updateEnergyFromPower(it.telemetry.chargeWatts)
            }
            maybeRecordHistory()
            maybeRecordOpsTelemetry()
            updateUsbAlarm(it.connected)
            tickController()
        }
        mpptController = SolarMpptController(
            setCharging = { epeverUsbMonitor.setCharging(it) },
            applyVoltageProfile = { profile, onDone -> epeverUsbMonitor.applyVoltageProfile(profile, onDone) },
            onBalanceDayStarted = { markBalanceDayStarted(it) },
            onState = { handleMpptControlState(it) },
            onEvent = { addControllerEvent(it) },
            onTopOffComplete = { reason ->
                resetSocDriftFromTopOff(reason)
                if (reason.startsWith("100%")) {
                    cancelBalanceDayAfterTopOff()
                }
            },
            onNoBms = { writeUnattendedProfile(chargeOn = true) }
        )
        bmsBleScanner = BmsBleScanner(this) {
            bmsBleState = it
            batteryCurrentAverage.add(System.currentTimeMillis(), it.telemetry.packCurrent)
            historyAccumulator.addBms(it)
            updateBmsEnergyFromPower(it.telemetry.packVoltage, it.telemetry.packCurrent)
            maybeRecordHistory()
            maybeRecordOpsTelemetry()
            updateSocDrift()
            updateLowSocAlarm()
            tickController()
        }
        bmsBleScanner.ensureAutoConnect()
        epeverUsbMonitor.start()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        setContent {
            EpeverTheme {
                EpeverApp(
                    bmsBleState = bmsBleState,
                    epeverUsbState = epeverUsbState,
                    appSettings = appSettings,
                    mpptControlState = mpptControlState,
                    energyCounters = energyCounters,
                    dailyHealth = dailyHealth,
                    historyPoints = historyPoints,
                    controllerEvents = controllerEvents,
                    alertState = alertState,
                    opsLogSummary = opsLogSummary,
                    logStorageWarning = logStorageWarning,
                    worstCaseSocPercent = worstCaseSocPercent,
                    socDriftAh = socDriftAh,
                    averagePackCurrentA = batteryCurrentAverage.average(System.currentTimeMillis()),
                    barometricPressureInHg = barometricPressureInHg,
                    gpsAltitudeMeters = gpsAltitudeMeters,
                    gpsAltitudeSampling = gpsAltitudeSampling,
                    onPressureTileClick = { startGpsAltitudeSample() },
                    onSettingsChanged = {
                        val previous = appSettings
                        if (it != previous) {
                            appSettings = it
                            saveSettings(it)
                            applyKeepScreenOn(it.keepScreenOn)
                            applyControllerKeepAlive(it.controllerEnabled)
                            updateSocDrift()
                            updateLowSocAlarm()
                            val holdSetpointChanged =
                                kotlin.math.abs(it.bmsVoltageHoldVolts - previous.bmsVoltageHoldVolts) >= 0.005 ||
                                    kotlin.math.abs(it.fullHoldVolts - previous.fullHoldVolts) >= 0.005 ||
                                    kotlin.math.abs(it.maxBatteryVolts - previous.maxBatteryVolts) >= 0.005
                            if (holdSetpointChanged) mpptController.invalidateVoltageWrite()
                            tickController()
                        }
                    },
                    onRequestBlePermissions = {
                        requestBlePermissions.launch(bmsBleScanner.requiredPermissions())
                    },
                    onStartBmsScan = { bmsBleScanner.startScan() },
                    onStopBmsScan = { bmsBleScanner.stopScan() },
                    onConnectBms = { bmsBleScanner.connect(it) },
                    onDisconnectBms = { bmsBleScanner.disconnect() },
                    balanceDayToday = isBalanceDayToday(appSettings),
                    daysUntilNextBalance = daysUntilNextBalance(appSettings),
                    onToggleBalanceToday = { toggleBalanceToday() },
                    onManualCvChanged = { enabled, volts ->
                        val next = appSettings.copy(
                            manualCvEnabled = enabled,
                            manualCvVolts = volts
                        )
                        if (next != appSettings) {
                            appSettings = next
                            saveSettings(next)
                            addControllerEvent(
                                if (enabled) "Manual CV %.2f V".format(next.manualCvVolts)
                                else "Manual CV off"
                            )
                            tickController()
                        }
                    },
                    onEpeverVoltageCalChanged = { updateEpeverVoltageCal(it) },
                    onToggleMpptCalDisplay = { toggleMpptCalDisplay() },
                    onClearEpeverEnergy = { clearEpeverGeneratedEnergy() },
                    onResetSocDrift = { resetSocDriftManual() },
                    onDeleteOpsLogsBeforeDate = { cutoff -> deleteOpsLogsBeforeDate(cutoff) },
                    onRefreshOpsLogSummary = { refreshOpsLogSummary() },
                    unattendedProfile = unattendedProfile,
                    extendedSettings = extendedSettings,
                    batteryProfileStatus = batteryProfileStatus,
                    onRefreshBatteryProfile = { refreshBatteryProfileFromEpever() },
                    onUnattendedModeChanged = { setUnattendedMode(it) },
                    chargeOutputOn = !chargeOutputHeldOff,
                    onChargeOutputChanged = { setChargeOutputOn(it) },
                    onBatteryProfileChanged = { profile, write -> updateBatteryProfile(profile, write) },
                    onExtendedSettingsChanged = { settings -> updateExtendedSettings(settings) },
                    onSyncEpeverClock = { syncEpeverClockFromPhone() },
                    onDismissLogStorageWarning = {
                        logStorageWarningDismissed = true
                        logStorageWarning = null
                    },
                    onSilenceLowSocAlarm = { silenceLowSocAlarm() },
                    onSilenceSocDriftAlarm = { silenceSocDriftAlarm() }
                )
            }
        }
        startGpsAltitudeSample()
    }

    private fun updateDailyHealthFromMppt(chargeAmps: Double?, fault: Boolean) {
        val today = nowDayKey()
        if (rolloverDailyHealthIfNeeded(today)) {
            refreshDailyHealth()
        }
        dailyHealthStore.recordChargeAmps(chargeAmps)
        dailyHealth = dailyHealthStore.state.copy(mpptFault = fault)
    }

    private fun rolloverDailyHealthIfNeeded(today: Int): Boolean {
        return dailyHealthStore.rolloverDayIfNeeded(today)
    }

    private fun refreshDailyHealth() {
        dailyHealth = dailyHealthStore.state.copy(mpptFault = epeverUsbState.telemetry.fault)
    }

    private fun tickController() {
        if (!::mpptController.isInitialized) return
        mpptController.tick(
            appSettings,
            bmsBleState,
            epeverUsbState,
            outputHeldOff = chargeOutputHeldOff,
            toolsProfile = unattendedProfile
        )
    }

    private fun updateUsbAlarm(connected: Boolean) {
        if (connected) {
            if (!hadGoodEpeverConnection) {
                addControllerEvent("EPEVER USB connected")
                if (!chargeOutputHeldOff) {
                    if (appSettings.unattendedMode) {
                        writeUnattendedProfile(chargeOn = true)
                    } else {
                        writeLiveLineImpedanceZero()
                    }
                }
            }
            hadGoodEpeverConnection = true
            stopUsbAlarm()
            publishAlerts()
            return
        }
        if (hadGoodEpeverConnection && !appSettings.unattendedMode) {
            if (usbAlarmJob == null) addControllerEvent("EPEVER USB disconnected")
            startUsbAlarmIfNeeded()
        }
        publishAlerts()
    }

    private fun startUsbAlarmIfNeeded() {
        if (usbAlarmJob != null) return
        usbAlarmJob = lifecycleScope.launch {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 100)
            } catch (_: Exception) {
                null
            }
            try {
                while (isActive) {
                    repeat(3) {
                        try {
                            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                        } catch (_: Exception) {
                        }
                        delay(350)
                    }
                    delay(3000)
                }
            } finally {
                try {
                    tone?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopUsbAlarm() {
        usbAlarmJob?.cancel()
        usbAlarmJob = null
    }

    private fun updateLowSocAlarm() {
        val soc = bmsBleState.telemetry.socPercent
        val active = bmsBleState.connectedDeviceAddress != null &&
            soc != null &&
            soc <= appSettings.lowSocAlarmPercent

        if (!active) {
            if (lowSocAlarmJob != null || lowSocSilenced) addControllerEvent("Low SOC alarm cleared")
            lowSocSilenced = false
            stopLowSocAlarm()
            publishAlerts()
            return
        }

        if (!lowSocSilenced) {
            if (lowSocAlarmJob == null) addControllerEvent("Low SOC alarm: $soc%")
            startLowSocAlarmIfNeeded()
        } else {
            stopLowSocAlarm()
        }
        publishAlerts()
    }

    private fun updateSocDrift() {
        if (!::socDriftTracker.isInitialized) return
        val telemetry = bmsBleState.telemetry
        val resetReason = socDriftTracker.update(
            nowMs = System.currentTimeMillis(),
            bmsConnected = bmsBleState.connectedDeviceAddress != null,
            socPercent = telemetry.socPercent,
            packCurrentA = telemetry.packCurrent,
            deadbandAmps = appSettings.socDriftDeadbandAmps
        )
        socDriftAh = socDriftTracker.driftAh
        val soc = telemetry.socPercent
        worstCaseSocPercent = if (soc != null) {
            socDriftTracker.worstCaseSocPercent(soc, telemetry.nominalAh)
        } else {
            null
        }
        if (resetReason != null) {
            addControllerEvent("SOC drift reset ($resetReason)")
        }
        updateSocDriftAlarm()
    }

    private fun resetSocDriftManual() {
        if (!::socDriftTracker.isInitialized) return
        socDriftTracker.reset()
        socDriftAh = 0.0
        val soc = bmsBleState.telemetry.socPercent
        worstCaseSocPercent = if (soc != null) {
            socDriftTracker.worstCaseSocPercent(soc, bmsBleState.telemetry.nominalAh)
        } else {
            null
        }
        addControllerEvent("SOC drift reset (manual)")
        updateSocDriftAlarm()
    }

    private fun resetSocDriftFromTopOff(reason: String) {
        if (!::socDriftTracker.isInitialized) return
        socDriftTracker.reset()
        socDriftAh = 0.0
        val soc = bmsBleState.telemetry.socPercent
        worstCaseSocPercent = if (soc != null) {
            socDriftTracker.worstCaseSocPercent(soc, bmsBleState.telemetry.nominalAh)
        } else {
            null
        }
        addControllerEvent("SOC drift reset ($reason)")
        updateSocDriftAlarm()
    }

    private fun updateSocDriftAlarm() {
        val worst = worstCaseSocPercent
        val active = bmsBleState.connectedDeviceAddress != null &&
            worst != null &&
            worst <= appSettings.socDriftAlarmPercent

        if (!active) {
            if (socDriftAlarmJob != null || socDriftSilenced) {
                addControllerEvent("SOC drift alarm cleared")
            }
            socDriftSilenced = false
            stopSocDriftAlarm()
            publishAlerts()
            return
        }

        if (!socDriftSilenced) {
            if (socDriftAlarmJob == null) {
                addControllerEvent("SOC drift alarm: worst-case $worst%")
            }
            startSocDriftAlarmIfNeeded()
        } else {
            stopSocDriftAlarm()
        }
        publishAlerts()
    }

    private fun startLowSocAlarmIfNeeded() {
        if (lowSocAlarmJob != null) return
        lowSocAlarmJob = lifecycleScope.launch {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 80)
            } catch (_: Exception) {
                null
            }
            try {
                while (isActive) {
                    try {
                        tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
                    } catch (_: Exception) {
                    }
                    delay(6000)
                }
            } finally {
                try {
                    tone?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopLowSocAlarm() {
        lowSocAlarmJob?.cancel()
        lowSocAlarmJob = null
    }

    private fun silenceLowSocAlarm() {
        if (!alertState.lowSocAlarmActive) return
        lowSocSilenced = true
        stopLowSocAlarm()
        addControllerEvent("Low SOC alarm silenced")
        publishAlerts()
    }

    private fun startSocDriftAlarmIfNeeded() {
        if (socDriftAlarmJob != null) return
        socDriftAlarmJob = lifecycleScope.launch {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 80)
            } catch (_: Exception) {
                null
            }
            try {
                while (isActive) {
                    try {
                        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                    } catch (_: Exception) {
                    }
                    delay(7000)
                }
            } finally {
                try {
                    tone?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopSocDriftAlarm() {
        socDriftAlarmJob?.cancel()
        socDriftAlarmJob = null
    }

    private fun silenceSocDriftAlarm() {
        if (!alertState.socDriftAlarmActive) return
        socDriftSilenced = true
        stopSocDriftAlarm()
        addControllerEvent("SOC drift alarm silenced")
        publishAlerts()
    }

    private fun publishAlerts() {
        val soc = bmsBleState.telemetry.socPercent
        val lowSocActive = bmsBleState.connectedDeviceAddress != null &&
            soc != null &&
            soc <= appSettings.lowSocAlarmPercent
        val worst = worstCaseSocPercent
        val driftActive = bmsBleState.connectedDeviceAddress != null &&
            worst != null &&
            worst <= appSettings.socDriftAlarmPercent
        alertState = AlertState(
            usbAlarmActive = usbAlarmJob != null,
            lowSocAlarmActive = lowSocActive,
            lowSocSilenced = lowSocActive && lowSocSilenced,
            lowSocThresholdPercent = appSettings.lowSocAlarmPercent,
            socDriftAlarmActive = driftActive,
            socDriftSilenced = driftActive && socDriftSilenced,
            socDriftThresholdPercent = appSettings.socDriftAlarmPercent
        )
    }

    private fun handleMpptControlState(state: MpptControlState) {
        val previous = mpptControlState
        mpptControlState = state
        logControllerTransitions(previous, state)
        updateOpsBurstLogging(state)
        maybeRecordOpsTelemetry()

        val signature = listOf(
            state.enabled,
            state.pvMode,
            state.status,
            "%.2f".format(state.commandCvVolts),
            state.chargingOn,
            state.socTargetPercent
        ).joinToString("|")
        if (signature == lastControllerEventSignature) return
        lastControllerEventSignature = signature

        val important = previous.enabled != state.enabled ||
            previous.pvMode != state.pvMode ||
            previous.status != state.status ||
            previous.chargingOn != state.chargingOn ||
            previous.socTargetPercent != state.socTargetPercent
        if (!important) return

        addControllerEvent("${state.pvMode}: ${state.status}")
    }

    private fun logControllerTransitions(previous: MpptControlState, state: MpptControlState) {
        if (previous.pvMode != state.pvMode ||
            abs(previous.commandCvVolts - state.commandCvVolts) >= 0.05 ||
            previous.chargingOn != state.chargingOn
        ) {
            maybeRecordOpsTelemetry(force = true)
        }
    }

    private fun updateOpsBurstLogging(control: MpptControlState) {
        opsBurstLogging = control.pvMode == "Hold" || control.pvMode == "Top-up" || control.pvMode == "Alarm"
    }

    private fun addControllerEvent(text: String) {
        val now = System.currentTimeMillis()
        val event = "${eventTimeFormat.format(now)}  $text"
        controllerEvents = (listOf(event) + controllerEvents).take(MAX_CONTROLLER_EVENTS)
        if (::opsLogger.isInitialized && appSettings.logEventsEnabled) {
            opsLogger.logEvent(now, text)
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        window.decorView.keepScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun maybeRecordOpsTelemetry(force: Boolean = false) {
        if (!::opsLogger.isInitialized) return
        if (!appSettings.logTelemetryEnabled) return
        if (!opsTelemetryReady()) return

        val control = mpptControlState
        val nowMs = System.currentTimeMillis()
        val intervalMs = opsTelemetryIntervalMs(control)
        if (!force && nowMs - lastOpsSampleMs < intervalMs) return

        val bms = bmsBleState.telemetry
        val mppt = epeverUsbState.telemetry
        val batteryVolts = bms.packVoltage ?: return
        val batteryAmps = bms.packCurrent ?: return
        val soc = bms.socPercent ?: return

        lastOpsSampleMs = nowMs
        lastOpsTelemetryPvMode = control.pvMode
        opsLogger.logTelemetry(
            OpsTelemetrySample(
                timestampMs = nowMs,
                pvMode = control.pvMode,
                commandCvVolts = control.commandCvVolts,
                voltageOffset = control.voltageOffset,
                pvVolts = mppt.pvVolts,
                pvAmps = mppt.pvAmps,
                pvWatts = mppt.pvWatts,
                mpptBattVolts = mppt.batteryVolts,
                chargeAmps = mppt.chargeAmps,
                chargeWatts = mppt.chargeWatts,
                batteryVolts = batteryVolts,
                batteryAmps = batteryAmps,
                batteryWatts = batteryVolts * batteryAmps,
                socPercent = soc,
                temperatureF = bms.temperatureF ?: 0.0,
                epeverConnected = epeverUsbState.connected,
                bmsConnected = bmsBleState.connectedDeviceAddress != null
            )
        )
        maybeCheckLogStorage(nowMs)
    }

    private fun opsTelemetryIntervalMs(control: MpptControlState): Long {
        if (opsBurstLogging || control.pvMode == "Hold" || control.pvMode == "Top-up" || control.pvMode == "Alarm") {
            return OPS_SAMPLE_BURST_MS
        }
        if (!control.enabled) return OPS_SAMPLE_IDLE_MS
        return OPS_SAMPLE_OTHER_MS
    }

    private fun opsTelemetryReady(): Boolean {
        val bms = bmsBleState.telemetry
        return bmsBleState.connectedDeviceAddress != null &&
            epeverUsbState.connected &&
            bms.packVoltage != null &&
            bms.packCurrent != null &&
            bms.socPercent != null
    }

    private fun maybeCheckLogStorage(nowMs: Long) {
        if (nowMs - lastOpsStorageCheckMs < OPS_STORAGE_CHECK_MS) return
        lastOpsStorageCheckMs = nowMs
        refreshOpsLogSummary()
        val total = opsLogSummary.totalBytes
        if (total < OpsLogger.STORAGE_WARN_BYTES) {
            logStorageWarningDismissed = false
            return
        }
        if (!logStorageWarningDismissed) {
            logStorageWarning =
                "Operational logs are using ${OpsLogger.formatSize(total)}. " +
                    "In the EMPPT app: bottom bar Tools → Logs tab → Refresh Log Summary, " +
                    "or run synclogs on your PC."
        }
    }

    private fun refreshOpsLogSummary() {
        if (!::opsLogger.isInitialized) return
        opsLogSummary = opsLogger.refreshSummary()
    }

    private fun deleteOpsLogsBeforeDate(cutoffDateLabel: String): String {
        if (!::opsLogger.isInitialized) return "Logger not ready"
        if (!cutoffDateLabel.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return "Use date format YYYY-MM-DD"
        }
        val parsed = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(cutoffDateLabel)
        } catch (_: Exception) {
            null
        }
        if (parsed == null) return "Invalid date: $cutoffDateLabel"
        return try {
            val removed = opsLogger.deleteLogsBeforeDate(cutoffDateLabel)
            refreshOpsLogSummary()
            if (opsLogSummary.totalBytes < OpsLogger.STORAGE_WARN_BYTES) {
                logStorageWarningDismissed = false
                logStorageWarning = null
            }
            if (removed == 0) {
                "No log files found before $cutoffDateLabel"
            } else {
                addControllerEvent("Deleted $removed ops log file(s) before $cutoffDateLabel")
                "Deleted $removed file(s) before $cutoffDateLabel"
            }
        } catch (error: IOException) {
            "Delete failed: ${error.message ?: "IO error"}"
        }
    }

    private fun applyControllerKeepAlive(enabled: Boolean) {
        if (enabled) {
            ControllerKeepAliveService.start(this)
            if (::bmsBleScanner.isInitialized) {
                bmsBleScanner.ensureAutoConnect()
            }
        } else {
            ControllerKeepAliveService.stop(this)
        }
    }

    private fun loadSettings(): AppSettings {
        val defaults = AppState.preview.settings
        val prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONTROLLER_ON_MIGRATED, false)) {
            prefs.edit {
                putBoolean(KEY_CONTROLLER_ON_MIGRATED, true)
                putBoolean(KEY_CONTROLLER_ENABLED, true)
            }
        }
        return AppSettings(
            maxBatteryVolts = prefs.getFloat(KEY_MAX_BATTERY_V, defaults.maxBatteryVolts.toFloat()).toDouble(),
            maxChargeAmps = prefs.getFloat(KEY_MAX_CHARGE_A, defaults.maxChargeAmps.toFloat()).toDouble().coerceIn(0.0, 80.0),
            bmsOfflineMaxBatteryVolts = prefs.getFloat(KEY_BMS_OFFLINE_MAX_BATTERY_V, defaults.bmsOfflineMaxBatteryVolts.toFloat()).toDouble(),
            balanceEveryDays = prefs.getInt(KEY_BALANCE_EVERY_DAYS, defaults.balanceEveryDays),
            lastBalanceEpochDay = prefs.getLong(KEY_LAST_BALANCE_EPOCH_DAY, defaults.lastBalanceEpochDay),
            controllerEnabled = prefs.getBoolean(KEY_CONTROLLER_ENABLED, true),
            normalSocCeilingPercent = prefs.getInt(KEY_NORMAL_SOC_CEILING_PERCENT, defaults.normalSocCeilingPercent).coerceIn(50, 100),
            normalSocResumePercent = prefs.getInt(KEY_NORMAL_SOC_RESUME_PERCENT, defaults.normalSocResumePercent).coerceIn(50, 99),
            holdSagVolts = prefs.getFloat(KEY_HOLD_SAG_V, defaults.holdSagVolts.toFloat()).toDouble().coerceIn(0.02, 0.50),
            holdSagDwellS = prefs.getInt(KEY_HOLD_SAG_DWELL_S, defaults.holdSagDwellS).coerceIn(5, 600),
            lowSocAlarmPercent = prefs.getInt(KEY_LOW_SOC_ALARM_PERCENT, defaults.lowSocAlarmPercent),
            socDriftDeadbandAmps = prefs.getFloat(KEY_SOC_DRIFT_DEADBAND_A, defaults.socDriftDeadbandAmps.toFloat()).toDouble().coerceIn(0.0, 5.0),
            socDriftAlarmPercent = prefs.getInt(KEY_SOC_DRIFT_ALARM_PERCENT, defaults.socDriftAlarmPercent).coerceIn(0, 100),
            bmsVoltageHoldVolts = prefs.getFloat(KEY_BMS_VOLTAGE_HOLD_V, defaults.bmsVoltageHoldVolts.toFloat()).toDouble().coerceIn(12.0, 15.0),
            fullHoldVolts = prefs.getFloat(KEY_FULL_HOLD_V, defaults.fullHoldVolts.toFloat()).toDouble().coerceIn(12.0, 15.0),
            controllerLoopMs = prefs.getInt(KEY_CONTROLLER_LOOP_MS, defaults.controllerLoopMs),
            voltageWritePeriodS = prefs.getInt(KEY_VOLTAGE_WRITE_S, defaults.voltageWritePeriodS).coerceIn(1, 30),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
            logEventsEnabled = prefs.getBoolean(KEY_LOG_EVENTS_ENABLED, defaults.logEventsEnabled),
            logTelemetryEnabled = prefs.getBoolean(KEY_LOG_TELEMETRY_ENABLED, defaults.logTelemetryEnabled),
            logHistoryEnabled = prefs.getBoolean(KEY_LOG_HISTORY_ENABLED, defaults.logHistoryEnabled),
            unattendedMode = prefs.getBoolean(KEY_UNATTENDED_MODE, defaults.unattendedMode),
            manualCvEnabled = prefs.getBoolean(KEY_MANUAL_CV_ENABLED, defaults.manualCvEnabled),
            manualCvVolts = prefs.getFloat(KEY_MANUAL_CV_V, defaults.manualCvVolts.toFloat()).toDouble(),
            epeverVoltageCal = prefs.getFloat(KEY_EPEVER_V_CAL, defaults.epeverVoltageCal.toFloat()).toDouble().coerceIn(-0.50, 0.50),
            showMpptCalVolts = prefs.getBoolean(KEY_SHOW_MPPT_CAL_V, defaults.showMpptCalVolts)
        )
    }

    private fun saveSettings(settings: AppSettings) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit {
            putFloat(KEY_MAX_BATTERY_V, settings.maxBatteryVolts.toFloat())
            putFloat(KEY_MAX_CHARGE_A, settings.maxChargeAmps.toFloat())
            putFloat(KEY_BMS_OFFLINE_MAX_BATTERY_V, settings.bmsOfflineMaxBatteryVolts.toFloat())
            putInt(KEY_BALANCE_EVERY_DAYS, settings.balanceEveryDays)
            putLong(KEY_LAST_BALANCE_EPOCH_DAY, settings.lastBalanceEpochDay)
            putBoolean(KEY_CONTROLLER_ENABLED, settings.controllerEnabled)
            putInt(KEY_NORMAL_SOC_CEILING_PERCENT, settings.normalSocCeilingPercent)
            putInt(KEY_NORMAL_SOC_RESUME_PERCENT, settings.normalSocResumePercent)
            putFloat(KEY_HOLD_SAG_V, settings.holdSagVolts.toFloat())
            putInt(KEY_HOLD_SAG_DWELL_S, settings.holdSagDwellS)
            putInt(KEY_LOW_SOC_ALARM_PERCENT, settings.lowSocAlarmPercent)
            putFloat(KEY_SOC_DRIFT_DEADBAND_A, settings.socDriftDeadbandAmps.toFloat())
            putInt(KEY_SOC_DRIFT_ALARM_PERCENT, settings.socDriftAlarmPercent)
            putFloat(KEY_BMS_VOLTAGE_HOLD_V, settings.bmsVoltageHoldVolts.toFloat())
            putFloat(KEY_FULL_HOLD_V, settings.fullHoldVolts.toFloat())
            putInt(KEY_CONTROLLER_LOOP_MS, settings.controllerLoopMs)
            putInt(KEY_VOLTAGE_WRITE_S, settings.voltageWritePeriodS)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            putBoolean(KEY_LOG_EVENTS_ENABLED, settings.logEventsEnabled)
            putBoolean(KEY_LOG_TELEMETRY_ENABLED, settings.logTelemetryEnabled)
            putBoolean(KEY_LOG_HISTORY_ENABLED, settings.logHistoryEnabled)
            putBoolean(KEY_UNATTENDED_MODE, settings.unattendedMode)
            putBoolean(KEY_MANUAL_CV_ENABLED, settings.manualCvEnabled)
            putFloat(KEY_MANUAL_CV_V, settings.manualCvVolts.toFloat())
            putFloat(KEY_EPEVER_V_CAL, settings.epeverVoltageCal.toFloat())
            putBoolean(KEY_SHOW_MPPT_CAL_V, settings.showMpptCalVolts)
        }
    }

    private fun loadUnattendedProfile(): EpeverBatteryProfile {
        val defaults = EpeverBatteryProfile()
        val prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        return EpeverBatteryProfile(
            batteryType = prefs.getInt(KEY_BP_TYPE, defaults.batteryType),
            capacityAh = prefs.getInt(KEY_BP_CAPACITY, defaults.capacityAh),
            tempCompensationMvv = prefs.getFloat(KEY_BP_TEMP, defaults.tempCompensationMvv.toFloat()).toDouble(),
            highVoltageDisconnect = prefs.getFloat(KEY_BP_HVD, defaults.highVoltageDisconnect.toFloat()).toDouble(),
            chargingLimitVolts = prefs.getFloat(KEY_BP_LIMIT, defaults.chargingLimitVolts.toFloat()).toDouble(),
            overVoltageReconnect = prefs.getFloat(KEY_BP_OVR, defaults.overVoltageReconnect.toFloat()).toDouble(),
            equalizeVolts = prefs.getFloat(KEY_BP_EQ, defaults.equalizeVolts.toFloat()).toDouble(),
            boostVolts = prefs.getFloat(KEY_BP_BOOST, defaults.boostVolts.toFloat()).toDouble(),
            floatVolts = prefs.getFloat(KEY_BP_FLOAT, defaults.floatVolts.toFloat()).toDouble(),
            boostReconnectVolts = prefs.getFloat(KEY_BP_BOOST_RE, defaults.boostReconnectVolts.toFloat()).toDouble(),
            lowVoltageReconnect = prefs.getFloat(KEY_BP_LVR, defaults.lowVoltageReconnect.toFloat()).toDouble(),
            underVoltageRecover = prefs.getFloat(KEY_BP_UVR, defaults.underVoltageRecover.toFloat()).toDouble(),
            underVoltageWarning = prefs.getFloat(KEY_BP_UVW, defaults.underVoltageWarning.toFloat()).toDouble(),
            lowVoltageDisconnect = prefs.getFloat(KEY_BP_LVD, defaults.lowVoltageDisconnect.toFloat()).toDouble(),
            dischargingLimitVolts = prefs.getFloat(KEY_BP_DLV, defaults.dischargingLimitVolts.toFloat()).toDouble()
        )
    }

    private fun saveUnattendedProfile(profile: EpeverBatteryProfile) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit {
            putInt(KEY_BP_TYPE, profile.batteryType)
            putInt(KEY_BP_CAPACITY, profile.capacityAh)
            putFloat(KEY_BP_TEMP, profile.tempCompensationMvv.toFloat())
            putFloat(KEY_BP_HVD, profile.highVoltageDisconnect.toFloat())
            putFloat(KEY_BP_LIMIT, profile.chargingLimitVolts.toFloat())
            putFloat(KEY_BP_OVR, profile.overVoltageReconnect.toFloat())
            putFloat(KEY_BP_EQ, profile.equalizeVolts.toFloat())
            putFloat(KEY_BP_BOOST, profile.boostVolts.toFloat())
            putFloat(KEY_BP_FLOAT, profile.floatVolts.toFloat())
            putFloat(KEY_BP_BOOST_RE, profile.boostReconnectVolts.toFloat())
            putFloat(KEY_BP_LVR, profile.lowVoltageReconnect.toFloat())
            putFloat(KEY_BP_UVR, profile.underVoltageRecover.toFloat())
            putFloat(KEY_BP_UVW, profile.underVoltageWarning.toFloat())
            putFloat(KEY_BP_LVD, profile.lowVoltageDisconnect.toFloat())
            putFloat(KEY_BP_DLV, profile.dischargingLimitVolts.toFloat())
        }
    }

    private fun loadExtendedSettings(): EpeverExtendedSettings {
        val prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        val defaults = EpeverExtendedSettings()
        return EpeverExtendedSettings(
            equalizeCycleDays = prefs.getInt(KEY_EX_EQ_CYCLE, defaults.equalizeCycleDays),
            batteryTempWarnHighC = prefs.getFloat(KEY_EX_BATT_T_HIGH, defaults.batteryTempWarnHighC.toFloat()).toDouble(),
            batteryTempWarnLowC = prefs.getFloat(KEY_EX_BATT_T_LOW, defaults.batteryTempWarnLowC.toFloat()).toDouble(),
            innerTempLimitC = prefs.getFloat(KEY_EX_INNER_T_LIM, defaults.innerTempLimitC.toFloat()).toDouble(),
            innerTempRecoverC = prefs.getFloat(KEY_EX_INNER_T_REC, defaults.innerTempRecoverC.toFloat()).toDouble(),
            powerTempLimitC = prefs.getFloat(KEY_EX_PWR_T_LIM, defaults.powerTempLimitC.toFloat()).toDouble(),
            powerTempRecoverC = prefs.getFloat(KEY_EX_PWR_T_REC, defaults.powerTempRecoverC.toFloat()).toDouble(),
            lineImpedanceMOhm = prefs.getFloat(KEY_EX_LINE_Z, defaults.lineImpedanceMOhm.toFloat()).toDouble(),
            batteryRatedVoltageCode = prefs.getInt(KEY_EX_RATED_V, defaults.batteryRatedVoltageCode),
            equalizeDurationMin = prefs.getInt(KEY_EX_EQ_MIN, defaults.equalizeDurationMin),
            boostDurationMin = prefs.getInt(KEY_EX_BOOST_MIN, defaults.boostDurationMin),
            dischargePercent = prefs.getInt(KEY_EX_DISCH_PCT, defaults.dischargePercent),
            chargePercent = prefs.getInt(KEY_EX_CHG_PCT, defaults.chargePercent),
            chargeManagementSoc = prefs.getBoolean(KEY_EX_MGMT_SOC, defaults.chargeManagementSoc)
        )
    }

    private fun saveExtendedSettings(settings: EpeverExtendedSettings) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit {
            putInt(KEY_EX_EQ_CYCLE, settings.equalizeCycleDays)
            putFloat(KEY_EX_BATT_T_HIGH, settings.batteryTempWarnHighC.toFloat())
            putFloat(KEY_EX_BATT_T_LOW, settings.batteryTempWarnLowC.toFloat())
            putFloat(KEY_EX_INNER_T_LIM, settings.innerTempLimitC.toFloat())
            putFloat(KEY_EX_INNER_T_REC, settings.innerTempRecoverC.toFloat())
            putFloat(KEY_EX_PWR_T_LIM, settings.powerTempLimitC.toFloat())
            putFloat(KEY_EX_PWR_T_REC, settings.powerTempRecoverC.toFloat())
            putFloat(KEY_EX_LINE_Z, settings.lineImpedanceMOhm.toFloat())
            putInt(KEY_EX_RATED_V, settings.batteryRatedVoltageCode)
            putInt(KEY_EX_EQ_MIN, settings.equalizeDurationMin)
            putInt(KEY_EX_BOOST_MIN, settings.boostDurationMin)
            putInt(KEY_EX_DISCH_PCT, settings.dischargePercent)
            putInt(KEY_EX_CHG_PCT, settings.chargePercent)
            putBoolean(KEY_EX_MGMT_SOC, settings.chargeManagementSoc)
        }
    }

    private fun syncEpeverClockFromPhone() {
        if (!epeverUsbState.connected) {
            batteryProfileStatus = "EPEVER disconnected"
            return
        }
        batteryProfileStatus = "Setting EPEVER clock..."
        epeverUsbMonitor.writeClockFromPhone { result ->
            result.onSuccess { clock ->
                batteryProfileStatus = "Clock set to ${clock.format()}"
                addControllerEvent("EPEVER clock synced to phone")
            }.onFailure { error ->
                batteryProfileStatus = "Clock set failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun refreshBatteryProfileFromEpever() {
        if (!epeverUsbState.connected) {
            batteryProfileStatus = "EPEVER disconnected"
            return
        }
        batteryProfileStatus = "Reading EPEVER..."
        epeverUsbMonitor.readBatteryProfile { result ->
            result.onSuccess { device ->
                unattendedProfile = when {
                    kotlin.math.abs(appSettings.epeverVoltageCal) >= 0.0005 ->
                        unattendedProfile.mergeKeepingVoltageSetpoints(device)
                    appSettings.unattendedMode -> device
                    else -> unattendedProfile.mergeKeepingLiveOverlay(device)
                }
                saveUnattendedProfile(unattendedProfile)
                epeverUsbState.extendedSettings?.let { device ->
                    extendedSettings = if (appSettings.unattendedMode) {
                        device
                    } else {
                        device.copy(lineImpedanceMOhm = extendedSettings.lineImpedanceMOhm)
                    }
                    saveExtendedSettings(extendedSettings)
                }
                batteryProfileStatus = "Read from EPEVER"
            }.onFailure { error ->
                batteryProfileStatus = "Read failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun updateBatteryProfile(profile: EpeverBatteryProfile, writeToEpever: Boolean) {
        unattendedProfile = profile
        saveUnattendedProfile(profile)
        if (!writeToEpever) return
        if (chargeOutputHeldOff) {
            batteryProfileStatus = "Charge output off — profile saved, not written"
            return
        }
        if (appSettings.unattendedMode) {
            writeUnattendedProfile(chargeOn = false)
        } else {
            writeProtectionParams(profile)
        }
    }

    private fun setChargeOutputOn(on: Boolean) {
        if (on == !chargeOutputHeldOff) return
        chargeOutputHeldOff = !on
        if (!epeverUsbState.connected) {
            batteryProfileStatus = "EPEVER disconnected"
            addControllerEvent(if (on) "Charge output ON requested (USB down)" else "Charge output OFF requested (USB down)")
            tickController()
            return
        }
        if (on) {
            batteryProfileStatus = "Restoring charge voltages..."
            epeverUsbMonitor.restoreParkedChargeOutput { result ->
                result.onSuccess { device ->
                    batteryProfileStatus = "Charge output ON — Boost %.2f V".format(device.boostVolts)
                    addControllerEvent("Charge output ON (Boost %.2f V)".format(device.boostVolts))
                    tickController()
                }.onFailure { error ->
                    batteryProfileStatus = "Restore failed: ${error.message ?: error.javaClass.simpleName}"
                    addControllerEvent("Charge output ON restore failed")
                    tickController()
                }
            }
        } else {
            batteryProfileStatus = "Parking charge at 13.00 V..."
            epeverUsbMonitor.parkChargeOutput { result ->
                result.onSuccess { device ->
                    val parked = kotlin.math.abs(device.regulationVolts - 13.00) < 0.08
                    batteryProfileStatus = if (parked) {
                        "Charge output PARKED at %.2f V — watch MPPT A".format(device.regulationVolts)
                    } else {
                        "Park rejected — Boost still %.2f V".format(device.boostVolts)
                    }
                    addControllerEvent(batteryProfileStatus)
                    tickController()
                }.onFailure { error ->
                    batteryProfileStatus = "Park failed: ${error.message ?: error.javaClass.simpleName}"
                    addControllerEvent("Charge output park failed")
                    tickController()
                }
            }
        }
        tickController()
    }

    private fun updateEpeverVoltageCal(cal: Double) {
        val next = appSettings.copy(epeverVoltageCal = cal.coerceIn(-0.50, 0.50))
        if (next == appSettings) return
        appSettings = next
        saveSettings(next)
        addControllerEvent("EPEVER voltage cal %+.2f V".format(next.epeverVoltageCal))
        if (!chargeOutputHeldOff && next.unattendedMode) {
            writeUnattendedProfile(chargeOn = false)
        }
    }

    private fun toggleMpptCalDisplay() {
        val next = appSettings.copy(showMpptCalVolts = !appSettings.showMpptCalVolts)
        appSettings = next
        saveSettings(next)
    }

    private fun setUnattendedMode(unattended: Boolean) {
        if (appSettings.unattendedMode == unattended) return
        appSettings = appSettings.copy(unattendedMode = unattended)
        saveSettings(appSettings)
        if (unattended) {
            addControllerEvent("Unattended mode")
            if (!chargeOutputHeldOff) writeUnattendedProfile(chargeOn = true)
        } else {
            addControllerEvent("Live mode")
            batteryProfileStatus = "Live control resumed"
            if (!chargeOutputHeldOff) writeLiveLineImpedanceZero()
        }
        tickController()
    }

    private fun writeUnattendedProfile(chargeOn: Boolean) {
        if (chargeOutputHeldOff) {
            batteryProfileStatus = "Charge output off — unattended write skipped"
            return
        }
        if (!epeverUsbState.connected) {
            batteryProfileStatus = "Will write when EPEVER reconnects"
            return
        }
        batteryProfileStatus = "Writing unattended profile..."
        val profile = if (appSettings.unattendedMode) {
            unattendedProfile.withVoltageCal(appSettings.epeverVoltageCal)
        } else {
            unattendedProfile
        }
        epeverUsbMonitor.writeBatteryProfile(profile) { result ->
            result.onSuccess {
                if (chargeOn) epeverUsbMonitor.setCharging(true)
                writeExtendedSettings(
                    extendedSettings,
                    "Unattended profile written",
                    lineImpedanceOverride = null
                )
                addControllerEvent("EPEVER unattended profile written")
            }.onFailure { error ->
                batteryProfileStatus = "Write failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun updateExtendedSettings(settings: EpeverExtendedSettings) {
        extendedSettings = settings
        saveExtendedSettings(settings)
        if (chargeOutputHeldOff) {
            batteryProfileStatus = "Charge output off — extras saved, not written"
            return
        }
        writeExtendedSettings(settings, "Extended settings written")
    }

    private fun writeLiveLineImpedanceZero() {
        writeExtendedSettings(
            extendedSettings,
            "Live line impedance 0",
            lineImpedanceOverride = 0.0
        )
    }

    private fun writeExtendedSettings(
        settings: EpeverExtendedSettings,
        successStatus: String,
        lineImpedanceOverride: Double? = if (appSettings.unattendedMode) null else 0.0
    ) {
        if (!epeverUsbState.connected) return
        val toWrite = if (lineImpedanceOverride == null) {
            settings
        } else {
            settings.copy(lineImpedanceMOhm = lineImpedanceOverride)
        }
        epeverUsbMonitor.writeExtendedSettings(toWrite) { result ->
            result.onSuccess {
                batteryProfileStatus = successStatus
            }.onFailure { error ->
                batteryProfileStatus = "Extras write failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun writeProtectionParams(profile: EpeverBatteryProfile) {
        if (!epeverUsbState.connected) return
        epeverUsbMonitor.writeProtectionParams(profile) { result ->
            result.onSuccess {
                batteryProfileStatus = "Protection parameters written"
            }.onFailure { error ->
                batteryProfileStatus = "Write failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun markBalanceDayStarted(epochDay: Long) {
        if (appSettings.lastBalanceEpochDay == epochDay) return
        appSettings = appSettings.copy(lastBalanceEpochDay = epochDay)
        saveSettings(appSettings)
    }

    private fun toggleBalanceToday() {
        val today = currentEpochDay()
        val wasBalanceDay = isBalanceDayToday(appSettings)
        val epochDay = if (wasBalanceDay) today - 1L else today
        markBalanceDayStarted(epochDay)
        addControllerEvent(if (wasBalanceDay) "Top-off day canceled" else "Top-off day forced")
        tickController()
    }

    /** After a completed 100% top-off on balance day, resume the normal SOC ceiling. */
    private fun cancelBalanceDayAfterTopOff() {
        if (!isBalanceDayToday(appSettings)) return
        markBalanceDayStarted(currentEpochDay() - 1L)
        addControllerEvent("Top-off day canceled (complete)")
        tickController()
    }

    private fun isBalanceDayToday(settings: AppSettings): Boolean {
        if (settings.balanceEveryDays <= 0) return false
        val today = currentEpochDay()
        return settings.lastBalanceEpochDay == today ||
            settings.lastBalanceEpochDay <= 0L ||
            today - settings.lastBalanceEpochDay >= settings.balanceEveryDays
    }

    private fun daysUntilNextBalance(settings: AppSettings): Int {
        if (isBalanceDayToday(settings)) return 0
        val today = currentEpochDay()
        val elapsedDays = (today - settings.lastBalanceEpochDay).coerceAtLeast(0L)
        return (settings.balanceEveryDays - elapsedDays).coerceAtLeast(0L).toInt()
    }

    private fun loadEnergy() {
        val prefs = getSharedPreferences(ENERGY_PREFS, MODE_PRIVATE)
        energyDayKey = prefs.getInt(KEY_ENERGY_DAY_KEY, 0)
        lastPersistEnergyMs = prefs.getLong(KEY_ENERGY_LAST_SAVE_MS, 0L)
        energyCounters = EnergyCounters(
            currentWatts = 0.0,
            whToday = prefs.getFloat(KEY_ENERGY_TODAY_WH, 0.0f).toDouble(),
            whYesterday = prefs.getFloat(KEY_ENERGY_YESTERDAY_WH, 0.0f).toDouble(),
            whTotal = prefs.getFloat(KEY_ENERGY_TOTAL_WH, 0.0f).toDouble(),
            bmsWhToday = prefs.getFloat(KEY_BMS_ENERGY_TODAY_WH, 0.0f).toDouble(),
            bmsWhYesterday = prefs.getFloat(KEY_BMS_ENERGY_YESTERDAY_WH, 0.0f).toDouble()
        )

        val today = nowDayKey()
        if (energyDayKey == 0) energyDayKey = today
        if (rolloverEnergyDayIfNeeded(today)) {
            rolloverDailyHealthIfNeeded(today)
            refreshDailyHealth()
            persistEnergy()
        }
    }

    private fun updateEnergyFromPower(watts: Double?) {
        val nowMs = SystemClock.elapsedRealtime()
        val today = nowDayKey()
        if (rolloverEnergyDayIfNeeded(today)) {
            rolloverDailyHealthIfNeeded(today)
            refreshDailyHealth()
            lastEnergyMs = nowMs
            lastEnergyWatts = watts
            persistEnergy()
            return
        }

        val previousMs = lastEnergyMs
        val previousWatts = lastEnergyWatts
        var nextToday = energyCounters.whToday
        var nextTotal = energyCounters.whTotal

        if (previousMs != 0L && previousWatts != null && watts != null) {
            var dtMs = nowMs - previousMs
            if (dtMs > 0L) {
                if (dtMs > ENERGY_MAX_DT_MS) dtMs = ENERGY_MAX_DT_MS
                val addWh = ((previousWatts + watts) * 0.5) * (dtMs.toDouble() / 3_600_000.0)
                if (addWh > 0.0) {
                    nextToday += addWh
                    nextTotal += addWh
                }
            }
        }

        energyCounters = energyCounters.copy(
            currentWatts = watts ?: 0.0,
            whToday = nextToday,
            whTotal = nextTotal
        )
        lastEnergyMs = nowMs
        lastEnergyWatts = watts

        maybePersistEnergy(nowMs)
    }

    private fun updateBmsEnergyFromPower(volts: Double?, amps: Double?) {
        val watts = if (volts != null && amps != null) volts * amps else null
        val nowMs = SystemClock.elapsedRealtime()
        val today = nowDayKey()
        if (rolloverEnergyDayIfNeeded(today)) {
            rolloverDailyHealthIfNeeded(today)
            refreshDailyHealth()
            lastBmsEnergyMs = nowMs
            lastBmsEnergyWatts = watts
            persistEnergy()
            return
        }

        val previousMs = lastBmsEnergyMs
        val previousWatts = lastBmsEnergyWatts
        var nextToday = energyCounters.bmsWhToday

        if (previousMs != 0L && previousWatts != null && watts != null) {
            var dtMs = nowMs - previousMs
            if (dtMs > 0L) {
                if (dtMs > ENERGY_MAX_DT_MS) dtMs = ENERGY_MAX_DT_MS
                val addWh = ((previousWatts + watts) * 0.5) * (dtMs.toDouble() / 3_600_000.0)
                if (addWh > 0.0) {
                    nextToday += addWh
                }
            }
        }

        energyCounters = energyCounters.copy(bmsWhToday = nextToday)
        lastBmsEnergyMs = nowMs
        lastBmsEnergyWatts = watts

        maybePersistEnergy(nowMs)
    }

    private fun clearEpeverGeneratedEnergy() {
        if (!epeverUsbState.connected) {
            batteryProfileStatus = "EPEVER disconnected"
            return
        }
        batteryProfileStatus = "Clearing generated energy..."
        epeverUsbMonitor.clearGeneratedEnergy { result ->
            result.onSuccess {
                energyCounters = energyCounters.copy(whToday = 0.0, whTotal = 0.0)
                persistEnergy()
                batteryProfileStatus = "Generated energy cleared"
                addControllerEvent("EPEVER generated energy cleared")
            }.onFailure { error ->
                batteryProfileStatus = "Energy clear failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun rolloverEnergyDayIfNeeded(today: Int): Boolean {
        if (energyDayKey == today) return false
        energyDayKey = today
        energyCounters = energyCounters.copy(
            whYesterday = energyCounters.whToday,
            whToday = 0.0,
            bmsWhYesterday = energyCounters.bmsWhToday,
            bmsWhToday = 0.0,
            currentWatts = 0.0
        )
        return true
    }

    private fun persistEnergy() {
        lastPersistEnergyMs = SystemClock.elapsedRealtime()
        getSharedPreferences(ENERGY_PREFS, MODE_PRIVATE).edit(commit = true) {
            putInt(KEY_ENERGY_DAY_KEY, energyDayKey)
            putFloat(KEY_ENERGY_TODAY_WH, energyCounters.whToday.toFloat())
            putFloat(KEY_ENERGY_YESTERDAY_WH, energyCounters.whYesterday.toFloat())
            putFloat(KEY_ENERGY_TOTAL_WH, energyCounters.whTotal.toFloat())
            putFloat(KEY_BMS_ENERGY_TODAY_WH, energyCounters.bmsWhToday.toFloat())
            putFloat(KEY_BMS_ENERGY_YESTERDAY_WH, energyCounters.bmsWhYesterday.toFloat())
            putLong(KEY_ENERGY_LAST_SAVE_MS, lastPersistEnergyMs)
        }
    }

    private fun maybePersistEnergy(nowMs: Long) {
        if (lastPersistEnergyMs == 0L || nowMs - lastPersistEnergyMs >= ENERGY_PERSIST_MS) {
            persistEnergy()
        }
    }

    private fun nowDayKey(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun currentEpochDay(): Long = localEpochDay()

    private fun maybeRecordHistory() {
        if (!appSettings.logHistoryEnabled) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastHistorySampleMs < HISTORY_SAMPLE_MS) return
        if (!historyAccumulator.hasCompleteSample(bmsBleState, epeverUsbState)) return

        val point = historyAccumulator.toPoint(nowMs, nowDayKey(), bmsBleState, epeverUsbState)
        lastHistorySampleMs = nowMs
        historyAccumulator = HistoryAccumulator()

        val trimmed = (historyPoints + point).filter { it.dayKey >= point.dayKey - HISTORY_KEEP_DAYS }
        historyPoints = trimmed
        appendHistoryPoint(point)
        pruneHistoryFile(trimmed)
    }

    private fun loadHistory(): List<HistoryPoint> {
        val file = historyFile()
        if (!file.exists()) return emptyList()
        val minDay = nowDayKey() - HISTORY_KEEP_DAYS
        return file.readLines()
            .mapNotNull { it.toHistoryPointOrNull() }
            .filter { it.dayKey >= minDay }
            .filter { it.isPlausibleHistoryPoint() }
    }

    private fun appendHistoryPoint(point: HistoryPoint) {
        historyFile().appendText(point.toCsvLine() + "\n")
    }

    private fun pruneHistoryFile(points: List<HistoryPoint>) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastHistoryPruneMs < HISTORY_PRUNE_MS) return
        lastHistoryPruneMs = nowMs
        historyFile().writeText(points.joinToString(separator = "\n", postfix = "\n") { it.toCsvLine() })
    }

    private fun historyFile(): File = File(filesDir, HISTORY_FILE)

    private fun HistoryPoint.toCsvLine(): String {
        return listOf(
            timestampMs,
            dayKey,
            batteryVolts,
            batteryAmps,
            batteryWatts,
            socPercent,
            temperatureF,
            pvVolts,
            mpptBattVolts,
            chargeAmps,
            chargeWatts
        ).joinToString(",")
    }

    private fun String.toHistoryPointOrNull(): HistoryPoint? {
        val parts = split(",")
        if (parts.size < 11) return null
        return try {
            HistoryPoint(
                timestampMs = parts[0].toLong(),
                dayKey = parts[1].toInt(),
                batteryVolts = parts[2].toDouble(),
                batteryAmps = parts[3].toDouble(),
                batteryWatts = parts[4].toDouble(),
                socPercent = parts[5].toInt(),
                temperatureF = parts[6].toDouble(),
                pvVolts = parts[7].toDouble(),
                mpptBattVolts = parts[8].toDouble(),
                chargeAmps = parts[9].toDouble(),
                chargeWatts = parts[10].toDouble()
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun HistoryPoint.isPlausibleHistoryPoint(): Boolean {
        return batteryVolts > 1.0 &&
            temperatureF > -40.0 &&
            temperatureF < 180.0 &&
            socPercent in 0..100
    }

    override fun onResume() {
        super.onResume()
        if (::bmsBleScanner.isInitialized) {
            bmsBleScanner.ensureAutoConnect()
        }
        val sensor = pressureSensor
        val manager = sensorManager
        if (sensor != null && manager != null) {
            manager.registerListener(pressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            barometricPressureInHg = null
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(pressureListener)
        persistEnergy()
        super.onPause()
    }

    override fun onDestroy() {
        gpsAltitudeSampleJob?.cancel()
        gpsAltitudeSampleJob = null
        sensorManager?.unregisterListener(pressureListener)
        stopUsbAlarm()
        stopLowSocAlarm()
        if (::bmsBleScanner.isInitialized) {
            if (isFinishing) {
                bmsBleScanner.shutdown()
            } else {
                bmsBleScanner.stopScan()
            }
        }
        epeverUsbMonitor.stop()
        persistEnergy()
        applyControllerKeepAlive(false)
        super.onDestroy()
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startGpsAltitudeSample() {
        if (gpsAltitudeSampleJob?.isActive == true) return
        if (!hasFineLocationPermission()) {
            gpsAltitudeSampling = false
            pendingGpsAltitudeSampleAfterPermission = true
            if (::bmsBleScanner.isInitialized) {
                requestBlePermissions.launch(bmsBleScanner.requiredPermissions())
            }
            addControllerEvent("Pressure altitude: location permission needed")
            return
        }
        pendingGpsAltitudeSampleAfterPermission = false
        gpsAltitudeSampleJob = lifecycleScope.launch {
            val previousLock = gpsAltitudeMeters
            gpsAltitudeSampling = true
            gpsAltitudeMeters = null
            try {
                val samples = collectGpsAltitudeSamples(
                    sampleCount = GPS_ALTITUDE_SAMPLE_COUNT,
                    minIntervalMs = GPS_ALTITUDE_SAMPLE_INTERVAL_MS,
                    timeoutMs = GPS_ALTITUDE_SAMPLE_TIMEOUT_MS
                )
                if (samples.size >= GPS_ALTITUDE_MIN_GOOD_SAMPLES) {
                    val averageM = samples.average()
                    gpsAltitudeMeters = averageM
                    val feet = averageM * METERS_TO_FEET
                    addControllerEvent("Pressure altitude locked: %.0f ft".format(feet))
                } else {
                    gpsAltitudeMeters = previousLock
                    addControllerEvent(
                        if (previousLock != null) {
                            "Pressure altitude GPS failed; kept previous lock"
                        } else {
                            "Pressure altitude GPS failed"
                        }
                    )
                }
            } catch (e: CancellationException) {
                gpsAltitudeMeters = previousLock
                throw e
            } finally {
                gpsAltitudeSampling = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectGpsAltitudeSamples(
        sampleCount: Int,
        minIntervalMs: Long,
        timeoutMs: Long
    ): List<Double> {
        val lm = getSystemService(LocationManager::class.java) ?: return emptyList()
        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                addControllerEvent("Pressure altitude: location providers disabled")
                return emptyList()
            }
        }
        val samples = mutableListOf<Double>()
        var listener: LocationListener? = null
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    var lastSampleElapsed = 0L
                    val locListener = LocationListener { location ->
                        if (!isAltitudeSampleAcceptable(location)) return@LocationListener
                        val now = SystemClock.elapsedRealtime()
                        if (samples.isNotEmpty() && now - lastSampleElapsed < minIntervalMs) {
                            return@LocationListener
                        }
                        lastSampleElapsed = now
                        samples.add(location.altitude)
                        if (samples.size >= sampleCount && cont.isActive) {
                            cont.resume(Unit)
                        }
                    }
                    listener = locListener
                    try {
                        lm.requestLocationUpdates(provider, 1000L, 0f, locListener, mainLooper)
                    } catch (_: SecurityException) {
                        if (cont.isActive) cont.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation {
                        runCatching { lm.removeUpdates(locListener) }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Return whatever samples were collected.
        } finally {
            listener?.let { runCatching { lm.removeUpdates(it) } }
        }
        return samples
    }

    private fun isAltitudeSampleAcceptable(location: Location): Boolean {
        if (!location.hasAltitude()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            if (location.verticalAccuracyMeters > GPS_ALTITUDE_MAX_VERTICAL_ACCURACY_M) {
                return false
            }
        }
        return true
    }

    companion object {
        private const val PRESSURE_UI_UPDATE_INTERVAL_MS = 1000L
        private const val GPS_ALTITUDE_SAMPLE_COUNT = 10
        private const val GPS_ALTITUDE_MIN_GOOD_SAMPLES = 3
        private const val GPS_ALTITUDE_SAMPLE_INTERVAL_MS = 2500L
        private const val GPS_ALTITUDE_SAMPLE_TIMEOUT_MS = 45_000L
        private const val GPS_ALTITUDE_MAX_VERTICAL_ACCURACY_M = 100f
        private const val METERS_TO_FEET = 3.280839895
        private const val SETTINGS_PREFS = "controller_settings"
        private const val ENERGY_PREFS = "energy_counters"
        private const val KEY_BALANCE_V = "balance_v"
        private const val KEY_MAX_BATTERY_V = "max_battery_v"
        private const val KEY_BMS_OFFLINE_MAX_BATTERY_V = "bms_offline_max_battery_v"
        private const val KEY_BALANCE_EVERY_DAYS = "balance_every_days"
        private const val KEY_LAST_BALANCE_EPOCH_DAY = "last_balance_epoch_day"
        private const val KEY_MAX_CHARGE_A = "max_charge_a"
        private const val KEY_TARGET_PV_V = "target_pv_v"
        private const val KEY_CONTROLLER_ENABLED = "controller_enabled"
        private const val KEY_CONTROLLER_ON_MIGRATED = "controller_on_migrated"
        private const val KEY_UNATTENDED_MODE = "unattended_mode"
        private const val KEY_MANUAL_CV_ENABLED = "manual_cv_enabled"
        private const val KEY_MANUAL_CV_V = "manual_cv_v"
        private const val KEY_EPEVER_V_CAL = "epever_voltage_cal"
        private const val KEY_SHOW_MPPT_CAL_V = "show_mppt_cal_v"
        private const val KEY_BP_TYPE = "bp_type"
        private const val KEY_BP_CAPACITY = "bp_capacity"
        private const val KEY_BP_TEMP = "bp_temp"
        private const val KEY_BP_HVD = "bp_hvd"
        private const val KEY_BP_LIMIT = "bp_limit"
        private const val KEY_BP_OVR = "bp_ovr"
        private const val KEY_BP_EQ = "bp_eq"
        private const val KEY_BP_BOOST = "bp_boost"
        private const val KEY_BP_FLOAT = "bp_float"
        private const val KEY_BP_BOOST_RE = "bp_boost_re"
        private const val KEY_BP_LVR = "bp_lvr"
        private const val KEY_BP_UVR = "bp_uvr"
        private const val KEY_BP_UVW = "bp_uvw"
        private const val KEY_BP_LVD = "bp_lvd"
        private const val KEY_BP_DLV = "bp_dlv"
        private const val KEY_EX_EQ_CYCLE = "ex_eq_cycle"
        private const val KEY_EX_BATT_T_HIGH = "ex_batt_t_high"
        private const val KEY_EX_BATT_T_LOW = "ex_batt_t_low"
        private const val KEY_EX_INNER_T_LIM = "ex_inner_t_lim"
        private const val KEY_EX_INNER_T_REC = "ex_inner_t_rec"
        private const val KEY_EX_PWR_T_LIM = "ex_pwr_t_lim"
        private const val KEY_EX_PWR_T_REC = "ex_pwr_t_rec"
        private const val KEY_EX_LINE_Z = "ex_line_z"
        private const val KEY_EX_RATED_V = "ex_rated_v"
        private const val KEY_EX_EQ_MIN = "ex_eq_min"
        private const val KEY_EX_BOOST_MIN = "ex_boost_min"
        private const val KEY_EX_DISCH_PCT = "ex_disch_pct"
        private const val KEY_EX_CHG_PCT = "ex_chg_pct"
        private const val KEY_EX_MGMT_SOC = "ex_mgmt_soc"
        private const val KEY_MAX_CHARGE_PERCENT = "max_charge_percent"
        private const val KEY_MAX_SOC_TRICKLE_A = "max_soc_trickle_a"
        private const val KEY_NORMAL_SOC_CEILING_PERCENT = "normal_soc_ceiling_percent"
        private const val KEY_NORMAL_SOC_RESUME_PERCENT = "normal_soc_resume_percent"
        private const val KEY_HOLD_SAG_V = "hold_sag_v"
        private const val KEY_HOLD_SAG_DWELL_S = "hold_sag_dwell_s"
        private const val KEY_SOC_HOLD_CURRENT_A = "soc_hold_current_a"
        private const val KEY_LOW_SOC_ALARM_PERCENT = "low_soc_alarm_percent"
        private const val KEY_SOC_DRIFT_DEADBAND_A = "soc_drift_deadband_a"
        private const val KEY_SOC_DRIFT_ALARM_PERCENT = "soc_drift_alarm_percent"
        private const val KEY_BMS_VOLTAGE_HOLD_V = "bms_voltage_hold_v"
        private const val KEY_FULL_HOLD_V = "full_hold_v"
        private const val KEY_FULL_HOLD_CURRENT_A = "full_hold_current_a"
        private const val KEY_BMS_VOLTAGE_HOLD_END_CURRENT_A = "bms_voltage_hold_end_current_a"
        private const val KEY_KNEE_VARIANCE_V = "knee_variance_v"
        private const val KEY_MIN_TARGET_PV_V = "min_target_pv_v"
        private const val KEY_MAX_TARGET_PV_V = "max_target_pv_v"
        private const val KEY_KNEE_STEP_V = "knee_step_v"
        private const val KEY_FAST_PROBE_RECOVERY_KNEE_BACK_V = "fast_probe_recovery_knee_back_v"
        private const val KEY_CLOUD_RECOVERY_KNEE_BACK_V = "cloud_recovery_knee_back_v"
        private const val KEY_PROBE_RECOVERY_KNEE_BACK_V = "probe_recovery_knee_back_v"
        private const val KEY_PROBE_RECOVERY_ISET_FRACTION = "probe_recovery_iset_fraction"
        private const val KEY_HCC_QUIET_S = "hcc_quiet_s"
        private const val KEY_KNEE_TRACKING_DELAY_S = "knee_tracking_delay_s"
        private const val KEY_KNEE_TRACKING_DELAY_MIN_S = "knee_tracking_delay_min_s"
        private const val KEY_KNEE_TRACKING_DELAY_MAX_S = "knee_tracking_delay_max_s"
        private const val KEY_FAST_ACQUIRE_SUCCESS_COUNT = "fast_acquire_success_count"
        private const val KEY_POWER_BASED_VTUNE_STOP = "power_based_vtune_stop"
        private const val KEY_CONTROLLER_LOOP_MS = "controller_loop_ms"
        private const val KEY_VOLTAGE_WRITE_S = "voltage_write_s"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LOG_EVENTS_ENABLED = "log_events_enabled"
        private const val KEY_LOG_TELEMETRY_ENABLED = "log_telemetry_enabled"
        private const val KEY_LOG_SKY_DISTURBANCES_ENABLED = "log_sky_disturbances_enabled"
        private const val KEY_LOG_CRASH_EPISODES_ENABLED = "log_crash_episodes_enabled"
        private const val KEY_LOG_HISTORY_ENABLED = "log_history_enabled"
        private const val KEY_ENERGY_DAY_KEY = "energy_day_key"
        private const val KEY_ENERGY_TODAY_WH = "energy_today_wh"
        private const val KEY_ENERGY_YESTERDAY_WH = "energy_yesterday_wh"
        private const val KEY_ENERGY_TOTAL_WH = "energy_total_wh"
        private const val KEY_BMS_ENERGY_TODAY_WH = "bms_energy_today_wh"
        private const val KEY_BMS_ENERGY_YESTERDAY_WH = "bms_energy_yesterday_wh"
        private const val KEY_ENERGY_LAST_SAVE_MS = "energy_last_save_ms"
        private const val ENERGY_MAX_DT_MS = 60_000L
        private const val ENERGY_PERSIST_MS = 5_000L
        private const val HISTORY_FILE = "controller_history.csv"
        private const val HISTORY_SAMPLE_MS = 30_000L
        private const val HISTORY_KEEP_DAYS = 30
        private const val HISTORY_PRUNE_MS = 300_000L
        private const val OPS_SAMPLE_BURST_MS = 200L
        private const val OPS_BURST_STABLE_CLEAR_MS = 5_000L
        private const val OPS_STABLE_VIN_ERROR_V = 0.05
        private const val OPS_KNEE_DOWN_EPS_V = 0.001
        private const val OPS_SAMPLE_AGGRESSIVE_MS = 5_000L
        private const val OPS_SAMPLE_NEAR_MS = 30_000L
        private const val OPS_SAMPLE_STABLE_MS = 90_000L
        private const val OPS_SAMPLE_OTHER_MS = 60_000L
        private const val OPS_SAMPLE_IDLE_MS = 120_000L
        private const val OPS_STORAGE_CHECK_MS = 60_000L
        private const val MAX_CONTROLLER_EVENTS = 80
        private val eventTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

private class RollingAverageWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(nowMs: Long, value: Double?) {
        if (value == null) {
            prune(nowMs)
            return
        }
        samples.addLast(nowMs to value)
        prune(nowMs)
    }

    fun average(nowMs: Long): Double? {
        prune(nowMs)
        if (samples.isEmpty()) return null
        return samples.sumOf { it.second } / samples.size
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }
}

private class HistoryAccumulator {
    private var batteryVolts = Average()
    private var batteryAmps = Average()
    private var batteryWatts = Average()
    private var temperatureF = Average()
    private var pvVolts = Average()
    private var mpptBattVolts = Average()
    private var chargeAmps = Average()
    private var chargeWatts = Average()

    fun addBms(state: BmsBleUiState) {
        val telemetry = state.telemetry
        val volts = telemetry.packVoltage
        val amps = telemetry.packCurrent
        batteryVolts.add(volts)
        batteryAmps.add(amps)
        if (volts != null && amps != null) batteryWatts.add(volts * amps)
        temperatureF.add(telemetry.temperatureF)
    }

    fun addEpever(state: EpeverUsbState) {
        val telemetry = state.telemetry
        pvVolts.add(telemetry.pvVolts)
        mpptBattVolts.add(telemetry.batteryVolts)
        chargeAmps.add(telemetry.chargeAmps)
        chargeWatts.add(telemetry.chargeWatts)
    }

    fun hasCompleteSample(
        bmsState: BmsBleUiState,
        epeverState: EpeverUsbState
    ): Boolean {
        val bms = bmsState.telemetry
        return bmsState.connectedDeviceAddress != null &&
            epeverState.connected &&
            bms.socPercent != null &&
            batteryVolts.hasValue &&
            batteryAmps.hasValue &&
            temperatureF.hasValue &&
            pvVolts.hasValue
    }

    fun toPoint(
        timestampMs: Long,
        dayKey: Int,
        bmsState: BmsBleUiState,
        epeverState: EpeverUsbState
    ): HistoryPoint {
        val bms = bmsState.telemetry
        val mppt = epeverState.telemetry
        return HistoryPoint(
            timestampMs = timestampMs,
            dayKey = dayKey,
            batteryVolts = batteryVolts.valueOr(bms.packVoltage ?: 0.0),
            batteryAmps = batteryAmps.valueOr(bms.packCurrent ?: 0.0),
            batteryWatts = batteryWatts.valueOr((bms.packVoltage ?: 0.0) * (bms.packCurrent ?: 0.0)),
            socPercent = bms.socPercent ?: 0,
            temperatureF = temperatureF.valueOr(bms.temperatureF ?: 0.0),
            pvVolts = pvVolts.valueOr(mppt.pvVolts),
            mpptBattVolts = mpptBattVolts.valueOr(mppt.batteryVolts),
            chargeAmps = chargeAmps.valueOr(mppt.chargeAmps),
            chargeWatts = chargeWatts.valueOr(mppt.chargeWatts)
        )
    }
}

private class Average {
    private var sum = 0.0
    private var count = 0

    val hasValue: Boolean
        get() = count > 0

    fun add(value: Double?) {
        if (value == null) return
        sum += value
        count += 1
    }

    fun valueOr(fallback: Double): Double {
        return if (count > 0) sum / count else fallback
    }
}

private enum class AppTab(
    val label: String,
    val icon: ImageVector
) {
    Dashboard("Dash", Icons.Filled.Dashboard),
    History("History", Icons.Filled.BarChart),
    Devices("Devices", Icons.Filled.Bluetooth),
    Settings("Settings", Icons.Filled.Settings),
    Tools("Tools", Icons.Filled.Build)
}

@Composable
private fun EpeverApp(
    bmsBleState: BmsBleUiState,
    epeverUsbState: EpeverUsbState,
    appSettings: AppSettings,
    mpptControlState: MpptControlState,
    energyCounters: EnergyCounters,
    dailyHealth: DailyHealthState,
    historyPoints: List<HistoryPoint>,
    controllerEvents: List<String>,
    alertState: AlertState,
    opsLogSummary: OpsLogStorageSummary,
    logStorageWarning: String?,
    worstCaseSocPercent: Int?,
    socDriftAh: Double,
    averagePackCurrentA: Double?,
    barometricPressureInHg: Double?,
    gpsAltitudeMeters: Double?,
    gpsAltitudeSampling: Boolean,
    onPressureTileClick: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onStartBmsScan: () -> Unit,
    onStopBmsScan: () -> Unit,
    onConnectBms: (BmsBleDevice) -> Unit,
    onDisconnectBms: () -> Unit,
    balanceDayToday: Boolean,
    daysUntilNextBalance: Int,
    onToggleBalanceToday: () -> Unit,
    onManualCvChanged: (Boolean, Double) -> Unit,
    onEpeverVoltageCalChanged: (Double) -> Unit,
    onToggleMpptCalDisplay: () -> Unit,
    onClearEpeverEnergy: () -> Unit,
    onResetSocDrift: () -> Unit,
    onDeleteOpsLogsBeforeDate: (String) -> String,
    onRefreshOpsLogSummary: () -> Unit,
    unattendedProfile: EpeverBatteryProfile,
    extendedSettings: EpeverExtendedSettings,
    batteryProfileStatus: String,
    onRefreshBatteryProfile: () -> Unit,
    onUnattendedModeChanged: (Boolean) -> Unit,
    chargeOutputOn: Boolean,
    onChargeOutputChanged: (Boolean) -> Unit,
    onBatteryProfileChanged: (EpeverBatteryProfile, Boolean) -> Unit,
    onExtendedSettingsChanged: (EpeverExtendedSettings) -> Unit,
    onSyncEpeverClock: () -> Unit,
    onDismissLogStorageWarning: () -> Unit,
    onSilenceLowSocAlarm: () -> Unit,
    onSilenceSocDriftAlarm: () -> Unit
) {
    val state = remember(
        bmsBleState,
        epeverUsbState,
        appSettings,
        mpptControlState,
        energyCounters,
        dailyHealth,
        historyPoints,
        controllerEvents,
        alertState,
        opsLogSummary,
        worstCaseSocPercent,
        socDriftAh,
        averagePackCurrentA,
        barometricPressureInHg,
        gpsAltitudeMeters,
        gpsAltitudeSampling,
        unattendedProfile
    ) {
        AppState.preview.copy(
            settings = appSettings,
            energy = energyCounters,
            dailyHealth = dailyHealth,
            history = historyPoints,
            events = controllerEvents,
            alerts = alertState,
            opsLogSummary = opsLogSummary,
            barometricPressureInHg = barometricPressureInHg,
            gpsAltitudeMeters = gpsAltitudeMeters,
            gpsAltitudeSampling = gpsAltitudeSampling
        )
            .withBmsTelemetry(bmsBleState)
            .withEpeverTelemetry(epeverUsbState)
            .withMpptControl(mpptControlState)
            .let { base ->
                base.copy(
                    battery = base.battery.copy(
                        worstCaseSocPercent = worstCaseSocPercent,
                        socDriftAh = socDriftAh
                    ),
                    controller = base.controller.copy(
                        averagePackCurrentA = averagePackCurrentA,
                        profileBoostVolts = epeverUsbState.deviceProfile?.boostVolts
                            ?: unattendedProfile.boostVolts,
                        profileFloatVolts = epeverUsbState.deviceProfile?.floatVolts
                            ?: unattendedProfile.floatVolts
                    )
                )
            }
    }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Dashboard) }
    val tabStateHolder = rememberSaveableStateHolder()

    if (logStorageWarning != null) {
        AlertDialog(
            onDismissRequest = onDismissLogStorageWarning,
            title = { Text("Log Storage Warning") },
            text = { Text(logStorageWarning) },
            confirmButton = {
                TextButton(onClick = onDismissLogStorageWarning) {
                    Text("Dismiss")
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppTab.Dashboard -> {
                    val layoutDirection = LocalLayoutDirection.current
                    DashboardScreen(
                        state = state,
                        onSilenceLowSocAlarm = onSilenceLowSocAlarm,
                        onSilenceSocDriftAlarm = onSilenceSocDriftAlarm,
                        onPressureTileClick = onPressureTileClick,
                        onToggleBalanceToday = onToggleBalanceToday,
                        onManualCvChanged = onManualCvChanged,
                        onToggleMpptCalDisplay = onToggleMpptCalDisplay,
                        modifier = Modifier.padding(
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                            bottom = innerPadding.calculateBottomPadding()
                        )
                    )
                }
                AppTab.History -> HistoryScreen(state, Modifier.padding(innerPadding))
                AppTab.Devices -> DevicesScreen(
                    bleState = bmsBleState,
                    epeverState = epeverUsbState,
                    mppt = state.mppt,
                    commandCvVolts = state.controller.commandCvVolts,
                    energy = state.energy,
                    onRequestPermissions = onRequestBlePermissions,
                    onStartScan = onStartBmsScan,
                    onStopScan = onStopBmsScan,
                    onConnect = onConnectBms,
                    onDisconnect = onDisconnectBms,
                    modifier = Modifier.padding(innerPadding)
                )
                AppTab.Settings -> SettingsScreen(
                    settings = state.settings,
                    mppt = state.mppt,
                    socDriftAh = state.battery.socDriftAh,
                    worstCaseSocPercent = state.battery.worstCaseSocPercent,
                    onSettingsChanged = onSettingsChanged,
                    balanceDayToday = balanceDayToday,
                    daysUntilNextBalance = daysUntilNextBalance,
                    onToggleBalanceToday = onToggleBalanceToday,
                    onResetSocDrift = onResetSocDrift,
                    opsLogSummary = state.opsLogSummary,
                    onDeleteOpsLogsBeforeDate = onDeleteOpsLogsBeforeDate,
                    onRefreshOpsLogSummary = onRefreshOpsLogSummary,
                    modifier = Modifier.padding(innerPadding)
                )
                AppTab.Tools -> ToolsScreen(
                    unattendedMode = appSettings.unattendedMode,
                    profile = unattendedProfile,
                    extended = extendedSettings,
                    epeverConnected = epeverUsbState.connected,
                    epeverClock = epeverUsbState.clock,
                    mppt = state.mppt,
                    bmsVolts = state.battery.volts,
                    bmsAmps = state.battery.amps,
                    status = batteryProfileStatus,
                    modifier = Modifier.padding(innerPadding),
                    onRefreshFromEpever = onRefreshBatteryProfile,
                    onUnattendedModeChanged = onUnattendedModeChanged,
                    chargeOutputOn = chargeOutputOn,
                    onChargeOutputChanged = onChargeOutputChanged,
                    onProfileChanged = onBatteryProfileChanged,
                    onExtendedChanged = onExtendedSettingsChanged,
                    voltageCal = appSettings.epeverVoltageCal,
                    onVoltageCalChanged = onEpeverVoltageCalChanged,
                    onSyncClockFromPhone = onSyncEpeverClock,
                    onClearGeneratedEnergy = onClearEpeverEnergy
                )
            }
        }
    }
}

private fun AppState.withMpptControl(control: MpptControlState): AppState {
    return copy(
        controller = controller.copy(
            enabled = control.enabled,
            pvMode = control.pvMode,
            status = control.status,
            epeverConnected = control.epeverConnected,
            socTargetPercent = control.socTargetPercent.takeIf { it > 0 } ?: settings.normalSocCeilingPercent,
            commandCvVolts = control.commandCvVolts,
            voltageOffset = control.voltageOffset,
            holdVolts = control.holdVolts,
            chargingOn = control.chargingOn
        ),
        mppt = mppt.copy(
            voltageOffset = control.voltageOffset,
            commandCvVolts = control.commandCvVolts
        )
    )
}

private fun AppState.withEpeverTelemetry(usbState: EpeverUsbState): AppState {
    val telemetry = usbState.telemetry
    val live = if (usbState.connected) {
        MpptState(
            pvVolts = telemetry.pvVolts,
            pvAmps = telemetry.pvAmps,
            pvWatts = telemetry.pvWatts,
            batteryVolts = telemetry.batteryVolts,
            chargeAmps = telemetry.chargeAmps,
            chargeWatts = telemetry.chargeWatts,
            deviceTempF = telemetry.deviceTempF,
            rtsTempF = telemetry.rtsTempF,
            chargingOn = telemetry.chargingOn,
            chargeStage = telemetry.chargeStage,
            fault = telemetry.fault,
            generatedWhToday = telemetry.generatedWhToday,
            generatedWhMonth = telemetry.generatedWhMonth,
            generatedWhYear = telemetry.generatedWhYear,
            generatedWhTotal = telemetry.generatedWhTotal,
            voltageOffset = mppt.voltageOffset,
            commandCvVolts = mppt.commandCvVolts,
            controllerSocPercent = telemetry.controllerSocPercent,
            systemRatedVolts = telemetry.systemRatedVolts,
            ratedPvVolts = telemetry.ratedPvVolts,
            ratedPvAmps = telemetry.ratedPvAmps,
            ratedPvWatts = telemetry.ratedPvWatts,
            ratedChargeVolts = telemetry.ratedChargeVolts,
            ratedChargeAmps = telemetry.ratedChargeAmps,
            ratedChargeWatts = telemetry.ratedChargeWatts,
            batteryStatusText = telemetry.batteryStatusText,
            chargeStatusText = telemetry.chargeStatusText,
            netBatteryAmps = telemetry.netBatteryAmps,
            chargingModeText = telemetry.chargingModeText,
            maxPvVoltsToday = telemetry.maxPvVoltsToday,
            minPvVoltsToday = telemetry.minPvVoltsToday,
            maxBatteryVoltsToday = telemetry.maxBatteryVoltsToday,
            minBatteryVoltsToday = telemetry.minBatteryVoltsToday
        )
    } else {
        mppt
    }
    return copy(
        mppt = live,
        controller = controller.copy(
            epeverConnected = usbState.connected,
            lastEepromWriteMs = usbState.lastEepromWriteMs,
            status = listOf(
                if (controller.bmsConnected) "BMS live" else "BMS disconnected",
                usbState.status
            ).joinToString(" | ")
        ),
        logs = logs + buildList {
            add("EPEVER status: ${usbState.status}")
            if (usbState.connected) {
                add("PV: %.2f V / %.2f A / %.0f W".format(telemetry.pvVolts, telemetry.pvAmps, telemetry.pvWatts))
                add("MPPT batt: %.2f V / %.2f A".format(telemetry.batteryVolts, telemetry.chargeAmps))
                add("Stage: ${telemetry.chargeStage.label}")
            }
        }
    )
}

private fun AppState.withBmsTelemetry(bleState: BmsBleUiState): AppState {
    val telemetry = bleState.telemetry
    val liveBattery = if (telemetry.packVoltage != null || telemetry.socPercent != null) {
        BatteryState(
            socPercent = telemetry.socPercent ?: battery.socPercent,
            volts = telemetry.packVoltage ?: battery.volts,
            amps = telemetry.packCurrent ?: battery.amps,
            watts = (telemetry.packVoltage ?: battery.volts) * (telemetry.packCurrent ?: battery.amps),
            remainingAh = telemetry.remainingAh ?: battery.remainingAh,
            nominalAh = telemetry.nominalAh ?: battery.nominalAh,
            temperatureF = telemetry.temperatureF ?: battery.temperatureF,
            direction = when {
                (telemetry.packCurrent ?: 0.0) > 0.05 -> PowerDirection.Charging
                (telemetry.packCurrent ?: 0.0) < -0.05 -> PowerDirection.Discharging
                else -> PowerDirection.Idle
            },
            chargeMode = when {
                bleState.connectedDeviceAddress == null -> ChargeMode.Idle
                telemetry.balancingActive == true -> ChargeMode.Balance
                (telemetry.packCurrent ?: 0.0) > 0.05 -> ChargeMode.Bulk
                else -> ChargeMode.Idle
            },
            balancing = telemetry.balancingActive == true,
            cellDeltaMv = telemetry.cellDeltaMv ?: battery.cellDeltaMv
        )
    } else {
        BatteryState(
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
        )
    }

    return copy(
        battery = liveBattery,
        controller = controller.copy(
            bmsConnected = bleState.connectedDeviceAddress != null,
            status = if (bleState.connectedDeviceAddress != null) {
                "BMS live: ${bleState.status}"
            } else {
                "BMS disconnected"
            }
        ),
        logs = buildList {
            add(if (bleState.connectedDeviceAddress != null) "BMS connected: ${bleState.connectedDeviceName}" else "BMS disconnected")
            add("BMS status: ${bleState.status}")
            telemetry.packVoltage?.let { add("BMS voltage: %.2f V".format(it)) }
            telemetry.packCurrent?.let { add("BMS current: %.2f A".format(it)) }
            telemetry.socPercent?.let { add("BMS SOC: $it%") }
            telemetry.cellDeltaMv?.let { add("BMS cell delta: $it mV") }
            telemetry.protectionStatus?.let {
                val alarms = telemetry.protectionAlarmNames.ifEmpty { listOf("none") }.joinToString()
                add("BMS protection: $alarms (0x%04X)".format(it))
            }
        }
    )
}
