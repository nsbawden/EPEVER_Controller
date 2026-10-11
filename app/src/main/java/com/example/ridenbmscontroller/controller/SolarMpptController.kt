package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.epever.EpeverUsbState
import com.example.ridenbmscontroller.epever.EpeverVoltageProfile
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.model.localEpochDay
import kotlin.math.abs
import kotlin.math.max

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

/**
 * Live charge policy. Every voltage change goes through [applyVoltageProfile]
 * (full 15-register User block — see [com.example.ridenbmscontroller.epever.EpeverUsbMonitor]).
 *
 * Do not use [setCharging] as inhibit: Tracer-AN coil 0x0000 is a no-op.
 * Do not park from this loop (Tools Off only). Offset EMA is display-only; never
 * add it to commanded CV (that held CV high vs BMS).
 *
 * Hold walks from device readback, never below BMS volts, 5 s / 0.10 V in sun.
 * BMS lost while holding: leave the last CV. BMS gone 10 s and not in Hold:
 * No BMS (Tools profile once, then monitor). No invented offline-max write.
 */
class SolarMpptController(
    private val setCharging: (Boolean) -> Unit,
    private val applyVoltageProfile: (EpeverVoltageProfile, onDone: (Boolean) -> Unit) -> Unit,
    private val onBalanceDayStarted: (Long) -> Unit,
    private val onState: (MpptControlState) -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val onTopOffComplete: (reason: String) -> Unit = {},
    private val onNoBms: () -> Unit = {}
) {
    private var lastTickMs = 0L
    private var lastWriteMs = 0L
    private var lastWrittenCv = Double.NaN
    private var lastCharging: Boolean? = null
    private var lastMode = MODE_IDLE
    private var offsetEma = 0.0
    private var offsetReady = false
    private var holdEstSyncedThisVisit = false
    private var lastSocTarget = 0
    private var sawSocBelowTarget = false
    private var alarmHoldUntilMs = 0L
    private var alarmBackoffMs = ALARM_BASE_HOLD_MS
    private var lastAlarmStartMs = 0L
    private var wasInHold = false
    private var holdCv = Double.NaN
    private var lastHoldWriteMs = 0L
    private var alarmLatched = false
    private var bmsMissingSinceMs = 0L

    fun tick(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        epeverState: EpeverUsbState,
        outputHeldOff: Boolean = false
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

        if (outputHeldOff) {
            resetNoBmsWatch()
            lastCharging = false
            publish(
                settings = settings,
                mode = MODE_OUTPUT_OFF,
                status = "Charge output held off from Tools",
                commandCv = lastWrittenCv.takeUnless { it.isNaN() } ?: 0.0,
                holdVolts = settings.bmsVoltageHoldVolts,
                socTarget = if (isBalanceDay(settings)) 100 else settings.normalSocCeilingPercent,
                chargingOn = false,
                epeverConnected = epeverState.connected,
                bmsConnected = bmsReady
            )
            return
        }

        if (settings.unattendedMode) {
            resetNoBmsWatch()
            holdEstSyncedThisVisit = false
            wasInHold = false
            clearHoldServo()
            lastCharging = null
            publish(
                settings = settings,
                mode = MODE_UNATTENDED,
                status = "Unattended: EPEVER running its battery profile",
                commandCv = 0.0,
                holdVolts = settings.bmsVoltageHoldVolts,
                socTarget = if (isBalanceDay(settings)) 100 else settings.normalSocCeilingPercent,
                chargingOn = epever.chargingOn,
                epeverConnected = epeverState.connected,
                bmsConnected = bmsReady
            )
            return
        }

        if (!settings.controllerEnabled) {
            resetNoBmsWatch()
            holdEstSyncedThisVisit = false
            wasInHold = false
            clearHoldServo()
            idle(settings, epeverState.connected, bmsReady, "Controller off")
            return
        }
        if (!epeverState.connected) {
            resetNoBmsWatch()
            lastHoldWriteMs = 0L
            idle(settings, false, bmsReady, "Controller off")
            return
        }

        if (bmsReady) {
            if (lastMode == MODE_NO_BMS) {
                lastWriteMs = 0L
                lastWrittenCv = Double.NaN
            }
            bmsMissingSinceMs = 0L
        } else if (bmsMissingSinceMs == 0L) {
            bmsMissingSinceMs = now
        }

        val balanceDay = bmsReady && isBalanceDay(settings)
        if (balanceDay && settings.lastBalanceEpochDay != currentEpochDay()) {
            onBalanceDayStarted(currentEpochDay())
        }
        val socTarget = if (balanceDay) 100 else settings.normalSocCeilingPercent
        val holdVolts = if (socTarget >= 100) settings.fullHoldVolts else settings.bmsVoltageHoldVolts
        if (socTarget > lastSocTarget && lastSocTarget > 0) {
            wasInHold = false
            holdEstSyncedThisVisit = false
            clearHoldServo()
            sawSocBelowTarget = socPercent != null && socPercent < socTarget
        }
        lastSocTarget = socTarget

        if (bmsReady) {
            val alarms = bms.chargeBlockingAlarmNames
            if (alarms.isNotEmpty()) {
                if (!alarmLatched) {
                    alarmLatched = true
                    updateAlarmBackoff(now)
                }
                inhibitCharging(settings, socTarget, holdVolts, epeverState, bmsReady, "BMS alarm: ${alarms.joinToString()}")
                return
            }
            alarmLatched = false
            if (now - lastAlarmStartMs >= ALARM_RESET_AFTER_MS) {
                alarmBackoffMs = ALARM_BASE_HOLD_MS
            }
            if (now < alarmHoldUntilMs) {
                val seconds = ((alarmHoldUntilMs - now) / 1000L).coerceAtLeast(1L)
                inhibitCharging(settings, socTarget, holdVolts, epeverState, bmsReady, "BMS alarm cleared: retry in ${seconds}s")
                return
            }
        }

        val bulkCeiling = settings.maxBatteryVolts
        val lowVoltageReconnect = epeverState.deviceProfile?.lowVoltageReconnect ?: 12.60
        updateOffset(epever.batteryVolts, batteryVolts)

        val soc = socPercent
        val reachedSoc = soc != null && soc >= socTarget
        val resumeSoc = settings.normalSocResumePercent.coerceAtMost(socTarget - 1)
        val belowResume = soc != null && soc <= resumeSoc
        if (soc != null && soc < socTarget) sawSocBelowTarget = true

        val wantHold = if (wasInHold) {
            !belowResume
        } else {
            reachedSoc
        }

        if (wantHold && !bmsReady) {
            publish(
                settings = settings,
                mode = MODE_HOLD,
                status = "Hold: BMS telemetry lost, leaving last CV",
                commandCv = lastWrittenCv.takeUnless { it.isNaN() } ?: holdVolts,
                holdVolts = holdVolts,
                socTarget = socTarget,
                chargingOn = true,
                epeverConnected = true,
                bmsConnected = false
            )
            return
        }

        if (wantHold) {
            val overshoot = batteryVolts != null && batteryVolts >= holdVolts + OVERSHOOT_MARGIN_V
            val cv = servoHoldCv(
                holdVolts = holdVolts,
                batteryVolts = batteryVolts,
                mpptVolts = epever.batteryVolts,
                harvestAmps = max(epever.chargeAmps, (bms.packCurrent ?: 0.0).coerceAtLeast(0.0)),
                deviceCv = epeverState.deviceProfile?.regulationVolts,
                bulkCeiling = bulkCeiling,
                lowVoltageReconnect = lowVoltageReconnect,
                now = now
            )
            commandCharging(true)
            if (!holdEstSyncedThisVisit && reachedSoc) {
                holdEstSyncedThisVisit = true
                onEvent("Hold: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V, CV ${"%.2f".format(cv)}V")
                if (sawSocBelowTarget) {
                    onTopOffComplete(if (socTarget >= 100) "100% hold" else "SOC $socTarget% hold")
                    sawSocBelowTarget = false
                }
            }
            wasInHold = true
            val belowHold = batteryVolts != null && batteryVolts < holdVolts - 0.02
            publish(
                settings = settings,
                mode = if (belowHold && !overshoot) MODE_TOPUP else MODE_HOLD,
                status = when {
                    overshoot -> "Hold overshoot: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V, lowering CV"
                    belowHold -> "Top-up: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V to ${"%.2f".format(holdVolts)}V (CV ${"%.2f".format(cv)}V)"
                    else -> "Holding BMS ${"%.2f".format(holdVolts)}V (CV ${"%.2f".format(cv)}V)"
                },
                commandCv = cv,
                holdVolts = holdVolts,
                socTarget = socTarget,
                chargingOn = true,
                epeverConnected = true,
                bmsConnected = true
            )
            return
        }

        if (!bmsReady) {
            if (now - bmsMissingSinceMs < NO_BMS_GRACE_MS) {
                publish(
                    settings = settings,
                    mode = lastMode,
                    status = "Waiting for BMS...",
                    commandCv = lastWrittenCv.takeUnless { it.isNaN() } ?: 0.0,
                    holdVolts = holdVolts,
                    socTarget = socTarget,
                    chargingOn = epever.chargingOn,
                    epeverConnected = true,
                    bmsConnected = false
                )
                return
            }
            enterNoBms(settings, epever.chargingOn, holdVolts, socTarget)
            return
        }

        commandCharging(true)

        if (wasInHold) {
            onEvent("Hold exit: resume bulk at ${"%.2f".format(bulkCeiling)}V")
            lastWriteMs = 0L
        }
        wasInHold = false
        holdEstSyncedThisVisit = false
        clearHoldServo()
        maybeWriteProfile(
            EpeverVoltageProfile.bulk(bulkCeiling, lowVoltageReconnect),
            bulkCeiling,
            now
        )
        publish(
            settings = settings,
            mode = MODE_BULK,
            status = "Bulk charge to SOC $socTarget%",
            commandCv = bulkCeiling,
            holdVolts = holdVolts,
            socTarget = socTarget,
            chargingOn = true,
            epeverConnected = true,
            bmsConnected = true
        )
    }

    /** Display tile ΔV only. Do not feed this into hold/bulk CV. */
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

    private fun clearHoldServo() {
        holdCv = Double.NaN
        lastHoldWriteMs = 0L
    }

    /** Never command a Hold CV below the last BMS pack volts. */
    private fun holdFloorV(batteryVolts: Double?): Double {
        return if (batteryVolts != null && batteryVolts > 0.0) max(12.40, batteryVolts) else 12.40
    }

    private fun clampHoldCv(cv: Double, batteryVolts: Double?, hi: Double): Double {
        return cv.coerceIn(holdFloorV(batteryVolts), hi)
    }

    /** Step toward [holdVolts] from the device readback, not from an offset-adjusted guess. */
    private fun servoHoldCv(
        holdVolts: Double,
        batteryVolts: Double?,
        mpptVolts: Double,
        harvestAmps: Double,
        deviceCv: Double?,
        bulkCeiling: Double,
        lowVoltageReconnect: Double,
        now: Long
    ): Double {
        val hi = bulkCeiling.coerceIn(12.40, 15.0)
        val target = clampHoldCv(holdVolts, batteryVolts, hi)
        val first = holdCv.isNaN()
        val from = when {
            deviceCv != null && deviceCv > 0.0 -> deviceCv
            !lastWrittenCv.isNaN() -> lastWrittenCv
            !holdCv.isNaN() -> holdCv
            else -> target
        }
        val gap = from - target
        val next = when {
            abs(gap) < HOLD_WRITE_EPS_V -> target
            gap > 0.15 -> target
            gap > 0.0 -> {
                val step = when {
                    harvestAmps >= 8.0 -> HOLD_SUN_STEP_V
                    harvestAmps >= 3.0 -> 0.06
                    else -> 0.03
                }
                max(target, from - step)
            }
            else -> minOf(target, from + 0.05)
        }
        holdCv = next
        if (!first && abs(from - target) < HOLD_WRITE_EPS_V) return target
        if (!first && now - lastHoldWriteMs < HOLD_SERVO_PERIOD_MS) return holdCv
        writeHoldCv(holdCv, batteryVolts, hi, lowVoltageReconnect, now)
        onEvent(
            "Hold CV ${"%.2f".format(holdCv)}V from ${"%.2f".format(from)}V " +
                "(BMS ${"%.2f".format(batteryVolts ?: 0.0)}V MPPT ${"%.2f".format(mpptVolts)}V " +
                "${"%.1f".format(harvestAmps)}A)"
        )
        return holdCv
    }

    private fun writeHoldCv(
        cv: Double,
        batteryVolts: Double?,
        bulkCeiling: Double,
        lowVoltageReconnect: Double,
        now: Long
    ) {
        val clamped = clampHoldCv(cv, batteryVolts, bulkCeiling)
        holdCv = clamped
        lastHoldWriteMs = now
        lastWriteMs = now
        applyVoltageProfile(EpeverVoltageProfile.hold(clamped, bulkCeiling, lowVoltageReconnect)) { ok ->
            if (ok) {
                lastWrittenCv = clamped
            } else {
                lastHoldWriteMs = now - HOLD_SERVO_PERIOD_MS + HOLD_RETRY_MS
                lastWrittenCv = Double.NaN
                onEvent("EPEVER hold CV ${"%.2f".format(clamped)}V write failed")
            }
            if (lastCharging == false) setCharging(false)
        }
    }

    private fun maybeWriteProfile(profile: EpeverVoltageProfile, cv: Double, now: Long) {
        val changedEnough = lastWrittenCv.isNaN() || abs(cv - lastWrittenCv) >= CV_WRITE_EPS_V
        val cooledDown = now - lastWriteMs >= CV_WRITE_COOLDOWN_MS
        if (!changedEnough || !cooledDown) return
        lastWriteMs = now
        applyVoltageProfile(profile) { ok ->
            if (ok) {
                lastWrittenCv = cv
                onEvent("EPEVER CV ${"%.2f".format(cv)}V")
            } else {
                lastWriteMs = 0L
                onEvent("EPEVER CV ${"%.2f".format(cv)}V write failed")
            }
        }
    }

    private fun commandCharging(on: Boolean, force: Boolean = false) {
        if (!force && lastCharging == on) return
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
        commandCharging(false, force = true)
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

    private fun enterNoBms(
        settings: AppSettings,
        chargingOn: Boolean,
        holdVolts: Double,
        socTarget: Int
    ) {
        if (lastMode != MODE_NO_BMS) {
            holdEstSyncedThisVisit = false
            wasInHold = false
            clearHoldServo()
            lastCharging = null
            lastWriteMs = 0L
            lastWrittenCv = Double.NaN
            onNoBms()
        }
        publish(
            settings = settings,
            mode = MODE_NO_BMS,
            status = "No BMS: EPEVER running its battery profile",
            commandCv = 0.0,
            holdVolts = holdVolts,
            socTarget = socTarget,
            chargingOn = chargingOn,
            epeverConnected = true,
            bmsConnected = false
        )
    }

    private fun resetNoBmsWatch() {
        bmsMissingSinceMs = 0L
    }

    private fun idle(settings: AppSettings, epeverConnected: Boolean, bmsReady: Boolean, status: String) {
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

    private fun currentEpochDay(): Long = localEpochDay()

    companion object {
        const val MODE_IDLE = "Idle"
        const val MODE_BULK = "Bulk"
        const val MODE_HOLD = "Hold"
        const val MODE_TOPUP = "Top-up"
        const val MODE_ALARM = "Alarm"
        const val MODE_NO_BMS = "No BMS"
        const val MODE_UNATTENDED = "Unattended"
        const val MODE_OUTPUT_OFF = "Off"

        private const val NO_BMS_GRACE_MS = 10_000L
        private const val OFFSET_ALPHA = 0.15
        private const val CV_WRITE_EPS_V = 0.05
        /** EEPROM: do not rewrite the 15-register block every tick. */
        private const val CV_WRITE_COOLDOWN_MS = 12_000L
        private const val OVERSHOOT_MARGIN_V = 0.15
        private const val HOLD_SERVO_PERIOD_MS = 5_000L
        private const val HOLD_RETRY_MS = 2_000L
        private const val HOLD_WRITE_EPS_V = 0.02
        private const val HOLD_SUN_STEP_V = 0.10
        private const val ALARM_BASE_HOLD_MS = 60_000L
        private const val ALARM_MAX_HOLD_MS = 1_800_000L
        private const val ALARM_REPEAT_WINDOW_MS = 600_000L
        private const val ALARM_RESET_AFTER_MS = 1_800_000L
    }
}
