package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.epever.EpeverBatteryProfile
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
 * add it to commanded CV (that held CV high vs BMS). EPEVER voltage cal is not
 * applied to Live writes — only Unattended profile writes.
 *
 * Enter Hold when SOC ≥ ceiling and BMS ≥ hold. Stay until SOC ≤ resume or
 * BMS stays under hold − sag for [AppSettings.holdSagDwellS], then full charge.
 * Any reading back at the sag floor resets that timer. High side is the Tracer.
 * Live walks from device readback toward the policy CV, 5 s / 0.10 V in sun. There is no
 * 12.40 / BMS floor — current limiting must be able to sit at or below pack
 * voltage. If BMS charge current exceeds [AppSettings.maxChargeAmps], the
 * target becomes BMS volts (then steps below if still over). 0 A disables the
 * limiter. Manual CV is not current-limited. BMS lost while holding: leave the
 * last CV. BMS gone 10 s and not in Hold: No BMS (Tools profile once, then
 * monitor). Manual CV replaces the live Bulk/Hold number at write time only —
 * not Unattended, No BMS, or Tools park.
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
    private var holdSagBelowMs = 0L
    private var holdSagLastBelowMs = 0L
    private var holdCv = Double.NaN
    private var lastHoldWriteMs = 0L
    private var alarmLatched = false
    private var bmsMissingSinceMs = 0L
    private var lastManualEnabled = false
    private var lastManualVolts = Double.NaN
    private var forceTick = false

    fun invalidateVoltageWrite() {
        lastWrittenCv = Double.NaN
        lastWriteMs = 0L
        lastHoldWriteMs = 0L
        holdCv = Double.NaN
        forceTick = true
    }

    fun tick(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        epeverState: EpeverUsbState,
        outputHeldOff: Boolean = false,
        toolsProfile: EpeverBatteryProfile? = null
    ) {
        val now = System.currentTimeMillis()
        val due = now - lastTickMs >= settings.controllerLoopMs.toLong()
        val manualPoke = settings.manualCvEnabled != lastManualEnabled ||
            (settings.manualCvEnabled &&
                (lastManualVolts.isNaN() || abs(settings.manualCvVolts - lastManualVolts) >= 0.01))
        if (!due && !manualPoke && !forceTick) return
        lastTickMs = now
        forceTick = false
        if (manualPoke) {
            lastWriteMs = 0L
            lastHoldWriteMs = 0L
            lastWrittenCv = Double.NaN
            holdCv = Double.NaN
        }
        lastManualEnabled = settings.manualCvEnabled
        lastManualVolts = settings.manualCvVolts

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
            clearHoldLatch()
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
            clearHoldLatch()
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
            clearHoldLatch()
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
        val protection = toolsProfile ?: epeverState.deviceProfile
        updateOffset(epever.batteryVolts, batteryVolts)

        val soc = socPercent
        val reachedSoc = soc != null && soc >= socTarget
        val holdSag = settings.holdSagVolts.coerceIn(0.02, 0.50)
        val resumeSoc = settings.normalSocResumePercent.coerceIn(50, (socTarget - 1).coerceAtLeast(50))
        val atHoldVolts = batteryVolts != null && batteryVolts >= holdVolts
        val aboveHoldFloor = batteryVolts != null && batteryVolts >= holdVolts - holdSag
        val belowResume = soc != null && soc <= resumeSoc
        if (soc != null && soc < socTarget) sawSocBelowTarget = true

        val sagDwellMs = settings.holdSagDwellS.coerceIn(5, 600) * 1000L
        val sagTimedOut = if (!wasInHold) {
            false
        } else if (aboveHoldFloor) {
            holdSagBelowMs = 0L
            holdSagLastBelowMs = 0L
            false
        } else if (!bmsReady || batteryVolts == null) {
            holdSagLastBelowMs = 0L
            false
        } else {
            if (holdSagLastBelowMs == 0L) {
                holdSagLastBelowMs = now
            } else {
                holdSagBelowMs += now - holdSagLastBelowMs
                holdSagLastBelowMs = now
            }
            holdSagBelowMs >= sagDwellMs
        }

        val wantHold = if (wasInHold) {
            !belowResume && !sagTimedOut
        } else {
            reachedSoc && atHoldVolts
        }

        if (wasInHold && !bmsReady) {
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
            commandCharging(true)
            val overshoot = batteryVolts != null && batteryVolts >= holdVolts + OVERSHOOT_MARGIN_V
            val chargeAmps = (bms.packCurrent ?: 0.0).coerceAtLeast(0.0)
            val cv = if (settings.manualCvEnabled) {
                val manual = manualCv(settings)
                maybeWriteProfile(
                    EpeverVoltageProfile.regulation(manual, 15.0, protection),
                    manual,
                    now
                )
                manual
            } else {
                servoRegulationCv(
                    policyCv = holdVolts,
                    batteryVolts = batteryVolts,
                    chargeAmps = chargeAmps,
                    iMax = settings.maxChargeAmps,
                    harvestAmps = max(epever.chargeAmps, chargeAmps),
                    deviceCv = epeverState.deviceProfile?.regulationVolts,
                    bulkCeiling = bulkCeiling,
                    protection = protection,
                    periodMs = settings.voltageWritePeriodS,
                    now = now
                )
            }
            if (!holdEstSyncedThisVisit) {
                holdEstSyncedThisVisit = true
                onEvent("Hold: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V, CV ${"%.2f".format(cv)}V")
                if (sawSocBelowTarget) {
                    onTopOffComplete(if (socTarget >= 100) "100% hold" else "SOC $socTarget% hold")
                    sawSocBelowTarget = false
                }
            }
            wasInHold = true
            publish(
                settings = settings,
                mode = MODE_HOLD,
                status = liveStatus(
                    modeLabel = "Hold",
                    manual = settings.manualCvEnabled,
                    cv = cv,
                    policyCv = holdVolts,
                    chargeAmps = chargeAmps,
                    iMax = settings.maxChargeAmps,
                    batteryVolts = batteryVolts,
                    overshoot = overshoot,
                    belowHold = false
                ),
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

        val chargeAmps = (bms.packCurrent ?: 0.0).coerceAtLeast(0.0)
        val sendCv = if (settings.manualCvEnabled) {
            manualCv(settings)
        } else {
            servoRegulationCv(
                policyCv = bulkCeiling,
                batteryVolts = batteryVolts,
                chargeAmps = chargeAmps,
                iMax = settings.maxChargeAmps,
                harvestAmps = max(epever.chargeAmps, chargeAmps),
                deviceCv = epeverState.deviceProfile?.regulationVolts,
                bulkCeiling = bulkCeiling,
                protection = protection,
                periodMs = settings.voltageWritePeriodS,
                now = now
            )
        }
        if (wasInHold) {
            onEvent("Hold exit: full charge toward ${"%.2f".format(bulkCeiling)}V")
            lastWriteMs = 0L
        }
        clearHoldLatch()
        holdEstSyncedThisVisit = false
        if (settings.manualCvEnabled) {
            maybeWriteProfile(
                EpeverVoltageProfile.regulation(sendCv, 15.0, protection),
                sendCv,
                now
            )
        }
        val voltageOnly = reachedSoc && !aboveHoldFloor
        publish(
            settings = settings,
            mode = if (voltageOnly) MODE_TOPUP else MODE_BULK,
            status = liveStatus(
                modeLabel = if (voltageOnly) "Top-up" else "Bulk",
                manual = settings.manualCvEnabled,
                cv = sendCv,
                policyCv = bulkCeiling,
                chargeAmps = chargeAmps,
                iMax = settings.maxChargeAmps,
                batteryVolts = batteryVolts,
                overshoot = false,
                belowHold = voltageOnly,
                socTarget = socTarget
            ),
            commandCv = sendCv,
            holdVolts = holdVolts,
            socTarget = socTarget,
            chargingOn = true,
            epeverConnected = true,
            bmsConnected = true
        )
    }

    private fun manualCv(settings: AppSettings): Double = settings.manualCvVolts

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

    private fun clearHoldLatch() {
        wasInHold = false
        holdSagBelowMs = 0L
        holdSagLastBelowMs = 0L
    }

    private fun sunStep(harvestAmps: Double): Double = when {
        harvestAmps >= 8.0 -> HOLD_SUN_STEP_V
        harvestAmps >= 3.0 -> 0.06
        else -> 0.03
    }

    /**
     * Walk toward policy CV, or toward/below BMS volts when charge current is over
     * [iMax]. No BMS / 12.40 floor — the limiter may sit under pack voltage.
     */
    private fun servoRegulationCv(
        policyCv: Double,
        batteryVolts: Double?,
        chargeAmps: Double,
        iMax: Double,
        harvestAmps: Double,
        deviceCv: Double?,
        bulkCeiling: Double,
        protection: EpeverBatteryProfile?,
        periodMs: Int,
        now: Long
    ): Double {
        val hi = bulkCeiling.coerceIn(EpeverVoltageProfile.MIN_LIVE_CV, 15.0)
        val policy = policyCv.coerceIn(EpeverVoltageProfile.MIN_LIVE_CV, hi)
        val first = holdCv.isNaN()
        val from = when {
            deviceCv != null && deviceCv > 0.0 -> deviceCv
            !lastWrittenCv.isNaN() -> lastWrittenCv
            !holdCv.isNaN() -> holdCv
            else -> policy
        }
        val limiterOn = iMax > 0.0
        val over = limiterOn && chargeAmps >= iMax
        val under = !limiterOn || chargeAmps <= iMax - CURRENT_LIMIT_HYST_A
        val step = sunStep(harvestAmps)
        val target = when {
            over && batteryVolts != null && batteryVolts > 0.0 && from <= batteryVolts + 0.03 ->
                (from - step).coerceAtLeast(EpeverVoltageProfile.MIN_LIVE_CV)
            over && batteryVolts != null && batteryVolts > 0.0 ->
                minOf(policy, batteryVolts)
            over ->
                (from - step).coerceAtLeast(EpeverVoltageProfile.MIN_LIVE_CV)
            under ->
                policy
            else ->
                holdCv.takeUnless { it.isNaN() } ?: from
        }.coerceIn(EpeverVoltageProfile.MIN_LIVE_CV, hi)

        val gap = from - target
        val bulkPolicy = abs(policy - hi) < 0.02
        val next = when {
            abs(gap) < HOLD_WRITE_EPS_V -> target
            gap > 0.15 -> target
            gap > 0.0 -> max(target, from - step)
            !limiterOn && bulkPolicy -> target
            else -> minOf(target, from + step)
        }
        holdCv = next
        val servoPeriodMs = periodMs.coerceIn(1, 30) * 1000L
        if (!first && abs(from - target) < HOLD_WRITE_EPS_V) return target
        if (!first && now - lastHoldWriteMs < servoPeriodMs) return holdCv
        writeRegulationCv(holdCv, hi, protection, now)
        onEvent(
            if (over) {
                "Current limit CV ${"%.2f".format(holdCv)}V from ${"%.2f".format(from)}V " +
                    "(BMS ${"%.2f".format(batteryVolts ?: 0.0)}V ${"%.1f".format(chargeAmps)}A > ${"%.0f".format(iMax)}A)"
            } else {
                "Live CV ${"%.2f".format(holdCv)}V from ${"%.2f".format(from)}V " +
                    "(BMS ${"%.2f".format(batteryVolts ?: 0.0)}V ${"%.1f".format(chargeAmps)}A)"
            }
        )
        return holdCv
    }

    private fun writeRegulationCv(
        cv: Double,
        bulkCeiling: Double,
        protection: EpeverBatteryProfile?,
        now: Long
    ) {
        val clamped = cv.coerceIn(EpeverVoltageProfile.MIN_LIVE_CV, bulkCeiling.coerceIn(EpeverVoltageProfile.MIN_LIVE_CV, 15.0))
        holdCv = clamped
        lastHoldWriteMs = now
        lastWriteMs = now
        applyVoltageProfile(EpeverVoltageProfile.regulation(clamped, bulkCeiling, protection)) { ok ->
            if (ok) {
                lastWrittenCv = clamped
            } else {
                lastHoldWriteMs = now - 3_000L
                lastWrittenCv = Double.NaN
                onEvent("EPEVER CV ${"%.2f".format(clamped)}V write failed")
            }
            if (lastCharging == false) setCharging(false)
        }
    }

    private fun liveStatus(
        modeLabel: String,
        manual: Boolean,
        cv: Double,
        policyCv: Double,
        chargeAmps: Double,
        iMax: Double,
        batteryVolts: Double?,
        overshoot: Boolean,
        belowHold: Boolean,
        socTarget: Int? = null
    ): String {
        if (manual) return "Manual CV ${"%.2f".format(cv)}V"
        val over = iMax > 0.0 && chargeAmps >= iMax
        val limiting = iMax > 0.0 && cv < policyCv - 0.05
        return when {
            over ->
                "$modeLabel current limit: ${"%.1f".format(chargeAmps)} A > ${"%.0f".format(iMax)} A, CV ${"%.2f".format(cv)}V"
            limiting ->
                "$modeLabel recovering from current limit, CV ${"%.2f".format(cv)}V"
            overshoot ->
                "Hold overshoot: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V, lowering CV"
            belowHold ->
                "Top-up: BMS ${"%.2f".format(batteryVolts ?: 0.0)}V, full charge until hold (CV ${"%.2f".format(cv)}V)"
            modeLabel == "Hold" ->
                "Holding BMS ${"%.2f".format(policyCv)}V (CV ${"%.2f".format(cv)}V)"
            socTarget != null ->
                "Bulk charge to SOC $socTarget%"
            else ->
                "$modeLabel CV ${"%.2f".format(cv)}V"
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
            clearHoldLatch()
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
        private const val HOLD_WRITE_EPS_V = 0.009
        private const val HOLD_SUN_STEP_V = 0.10
        private const val CURRENT_LIMIT_HYST_A = 2.0
        private const val ALARM_BASE_HOLD_MS = 60_000L
        private const val ALARM_MAX_HOLD_MS = 1_800_000L
        private const val ALARM_REPEAT_WINDOW_MS = 600_000L
        private const val ALARM_RESET_AFTER_MS = 1_800_000L
    }
}
