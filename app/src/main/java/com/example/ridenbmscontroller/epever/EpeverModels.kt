package com.example.ridenbmscontroller.epever

enum class EpeverChargeStage {
    NoCharging,
    Float,
    Boost,
    Equalize;

    val label: String
        get() = when (this) {
            NoCharging -> "No charge"
            Float -> "Float"
            Boost -> "Boost"
            Equalize -> "Equalize"
        }
}

data class EpeverTelemetry(
    val pvVolts: Double = 0.0,
    val pvAmps: Double = 0.0,
    val pvWatts: Double = 0.0,
    val batteryVolts: Double = 0.0,
    val chargeAmps: Double = 0.0,
    val chargeWatts: Double = 0.0,
    val loadVolts: Double = 0.0,
    val loadAmps: Double = 0.0,
    val loadWatts: Double = 0.0,
    val deviceTempF: Double? = null,
    val rtsTempF: Double? = null,
    val chargingOn: Boolean = false,
    val chargeStage: EpeverChargeStage = EpeverChargeStage.NoCharging,
    val running: Boolean = false,
    val fault: Boolean = false,
    val generatedWhToday: Double = 0.0,
    val generatedWhMonth: Double = 0.0,
    val generatedWhYear: Double = 0.0,
    val generatedWhTotal: Double = 0.0,
    val maxPvVoltsToday: Double = 0.0,
    val minPvVoltsToday: Double = 0.0,
    val maxBatteryVoltsToday: Double = 0.0,
    val minBatteryVoltsToday: Double = 0.0,
    val controllerSocPercent: Int? = null,
    val systemRatedVolts: Double? = null,
    val ratedPvVolts: Double? = null,
    val ratedPvAmps: Double? = null,
    val ratedPvWatts: Double? = null,
    val ratedChargeVolts: Double? = null,
    val ratedChargeAmps: Double? = null,
    val ratedChargeWatts: Double? = null,
    val batteryStatusText: String? = null,
    val chargeStatusText: String? = null,
    val netBatteryAmps: Double? = null,
    val chargingModeText: String? = null
)

data class EpeverClock(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int
) {
    val valid: Boolean
        get() = year in 2000..2099 &&
            month in 1..12 &&
            day in 1..31 &&
            hour in 0..23 &&
            minute in 0..59 &&
            second in 0..59

    fun format(): String =
        "%04d-%02d-%02d  %02d:%02d:%02d".format(year, month, day, hour, minute, second)

    fun toRegisters(): IntArray {
        val year2 = (year - 2000).coerceIn(0, 99)
        return intArrayOf(
            ((minute and 0xFF) shl 8) or (second and 0xFF),
            ((day and 0xFF) shl 8) or (hour and 0xFF),
            ((year2 and 0xFF) shl 8) or (month and 0xFF)
        )
    }

    companion object {
        fun fromRegisters(regs: IntArray): EpeverClock {
            val yearRaw = (regs[2] ushr 8) and 0xFF
            return EpeverClock(
                year = if (yearRaw < 100) 2000 + yearRaw else yearRaw,
                month = regs[2] and 0xFF,
                day = (regs[1] ushr 8) and 0xFF,
                hour = regs[1] and 0xFF,
                minute = (regs[0] ushr 8) and 0xFF,
                second = regs[0] and 0xFF
            )
        }

        fun fromPhoneNow(nowMs: Long = System.currentTimeMillis()): EpeverClock {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            return EpeverClock(
                year = cal.get(java.util.Calendar.YEAR),
                month = cal.get(java.util.Calendar.MONTH) + 1,
                day = cal.get(java.util.Calendar.DAY_OF_MONTH),
                hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                minute = cal.get(java.util.Calendar.MINUTE),
                second = cal.get(java.util.Calendar.SECOND)
            )
        }
    }
}

