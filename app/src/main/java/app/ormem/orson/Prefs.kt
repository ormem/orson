package app.ormem.orson

import android.content.Context

/** Everything the device remembers about its setup. */
class Prefs(context: Context) {

    private val p = context.getSharedPreferences("orson", Context.MODE_PRIVATE)

    /** The Ormem API key, stored encrypted. An older plain-text key is encrypted on first read. */
    var key: String?
        get() {
            p.getString("key", null)?.let { plain ->
                key = plain
                return plain
            }
            return p.getString("key_sealed", null)?.let { KeyVault.decrypt(it) }
        }
        set(v) {
            p.edit().remove("key").apply {
                if (v.isNullOrBlank()) remove("key_sealed") else putString("key_sealed", KeyVault.encrypt(v))
            }.commit()
        }

    var name: String?
        get() = p.getString("name", null)
        set(v) = p.edit().putString("name", v).apply()

    /** Ormem user ID; defaults to the name. */
    var owner: String
        get() = p.getString("owner", null) ?: name?.lowercase()?.replace(Regex("\\s+"), "_") ?: "me"
        set(v) = p.edit().putString("owner", v).apply()

    var wake: WakeWord
        get() = WakeWord.entries.firstOrNull { it.name == p.getString("wake", null) } ?: WakeWord.ORSON
        set(v) = p.edit().putString("wake", v.name).apply()

    var sensitivity: Sensitivity
        get() = Sensitivity.entries.firstOrNull { it.name == p.getString("sensitivity", null) } ?: Sensitivity.NORMAL
        set(v) = p.edit().putString("sensitivity", v.name).apply()

    var speed: Float
        get() = p.getFloat("speed", 1.0f)
        set(v) = p.edit().putFloat("speed", v).apply()

    /** 0..1, or -1 to follow the system. */
    var brightness: Float
        get() = p.getFloat("brightness", -1f)
        set(v) = p.edit().putFloat("brightness", v).apply()

    /** Minutes of quiet before the screen dims; 0 = never. */
    var dimAfter: Int
        get() = p.getInt("dim_after", 5)
        set(v) = p.edit().putInt("dim_after", v).apply()

    var pin: String?
        get() = p.getString("pin", null)
        set(v) = p.edit().putString("pin", v).apply()

    var muted: Boolean
        get() = p.getBoolean("muted", false)
        set(v) = p.edit().putBoolean("muted", v).apply()

    /** Off means Tap to talk only. */
    var wakeOn: Boolean
        get() = p.getBoolean("wake_on", true)
        set(v) = p.edit().putBoolean("wake_on", v).apply()

    /** Software mic boost; the phone's recognition input is quiet. */
    var gain: Float
        get() = p.getFloat("gain", 2.5f)
        set(v) = p.edit().putFloat("gain", v).apply()

    /** Seconds of silence that end a request. */
    var endSilence: Float
        get() = p.getFloat("end_silence", 1.0f)
        set(v) = p.edit().putFloat("end_silence", v).apply()

    var beep: Boolean
        get() = p.getBoolean("beep", true)
        set(v) = p.edit().putBoolean("beep", v).apply()

    /** Keep listening after a reply, without the wake word. */
    var followUp: Boolean
        get() = p.getBoolean("follow_up", true)
        set(v) = p.edit().putBoolean("follow_up", v).apply()

    var captions: Boolean
        get() = p.getBoolean("captions", true)
        set(v) = p.edit().putBoolean("captions", v).apply()

    /** True while unpinned so the phone can be serviced. */
    var maintenance: Boolean
        get() = p.getBoolean("maintenance", false)
        set(v) = p.edit().putBoolean("maintenance", v).apply()
}

/** Wake words; each has a keyword list at assets/kws/<name>.txt in the model's token format. */
/** [heardAs] is how Whisper tends to write the wake word, so it can be dropped from the request. */
enum class WakeWord(val label: String, val modelFile: String? = null, val heardAs: List<String> = emptyList()) {
    ORSON("Orson", "wake/orson.onnx", listOf("awesome", "awson", "orsen", "or son", "arson", "ourson")),
    ORMUS("Ormus"),
    ORACLE("Hey Oracle"),
    MEMORY("Hey Memory"),
    COMPUTER("Hey Computer"),
    ATLAS("Hey Atlas");

    val keywordsFile get() = "kws/${name.lowercase()}.txt"
}

/** [threshold] is for the keyword spotter, [score] for trained models; lower wakes more easily. */
enum class Sensitivity(val label: String, val threshold: Float, val score: Float) {
    LOW("Low", 0.25f, 0.7f),
    NORMAL("Normal", 0.15f, 0.5f),
    HIGH("High", 0.08f, 0.3f),
}
