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
                        val (telemetry, clock) = ioMutex.withLock {
                            val nextTelemetry = readTelemetry(io)
                            val nextClock = if (sampleSeq % 5L == 0L) {
                                runCatching { readClock(io) }.getOrNull()
                            } else {
                                null
                            }
                            nextTelemetry to nextClock
                        }
                        sampleSeq += 1L
                        publishOnMain(
                            state.copy(
                                connected = true,
                                permissionNeeded = false,
                                status = "EPEVER live",
                                telemetry = telemetry,
                                telemetrySampleSeq = sampleSeq,
                                clock = clock ?: state.clock
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

    fun setCharging(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo ?: return@launch
            runCatching {
                ioMutex.withLock { io.writeSingleCoil(SLAVE_ID, COIL_CHARGING, enabled) }
            }.onFailure {
                publishOnMain(state.copy(status = "Charge coil failed: ${it.message ?: it.javaClass.simpleName}"))
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
                ioMutex.withLock { readBatteryProfile(io) }
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

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

    fun applyVoltageProfile(profile: EpeverVoltageProfile) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo ?: return@launch
            runCatching {
                ioMutex.withLock {
                    val values = intArrayOf(
                        toRegV(profile.highVoltageDisconnect),
                        toRegV(profile.chargeLimitVolts),
                        toRegV(profile.overVoltageReconnect),
                        toRegV(profile.equalizeVolts),
                        toRegV(profile.boostVolts),
                        toRegV(profile.floatVolts),
                        toRegV(profile.boostReconnectVolts)
                    )
                    io.writeHoldingRegs(SLAVE_ID, REG_HVD, values)
                }
            }.onFailure {
                publishOnMain(state.copy(status = "Voltage profile failed: ${it.message ?: it.javaClass.simpleName}"))
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

    private suspend fun writeBatteryProfile(io: ModbusIo, profile: EpeverBatteryProfile) {
        io.writeHoldingRegs(SLAVE_ID, REG_BATTERY_TYPE, profile.toRegisterBlock())
    }

    private suspend fun writeProtectionParams(io: ModbusIo, profile: EpeverBatteryProfile) {
        io.writeHoldingRegs(
            SLAVE_ID,
            REG_BATTERY_TYPE,
            intArrayOf(
                profile.batteryType.coerceIn(0, 5),
                profile.capacityAh.coerceIn(1, 3000),
                toRegTempComp(profile.tempCompensationMvv)
            )
        )
        io.writeHoldingRegs(
            SLAVE_ID,
            REG_LVR,
            intArrayOf(
                toRegV(profile.lowVoltageReconnect),
                toRegV(profile.underVoltageRecover),
                toRegV(profile.underVoltageWarning),
                toRegV(profile.lowVoltageDisconnect),
                toRegV(profile.dischargingLimitVolts)
            )
        )
    }

    private suspend fun readClock(io: ModbusIo): EpeverClock {
        return EpeverClock.fromRegisters(io.readHoldingRegs(SLAVE_ID, REG_RTC, 3))
    }

    private suspend fun readTelemetry(io: ModbusIo): EpeverTelemetry {
        val rt = io.readInputRegs(SLAVE_ID, REG_REALTIME, 18)
        val status = io.readInputRegs(SLAVE_ID, REG_CHARGING_STATUS, 1)
        val stats = io.readInputRegs(SLAVE_ID, REG_STATS, 20)
        val chargingStatus = status[0]
        val stageBits = (chargingStatus ushr 2) and 0x03
        return EpeverTelemetry(
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
            minBatteryVoltsToday = fromRegV(stats[3])
        )
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
        private const val COIL_CHARGING = 0x0000
        private const val COIL_CLEAR_GENERATED_ENERGY = 0x0014
        private const val REG_REALTIME = 0x3100
        private const val REG_CHARGING_STATUS = 0x3201
        private const val REG_STATS = 0x3300
        private const val REG_BATTERY_TYPE = 0x9000
        private const val REG_HVD = 0x9003
        private const val REG_LVR = 0x900A
        private const val REG_RTC = 0x9013

        private fun toRegV(volts: Double): Int = (volts * 100.0).toInt().coerceIn(0, 1500)
        private fun fromRegV(raw: Int): Double = (raw and 0xFFFF) / 100.0
        private fun toRegTempComp(value: Double): Int = (value * 100.0).toInt().coerceIn(0, 900)
        private fun fromRegTempComp(raw: Int): Double = raw.toShort().toInt() / 100.0

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