data class EpeverUsbState(
    val connected: Boolean = false,
    val permissionNeeded: Boolean = false,
    val status: String = "Disconnected",
    val deviceName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val telemetry: EpeverTelemetry = EpeverTelemetry(),
    val telemetrySampleSeq: Long = 0L,
    val clock: EpeverClock? = null,
    val deviceProfile: EpeverBatteryProfile? = null,
    val extendedSettings: EpeverExtendedSettings? = null,
    val lastWriteNote: String? = null,
    val lastEepromWriteMs: Long = 0L
)

/** Non-load settings outside the 0x9000–0x900E voltage block. */
data class EpeverExtendedSettings(
    val equalizeCycleDays: Int = 28,
    val batteryTempWarnHighC: Double = 65.0,
    val batteryTempWarnLowC: Double = -40.0,
    val innerTempLimitC: Double = 85.0,
    val innerTempRecoverC: Double = 75.0,
    val powerTempLimitC: Double = 85.0,
    val powerTempRecoverC: Double = 75.0,
    val lineImpedanceMOhm: Double = 0.0,
    val batteryRatedVoltageCode: Int = 0,
    val equalizeDurationMin: Int = 0,
    val boostDurationMin: Int = 120,
    val dischargePercent: Int = 30,
    val chargePercent: Int = 100,
    val chargeManagementSoc: Boolean = false
) {
    val batteryRatedVoltageLabel: String
        get() = batteryRatedVoltageLabel(batteryRatedVoltageCode)

    val chargeManagementLabel: String
        get() = if (chargeManagementSoc) "SOC" else "Voltage"

    companion object {
        fun batteryRatedVoltageLabel(code: Int): String = when (code) {
            1 -> "12 V"
            2 -> "24 V"
            3 -> "36 V"
            4 -> "48 V"
            else -> "Auto"
        }

        fun nextRatedVoltageCode(code: Int): Int = when (code) {
            0 -> 1
            1 -> 2
            else -> 0
        }
    }
}

