package com.example.ridenbmscontroller.health

import android.content.Context
import androidx.core.content.edit
import com.example.ridenbmscontroller.model.DailyHealthState

class DailyHealthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var dayKey = 0
    private var minAmps = Double.NaN
    private var maxAmps = Double.NaN

    var state: DailyHealthState = DailyHealthState()
        private set

    fun load(today: Int) {
        dayKey = prefs.getInt(KEY_DAY, today)
        minAmps = readStoredAmps(prefs.getFloat(KEY_MIN_AMPS, UNSET).toDouble())
        maxAmps = readStoredAmps(prefs.getFloat(KEY_MAX_AMPS, UNSET).toDouble())
        if (dayKey != today) {
            resetForDay(today)
        } else {
            publishState()
        }
    }

    fun rolloverDayIfNeeded(today: Int): Boolean {
        if (dayKey == today) return false
        resetForDay(today)
        persist()
        return true
    }

    fun recordChargeAmps(amps: Double?) {
        if (amps == null || amps < MIN_SAMPLE_AMPS) return
        var changed = false
        if (!hasRecordedAmps(minAmps) || amps < minAmps) {
            minAmps = amps
            changed = true
        }
        if (!hasRecordedAmps(maxAmps) || amps > maxAmps) {
            maxAmps = amps
            changed = true
        }
        if (changed) {
            publishState()
            persist()
        }
    }

    private fun resetForDay(today: Int) {
        dayKey = today
        minAmps = Double.NaN
        maxAmps = Double.NaN
        publishState()
    }

    private fun publishState() {
        state = DailyHealthState(
            minChargeAmpsToday = minAmps.takeIf { hasRecordedAmps(it) },
            maxChargeAmpsToday = maxAmps.takeIf { hasRecordedAmps(it) }
        )
    }

    private fun persist() {
        prefs.edit(commit = true) {
            putInt(KEY_DAY, dayKey)
            putFloat(KEY_MIN_AMPS, if (hasRecordedAmps(minAmps)) minAmps.toFloat() else UNSET)
            putFloat(KEY_MAX_AMPS, if (hasRecordedAmps(maxAmps)) maxAmps.toFloat() else UNSET)
        }
    }

    private fun readStoredAmps(value: Double): Double {
        return if (hasRecordedAmps(value)) value else Double.NaN
    }

    private fun hasRecordedAmps(value: Double): Boolean {
        return !value.isNaN() && value >= 0.0
    }

    companion object {
        private const val PREFS_NAME = "daily_health"
        private const val KEY_DAY = "day"
        private const val KEY_MIN_AMPS = "min_charge_amps"
        private const val KEY_MAX_AMPS = "max_charge_amps"
        private const val UNSET = -1f
        private const val MIN_SAMPLE_AMPS = 0.05
    }
}
