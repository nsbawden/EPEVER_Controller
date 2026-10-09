package com.example.ridenbmscontroller.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OpsLogDaySummary(
    val dateLabel: String,
    val telemetryBytes: Long,
    val eventsBytes: Long
) {
    val totalBytes: Long get() = telemetryBytes + eventsBytes
}

data class OpsLogStorageSummary(
    val totalBytes: Long,
    val days: List<OpsLogDaySummary>,
    val logDirectory: String
)

data class OpsTelemetrySample(
    val timestampMs: Long,
    val pvMode: String,
    val commandCvVolts: Double,
    val voltageOffset: Double,
    val pvVolts: Double,
    val pvAmps: Double,
    val pvWatts: Double,
    val mpptBattVolts: Double,
    val chargeAmps: Double,
    val chargeWatts: Double,
    val batteryVolts: Double,
    val batteryAmps: Double,
    val batteryWatts: Double,
    val socPercent: Int,
    val temperatureF: Double,
    val epeverConnected: Boolean,
    val bmsConnected: Boolean
)

class OpsLogger(baseDir: File) {
    private val logDir = File(baseDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun logTelemetry(sample: OpsTelemetrySample) {
        val file = telemetryFileFor(sample.timestampMs)
        ensureHeader(file, TELEMETRY_HEADER)
        file.appendText(sample.toCsvLine() + "\n")
    }

    fun logEvent(timestampMs: Long, event: String) {
        val sanitized = event.replace('\n', ' ').replace(',', ';')
        val file = eventsFileFor(timestampMs)
        ensureHeader(file, EVENTS_HEADER)
        file.appendText("$timestampMs,$sanitized\n")
    }

    fun refreshSummary(): OpsLogStorageSummary {
        pruneOldSchemaFiles()
        purgeLogsOlderThanDays(PHONE_LOG_RETAIN_DAYS)
        val byDay = linkedMapOf<String, OpsLogDaySummary>()
        logDir.listFiles()?.forEach { file ->
            val dateLabel = file.name.substringBefore('_')
            if (!dateLabel.matches(DATE_PATTERN)) return@forEach
            val current = byDay[dateLabel] ?: OpsLogDaySummary(dateLabel, 0L, 0L)
            byDay[dateLabel] = when {
                file.name.endsWith("_telemetry.csv") || file.name.contains("_telemetry") ->
                    current.copy(telemetryBytes = current.telemetryBytes + file.length())
                file.name.endsWith("_events.csv") ->
                    current.copy(eventsBytes = current.eventsBytes + file.length())
                else -> current
            }
        }
        val days = byDay.values.sortedByDescending { it.dateLabel }
        return OpsLogStorageSummary(
            totalBytes = days.sumOf { it.totalBytes },
            days = days,
            logDirectory = logDir.absolutePath
        )
    }

    fun pruneOldSchemaFiles(): Int {
        var removed = 0
        logDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.contains("_sky_disturbances") ||
                name.contains("_crash_episodes") ||
                name.contains("_telemetry_v")
            ) {
                if (file.delete()) removed += 1
            }
        }
        return removed
    }

    fun purgeLogsOlderThanDays(retainDays: Int): Int {
        val cutoff = System.currentTimeMillis() - retainDays * 86_400_000L
        var removed = 0
        logDir.listFiles()?.forEach { file ->
            val dateLabel = file.name.substringBefore('_')
            if (!dateLabel.matches(DATE_PATTERN)) return@forEach
            val parsed = runCatching { dayFormat.parse(dateLabel)?.time }.getOrNull() ?: return@forEach
            if (parsed < cutoff && file.delete()) removed += 1
        }
        return removed
    }

    fun deleteLogsBeforeDate(cutoffDateLabel: String): Int {
        var removed = 0
        logDir.listFiles()?.forEach { file ->
            val dateLabel = file.name.substringBefore('_')
            if (dateLabel.matches(DATE_PATTERN) && dateLabel < cutoffDateLabel && file.delete()) {
                removed += 1
            }
        }
        return removed
    }

    fun clearAllLogs(): Int {
        var removed = 0
        logDir.listFiles()?.forEach { file ->
            if (file.delete()) removed += 1
        }
        return removed
    }

    private fun telemetryFileFor(timestampMs: Long) = File(logDir, "${dayFormat.format(Date(timestampMs))}_telemetry.csv")
    private fun eventsFileFor(timestampMs: Long) = File(logDir, "${dayFormat.format(Date(timestampMs))}_events.csv")

    private fun ensureHeader(file: File, header: String) {
        if (!file.exists() || file.length() == 0L) {
            file.appendText(header + "\n")
        }
    }

    private fun OpsTelemetrySample.toCsvLine(): String {
        return listOf(
            timestampMs,
            pvMode,
            commandCvVolts,
            voltageOffset,
            pvVolts,
            pvAmps,
            pvWatts,
            mpptBattVolts,
            chargeAmps,
            chargeWatts,
            batteryVolts,
            batteryAmps,
            batteryWatts,
            socPercent,
            temperatureF,
            if (epeverConnected) 1 else 0,
            if (bmsConnected) 1 else 0
        ).joinToString(",")
    }

    companion object {
        const val LOG_DIR_NAME = "ops_logs"
        const val STORAGE_WARN_BYTES = 100L * 1024L * 1024L
        const val PHONE_LOG_RETAIN_DAYS = 7
        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        const val TELEMETRY_HEADER =
            "timestamp_ms,pv_mode,command_cv_v,voltage_offset_v," +
                "pv_v,pv_a,pv_w,mppt_batt_v,charge_a,charge_w," +
                "battery_v,battery_a,battery_w,soc_pct,temp_f,epever_connected,bms_connected"
        const val EVENTS_HEADER = "timestamp_ms,event"

        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}