/** 0x9000–0x900E. Custom voltages require type User (0) in this same 15-register write. */
data class EpeverBatteryProfile(
    val batteryType: Int = 0,
    val capacityAh: Int = 330,
    val tempCompensationMvv: Double = 0.0,
    val highVoltageDisconnect: Double = 14.50,
    val chargingLimitVolts: Double = 14.20,
    val overVoltageReconnect: Double = 14.20,
    val equalizeVolts: Double = 14.20,
    val boostVolts: Double = 14.20,
    val floatVolts: Double = 13.60,
    val boostReconnectVolts: Double = 13.20,
    val lowVoltageReconnect: Double = 12.60,
    val underVoltageRecover: Double = 12.20,
    val underVoltageWarning: Double = 12.00,
    val lowVoltageDisconnect: Double = 11.10,
    val dischargingLimitVolts: Double = 11.00
) {
    val batteryTypeLabel: String
        get() = batteryTypeLabel(batteryType)

    val regulationVolts: Double
        get() = minOf(chargingLimitVolts, boostVolts, floatVolts, equalizeVolts)

    fun mergeKeepingLiveOverlay(device: EpeverBatteryProfile): EpeverBatteryProfile {
        return copy(
            batteryType = device.batteryType,
            capacityAh = device.capacityAh,
            tempCompensationMvv = device.tempCompensationMvv,
            lowVoltageReconnect = device.lowVoltageReconnect,
            underVoltageRecover = device.underVoltageRecover,
            underVoltageWarning = device.underVoltageWarning,
            lowVoltageDisconnect = device.lowVoltageDisconnect,
            dischargingLimitVolts = device.dischargingLimitVolts
        )
    }

    /** Keep Tools voltage setpoints; take type / Ah / temp-comp from a device read. */
    fun mergeKeepingVoltageSetpoints(device: EpeverBatteryProfile): EpeverBatteryProfile {
        return device.copy(
            highVoltageDisconnect = highVoltageDisconnect,
            chargingLimitVolts = chargingLimitVolts,
            overVoltageReconnect = overVoltageReconnect,
            equalizeVolts = equalizeVolts,
            boostVolts = boostVolts,
            floatVolts = floatVolts,
            boostReconnectVolts = boostReconnectVolts,
            lowVoltageReconnect = lowVoltageReconnect,
            underVoltageRecover = underVoltageRecover,
            underVoltageWarning = underVoltageWarning,
            lowVoltageDisconnect = lowVoltageDisconnect,
            dischargingLimitVolts = dischargingLimitVolts
        )
    }

    /**
     * Add [cal] to every voltage in the 15-register block for Unattended / No BMS
     * writes. Tools fields stay unadjusted. Clamp on the wire only.
     */
    fun withVoltageCal(cal: Double): EpeverBatteryProfile {
        if (kotlin.math.abs(cal) < 0.0005) return this
        fun adj(volts: Double) = (volts + cal).coerceIn(CAL_WRITE_MIN_V, CAL_WRITE_MAX_V)
        return copy(
            highVoltageDisconnect = adj(highVoltageDisconnect),
            chargingLimitVolts = adj(chargingLimitVolts),
            overVoltageReconnect = adj(overVoltageReconnect),
            equalizeVolts = adj(equalizeVolts),
            boostVolts = adj(boostVolts),
            floatVolts = adj(floatVolts),
            boostReconnectVolts = adj(boostReconnectVolts),
            lowVoltageReconnect = adj(lowVoltageReconnect),
            underVoltageRecover = adj(underVoltageRecover),
            underVoltageWarning = adj(underVoltageWarning),
            lowVoltageDisconnect = adj(lowVoltageDisconnect),
            dischargingLimitVolts = adj(dischargingLimitVolts)
        )
    }

    /**
     * Overlay live CVs onto a device-read block. Type 0 (User) belongs in this same
     * write — do not send User as a separate 0x9000 transaction beforehand.
     */
    fun withLiveVoltages(profile: EpeverVoltageProfile): EpeverBatteryProfile {
        return copy(
            batteryType = 0,
            highVoltageDisconnect = profile.highVoltageDisconnect,
            chargingLimitVolts = profile.chargeLimitVolts,
            overVoltageReconnect = profile.overVoltageReconnect,
            equalizeVolts = profile.equalizeVolts,
            boostVolts = profile.boostVolts,
            floatVolts = profile.floatVolts,
            boostReconnectVolts = profile.boostReconnectVolts,
            lowVoltageReconnect = profile.lowVoltageReconnect ?: lowVoltageReconnect,
            underVoltageRecover = profile.underVoltageRecover ?: underVoltageRecover,
            underVoltageWarning = profile.underVoltageWarning ?: underVoltageWarning,
            lowVoltageDisconnect = profile.lowVoltageDisconnect ?: lowVoltageDisconnect,
            dischargingLimitVolts = profile.dischargingLimitVolts ?: dischargingLimitVolts
        )
    }

    companion object {
        const val CAL_WRITE_MIN_V = 9.0
        const val CAL_WRITE_MAX_V = 17.0

        fun batteryTypeLabel(type: Int): String = when (type) {
            1 -> "Sealed"
            2 -> "Gel"
            3 -> "Flooded"
            4 -> "LiFePO4"
            5 -> "Li(NiCoMn)"
            else -> "User"
        }
    }
}

