package app.ormem.orson

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

/** Things the screens share, and a readout of the phone. */
object Orson {
    /** Loaded once at startup; null until ready. */
    @Volatile var voice: Voice? = null

    /** How loud Orson's own voice is right now, 0..1. */
    @Volatile var speakingLevel = 0f
    @Volatile var speaking = false

    fun battery(c: Context): String {
        val intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return "$level%" + if (plugged) " · charging" else ""
    }

    fun memory(c: Context): String {
        val info = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return "${gb(info.availMem)} free of ${gb(info.totalMem)}"
    }

    fun storage(): String {
        val fs = StatFs(Environment.getDataDirectory().path)
        return "${gb(fs.availableBytes)} free of ${gb(fs.totalBytes)}"
    }

    fun version(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
}
