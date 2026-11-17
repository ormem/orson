package app.ormem.orson

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** The whole device: wait for the wake word, hear a request, ask Ormem, speak the answer. */
class MainActivity : Activity(), Speech.Events {

    private enum class State { LOADING, IDLE, READY, LISTENING, THINKING, SPEAKING, MUTED, ERROR }
    private enum class Link { UNKNOWN, OK, NO_KEY, BAD_KEY, OFFLINE }

    private var state = State.LOADING
    private var speech: Speech? = null
    private var speechSetup: Triple<WakeWord, Sensitivity, Float>? = null
    private var lastAnswer: String? = null
    private var requestId = 0
    private var sayId = 0
    private var demo = false
    private var sessionId: String? = null
    private var lastTurnAt = 0L
    private val turns = ArrayList<Ormem.Turn>()
    private val conversation by lazy { Conversation(this) }
    @Volatile private var link = Link.UNKNOWN
    private val main = Handler(Looper.getMainLooper())
    private val beep = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    private val prefs by lazy { Prefs(this) }
    private val wifi by lazy { Wifi(this) }

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var convoList: LinearLayout
    private lateinit var convoScroll: Ui.CappedScroll
    private var liveQuestion: TextView? = null
    private lateinit var rings: RingsView
    private lateinit var settingsButton: Button
    private lateinit var muteButton: Button
    private lateinit var stopButton: Button
    private lateinit var repeatButton: Button
    private lateinit var talkButton: ImageButton
    private lateinit var typeButton: ImageButton

