package app.ormem.orson

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** PIN-protected settings, one tab per area. Tabs sit on top in portrait and down the side in landscape. */
class SettingsActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val wifi by lazy { Wifi(this) }
    private val main = Handler(Looper.getMainLooper())
    private var tab = 0
    private var unlocked = false
    private var showTab: () -> Unit = {}
    private var showPin: (() -> Unit)? = null

    private val tabs = listOf<Pair<String, (LinearLayout) -> Unit>>(
        "Screen" to ::screenTab,
        "Wi-Fi" to ::wifiTab,
        "Voice" to ::voiceTab,
        "Ormem" to ::ormemTab,
        "Device" to ::deviceTab,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyBrightness(prefs.brightness)
        window.decorView.setBackgroundColor(Ui.BG)
        askPin()
    }

    override fun onResume() {
        super.onResume()
        if (unlocked && showPin == null) showTab()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val pin = showPin
        if (pin != null) pin() else if (unlocked) build()
    }

    // PIN

    /** Asks for the PIN, or sets one if there's none or [change] is true. */
    private fun askPin(change: Boolean = false) {
        val saved = prefs.pin
        if (saved != null && !change) {
            lateinit var pad: PinPad
            pad = PinPad(this, "Enter PIN", saved.length, { pin ->
                if (pin == saved) { open(); true } else { pad.say("Wrong PIN"); false }
            }, ::leavePin)
            showPin = { askPin(change) }
            setContentView(pad)
            return
        }
        var first: String? = null
        lateinit var pad: PinPad
        pad = PinPad(this, "Choose a 4-digit PIN", 4, { pin ->
            when {
                first == null -> { first = pin; pad.say(" ", "Enter it again"); false }
                pin == first -> { prefs.pin = pin; open(); true }
                else -> { first = null; pad.say("Those didn't match", "Choose a 4-digit PIN"); false }
            }
        }, ::leavePin)
        showPin = { askPin(change) }
        setContentView(pad)
    }

    private fun open() {
        unlocked = true
        showPin = null
        build()
    }

    private fun leavePin() {
        if (unlocked) { showPin = null; build() } else finish()
    }

    // Layout

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val content = Ui.column(this)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        val pills = mutableListOf<Button>()
        showTab = {
            content.removeAllViews()
            tabs[tab].second(content)
            pills.forEachIndexed { i, b -> Ui.select(b, i == tab) }
        }
        tabs.forEachIndexed { i, t ->
            pills += Ui.pill(this, t.first) { tab = i; showTab(); scroll.scrollTo(0, 0) }.apply {
                textSize = 13f
                if (landscape) gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
        }
        val title = Ui.serif(this, "Settings", if (landscape) 24f else 28f)
        val done = Ui.chip(this, "Done") { finish() }.apply { minimumHeight = Ui.dp(context, 36) }
        val pad = Ui.dp(this, 16)
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        setContentView(if (landscape) {
            LinearLayout(this).apply {
                setBackgroundColor(Ui.BG)
                setPadding(pad, pad, pad, pad)
                addView(Ui.column(context, spacing = 12).apply {
                    addView(title)
                    addView(Ui.track(context, pills, vertical = true, height = 36))
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    addView(done, LinearLayout.LayoutParams(wrap, Ui.dp(context, 36)))
                }, LinearLayout.LayoutParams(Ui.dp(context, 150), fill))
                addView(scroll, LinearLayout.LayoutParams(0, fill, 1f).apply { leftMargin = pad })
            }
        } else {
            Ui.column(this, spacing = 8).apply {
                setBackgroundColor(Ui.BG)
                setPadding(pad, pad, pad, pad)
                addView(Ui.row(context,
                    title.apply { layoutParams = LinearLayout.LayoutParams(0, wrap, 1f) },
                    done.apply { layoutParams = LinearLayout.LayoutParams(wrap, Ui.dp(context, 36)) },
                ))
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = true
                    addView(Ui.track(context, pills))
                })
                addView(scroll, LinearLayout.LayoutParams(fill, 0, 1f))
            }
        })
        showTab()
    }

    private fun label(body: LinearLayout, s: String) = body.addView(Ui.text(this, s, 14f))

    // Screen

    private fun screenTab(page: LinearLayout) {
        val body = Ui.section(page, "Display")
        val brightness = Ui.line(this, "Brightness")
        val value = brightness.getChildAt(1) as TextView
        val current = prefs.brightness.takeIf { it >= 0 } ?: 0.6f
        value.text = "${(current * 100).toInt()}%"
        body.addView(brightness)
        body.addView(Ui.slider(this, 100, (current * 100).toInt()) {
            prefs.brightness = maxOf(it, 2) / 100f
            value.text = "$it%"
            applyBrightness(prefs.brightness)
        })
        body.addView(Ui.toggle(this, "Show what Orson hears", prefs.captions) { prefs.captions = it })
        Ui.section(page, "Dim the screen after").addView(
            Ui.choice(this, listOf(0, 1, 5, 15), { if (it == 0) "Never" else "${it}m" }, prefs.dimAfter) { prefs.dimAfter = it }
        )
        Ui.section(page, "Conversation").addView(Ui.wide(this, "Clear saved conversation", danger = true) {
            confirm("Clear the conversation?", "This removes it from the phone. Your Ormem memories stay.", "Clear") {
                Conversation(this).clear()
            }
        })
    }

    // Wi-Fi

    private fun wifiTab(page: LinearLayout) {
        val body = Ui.section(page, "Network")
        val status = Ui.line(this, "Connected to")
        val current = status.getChildAt(1) as TextView
        fun refresh() { current.text = wifi.current() ?: "Nothing" }
        refresh()
        body.addView(Ui.toggle(this, "Wi-Fi", wifi.isOn()) {
            wifi.setOn(it)
            main.postDelayed({ refresh() }, 4_000)
        })
        body.addView(status)

        val join = Ui.section(page, "Join a network")
        val ssid = Ui.field(this, "Network name")
        val password = Ui.field(this, "Password", secret = true)
        val result = Ui.text(this, "", 14f, Ui.ACCENT)
        val nearby = Ui.column(this, spacing = 6)
        fun showNearby() {
            nearby.removeAllViews()
            val names = wifi.nearby().take(8)
            if (names.isEmpty()) nearby.addView(Ui.text(this, "No networks found yet. Scan again in a few seconds.", 13f, Ui.QUIET))
            names.forEach { name -> nearby.addView(Ui.wide(this, name) { ssid.setText(name); password.requestFocus() }) }
        }
        join.addView(Ui.wide(this, "Scan for networks") { showNearby() })
        join.addView(nearby)
        join.addView(ssid)
        join.addView(password)
        join.addView(Ui.wide(this, "Connect") {
            val name = ssid.text.toString().trim()
            if (name.isEmpty()) { result.text = "Enter a network name first."; return@wide }
            hideKeyboard(password)
            val ok = wifi.connect(name, password.text.toString())
            result.text = if (ok) "Connecting to $name…" else "Android refused this network."
            if (ok) main.postDelayed({
                refresh()
                result.text = if (wifi.current() == name) "Connected to $name." else "Couldn't join $name. Check the password."
            }, 8_000)
        })
        join.addView(result)
        page.addView(Ui.wide(this, "Forget this network", danger = true) {
            val name = wifi.current() ?: return@wide
            confirm("Forget $name?", null, "Forget") {
                current.text = if (wifi.forgetCurrent()) "Nothing" else current.text
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 14) } })
    }

    // Voice

    private fun voiceTab(page: LinearLayout) {
        val wake = Ui.section(page, "Wake word")
        wake.addView(Ui.toggle(this, "Listen for the wake word", prefs.wakeOn) { prefs.wakeOn = it })
        wake.addView(Ui.choice(this, WakeWord.entries, { it.label.removePrefix("Hey ") }, prefs.wake) { prefs.wake = it })
        label(wake, "Sensitivity")
        wake.addView(Ui.choice(this, Sensitivity.entries, { it.label }, prefs.sensitivity) { prefs.sensitivity = it })

        val mic = Ui.section(page, "Microphone")
        val gain = Ui.line(this, "Mic boost")
        val gainValue = gain.getChildAt(1) as TextView
        fun showGain() { gainValue.text = "${"%.1f".format(prefs.gain)}×" }
        showGain()
        mic.addView(gain)
        mic.addView(Ui.slider(this, 10, ((prefs.gain - 1f) / 0.5f).toInt().coerceIn(0, 10)) {
            prefs.gain = 1f + it * 0.5f
            showGain()
        })
        label(mic, "Pause that ends your sentence")
        mic.addView(Ui.choice(this, listOf(0.8f, 1.0f, 1.5f, 2.0f), { "$it s" }, prefs.endSilence) { prefs.endSilence = it })
        mic.addView(Ui.toggle(this, "Beep when listening", prefs.beep) { prefs.beep = it })
        mic.addView(Ui.toggle(this, "Keep listening after a reply", prefs.followUp) { prefs.followUp = it })

        val voice = Ui.section(page, "Orson's voice")
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        label(voice, "Volume")
        voice.addView(Ui.slider(this, max, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        })
        val speed = Ui.line(this, "Speaking speed")
        val speedValue = speed.getChildAt(1) as TextView
        fun showSpeed() { speedValue.text = "${"%.1f".format(prefs.speed)}×" }
        showSpeed()
        voice.addView(speed)
        voice.addView(Ui.slider(this, 6, Math.round((prefs.speed - 0.7f) / 0.1f).coerceIn(0, 6)) {
            prefs.speed = 0.7f + it * 0.1f
            showSpeed()
        })
        voice.addView(Ui.wide(this, "Test voice") {
            val rate = prefs.speed
            thread { runCatching { Orson.voice?.speak("Hello, I'm Orson.", rate) } }
        })
    }

    // Ormem

    private fun ormemTab(page: LinearLayout) {
        val body = Ui.section(page, "Connection")
        val status = Ui.line(this, "Status", "Checking…")
        val statusValue = status.getChildAt(1) as TextView
        val name = Ui.field(this, "Your name").apply { setText(prefs.name.orEmpty()) }
        body.addView(status)
        body.addView(name)
        body.addView(Ui.wide(this, "Save name") {
            name.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.name = it }
            hideKeyboard(name)
        })

        val keys = Ui.section(page, "API key")
        keys.addView(Ui.line(this, "Key", prefs.key?.let { "Saved (…${it.takeLast(4)})" } ?: "Not added"))
        keys.addView(Ui.wide(this, "Scan QR code") { startActivity(Intent(this, ScanActivity::class.java)) })
        val key = Ui.field(this, "Or type the key (om_…)", secret = true)
        keys.addView(key)
        keys.addView(Ui.wide(this, "Save key") {
            key.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.key = it; key.setText("") }
            hideKeyboard(key)
            showTab()
        })

        val usage = Ui.text(this, "Loading…", 14f)
        Ui.section(page, "This month").addView(usage)
        val list = Ui.column(this, spacing = 4)
        Ui.section(page, "Recent memories").addView(list)
        loadOrmem(statusValue, usage, list)
    }

    private fun loadOrmem(status: TextView, usage: TextView, list: LinearLayout) {
        val key = prefs.key ?: run {
            status.text = "Not set up"
            usage.text = "Add a key first."
            return
        }
        val ormem = Ormem(key, prefs.owner)
        thread {
            val link = try {
                ormem.check()
                "Connected"
            } catch (e: Ormem.OrmemError) {
                if (e.code == 401) "Key rejected" else "Ormem error"
            } catch (e: Exception) {
                "Unreachable"
            }
            val used = runCatching { ormem.usage() }.getOrElse { "Couldn't load usage." }
            val memories = runCatching { ormem.recent() }.getOrNull()
            main.post {
                status.text = link
                usage.text = used
                list.removeAllViews()
                when {
                    memories.isNullOrEmpty() -> list.addView(Ui.text(this, "No memories yet.", 14f, Ui.QUIET))
                    else -> memories.forEach { (id, text) -> list.addView(memoryRow(ormem, id, text)) }
                }
            }
        }
    }

    private fun memoryRow(ormem: Ormem, id: String, text: String): View {
        lateinit var row: LinearLayout
        row = Ui.row(this,
            Ui.text(this, text, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            Ui.chip(this, "Erase") {
                confirm("Erase this memory?", "This can't be undone.", "Erase") {
                    thread {
                        val ok = runCatching { ormem.erase(id) }.isSuccess
                        main.post { if (ok) row.visibility = View.GONE }
                    }
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(context, 34)) },
        )
        return row
    }

    // Device

    private fun deviceTab(page: LinearLayout) {
        val status = Ui.section(page, "Status")
        status.addView(Ui.line(this, "Battery", Orson.battery(this)))
        status.addView(Ui.line(this, "Memory", Orson.memory(this)))
        status.addView(Ui.line(this, "Storage", Orson.storage()))
        status.addView(Ui.line(this, "Orson version", Orson.version(this)))

        val usb = Ui.section(page, "USB debugging")
        val note = Ui.text(this, "", 13f, Ui.QUIET)
        usb.addView(Ui.toggle(this, "USB debugging", Kiosk.isAdbOn(this)) {
            note.text = if (Kiosk.setAdb(this, it)) "" else "Android refused this change."
        })
        usb.addView(Ui.toggle(this, "Keep on in charge-only mode", Kiosk.isChargeAdbOn(this)) {
            note.text = if (Kiosk.setChargeAdb(this, it)) "" else "Android refused this change."
        })
        usb.addView(note)

        val lock = Ui.section(page, "Lock")
        lock.addView(Ui.toggle(this, "Phone locked to Orson", !prefs.maintenance) { Kiosk.setMaintenance(this, !it) })

        val power = Ui.section(page, "Restart")
        power.addView(Ui.wide(this, "Restart Orson") { exitProcess(0) })
        power.addView(Ui.wide(this, "Restart phone", danger = true) {
            confirm("Restart the phone?", "Orson will be back in about a minute.", "Restart phone") { Kiosk.reboot(this) }
        })

        Ui.section(page, "Security").addView(Ui.wide(this, "Change PIN") {
            askPin(change = true)
        })
    }

    // Helpers

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun confirm(title: String, message: String?, action: String, onYes: () -> Unit) {
        Ui.sheet(this, title, message, null) { dialog, body ->
            body.addView(Ui.wide(this, action, danger = true) { dialog.dismiss(); onYes() })
            body.addView(Ui.wide(this, "Cancel") { dialog.dismiss() })
        }
    }

    private fun applyBrightness(v: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (v < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v
        }
    }
}
