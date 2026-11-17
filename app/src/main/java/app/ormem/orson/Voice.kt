package app.ormem.orson

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** Offline text-to-speech with a Piper voice. [speak] blocks until playback ends. */
class Voice(context: Context) {

    private val tts = OfflineTts(
        context.assets,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "tts/model.onnx",
                    tokens = "tts/tokens.txt",
                    dataDir = copyToFiles(context, "tts/espeak-ng-data"),
                ),
                numThreads = 4,
            )
        )
    )

    @Volatile private var stopped = false

    init {
        // First use is slow, so do it at startup.
        tts.generateWithCallback("Ready.", callback = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray) = 1
        })
    }

    fun stop() {
        stopped = true
    }

    /** Plays each sentence as soon as it's ready, instead of waiting for the whole answer. */
    fun speak(text: String, speed: Float = 1.0f) {
        stopped = false
        val rate = tts.sampleRate()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(rate * 4)
            .build()
        val started = System.currentTimeMillis()
        var first = true
        var written = 0L
        Orson.speaking = true
        try {
            track.play()
            // A real object, not a lambda: the native code looks up invoke([F)Integer by name.
            val play = object : (FloatArray) -> Int {
                override fun invoke(samples: FloatArray): Int {
                    if (first) {
                        first = false
                        android.util.Log.d("Orson", "first audio after ${System.currentTimeMillis() - started} ms")
                    }
                    var at = 0
                    while (at < samples.size && !stopped) {
                        val n = minOf(CHUNK, samples.size - at)
                        Orson.speakingLevel = loudness(samples, at)
                        track.write(samples, at, n, AudioTrack.WRITE_BLOCKING)
                        at += n
                    }
                    written += samples.size
                    return if (stopped) 0 else 1
                }
            }
            for (part in phrases(text)) {
                if (stopped) break
                tts.generateWithCallback(part, speed = speed, callback = play)
            }
            // This phone resets the play position on stop(), so measure what's left before stopping.
            val played = track.playbackHeadPosition.toLong()
            // stop() plays what's left; a short reply never fills the buffer, so it wouldn't start otherwise.
            if (!stopped) track.stop()
            val leftMs = (written - played).coerceAtLeast(0) * 1000 / rate + TAIL_MS
            val until = System.currentTimeMillis() + leftMs
            while (!stopped && System.currentTimeMillis() < until) {
                Orson.speakingLevel = .2f
                Thread.sleep(20)
            }
            android.util.Log.d("Orson", "voice done: waited $leftMs ms for the last ${written - played} samples")
        } finally {
            Orson.speakingLevel = 0f
            Orson.speaking = false
            if (stopped) {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    /** Splits at natural pauses, so the first words are ready sooner. */
    private fun phrases(text: String): List<String> {
        val parts = mutableListOf<String>()
        for (piece in text.split(Regex("(?<=[,;:.!?])\\s+"))) {
            if (parts.isNotEmpty() && parts.last().length < MIN_PHRASE) parts[parts.lastIndex] += " $piece" else parts += piece
        }
        return parts
    }

    private fun loudness(samples: FloatArray, at: Int): Float {
        var peak = 0f
        for (i in at until minOf(at + 1000, samples.size)) peak = maxOf(peak, kotlin.math.abs(samples[i]))
        return minOf(1f, peak * 1.5f)
    }

    fun release() = tts.release()

    private companion object {
        const val CHUNK = 2048
        const val MIN_PHRASE = 24
        /** Speaker latency after the last sample. */
        const val TAIL_MS = 250

        /** espeak-ng needs real files, not assets. Copies once and returns the folder path. */
        fun copyToFiles(context: Context, path: String): String {
            val target = File(context.filesDir, path)
            if (!File(target, ".done").exists()) {
                copyAssetDir(context.assets, path, target)
                File(target, ".done").createNewFile()
            }
            return target.absolutePath
        }

        fun copyAssetDir(assets: AssetManager, path: String, target: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
                return
            }
            target.mkdirs()
            children.forEach { copyAssetDir(assets, "$path/$it", File(target, it)) }
        }
    }
}