    private var lastActivity = System.currentTimeMillis()
    private var dimmed = false
    private val tick = object : Runnable {
        override fun run() {
            updateInfo()
            maybeDim()
            main.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        saveConfig(intent)
        Kiosk.setUp(this)
        setState(State.LOADING)
        thread(name = "orson-voice") {
            try {
                Orson.voice = Voice(this)
            } catch (e: Exception) {
                Log.e(TAG, "voice", e)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layOut()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveConfig(intent)
    }

    override fun onResume() {
        super.onResume()
        rings.onResume()
        if (Kiosk.isOwner(this) && !prefs.maintenance) runCatching { startLockTask() }
        wakeScreen()
        applySettings()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSpeech()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        checkOrmem()
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        rings.onPause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSpeech()
        else showError("Orson needs the microphone.")
    }

    override fun onDestroy() {
        speech?.stop()
        Orson.voice?.release()
        Orson.voice = null
        beep.release()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        wakeScreen()
        return super.dispatchTouchEvent(ev)
    }

    // Setup

    /** Config can also arrive as launch extras, e.g. `adb shell am start -n app.ormem.orson/.MainActivity --es ormem_key om_...`. */
    private fun saveConfig(intent: Intent?) {
        val extras = intent?.extras ?: return
        extras.getString("ormem_key")?.let { prefs.key = it.trim(); link = Link.UNKNOWN }
        extras.getString("owner")?.let { prefs.owner = it.trim() }
        extras.getString("name")?.let { prefs.name = it.trim() }
        if (extras.getBoolean("demo")) showDemo()
    }

    /** (Re)starts listening when a setting that needs reloading changed. */
    private fun startSpeech() {
        val setup = Triple(prefs.wake, prefs.sensitivity, prefs.endSilence)
        if (speech != null && setup == speechSetup) {
            if (state == State.IDLE || state == State.READY || state == State.MUTED) resumeListening()
            return
        }
        speech?.stop()
        speechSetup = setup
        setState(State.LOADING)
        speech = Speech(assets, this, getExternalFilesDir("asr")).also {
            it.gain = prefs.gain
            it.start(setup.first, setup.second, setup.third, paused = !canWake())
        }
    }

    private fun canWake() = prefs.wakeOn && !prefs.muted

    private fun applySettings() {
        speech?.gain = prefs.gain
        if (!demo && state != State.LISTENING && state != State.THINKING) {
            conversation.reload()
            showConversation()
        }
        updateButtons()
    }

    private fun checkOrmem() {
        val key = prefs.key ?: run { link = Link.NO_KEY; updateInfo(); return }
        if (link == Link.OK) return
        thread {
            link = try {
                Ormem(key, prefs.owner).check()
                Link.OK
            } catch (e: Ormem.OrmemError) {
                if (e.code == 401) Link.BAD_KEY else Link.OFFLINE
            } catch (e: Exception) {
                Link.OFFLINE
            }
            main.post { updateInfo() }
        }
    }

    // Speech events (called from the speech thread)

    override fun onReady() {
        main.post { resumeListening() }
    }

    override fun onWake(keyword: String) {
        main.post {
            Log.d(TAG, "wake: $keyword")
            wakeScreen()
            startListeningUi()
        }
    }

    override fun onHeard() {
        main.post {
            if (state != State.LISTENING) return@post
            if (prefs.beep) beep.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            liveQuestion?.text = "…"
            setState(State.THINKING)
        }
    }

    override fun onVoice(print: Speech.Voiceprint) {
        rings.voice = print
    }

    // Live text is too rough to show; the final text comes from Whisper.
    override fun onPartial(text: String) = Unit

    override fun onRequest(text: String) {
        main.post {
            Log.d(TAG, "heard: $text")
            // Stopped while the words were being written out.
            if (state != State.LISTENING && state != State.THINKING) return@post
            val request = withoutWakeWord(text)
            if (request.isBlank()) {
                dropTurn()
                resumeListening()
            } else {
                handle(request)
            }
        }
    }

    override fun onError(message: String) {
        main.post { showError("Speech error: $message") }
    }

    private fun tidy(text: String) = text.lowercase().replaceFirstChar { it.uppercase() }

    /** The request audio starts just before the wake word ended, so its tail can appear in the text. */
    private fun withoutWakeWord(text: String): String {
        val clean = if (text == text.uppercase()) tidy(text) else text
        val names = (listOf(prefs.wake.label.substringAfterLast(' ')) + prefs.wake.heardAs).joinToString("|") { Regex.escape(it) }
        return clean.replace(Regex("""^\W*(hey\W+)?($names)\b\W*""", RegexOption.IGNORE_CASE), "")
            .trim().replaceFirstChar { it.uppercase() }
    }


    private fun startListeningUi() {
        wakeScreen()
        if (prefs.beep) beep.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        startTurn()
        setState(State.LISTENING)
    }

    private fun resumeListening() {
        when {
            prefs.muted -> { speech?.pause(); setState(State.MUTED) }
            !prefs.wakeOn -> { speech?.pause(); setState(State.READY) }
            else -> { setState(State.IDLE); speech?.listenForWake() }
        }
    }

    private fun tapToTalk() {
        if (state != State.IDLE && state != State.READY && state != State.MUTED) return
        startListeningUi()
        speech?.listenNow()
    }

    /** Cancels whatever Orson is doing and goes back to waiting. */
    private fun stopAll() {
        requestId++
        sayId++
        Orson.voice?.stop()
        if (state == State.SPEAKING) resumeListening()
        if (state == State.LISTENING || state == State.THINKING) {
            dropTurn()
            resumeListening()
        }
    }

    private fun repeatLast() {
        val answer = lastAnswer ?: return
        if (state == State.IDLE || state == State.READY || state == State.MUTED) say(answer)
    }

    private fun toggleMute() {
        prefs.muted = !prefs.muted
        if (state == State.IDLE || state == State.READY || state == State.MUTED) resumeListening()
        updateButtons()
    }

    private fun updateButtons() {
        muteButton.text = if (prefs.muted) "Unmute" else "Mute"
        Ui.select(muteButton, prefs.muted, Ui.DANGER)
    }

    // Ormem

    private fun handle(request: String, typed: Boolean = false) {
        liveQuestion?.text = request
        if (deviceCommand(request)) return
        val key = prefs.key
        if (key.isNullOrBlank()) {
            val reply = "I don't have an Ormem key yet. Add one in settings."
            remember(request, reply)
            return say(reply)
        }

        setState(State.THINKING)
        val id = ++requestId
        val owner = prefs.owner
        val name = prefs.name ?: owner
        val session = session()
        val recent = turns.toList()
        thread {
            var followUp = true
            val reply = try {
                val res = Ormem(key, owner).converse(request, name, session, recent)
                Log.d(TAG, "converse ${res.intent}")
                link = Link.OK
                res.reply.ifBlank { "Sorry, I didn't catch that." }
            } catch (e: Ormem.OrmemError) {
                Log.w(TAG, "ormem ${e.code}: ${e.message}")
                if (e.code == 401) link = Link.BAD_KEY
                if (e.code == 422) "Sorry, I didn't catch that." else { followUp = false; "I can't reach my memory right now." }
            } catch (e: Exception) {
                Log.w(TAG, "network", e)
                link = Link.OFFLINE
                followUp = false
                "I can't reach my memory right now. Check the Wi-Fi."
            }
            main.post {
                updateInfo()
                if (id == requestId) {
                    turns += Ormem.Turn("user", request)
                    turns += Ormem.Turn("assistant", reply)
                    while (turns.size > MAX_TURNS) turns.removeAt(0)
                    remember(request, reply)
                    say(reply, followUp && !typed)
                }
            }
        }
    }

    /** A new conversation starts after a few minutes of silence. */
    private fun session(): String {
        val now = System.currentTimeMillis()
        if (sessionId == null || now - lastTurnAt > SESSION_GAP_MS) {
            sessionId = "orson-" + java.util.UUID.randomUUID()
            turns.clear()
        }
        lastTurnAt = now
        return sessionId!!
    }

    /** Handles stop, louder, quieter, repeat and mute on the phone. Returns false for anything else. */
    private fun deviceCommand(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[^a-z ]"), "").trim().removePrefix("please ").removeSuffix(" please").trim()
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        when {
            words in STOP || words in GOODBYE -> { dropTurn(); endConversation() }
            words in LOUDER -> { dropTurn(); audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); say("Okay.", followUp = true) }
            words in QUIETER -> { dropTurn(); audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); say("Okay.", followUp = true) }
            words in REPEAT -> { dropTurn(); lastAnswer?.let { say(it, followUp = true) } ?: resumeListening() }
            words in MUTE -> { dropTurn(); prefs.muted = true; updateButtons(); resumeListening() }
            words in UNMUTE -> { dropTurn(); prefs.muted = false; updateButtons(); say("I'm listening again.") }
            else -> return false
        }
        Log.d(TAG, "device command: $words")
        return true
    }

    private fun remember(request: String, reply: String) {
        lastAnswer = reply
        conversation.add(request, reply)
        dropTurn()
        convoList.addView(turnView(request, reply))
        convoScroll.visibility = View.VISIBLE
        scrollToEnd()
    }

    // Conversation card

    private fun turnView(question: String, answer: String?) = Ui.column(this, spacing = 3).apply {
        val pad = Ui.dp(context, 10)
        setPadding(0, pad, 0, pad)
        addView(Ui.text(context, question, 12f, Ui.QUIET).apply {
            if (answer != null) setOnClickListener { typeMessage(question) }
        })
        if (answer != null) addView(Ui.serif(context, answer, 15f))
    }

    private fun showConversation() {
        convoList.removeAllViews()
        conversation.turns.forEach { convoList.addView(turnView(it.question, it.answer)) }
        lastAnswer = conversation.turns.lastOrNull()?.answer
        convoScroll.visibility = if (conversation.turns.isEmpty()) View.GONE else View.VISIBLE
        scrollToEnd()
    }

    /** Adds a row that fills in as the person talks. */
    private fun startTurn() {
        dropTurn()
        val row = turnView("Listening…", null)
        liveQuestion = row.getChildAt(0) as TextView
        convoList.addView(row)
        convoScroll.visibility = View.VISIBLE
        scrollToEnd()
    }

    private fun dropTurn() {
        val q = liveQuestion ?: return
        convoList.removeView(q.parent as View)
        liveQuestion = null
        if (conversation.turns.isEmpty()) convoScroll.visibility = View.GONE
    }

    private fun scrollToEnd() {
        convoScroll.post { convoScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Speaks [text]; with [followUp], listens again straight after without the wake word. */
    private fun say(text: String, followUp: Boolean = false) {
        setState(State.SPEAKING)
        speech?.pause()
        val speed = prefs.speed
        val id = ++sayId
        thread {
            try {
                Orson.voice?.speak(text, speed)
            } catch (e: Exception) {
                Log.e(TAG, "speak", e)
            }
            main.post {
                val interrupted = id != sayId || state != State.SPEAKING
                if (followUp && !interrupted && prefs.followUp && !prefs.muted) listenForFollowUp() else if (!interrupted) resumeListening()
            }
        }
    }

    private fun listenForFollowUp() {
        Log.d(TAG, "follow-up listening")
        startTurn()
        setState(State.LISTENING)
        speech?.listenNow(FOLLOW_UP_MS)
    }

    /** A box to type a message, or to fix a misheard one. Orson stops listening while it's open. */
    private fun typeMessage(text: String) {
        if (state == State.LOADING || state == State.ERROR) return
        stopAll()
        wakeScreen()
        speech?.pause()
        var sent = false
        val field = Ui.field(this, "Type a message").apply {
            isSingleLine = false
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setText(text)
            setSelection(text.length)
        }
        val title = if (text.isEmpty()) "Type to Orson" else "Fix what I heard"
        val dialog = Ui.sheet(this, title, null, field) { dialog, body ->
            val send = {
                val message = field.text.toString().trim()
                if (message.isNotEmpty()) {
                    sent = true
                    dialog.dismiss()
                    startTurn()
                    handle(message, typed = true)
                }
            }
            field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            body.addView(Ui.wide(this, "Send") { send() })
            body.addView(Ui.wide(this, "Cancel") { dialog.dismiss() })
        }
        dialog.setOnDismissListener { if (!sent) resumeListening() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
    }

    /** Back to waiting for the wake word, with a short closing tone. */
    private fun endConversation() {
        if (prefs.beep) beep.startTone(ToneGenerator.TONE_PROP_NACK, 120)
        resumeListening()
    }

    // UI

    private fun buildUi() {
        rings = RingsView(this)
        infoView = Ui.text(this, size = 11f, color = Ui.MUTED)
        settingsButton = Ui.chip(this, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        statusView = Ui.serif(this, size = 16f).apply { gravity = Gravity.CENTER }
        convoList = Ui.column(this).apply {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setColor(Ui.HAIR); setSize(0, 1) }
        }
        convoScroll = Ui.CappedScroll(this).apply {
            background = Ui.rounded(Color.argb(205, 29, 32, 35), Ui.dp(context, 20).toFloat(), Ui.LINE, 1)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 2), Ui.dp(context, 14), Ui.dp(context, 2))
            isVerticalScrollBarEnabled = false
            addView(convoList)
        }

        talkButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = "Tap to talk"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.SOFT)
                setStroke(1, Color.argb(115, 147, 166, 220))
            }
            setOnClickListener { tapToTalk() }
        }
        typeButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = "Type a message"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.LIFT)
                setStroke(1, Ui.LINE)
            }
            setOnClickListener { typeMessage("") }
        }
        muteButton = Ui.pill(this, "Mute") { toggleMute() }
        stopButton = Ui.pill(this, "Stop") { stopAll() }
        repeatButton = Ui.pill(this, "Repeat") { repeatLast() }
        layOut()
        showConversation()
    }

    /** Puts the same views in place for the current orientation. */
    private fun layOut() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        listOf<View>(rings, infoView, settingsButton, statusView, convoScroll, talkButton, typeButton, muteButton, stopButton, repeatButton)
            .forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rings.centreX = if (landscape) .32f else .5f
        rings.centreY = if (landscape) .5f else .22f
        val metrics = resources.displayMetrics
        convoScroll.maxHeight = metrics.heightPixels * (if (landscape) 46 else 36) / 100

        val pad = Ui.dp(this, 16)
        val top = Ui.row(this,
            infoView.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) },
            settingsButton.apply { layoutParams = LinearLayout.LayoutParams(WRAP, Ui.dp(context, 36)) },
        )
        val words = Ui.column(this, spacing = 8).apply {
            addView(statusView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(convoScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val controls = Ui.track(this, listOf(stopButton, repeatButton, muteButton))
        val size = Ui.dp(this, if (landscape) 60 else 76)
        val small = size * 3 / 5
        // Mic in the middle, keyboard to its right.
        val micRow = FrameLayout(this).apply {
            addView(talkButton, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(typeButton, FrameLayout.LayoutParams(small, small, Gravity.CENTER).apply { leftMargin = size + Ui.dp(context, 36) })
        }

        val ui = Ui.column(this, spacing = 12).apply {
            setPadding(pad, pad, pad, pad)
            addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(words, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(micRow, LinearLayout.LayoutParams(MATCH, size))
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // Fades the rings out behind the words.
        val fade = View(this).apply {
            background = GradientDrawable(
                if (landscape) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(225, 21, 23, 25), Ui.BG, Ui.BG),
            )
        }
        setContentView(FrameLayout(this).apply {
            addView(rings, FrameLayout.LayoutParams(MATCH, MATCH))
            if (landscape) {
                addView(fade, FrameLayout.LayoutParams(metrics.widthPixels / 2, MATCH, Gravity.END))
                addView(ui, FrameLayout.LayoutParams(metrics.widthPixels * 2 / 5, MATCH, Gravity.END))
            } else {
                addView(fade, FrameLayout.LayoutParams(MATCH, metrics.heightPixels * 3 / 5, Gravity.BOTTOM))
                addView(ui, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        })
    }

    private fun setState(s: State) {
        state = s
        val wake = prefs.wake.label
        statusView.text = when (s) {
            State.LOADING -> "Starting…"
            State.IDLE -> "Say “$wake”"
            State.READY -> "Tap to talk"
            State.LISTENING -> "Listening…"
            State.THINKING -> "Thinking…"
            State.SPEAKING -> "Orson"
            State.MUTED -> "Mic is off"
            State.ERROR -> "Something's wrong"
        }
        rings.mode = when (s) {
            State.LISTENING -> RingsView.Mode.LISTENING
            State.THINKING -> RingsView.Mode.THINKING
            State.SPEAKING -> RingsView.Mode.SPEAKING
            State.MUTED, State.ERROR -> RingsView.Mode.MUTED
            else -> RingsView.Mode.IDLE
        }
        statusView.setTextColor(if (s == State.MUTED || s == State.ERROR) Ui.DANGER else Ui.TEXT)
    }

    /** Sample conversation for screenshots; shown until restart, never saved. */
    private fun showDemo() {
        demo = true
        convoList.removeAllViews()
        DEMO.forEach { (q, a) -> convoList.addView(turnView(q, a)) }
        convoScroll.visibility = View.VISIBLE
        scrollToEnd()
        updateInfo()
    }

    private fun updateInfo() {
        if (demo) { infoView.text = "Wi-Fi: Home\nOrmem connected · 100%"; return }
        val net = wifi.current()?.let { "Wi-Fi: $it" } ?: "No Wi-Fi"
        val ormem = when (link) {
            Link.OK -> "Ormem connected"
            Link.NO_KEY -> "Ormem not set up"
            Link.BAD_KEY -> "Ormem key rejected"
            Link.OFFLINE -> "Ormem unreachable"
            Link.UNKNOWN -> "Ormem…"
        }
        infoView.text = "$net\n$ormem · ${Orson.battery(this)}"
    }

    private fun showError(message: String) {
        setState(State.ERROR)
        statusView.text = message
    }

    // Screen dimming

    private fun wakeScreen() {
        lastActivity = System.currentTimeMillis()
        if (dimmed || window.attributes.screenBrightness != targetBrightness()) setBrightness(targetBrightness())
        dimmed = false
    }

    private fun maybeDim() {
        val after = prefs.dimAfter
        if (after == 0 || dimmed || state == State.LISTENING || state == State.THINKING || state == State.SPEAKING) return
        if (System.currentTimeMillis() - lastActivity > after * 60_000L) {
            setBrightness(0.02f)
            dimmed = true
        }
    }

    private fun targetBrightness() =
        prefs.brightness.takeIf { it >= 0 } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    companion object {
        private const val TAG = "Orson"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_TURNS = 12
        private val DEMO = listOf(
            "My sister's birthday is on June fifth." to "Got it. Your sister's birthday is June 5.",
            "I parked on level two, spot B14." to "Noted. Level 2, spot B14.",
            "Where did I park?" to "On level 2, in spot B14.",
            "When's my sister's birthday?" to "June 5. That's in three weeks.",
        )
        private const val SESSION_GAP_MS = 5 * 60_000L
        private val STOP = setOf("stop", "stop talking", "be quiet", "cancel", "never mind", "nevermind", "shut up", "thats enough")
        private val LOUDER = setOf("louder", "speak louder", "a bit louder", "speak a bit louder", "volume up", "turn it up", "turn up the volume")
        private val QUIETER = setOf("quieter", "softer", "speak quieter", "speak softer", "a bit quieter", "volume down", "turn it down", "turn down the volume")
        private val REPEAT = setOf("repeat", "repeat that", "say that again", "come again", "what did you say", "pardon")
        private val MUTE = setOf("mute", "mute the mic", "mute yourself", "stop listening")
        private val UNMUTE = setOf("unmute", "unmute the mic", "start listening")
        private val GOODBYE = setOf(
            "bye", "goodbye", "bye bye", "bye orson", "goodbye orson", "thanks", "thank you", "thanks orson",
            "thank you orson", "thats all", "thats it", "that is all", "im done", "were done", "done", "nothing", "no thanks",
        )
        /** How long Orson waits for a follow-up before going back to the wake word. */
        private const val FOLLOW_UP_MS = 7_000
    }
}
