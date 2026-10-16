package com.example.ridenbmscontroller.epever

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.ridenbmscontroller.modbus.ModbusIo
import com.example.ridenbmscontroller.modbus.ModbusRtu
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Tracer-AN Modbus RTU (115200 8N1, slave 1) on the official XR21V1410 USB cable.
 *
 * Charge voltages live in EEPROM. Firmware accepts a custom curve only when battery
 * type is User (0) **and** 0x9000–0x900E are written together as one 15-register FC10.
 * Partial writes (FC06, or 3/7/10-register FC10) are rejected: Boost/Float stay at
 * the last good values (we saw Tools stuck at 13.60 V).
 *
 * Live / park / unattended writes: read the current 15-register block, overlay the
 * voltages, write the whole block. Do not write User type as its own transaction —
 * type 0 rides along in that block. Do not poke 0x9000 on a timer.
 *
 * Coil 0x0000 (charging) is a no-op on AN (AN coils 2/3/5/6 are load). It does not
 * stop harvest. Manual Off parks the same 15-register block at 13.00 V — Tools only,
 * never from the live loop (EEPROM wear, and it is a blunt inhibit). Clear generated
 * energy is coil 0x0014, not 0x0006.
 *
 * After a voltage write, wait ~400 ms and read back. If limit/Boost/Float/equalize
 * do not match, rewrite from the **readback**, not a stale local copy. Large downward
 * steps should walk through a midpoint so the firmware does not reject the jump.
 *
 * boostReconnect must be > LVR + 0.02 V or the block is rejected.
 * Input 0x3104 is measured battery voltage (dashboard MPPT V), not commanded CV.
 * Energy words are 0.01 kWh.
 *
 * Non-load extras (temps, rated-V code, EQ/Boost minutes, charge/discharge %,
 * voltage-vs-SOC management) live outside 0x9000–0x900E and are written as their
 * own FC10 groups. Tracer-AN 80A has no load terminals — skip 0x0002–0x0006 coils,
 * 0x901E–0x9021 / 0x903D–0x906A load timers, and consumed-energy-by-load.
 */
class EpeverUsbMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (EpeverUsbState) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var pollJob: Job? = null
    private var receiverRegistered = false
    private var state = EpeverUsbState()
    private var permissionRequestInFlight = false
    private var currentIo: ModbusIo? = null
    private val ioMutex = Mutex()
    private var parkedProfile: EpeverBatteryProfile? = null
    private var lastTelemetry = EpeverTelemetry()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (granted && device != null) {
                permissionRequestInFlight = false
                publish(state.copy(permissionNeeded = false, status = "USB permission granted"))
                start()
            } else {
                permissionRequestInFlight = false
                publish(state.copy(permissionNeeded = true, status = "USB permission denied"))
            }
        }
    }

    fun start() {
        ensureReceiver()
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            var preferredVid: Int? = null
            var preferredPid: Int? = null
            while (isActive) {
                val driver = findDriver(preferredVid, preferredPid)
                if (driver == null) {
                    preferredVid = null
                    preferredPid = null
                    publishOnMain(EpeverUsbState(status = "No USB serial device found"))
                    delay(1_500)
                    continue
                }
                val device = driver.device
                preferredVid = device.vendorId
                preferredPid = device.productId
                if (!usbManager.hasPermission(device)) {
                    publishOnMain(
                        EpeverUsbState(
                            permissionNeeded = true,
                            status = "USB permission needed",
                            deviceName = device.productName ?: "USB serial",
                            vendorId = device.vendorId,
                            productId = device.productId
                        )
                    )
                    if (!permissionRequestInFlight) requestPermission(device)
                    delay(1_500)
                    continue
                }
                permissionRequestInFlight = false
                var connection: UsbDeviceConnection? = null
                var port: UsbSerialPort? = null
                try {
                    connection = usbManager.openDevice(device)
                    if (connection == null) {
                        publishOnMain(state.copy(connected = false, status = "Unable to open USB device"))
                        delay(1_500)
                        continue
                    }
                    runCatching { ExarRs485.enable(connection, device) }
                    port = driver.ports.firstOrNull()
                    if (port == null) {
                        publishOnMain(state.copy(connected = false, status = "USB serial port not found"))
                        delay(1_500)
                        continue
                    }
                    port.open(connection)
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    runCatching { port.dtr = true }
                    runCatching { port.rts = true }
                    delay(200)
                    val io = ModbusRtu(port)
                    currentIo = io
                    publishOnMain(
                        EpeverUsbState(
                            connected = true,
                            status = "EPEVER connected",
                            deviceName = device.productName ?: "USB serial",
                            vendorId = device.vendorId,
                            productId = device.productId
                        )
                    )
                    var sampleSeq = 0L
                    while (isActive) {
                        val snapshot = ioMutex.withLock {
                            PollSnapshot(
                                telemetry = readTelemetry(io, includeRated = sampleSeq % 5L == 2L),
                                clock = if (sampleSeq % 5L == 0L) {
                                    runCatching { readClock(io) }.getOrNull()
                                } else {
                                    null
                                },
                                profile = if (sampleSeq % 5L == 2L) {
                                    runCatching { readBatteryProfile(io) }.getOrNull()
                                } else {
                                    null
                                },
                                extended = if (sampleSeq % 5L == 2L) {
                                    runCatching { readExtendedSettings(io) }.getOrNull()
                                } else {
                                    null
                                }
                            )
                        }
                        sampleSeq += 1L
                        publishOnMain(
                            state.copy(
                                connected = true,
                                permissionNeeded = false,
                                status = "EPEVER live",
                                telemetry = snapshot.telemetry,
                                telemetrySampleSeq = sampleSeq,
                                clock = snapshot.clock ?: state.clock,
                                deviceProfile = snapshot.profile ?: state.deviceProfile,
                                extendedSettings = snapshot.extended ?: state.extendedSettings,
                                lastWriteNote = state.lastWriteNote
                            )
                        )
                        delay(POLL_MS)
                    }
                } catch (e: Exception) {
                    publishOnMain(
                        state.copy(
                            connected = false,
                            status = "EPEVER USB error: ${e.message ?: e.javaClass.simpleName}"
                        )
                    )
                    delay(1_500)
                } finally {
                    currentIo = null
                    closeQuietly(port, connection)
                }
            }
        }
    }

    /** Tracer-AN ignores this coil. Kept for protocol completeness; do not use as inhibit. */
    fun setCharging(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                publishOnMain(state.copy(status = "Charge coil skipped: USB not ready"))
                return@launch
            }
            runCatching {
                ioMutex.withLock { io.writeSingleCoil(SLAVE_ID, COIL_CHARGING, enabled) }
            }.onFailure {
                publishOnMain(state.copy(status = "Charge coil failed: ${it.message ?: it.javaClass.simpleName}"))
            }
        }
    }

    /** Tools-only inhibit: full 15-register park at [PARK_CHARGE_VOLTS]. Do not call from Live. */
    fun parkChargeOutput(onDone: (Result<EpeverBatteryProfile>) -> Unit = {}) {
        applyParkedVoltages(PARK_CHARGE_VOLTS, saveCurrent = true, onDone)
    }

    fun restoreParkedChargeOutput(onDone: (Result<EpeverBatteryProfile>) -> Unit = {}) {
        val stored = parkedProfile
        if (stored == null) {
            onDone(Result.failure(IllegalStateException("No parked profile to restore")))
            return
        }
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onDone(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock {
                    writeBatteryProfile(io, stored)
                    delay(400)
                    readBatteryProfile(io)
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { publish(state.copy(deviceProfile = it)) }
                onDone(result)
            }
        }
    }

    /** Walk a large drop through a midpoint, then full-block write + readback. */
    private fun applyParkedVoltages(
        parkVolts: Double,
        saveCurrent: Boolean,
        onDone: (Result<EpeverBatteryProfile>) -> Unit
    ) {
        val profile = EpeverVoltageProfile.hold(parkVolts, parkVolts)
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onDone(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock {
                    val current = readBatteryProfile(io)
                    if (saveCurrent) parkedProfile = current
                    var working = current
                    val from = current.regulationVolts
                    if (from > parkVolts + 0.15) {
                        val mid = (from + parkVolts) / 2.0
                        writeBatteryProfile(io, working.withLiveVoltages(EpeverVoltageProfile.hold(mid, mid)))
                        delay(400)
                        working = readBatteryProfile(io)
                    }
                    writeBatteryProfile(io, working.withLiveVoltages(profile))
                    delay(400)
                    var readback = readBatteryProfile(io)
                    if (!readback.matchesLive(profile)) {
                        writeBatteryProfile(io, readback.withLiveVoltages(profile))
                        delay(400)
                        readback = readBatteryProfile(io)
                    }
                    readback
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { publish(state.copy(deviceProfile = it)) }
                onDone(result)
            }
        }
    }

    fun readBatteryProfile(onResult: (Result<EpeverBatteryProfile>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock {
                    val profile = readBatteryProfile(io)
                    val extended = runCatching { readExtendedSettings(io) }.getOrNull()
                    profile to extended
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (profile, extended) ->
                    publish(
                        state.copy(
                            deviceProfile = profile,
                            extendedSettings = extended ?: state.extendedSettings
                        )
                    )
                }
                onResult(result.map { it.first })
            }
        }
    }

    /** Full 15-register Tools / unattended / No-BMS profile. Same atomic rule as live writes. */
    fun writeBatteryProfile(profile: EpeverBatteryProfile, onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock { writeBatteryProfile(io, profile) }
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun writeExtendedSettings(settings: EpeverExtendedSettings, onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock { writeExtendedSettings(io, settings) }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { publish(state.copy(extendedSettings = settings)) }
                onResult(result)
            }
        }
    }

    /** Type / capacity / LVR only — still written as the full 15-register block. */
    fun writeProtectionParams(profile: EpeverBatteryProfile, onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock { writeProtectionParams(io, profile) }
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** Coil 0x0014. 0x0006 is a different (wrong) coil. */
    fun clearGeneratedEnergy(onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock {
                    io.writeSingleCoil(SLAVE_ID, COIL_CLEAR_GENERATED_ENERGY, true)
                    delay(250)
                    readTelemetry(io)
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { telemetry ->
                    publish(
                        state.copy(
                            telemetry = telemetry.copy(
                                generatedWhToday = 0.0,
                                generatedWhMonth = 0.0,
                                generatedWhYear = 0.0,
                                generatedWhTotal = 0.0
                            )
                        )
                    )
                }
                onResult(result.map { })
            }
        }
    }

    fun writeClockFromPhone(onResult: (Result<EpeverClock>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(IllegalStateException("EPEVER not connected")))
                }
                return@launch
            }
            val clock = EpeverClock.fromPhoneNow()
            val result = runCatching {
                ioMutex.withLock { io.writeHoldingRegs(SLAVE_ID, REG_RTC, clock.toRegisters()) }
                clock
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { written ->
                    publish(state.copy(clock = written, status = "EPEVER clock set"))
                }
                onResult(result)
            }
        }
    }

    /**
     * Live CV change: read-modify-write the full 0x9000–0x900E block, then 400 ms readback.
     * User type is forced only inside that block ([EpeverBatteryProfile.withLiveVoltages]).
     */
    fun applyVoltageProfile(profile: EpeverVoltageProfile, onDone: (Boolean) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo
            if (io == null) {
                withContext(Dispatchers.Main) { onDone(false) }
                return@launch
            }
            val result = runCatching {
                ioMutex.withLock {
                    val current = runCatching { readBatteryProfile(io) }.getOrNull()
                        ?: EpeverBatteryProfile()
                    writeBatteryProfile(io, current.withLiveVoltages(profile))
                    delay(400)
                    var readback = readBatteryProfile(io)
                    if (!readback.matchesLive(profile)) {
                        writeBatteryProfile(io, readback.withLiveVoltages(profile))
                        delay(400)
                        readback = readBatteryProfile(io)
                    }
                    readback
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { device ->
                    val ok = device.matchesLive(profile)
                    val note = if (ok) {
                        "Live CV %.2f V written".format(profile.boostVolts)
                    } else {
                        "Live CV %.2f V rejected (Boost %.2f Float %.2f)".format(
                            profile.boostVolts,
                            device.boostVolts,
                            device.floatVolts
                        )
                    }
                    publish(
                        state.copy(
                            deviceProfile = device,
                            status = note,
                            lastWriteNote = note
                        )
                    )
                    onDone(ok)
                }.onFailure {
                    publish(state.copy(status = "Voltage profile failed: ${it.message ?: it.javaClass.simpleName}"))
                    onDone(false)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
    }

    private suspend fun readBatteryProfile(io: ModbusIo): EpeverBatteryProfile {
        val regs = io.readHoldingRegs(SLAVE_ID, REG_BATTERY_TYPE, 15)
        return EpeverBatteryProfile(
            batteryType = regs[0] and 0xFFFF,
            capacityAh = regs[1] and 0xFFFF,
            tempCompensationMvv = fromRegTempComp(regs[2]),
            highVoltageDisconnect = fromRegV(regs[3]),
            chargingLimitVolts = fromRegV(regs[4]),
            overVoltageReconnect = fromRegV(regs[5]),
            equalizeVolts = fromRegV(regs[6]),
            boostVolts = fromRegV(regs[7]),
            floatVolts = fromRegV(regs[8]),
            boostReconnectVolts = fromRegV(regs[9]),
            lowVoltageReconnect = fromRegV(regs[10]),
            underVoltageRecover = fromRegV(regs[11]),
            underVoltageWarning = fromRegV(regs[12]),
            lowVoltageDisconnect = fromRegV(regs[13]),
            dischargingLimitVolts = fromRegV(regs[14])
        )
    }

    /** Always the full 15-register FC10. Never FC06 or a short slice of 0x9000–0x900E. */
    private suspend fun writeBatteryProfile(io: ModbusIo, profile: EpeverBatteryProfile) {
        io.writeHoldingRegs(SLAVE_ID, REG_BATTERY_TYPE, profile.toRegisterBlock())
    }

    /**
     * Type / capacity / LVR changes still need the full voltage block. A 3-register
     * write of User type + capacity is rejected or leaves Boost/Float unchanged.
     * Overlay onto the device block so Live CVs are not clobbered.
     */
    private suspend fun writeProtectionParams(io: ModbusIo, profile: EpeverBatteryProfile) {
        val current = readBatteryProfile(io)
        writeBatteryProfile(
            io,
            current.copy(
                batteryType = profile.batteryType,
                capacityAh = profile.capacityAh,
                tempCompensationMvv = profile.tempCompensationMvv,
                lowVoltageReconnect = profile.lowVoltageReconnect,
                underVoltageRecover = profile.underVoltageRecover,
                underVoltageWarning = profile.underVoltageWarning,
                lowVoltageDisconnect = profile.lowVoltageDisconnect,
                dischargingLimitVolts = profile.dischargingLimitVolts
            )
        )
    }

    private suspend fun readClock(io: ModbusIo): EpeverClock {
        return EpeverClock.fromRegisters(io.readHoldingRegs(SLAVE_ID, REG_RTC, 3))
    }

    private suspend fun readExtendedSettings(io: ModbusIo): EpeverExtendedSettings {
        val previous = state.extendedSettings ?: EpeverExtendedSettings()
        val eqCycle = runCatching { io.readHoldingRegs(SLAVE_ID, REG_EQUALIZE_CYCLE, 1) }.getOrNull()
        val temps = runCatching { io.readHoldingRegs(SLAVE_ID, REG_TEMP_LIMITS, 7) }.getOrNull()
        val ratedCode = runCatching { io.readHoldingRegs(SLAVE_ID, REG_BATTERY_RATED_V, 1) }.getOrNull()
        val durations = runCatching { io.readHoldingRegs(SLAVE_ID, REG_CHARGE_DURATIONS, 4) }.getOrNull()
        val management = runCatching { io.readHoldingRegs(SLAVE_ID, REG_CHARGE_MANAGEMENT, 1) }.getOrNull()
        if (eqCycle == null && temps == null && ratedCode == null && durations == null && management == null) {
            error("No extended settings registers responded")
        }
        return EpeverExtendedSettings(
            equalizeCycleDays = eqCycle?.get(0)?.and(0xFFFF) ?: previous.equalizeCycleDays,
            batteryTempWarnHighC = temps?.get(0)?.let { fromRegTempC(it) } ?: previous.batteryTempWarnHighC,
            batteryTempWarnLowC = temps?.get(1)?.let { fromRegTempC(it) } ?: previous.batteryTempWarnLowC,
            innerTempLimitC = temps?.get(2)?.let { fromRegTempC(it) } ?: previous.innerTempLimitC,
            innerTempRecoverC = temps?.get(3)?.let { fromRegTempC(it) } ?: previous.innerTempRecoverC,
            powerTempLimitC = temps?.get(4)?.let { fromRegTempC(it) } ?: previous.powerTempLimitC,
            powerTempRecoverC = temps?.get(5)?.let { fromRegTempC(it) } ?: previous.powerTempRecoverC,
            lineImpedanceMOhm = temps?.get(6)?.let { fromRegMilliOhm(it) } ?: previous.lineImpedanceMOhm,
            batteryRatedVoltageCode = ratedCode?.get(0)?.and(0xFFFF) ?: previous.batteryRatedVoltageCode,
            equalizeDurationMin = durations?.get(0)?.and(0xFFFF) ?: previous.equalizeDurationMin,
            boostDurationMin = durations?.get(1)?.and(0xFFFF) ?: previous.boostDurationMin,
            dischargePercent = durations?.get(2)?.and(0xFFFF) ?: previous.dischargePercent,
            chargePercent = durations?.get(3)?.and(0xFFFF) ?: previous.chargePercent,
            chargeManagementSoc = management?.get(0)?.let { (it and 0xFFFF) != 0 } ?: previous.chargeManagementSoc
        )
    }

    private suspend fun writeExtendedSettings(io: ModbusIo, settings: EpeverExtendedSettings) {
        val failures = mutableListOf<String>()
        runCatching {
            io.writeHoldingRegs(SLAVE_ID, REG_EQUALIZE_CYCLE, intArrayOf(settings.equalizeCycleDays.coerceIn(0, 255)))
        }.onFailure { failures += "eq-cycle" }
        runCatching {
            io.writeHoldingRegs(
                SLAVE_ID,
                REG_TEMP_LIMITS,
                intArrayOf(
                    toRegTempC(settings.batteryTempWarnHighC),
                    toRegTempC(settings.batteryTempWarnLowC),
                    toRegTempC(settings.innerTempLimitC),
                    toRegTempC(settings.innerTempRecoverC),
                    toRegTempC(settings.powerTempLimitC),
                    toRegTempC(settings.powerTempRecoverC),
                    toRegMilliOhm(settings.lineImpedanceMOhm)
                )
            )
        }.onFailure { failures += "temps" }
        runCatching {
            io.writeHoldingRegs(
                SLAVE_ID,
                REG_BATTERY_RATED_V,
                intArrayOf(settings.batteryRatedVoltageCode.coerceIn(0, 4))
            )
        }.onFailure { failures += "rated-V" }
        runCatching {
            io.writeHoldingRegs(
                SLAVE_ID,
                REG_CHARGE_DURATIONS,
                intArrayOf(
                    settings.equalizeDurationMin.coerceIn(0, 180),
                    settings.boostDurationMin.coerceIn(10, 180),
                    settings.dischargePercent.coerceIn(0, 100),
                    settings.chargePercent.coerceIn(0, 100)
                )
            )
        }.onFailure { failures += "durations" }
        runCatching {
            io.writeHoldingRegs(
                SLAVE_ID,
                REG_CHARGE_MANAGEMENT,
                intArrayOf(if (settings.chargeManagementSoc) 1 else 0)
            )
        }.onFailure { failures += "mgmt" }
        val required = failures.filter { it == "eq-cycle" || it == "temps" }
        if (required.isNotEmpty()) {
            error("Extended write failed: ${required.joinToString()}")
        }
    }

    private suspend fun readTelemetry(io: ModbusIo, includeRated: Boolean = false): EpeverTelemetry {
        val rt = io.readInputRegs(SLAVE_ID, REG_REALTIME, 18)
        val status = io.readInputRegs(SLAVE_ID, REG_BATTERY_STATUS, 2)
        val stats = io.readInputRegs(SLAVE_ID, REG_STATS, 20)
        val socBlock = runCatching { io.readInputRegs(SLAVE_ID, REG_BATTERY_SOC, 4) }.getOrNull()
        val rated = if (includeRated) {
            runCatching { io.readInputRegs(SLAVE_ID, REG_RATED, 9) }.getOrNull()
        } else {
            null
        }
        val net = if (includeRated) {
            runCatching { io.readInputRegs(SLAVE_ID, REG_NET_BATTERY_A, 2) }.getOrNull()
        } else {
            null
        }
        val batteryStatus = status[0]
        val chargingStatus = status[1]
        val stageBits = (chargingStatus ushr 2) and 0x03
        val next = EpeverTelemetry(
            pvVolts = fromRegV(rt[0]),
            pvAmps = fromRegA(rt[1]),
            pvWatts = fromDwordW(rt[2], rt[3]),
            batteryVolts = fromRegV(rt[4]),
            chargeAmps = fromRegA(rt[5]),
            chargeWatts = fromDwordW(rt[6], rt[7]),
            loadVolts = fromRegV(rt[12]),
            loadAmps = fromRegA(rt[13]),
            loadWatts = fromDwordW(rt[14], rt[15]),
            deviceTempF = cToF(fromRegTempC(rt[17])),
            rtsTempF = cToF(fromRegTempC(rt[16])),
            chargingOn = (chargingStatus and 0x01) != 0,
            chargeStage = when (stageBits) {
                1 -> EpeverChargeStage.Float
                2 -> EpeverChargeStage.Boost
                3 -> EpeverChargeStage.Equalize
                else -> EpeverChargeStage.NoCharging
            },
            running = (chargingStatus and 0x01) != 0,
            fault = (chargingStatus and 0x02) != 0,
            generatedWhToday = fromEnergyWh(stats[12], stats[13]),
            generatedWhMonth = fromEnergyWh(stats[14], stats[15]),
            generatedWhYear = fromEnergyWh(stats[16], stats[17]),
            generatedWhTotal = fromEnergyWh(stats[18], stats[19]),
            maxPvVoltsToday = fromRegV(stats[0]),
            minPvVoltsToday = fromRegV(stats[1]),
            maxBatteryVoltsToday = fromRegV(stats[2]),
            minBatteryVoltsToday = fromRegV(stats[3]),
            controllerSocPercent = socBlock?.get(0)?.and(0xFFFF),
            systemRatedVolts = socBlock?.get(3)?.let { fromRegV(it) } ?: lastTelemetry.systemRatedVolts,
            ratedPvVolts = rated?.get(0)?.let { fromRegV(it) } ?: lastTelemetry.ratedPvVolts,
            ratedPvAmps = rated?.get(1)?.let { fromRegA(it) } ?: lastTelemetry.ratedPvAmps,
            ratedPvWatts = rated?.let { fromDwordW(it[2], it[3]) } ?: lastTelemetry.ratedPvWatts,
            ratedChargeVolts = rated?.get(4)?.let { fromRegV(it) } ?: lastTelemetry.ratedChargeVolts,
            ratedChargeAmps = rated?.get(5)?.let { fromRegA(it) } ?: lastTelemetry.ratedChargeAmps,
            ratedChargeWatts = rated?.let { fromDwordW(it[6], it[7]) } ?: lastTelemetry.ratedChargeWatts,
            chargingModeText = rated?.get(8)?.let { chargingModeLabel(it) } ?: lastTelemetry.chargingModeText,
            batteryStatusText = decodeBatteryStatus(batteryStatus),
            chargeStatusText = decodeChargeStatusExtra(chargingStatus),
            netBatteryAmps = net?.let { fromDwordA(it[0], it[1]) } ?: lastTelemetry.netBatteryAmps
        )
        lastTelemetry = next
        return next
    }

    private fun findDriver(preferredVid: Int?, preferredPid: Int?): UsbSerialDriver? {
        val found = linkedMapOf<String, UsbSerialDriver>()
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).forEach {
            found["${it.device.vendorId}:${it.device.productId}"] = it
        }
        val exarTable = ProbeTable().apply {
            addProduct(ExarRs485.VID_EXAR, ExarRs485.PID_XR21V1410, CdcAcmSerialDriver::class.java)
        }
        UsbSerialProber(exarTable).findAllDrivers(usbManager).forEach {
            found["${it.device.vendorId}:${it.device.productId}"] = it
        }
        val drivers = found.values.toList()
        if (drivers.isEmpty()) return null
        val preferred = if (preferredVid != null && preferredPid != null) {
            drivers.firstOrNull { it.device.vendorId == preferredVid && it.device.productId == preferredPid }
        } else {
            null
        }
        return preferred
            ?: drivers.firstOrNull { ExarRs485.looksLikeOfficialCable(it.device) }
            ?: drivers.first()
    }

    private fun requestPermission(device: UsbDevice) {
        permissionRequestInFlight = true
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        runCatching {
            usbManager.requestPermission(device, pendingIntent)
        }.onFailure {
            permissionRequestInFlight = false
            publish(state.copy(permissionNeeded = true, status = "USB permission request failed: ${it.message ?: it.javaClass.simpleName}"))
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private suspend fun publishOnMain(next: EpeverUsbState) {
        withContext(Dispatchers.Main) { publish(next) }
    }

    private fun publish(next: EpeverUsbState) {
        state = next
        onStateChanged(next)
    }

    private fun closeQuietly(port: UsbSerialPort?, connection: UsbDeviceConnection?) {
        runCatching { port?.close() }
        runCatching { connection?.close() }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.emppt.USB_PERMISSION"
        private const val SLAVE_ID = 1
        private const val POLL_MS = 400L
        /** Documented on BN/AN; AN firmware does not honor it for charge output. */
        private const val COIL_CHARGING = 0x0000
        private const val COIL_CLEAR_GENERATED_ENERGY = 0x0014
        private const val PARK_CHARGE_VOLTS = 13.00
        /** 0x3104 in this block is measured battery V (dashboard MPPT V). */
        private const val REG_REALTIME = 0x3100
        private const val REG_RATED = 0x3000
        private const val REG_BATTERY_SOC = 0x311A
        private const val REG_BATTERY_STATUS = 0x3200
        private const val REG_STATS = 0x3300
        private const val REG_NET_BATTERY_A = 0x331B
        /** Start of the 15-register battery block that must be written atomically. */
        private const val REG_BATTERY_TYPE = 0x9000
        private const val REG_RTC = 0x9013
        private const val REG_EQUALIZE_CYCLE = 0x9016
        private const val REG_TEMP_LIMITS = 0x9017
        private const val REG_BATTERY_RATED_V = 0x9067
        private const val REG_CHARGE_DURATIONS = 0x906B
        private const val REG_CHARGE_MANAGEMENT = 0x9070

        private fun toRegV(volts: Double): Int = kotlin.math.round(volts * 100.0).toInt().coerceIn(0, 1500)
        private fun fromRegV(raw: Int): Double = (raw and 0xFFFF) / 100.0
        private fun toRegTempComp(value: Double): Int = (value * 100.0).toInt().coerceIn(0, 900)
        private fun fromRegTempComp(raw: Int): Double = raw.toShort().toInt() / 100.0

        private fun EpeverBatteryProfile.matchesLive(profile: EpeverVoltageProfile): Boolean {
            return abs(chargingLimitVolts - profile.chargeLimitVolts) < 0.06 &&
                abs(boostVolts - profile.boostVolts) < 0.06 &&
                abs(floatVolts - profile.floatVolts) < 0.06 &&
                abs(equalizeVolts - profile.equalizeVolts) < 0.06
        }

        /** 15 words: type, Ah, temp-comp, HVD, limit, OVR, EQ, boost, float, boost-rec, LVR…DLV. */
        private fun EpeverBatteryProfile.toRegisterBlock(): IntArray {
            return intArrayOf(
                batteryType.coerceIn(0, 5),
                capacityAh.coerceIn(1, 3000),
                toRegTempComp(tempCompensationMvv),
                toRegV(highVoltageDisconnect),
                toRegV(chargingLimitVolts),
                toRegV(overVoltageReconnect),
                toRegV(equalizeVolts),
                toRegV(boostVolts),
                toRegV(floatVolts),
                toRegV(boostReconnectVolts),
                toRegV(lowVoltageReconnect),
                toRegV(underVoltageRecover),
                toRegV(underVoltageWarning),
                toRegV(lowVoltageDisconnect),
                toRegV(dischargingLimitVolts)
            )
        }
        private fun fromRegA(raw: Int): Double = (raw and 0xFFFF) / 100.0
        private fun fromDwordW(low: Int, high: Int): Double {
            val value = ((high and 0xFFFF) shl 16) or (low and 0xFFFF)
            return value / 100.0
        }
        private fun fromDwordA(low: Int, high: Int): Double {
            val value = ((high and 0xFFFF) shl 16) or (low and 0xFFFF)
            return value / 100.0
        }
        private fun toRegTempC(celsius: Double): Int = (celsius * 100.0).toInt().and(0xFFFF)
        private fun toRegMilliOhm(value: Double): Int = kotlin.math.round(value * 100.0).toInt().coerceIn(0, 65535)
        private fun fromRegMilliOhm(raw: Int): Double = (raw and 0xFFFF) / 100.0
        private fun chargingModeLabel(raw: Int): String = when (raw and 0xFFFF) {
            0 -> "Connect/Disconnect"
            1 -> "PWM"
            2 -> "MPPT"
            else -> "Mode $raw"
        }
        private fun fromEnergyWh(low: Int, high: Int): Double {
            val value = ((high and 0xFFFF) shl 16) or (low and 0xFFFF)
            return value * 10.0
        }
        private fun fromRegTempC(raw: Int): Double {
            val signed = raw.toShort().toInt()
            return signed / 100.0
        }
        private fun cToF(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0
    }
}

private data class PollSnapshot(
    val telemetry: EpeverTelemetry,
    val clock: EpeverClock?,
    val profile: EpeverBatteryProfile?,
    val extended: EpeverExtendedSettings?
)
