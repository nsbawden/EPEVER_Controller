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
    val minBatteryVoltsToday: Double = 0.0
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
    val clock: EpeverClock? = null
)

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

    companion object {
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

data class EpeverVoltageProfile(
    val chargeLimitVolts: Double,
    val highVoltageDisconnect: Double,
    val overVoltageReconnect: Double,
    val equalizeVolts: Double,
    val boostVolts: Double,
    val floatVolts: Double,
    val boostReconnectVolts: Double
) {
    companion object {
        fun bulk(maxBatteryVolts: Double): EpeverVoltageProfile {
            val cv = maxBatteryVolts.coerceIn(12.0, 15.0)
            return EpeverVoltageProfile(
                chargeLimitVolts = cv,
                highVoltageDisconnect = (cv + 0.30).coerceAtMost(15.0),
                overVoltageReconnect = cv,
                equalizeVolts = cv,
                boostVolts = cv,
                floatVolts = cv,
                boostReconnectVolts = (cv - 0.40).coerceAtLeast(12.60)
            )
        }

        fun hold(holdCv: Double, maxBatteryVolts: Double): EpeverVoltageProfile {
            val cv = holdCv.coerceIn(12.40, maxBatteryVolts.coerceIn(12.40, 15.0))
            return EpeverVoltageProfile(
                chargeLimitVolts = cv,
                highVoltageDisconnect = (cv + 0.30).coerceAtMost(15.0),
                overVoltageReconnect = cv,
                equalizeVolts = cv,
                boostVolts = cv,
                floatVolts = cv,
                boostReconnectVolts = (cv - 0.20).coerceAtLeast(12.40)
            )
        }
    }
}
