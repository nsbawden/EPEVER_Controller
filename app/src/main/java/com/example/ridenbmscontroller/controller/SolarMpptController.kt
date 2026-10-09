package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.epever.EpeverUsbState
import com.example.ridenbmscontroller.epever.EpeverVoltageProfile
import com.example.ridenbmscontroller.model.AppSettings
import java.util.Calendar
import kotlin.math.abs

data class MpptControlState(
    val enabled: Boolean = false,
    val pvMode: String = "Idle",
    val status: String = "Controller off",
    val commandCvVolts: Double = 0.0,
    val voltageOffset: Double = 0.0,
    val holdVolts: Double = 0.0,
    val chargingOn: Boolean = false,
    val socTargetPercent: Int = 0,
    val epeverConnected: Boolean = false,
    val bmsConnected: Boolean = false
)

class SolarMpptController(
    private val setCharging: (Boolean) -> Unit,
    private val applyVoltageProfile: (EpeverVoltageProfile) -> Unit,
    private val onBalanceDayStarted: (Long) -> Unit,
    private val onState: (MpptControlState) -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val onTopOffComplete: (reason: String) -> Unit = {}
) {
    private var lastTickMs = 0L
    private var lastWriteMs = 0L
    private var lastWrittenCv = Double.NaN
    private var lastCharging: Boolean? = null
    private var lastMode = MODE_IDLE
    private var offsetEma = 0.0
    private var offsetReady = false
    private var holdEstSyncedThisVisit = false
    private var alarmHoldUntilMs = 0L
    private var alarmBackoffMs = ALARM_BASE_HOLD_MS
    private var lastAlarmStartMs = 0L
    private var wasInHold = false

    fun tick(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        epeverState: EpeverUsbState
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTickMs < settings.controllerLoopMs.toLong()) return
        lastTickMs = now

        val bms = bmsState.telemetry
        val epever = epeverState.telemetry
        val bmsReady = bmsState.connectedDeviceAddress != null &&
            bms.packVoltage != null &&
            bms.packCurrent != null &&
            bms.socPercent != null
        val socPercent = bms.socPercent
        val batteryVolts = bms.packVoltage

        if (!settings.controllerEnabled || !epeverState.connected) {
            holdEstSyncedThisVisit = false
            wasInHold = false
            idle(settings, epeverState.connected, bmsReady, "Controller off")
            return
        }

        val balanceDay = bmsReady && isBalanceDay(settings)
        if (balanceDay && settings.lastBalanceEpochDay != currentEpochDay()) {
            onBalanceDayStarted(currentEpochDay())
        }
        val socTarget = if (balanceDay) 100 else settings.normalSocCeilingPercent
        val holdVolts = if (socTarget >= 100) settings.fullHoldVolts else settings.bmsVoltageHoldVolts

        if (bmsReady) {
            val alarms = bms.chargeBlockingAlarmNames
            if (alarms.isNotEmpty()) {
                updateAlarmBackoff(now)
                inhibitCharging(settings, socTarget, holdVolts, epeverState, bmsReady, "BMS alarm: ${alarms.joinToString()}")
                return
            }
            if (now - lastAlarmStartMs >= ALARM_RESET_AFTER_MS) {
                alarmBackoffMs = ALARM_BASE_HOLD_MS
            }
            if (now < alarmHoldUntilMs) {
                val seconds = ((alarmHoldUntilMs - now) / 1000L).coerceAtLeast(1L)
                inhibitCharging(settings, socTarget, holdVolts, epeverState, bmsReady, "BMS alarm cleared: retry in ${seconds}s")
                return
            }
        }

        val bulkCeiling = if (bmsReady) settings.maxBatteryVolts else settings.bmsOfflineMaxBatteryVolts
        updateOffset(epever.batteryVolts, batteryVolts)

        val soc = socPercent
        val reachedSoc = soc != null && soc >= socTarget
        val resumeSoc = settings.normalSocResumePercent.coerceAtMost(socTarget - 1)
        val belowResume = soc != null && soc <= resumeSoc
        if (!reachedSoc) holdEstSyncedThisVisit = false

        val wantHold = if (wasInHold) {
            bmsReady && !belowResume
        } else {
            reachedSoc
        }

        if (wantHold && batteryVolts != null && batteryVolts >= holdVolts + OVERSHOOT_MARGIN_V) {
            commandCharging(false)
            publish(
                settings = settings,
                mode = MODE_HOLD,
                status = "Hold overshoot: charging off at ${"%.2f".format(batteryVolts)}V",
                commandCv = lastWrittenCv.takeUnless { it.isNaN() } ?: holdVolts,
                holdVolts = holdVolts,
                socTarget = socTarget,
                chargingOn = false,
                epeverConnected = true,
                bmsConnected = bmsReady
            )
            return
        }

        commandCharging(true)

        if (wantHold) {
            val cv = (holdVolts + filteredOffset()).coerceIn(12.40, bulkCeiling)
            maybeWriteProfile(EpeverVoltageProfile.hold(cv, bulkCeiling), cv, now)
            if (!holdEstSyncedThisVisit) {
                holdEstSyncedThisVisit = true
                onEvent("Hold: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V, MPPT offset ${"%+.2f".format(filteredOffset())}V")
                onTopOffComplete(if (socTarget >= 100) "100% hold" else "SOC $socTarget% hold")
            }
            wasInHold = true
            publish(
                settings = settings,
                mode = MODE_HOLD,
                status = "Holding BMS ${"%.2f".format(holdVolts)}V (CV ${"%.2f".format(cv)}V)",
                commandCv = cv,
                holdVolts = holdVolts,
                socTarget = socTarget,
                chargingOn = true,
                epeverConnected = true,
                bmsConnected = bmsReady
            )
            return
        }

        if (wasInHold) {
            onEvent("Hold exit: resume bulk at ${"%.2f".format(bulkCeiling)}V")
        }
        wasInHold = false
        maybeWriteProfile(EpeverVoltageProfile.bulk(bulkCeiling), bulkCeiling, now)
        val mode = if (bmsReady) MODE_BULK else MODE_VOLTAGE
        publish(
            settings = settings,
            mode = mode,
            status = if (bmsReady) {
                "Bulk charge to SOC $socTarget%"
            } else {
                "BMS offline: MPPT at ${"%.2f".format(bulkCeiling)}V"
            },
            commandCv = bulkCeiling,
            holdVolts = holdVolts,
            socTarget = socTarget,
            chargingOn = true,
            epeverConnected = true,
            bmsConnected = bmsReady
        )
    }

    private fun updateOffset(mpptVolts: Double, bmsVolts: Double?) {
        if (bmsVolts == null || mpptVolts <= 0.0 || bmsVolts <= 0.0) return
        val sample = mpptVolts - bmsVolts
        offsetEma = if (offsetReady) {
            OFFSET_ALPHA * sample + (1.0 - OFFSET_ALPHA) * offsetEma
        } else {
            sample
        }
        offsetReady = true
    }

    private fun filteredOffset(): Double = if (offsetReady) offsetEma else 0.0

    private fun maybeWriteProfile(profile: EpeverVoltageProfile, cv: Double, now: Long) {
        val changedEnough = lastWrittenCv.isNaN() || abs(cv - lastWrittenCv) >= CV_WRITE_EPS_V
        val cooledDown = now - lastWriteMs >= CV_WRITE_COOLDOWN_MS
        if (!changedEnough || !cooledDown) return
        applyVoltageProfile(profile)
        lastWrittenCv = cv
        lastWriteMs = now
        onEvent("EPEVER CV ${"%.2f".format(cv)}V")
    }

    private fun commandCharging(on: Boolean) {
        if (lastCharging == on) return
        lastCharging = on
        setCharging(on)
    }

    private fun inhibitCharging(
        settings: AppSettings,
        socTarget: Int,
        holdVolts: Double,
        epeverState: EpeverUsbState,
        bmsReady: Boolean,
        status: String
    ) {
        holdEstSyncedThisVisit = false
        wasInHold = false
        commandCharging(false)
        publish(
            settings = settings,
            mode = MODE_ALARM,
            status = status,
            commandCv = lastWrittenCv.takeUnless { it.isNaN() } ?: settings.maxBatteryVolts,
            holdVolts = holdVolts,
            socTarget = socTarget,
            chargingOn = false,
            epeverConnected = epeverState.connected,
            bmsConnected = bmsReady
        )
    }

    private fun idle(settings: AppSettings, epeverConnected: Boolean, bmsReady: Boolean, status: String) {
        commandCharging(false)
        publish(
            settings = settings,
            mode = MODE_IDLE,
            status = status,
            commandCv = 0.0,
            holdVolts = settings.bmsVoltageHoldVolts,
            socTarget = if (isBalanceDay(settings)) 100 else settings.normalSocCeilingPercent,
            chargingOn = false,
            epeverConnected = epeverConnected,
            bmsConnected = bmsReady
        )
    }

    private fun publish(
        settings: AppSettings,
        mode: String,
        status: String,
        commandCv: Double,
        holdVolts: Double,
        socTarget: Int,
        chargingOn: Boolean,
        epeverConnected: Boolean,
        bmsConnected: Boolean
    ) {
        if (mode != lastMode) {
            onEvent("$mode: $status")
            lastMode = mode
        }
        onState(
            MpptControlState(
                enabled = settings.controllerEnabled,
                pvMode = mode,
                status = status,
                commandCvVolts = commandCv,
                voltageOffset = filteredOffset(),
                holdVolts = holdVolts,
                chargingOn = chargingOn,
                socTargetPercent = socTarget,
                epeverConnected = epeverConnected,
                bmsConnected = bmsConnected
            )
        )
    }

    private fun updateAlarmBackoff(now: Long) {
        if (now - lastAlarmStartMs < ALARM_REPEAT_WINDOW_MS) {
            alarmBackoffMs = (alarmBackoffMs * 2L).coerceAtMost(ALARM_MAX_HOLD_MS)
        } else {
            alarmBackoffMs = ALARM_BASE_HOLD_MS
        }
        lastAlarmStartMs = now
        alarmHoldUntilMs = now + alarmBackoffMs
    }

    private fun isBalanceDay(settings: AppSettings): Boolean {
        if (settings.balanceEveryDays <= 0) return false
        val today = currentEpochDay()
        return settings.lastBalanceEpochDay == today ||
            settings.lastBalanceEpochDay <= 0L ||
            today - settings.lastBalanceEpochDay >= settings.balanceEveryDays
    }

    private fun currentEpochDay(): Long {
        val cal = Calendar.getInstance()
        return cal.timeInMillis / 86_400_000L
    }

    companion object {
        const val MODE_IDLE = "Idle"
        const val MODE_BULK = "Bulk"
        const val MODE_HOLD = "Hold"
        const val MODE_ALARM = "Alarm"
        const val MODE_VOLTAGE = "Voltage"

        private const val OFFSET_ALPHA = 0.15
        private const val CV_WRITE_EPS_V = 0.05
        private const val CV_WRITE_COOLDOWN_MS = 12_000L
        private const val OVERSHOOT_MARGIN_V = 0.15
        private const val ALARM_BASE_HOLD_MS = 60_000L
        private const val ALARM_MAX_HOLD_MS = 1_800_000L
        private const val ALARM_REPEAT_WINDOW_MS = 600_000L
        private const val ALARM_RESET_AFTER_MS = 1_800_000L
    }
}