/** Live / park voltage overlay. boostReconnect must stay above LVR + 0.02 V. */
data class EpeverVoltageProfile(
    val chargeLimitVolts: Double,
    val highVoltageDisconnect: Double,
    val overVoltageReconnect: Double,
    val equalizeVolts: Double,
    val boostVolts: Double,
    val floatVolts: Double,
    val boostReconnectVolts: Double,
    val lowVoltageReconnect: Double? = null,
    val underVoltageRecover: Double? = null,
    val underVoltageWarning: Double? = null,
    val lowVoltageDisconnect: Double? = null,
    val dischargingLimitVolts: Double? = null
) {
    companion object {
        const val MIN_LIVE_CV = 9.0

        fun bulk(maxBatteryVolts: Double, lowVoltageReconnect: Double = 12.60): EpeverVoltageProfile {
            return regulation(maxBatteryVolts, maxBatteryVolts, lowVoltageReconnect)
        }

        fun hold(holdCv: Double, maxBatteryVolts: Double, lowVoltageReconnect: Double = 12.60): EpeverVoltageProfile {
            return regulation(holdCv, maxBatteryVolts, lowVoltageReconnect)
        }

        /**
         * Live CV with no 12.40 / BMS floor. If [cv] is below the saved LVR window,
         * the overlay also drops LVR…DLV so boostReconnect stays legal. Tools
         * protection values are restored automatically when CV rises again.
         */
        fun regulation(
            cv: Double,
            bulkCeiling: Double,
            device: EpeverBatteryProfile?
        ): EpeverVoltageProfile {
            return regulation(
                cv,
                bulkCeiling,
                device?.lowVoltageReconnect ?: 12.60,
                device
            )
        }

        fun regulation(
            cv: Double,
            bulkCeiling: Double,
            lowVoltageReconnect: Double,
            device: EpeverBatteryProfile? = null
        ): EpeverVoltageProfile {
            val hi = bulkCeiling.coerceIn(MIN_LIVE_CV, 15.0)
            val command = cv.coerceIn(MIN_LIVE_CV, hi)
            val deviceLvr = (device?.lowVoltageReconnect ?: lowVoltageReconnect).coerceIn(8.5, 14.0)
            val lvr = minOf(deviceLvr, command - 0.22).coerceAtLeast(8.5)
            val uvr = minOf(device?.underVoltageRecover ?: 12.20, lvr - 0.20).coerceAtLeast(8.3)
            val uvw = minOf(device?.underVoltageWarning ?: 12.00, uvr - 0.20).coerceAtLeast(8.1)
            val lvd = minOf(device?.lowVoltageDisconnect ?: 11.10, uvw - 0.20).coerceAtLeast(8.0)
            val dlv = minOf(device?.dischargingLimitVolts ?: 11.00, lvd - 0.10).coerceAtLeast(7.9)
            return EpeverVoltageProfile(
                chargeLimitVolts = command,
                highVoltageDisconnect = (command + 0.30).coerceAtMost(15.0),
                overVoltageReconnect = command,
                equalizeVolts = command,
                boostVolts = command,
                floatVolts = command,
                boostReconnectVolts = (command - 0.20).coerceAtLeast(lvr + 0.02),
                lowVoltageReconnect = lvr,
                underVoltageRecover = uvr,
                underVoltageWarning = uvw,
                lowVoltageDisconnect = lvd,
                dischargingLimitVolts = dlv
            )
        }
    }
}

fun decodeBatteryStatus(raw: Int): String {
    val volt = when (raw and 0x0F) {
        1 -> "Overvolt"
        2 -> "Undervolt"
        3 -> "LVD"
        4 -> "Fault"
        else -> "Normal"
    }
    val temp = when ((raw ushr 4) and 0x0F) {
        1 -> "over-temp"
        2 -> "low-temp"
        else -> "temp OK"
    }
    val bits = buildList {
        if ((raw and 0x0100) != 0) add("IR abnormal")
        if ((raw and 0x8000) != 0) add("rated-V mismatch")
    }
    return if (bits.isEmpty()) "$volt, $temp" else "$volt, $temp, ${bits.joinToString()}"
}

fun decodeChargeStatusExtra(raw: Int): String {
    val input = when ((raw ushr 14) and 0x03) {
        1 -> "PV none"
        2 -> "PV high"
        3 -> "PV error"
        else -> "PV OK"
    }
    val faults = buildList {
        if ((raw and 0x2000) != 0) add("charge FET short")
        if ((raw and 0x1000) != 0) add("charge/anti-rev short")
        if ((raw and 0x0800) != 0) add("anti-rev short")
        if ((raw and 0x0400) != 0) add("input overcurrent")
        if ((raw and 0x0010) != 0) add("PV short")
    }
    return if (faults.isEmpty()) input else "$input, ${faults.joinToString()}"
}
