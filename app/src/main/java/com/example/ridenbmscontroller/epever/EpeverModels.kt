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
    val generatedWhTotal: Double = 0.0,
    val maxPvVoltsToday: Double = 0.0,
    val minPvVoltsToday: Double = 0.0,
    val maxBatteryVoltsToday: Double = 0.0,
    val minBatteryVoltsToday: Double = 0.0
)

data class EpeverUsbState(
    val connected: Boolean = false,
    val permissionNeeded: Boolean = false,
    val status: String = "Disconnected",
    val deviceName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val telemetry: EpeverTelemetry = EpeverTelemetry(),
    val telemetrySampleSeq: Long = 0L
)

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
