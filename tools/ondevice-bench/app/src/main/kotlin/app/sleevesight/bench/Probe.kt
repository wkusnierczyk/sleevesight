package app.sleevesight.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import java.io.File

/** One reading of the phone's state: battery, heat and this process's memory. */
data class Reading(
    val elapsedMs: Long,
    val phase: String,
    val batteryPercent: Int,
    val chargeCounterUah: Long,
    val currentNowUa: Long,
    val voltageMv: Int,
    val batteryTempC: Double,
    val plugged: Boolean,
    val thermalStatus: Int,
    val thermalHeadroom: Float,
    val vmRssKb: Long,
    val vmHwmKb: Long,
    val nativeHeapKb: Long,
    val pssKb: Long,
    val nativePssKb: Long,
    val graphicsKb: Long,
) {
    fun csv(): String =
        listOf(
            elapsedMs,
            phase,
            batteryPercent,
            chargeCounterUah,
            currentNowUa,
            voltageMv,
            batteryTempC,
            plugged,
            thermalStatus,
            "%.3f".format(thermalHeadroom),
            vmRssKb,
            vmHwmKb,
            nativeHeapKb,
            pssKb,
            nativePssKb,
            graphicsKb,
        ).joinToString(",")

    companion object {
        const val HEADER: String =
            "elapsed_ms,phase,battery_pct,charge_counter_uah,current_now_ua,voltage_mv,battery_temp_c,plugged," +
                "thermal_status,thermal_headroom_10s,vm_rss_kb,vm_hwm_kb,native_heap_kb,pss_kb,native_pss_kb," +
                "graphics_kb"
    }
}

/** Reads [Reading]s. [withPss] adds `Debug.getMemoryInfo`, which walks the process's maps: slower. */
class Probe(
    private val context: Context,
) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val start = SystemClock.elapsedRealtime()

    fun read(
        phase: String,
        withPss: Boolean,
    ): Reading {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = procStatus()
        var pss = -1L
        var nativePss = -1L
        var graphics = -1L
        if (withPss) {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            pss = info.totalPss.toLong()
            nativePss = info.nativePss.toLong()
            graphics = info.getMemoryStat("summary.graphics")?.toLongOrNull() ?: -1L
        }
        return Reading(
            elapsedMs = SystemClock.elapsedRealtime() - start,
            phase = phase,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            chargeCounterUah = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentNowUa = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            voltageMv = voltage,
            batteryTempC = if (temp == Int.MIN_VALUE) Double.NaN else temp / 10.0,
            plugged = plugged,
            thermalStatus = power.currentThermalStatus,
            thermalHeadroom = power.getThermalHeadroom(10),
            vmRssKb = status["VmRSS"] ?: -1,
            vmHwmKb = status["VmHWM"] ?: -1,
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            pssKb = pss,
            nativePssKb = nativePss,
            graphicsKb = graphics,
        )
    }

    /** `VmRSS` and `VmHWM` (the peak resident set) from `/proc/self/status`, in kB. */
    private fun procStatus(): Map<String, Long> =
        runCatching {
            File("/proc/self/status")
                .readLines()
                .mapNotNull { line ->
                    val key = line.substringBefore(':')
                    if (key != "VmRSS" && key != "VmHWM") return@mapNotNull null
                    line
                        .substringAfter(':')
                        .trim()
                        .substringBefore(' ')
                        .toLongOrNull()
                        ?.let { key to it }
                }.toMap()
        }.getOrDefault(emptyMap())
}
