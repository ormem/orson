package app.ormem.orson

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/** Wi-Fi setup without Android's Settings app. Joining needs device owner on newer Android. */
@Suppress("DEPRECATION")
class Wifi(context: Context) {

    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** The connected network's name, or null. */
    fun current(): String? {
        val info = wm.connectionInfo ?: return null
        if (info.networkId == -1) return null
        return info.ssid?.trim('"')?.takeUnless { it == "<unknown ssid>" || it.isBlank() }
    }

    /** Nearby network names, strongest first. Starts a fresh scan for next time. */
    fun nearby(): List<String> {
        if (!wm.isWifiEnabled) runCatching { wm.isWifiEnabled = true }
        runCatching { wm.startScan() }
        return runCatching { wm.scanResults }.getOrDefault(emptyList())
            .sortedByDescending { it.level }
            .map { it.SSID }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun isOn() = wm.isWifiEnabled

    /** Returns false if Android refused. */
    fun setOn(on: Boolean) = runCatching { wm.setWifiEnabled(on) }.getOrDefault(false)

    /** Forgets the connected network so it won't rejoin. */
    fun forgetCurrent(): Boolean {
        val id = wm.connectionInfo?.networkId ?: -1
        return id != -1 && wm.removeNetwork(id)
    }

    /** Returns false if Android refused to add the network. */
    fun connect(ssid: String, password: String): Boolean {
        if (!wm.isWifiEnabled) runCatching { wm.isWifiEnabled = true }
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            else preSharedKey = "\"$password\""
        }
        val existing = runCatching { wm.configuredNetworks }.getOrNull()?.firstOrNull { it.SSID == config.SSID }
        val id = if (existing != null) {
            config.networkId = existing.networkId
            wm.updateNetwork(config)
        } else {
            wm.addNetwork(config)
        }
        if (id == -1) return false
        wm.disconnect()
        wm.enableNetwork(id, true)
        wm.reconnect()
        return true
    }
}
