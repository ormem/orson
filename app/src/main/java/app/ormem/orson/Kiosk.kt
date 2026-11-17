package app.ormem.orson

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Device-owner controls that lock the phone to Orson. All no-ops unless Orson is device owner. */
object Kiosk {

    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    /** Pin Orson, grant what it needs, and hide the rest of Android. */
    fun setUp(c: Context) {
        if (!isOwner(c)) return
        val dpm = dpm(c)
        val admin = admin(c)
        dpm.setLockTaskPackages(admin, arrayOf(c.packageName))
        for (perm in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)) {
            dpm.setPermissionGrantState(admin, c.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        // Wi-Fi scan results need location on (Android 9).
        @Suppress("DEPRECATION")
        runCatching { dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString()) }
        lockDown(c, !Prefs(c).maintenance)
        // Huawei turns some of these back on at restart, so hide them again on every start.
        HIDE.forEach { runCatching { dpm.setApplicationHidden(admin, it, true) } }
    }

    fun isAdbOn(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** Returns false if Android refused. */
    fun setAdb(c: Context, on: Boolean): Boolean {
        val value = if (on) "1" else "0"
        if (runCatching { Settings.Global.putString(c.contentResolver, Settings.Global.ADB_ENABLED, value) }.getOrDefault(false)) return true
        if (!isOwner(c)) return false
        return runCatching { dpm(c).setGlobalSetting(admin(c), Settings.Global.ADB_ENABLED, value) }.isSuccess
    }

    /** Huawei's "Allow ADB debugging in charge only mode": keeps USB debugging working after a restart. */
    fun isChargeAdbOn(c: Context) = Settings.Global.getInt(c.contentResolver, CHARGE_ADB, 0) == 1

    /** Needs WRITE_SECURE_SETTINGS, granted once over adb. Returns false if Android refused. */
    fun setChargeAdb(c: Context, on: Boolean) =
        runCatching { Settings.Global.putInt(c.contentResolver, CHARGE_ADB, if (on) 1 else 0) }.getOrDefault(false)

    fun reboot(c: Context) {
        if (isOwner(c)) dpm(c).reboot(admin(c))
    }

    /** Unpins Orson and shows the status bar so the phone can be serviced, or locks it again. */
    fun setMaintenance(activity: Activity, on: Boolean) {
        Prefs(activity).maintenance = on
        if (!isOwner(activity)) return
        lockDown(activity, !on)
        if (on) runCatching { activity.stopLockTask() } else runCatching { activity.startLockTask() }
    }

    private const val CHARGE_ADB = "allow_charging_adb"

    /** Background apps Orson doesn't need. */
    private val HIDE = listOf(
        "com.huawei.android.launcher", "com.huawei.android.pushagent", "com.huawei.lbs", "com.huawei.mmitest",
        "com.huawei.hiview", "com.huawei.hiviewtunnel", "com.android.providers.calendar", "com.android.supl",
        "com.huawei.nb.service", "com.huawei.trustagent", "com.huawei.hwasm", "com.huawei.motionservice",
        "com.huawei.msdp", "com.huawei.android.hwaps", "com.huawei.android.projectmenu",
    )

    private fun lockDown(c: Context, locked: Boolean) {
        runCatching { dpm(c).setKeyguardDisabled(admin(c), locked) }
        runCatching { dpm(c).setStatusBarDisabled(admin(c), locked) }
    }
}
