package app.ormem.orson

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Offline listening with sherpa-onnx: wake word, live captions while you talk, then Whisper for the final text. */
/** [modelDir] may hold larger models copied on at setup; Parakeet there replaces the built-in Whisper. */
class Speech(private val assets: AssetManager, private val events: Events, private val modelDir: java.io.File? = null) {

    interface Events {
        fun onReady()
        /** The person stopped talking; their words are being written out. */
        fun onHeard()
        fun onWake(keyword: String)
        fun onPartial(text: String)
        fun onRequest(text: String)
        fun onError(message: String)
        fun onVoice(print: Voiceprint)
    }

    /** One reading of the voice: energy per band (low to high), pitch 0..1 and level 0..1. */
    class Voiceprint(val bands: FloatArray, val pitch: Float, val level: Float)

    private enum class Mode { WAKE, REQUEST, PAUSED }

    @Volatile private var mode = Mode.WAKE
    @Volatile private var running = false
    @Volatile private var tapped = false
    @Volatile private var waitMs = 6_000
    @Volatile var gain = 2.5f
    private var worker: Thread? = null

    fun start(wake: WakeWord, sensitivity: Sensitivity, endSilence: Float, paused: Boolean = false) {
        running = true
        mode = if (paused) Mode.PAUSED else Mode.WAKE
        worker = thread(name = "orson-speech") {
            try {
                loop(wake, sensitivity, endSilence)
            } catch (e: Exception) {
                events.onError(e.message ?: e.toString())
            }
        }
    }

    /** Stops and waits for the mic to be released. */
    fun stop() {
        running = false
        worker?.join(2_000)
    }

    fun listenForWake() {
        mode = Mode.WAKE
    }

    fun pause() {
        mode = Mode.PAUSED
    }

    /** Starts hearing a request now, without the wake word; gives up after [wait] ms of no speech. */
    fun listenNow(wait: Int = 6_000) {
        waitMs = wait
        tapped = true
    }

    @SuppressLint("MissingPermission")
    private fun loop(wake: WakeWord, sensitivity: Sensitivity, endSilence: Float) {
        // A trained model where we have one, else the general keyword spotter.
        val model = wake.modelFile?.let { runCatching { WakeModel(assets, it) }.getOrNull() }
        val kws = if (model == null) KeywordSpotter(assets, kwsConfig(wake.keywordsFile, sensitivity.threshold)) else null
        val asr = OnlineRecognizer(assets, asrConfig(endSilence))
        val final = finalRecognizer()
        val kwsStream = kws?.createStream()
        var request: OnlineStream? = null
        var lastPartial = ""
        // The wake word is spotted ~0.5 s after it ends, so replay recent audio to catch the request's first words.
        val recent = ArrayDeque<FloatArray>()
        var noise = .01f
        val talk = Talk()
        val requestAudio = ArrayList<FloatArray>()

        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val mic = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4)
        )
        mic.startRecording()
        events.onReady()

        val buf = ShortArray(CHUNK)
        while (running) {
            val n = mic.read(buf, 0, buf.size)
            if (n <= 0) continue
            val samples = FloatArray(n) { (buf[it] * gain / 32768f).coerceIn(-1f, 1f) }
            recent.addLast(samples)
            if (recent.size > CATCH_UP_CHUNKS) recent.removeFirst()
            val rms = sqrt(samples.sumOf { (it * it).toDouble() }.toFloat() / samples.size)

            if (tapped) {
                tapped = false
                request?.release()
                request = asr.createStream()
                requestAudio.clear()
                lastPartial = ""
                talk.start()
                mode = Mode.REQUEST
            }

            // Only learn the room's noise while waiting, never from Orson's own voice.
            if (mode != Mode.REQUEST && !Orson.speaking) noise = noise * .97f + rms * .03f

            when (mode) {
                Mode.WAKE -> {
                    var heard: String? = null
                    if (model != null) {
                        val score = model.feed(samples)
                        if (score > .1f) android.util.Log.d("Orson", "wake score: $score")
                        if (score >= sensitivity.score) {
                            model.reset()
                            heard = wake.name
                        }
                    } else if (kws != null && kwsStream != null) {
                        kwsStream.acceptWaveform(samples, RATE)
                        while (heard == null && kws.isReady(kwsStream)) {
                            kws.decode(kwsStream)
                            heard = kws.getResult(kwsStream).keyword.ifEmpty { null }
                        }
                        if (heard != null) kws.reset(kwsStream)
                    }
                    if (heard != null) {
                        request?.release()
                        // The trained model fires as the word ends; the keyword spotter about half a second later.
                        val replay = if (model != null) recent.toList().takeLast(2) else recent.toList()
                        request = asr.createStream().also { s -> replay.forEach { s.acceptWaveform(it, RATE) } }
                        requestAudio.clear()
                        requestAudio.addAll(replay)
                        talk.start()
                        waitMs = 6_000
                        lastPartial = ""
                        mode = Mode.REQUEST
                        events.onWake(heard)
                    }
                }
                Mode.REQUEST -> {
                    val s = request ?: continue
                    s.acceptWaveform(samples, RATE)
                    requestAudio.add(samples)
                    events.onVoice(analyse(samples))
                    while (asr.isReady(s)) asr.decode(s)
                    val text = asr.getResult(s).text.trim()
                    if (text != lastPartial) {
                        lastPartial = text
                        events.onPartial(text)
                    }
                    val quiet = talk.step(rms, noise, endSilence, waitMs)
                    if (asr.isEndpoint(s) || quiet) {
                        mode = Mode.PAUSED
                        val spoke = text.isNotEmpty() || talk.spoke
                        if (spoke) events.onHeard()
                        events.onRequest(if (!spoke) "" else transcribe(final, requestAudio))
                    }
                }
                Mode.PAUSED -> Unit
            }
        }
        mic.stop()
        mic.release()
    }

    /** Ends a request on loudness alone, for rooms where the recognizer never hears silence. */
    private class Talk {
        var spoke = false
        private var quietMs = 0
        private var totalMs = 0

        fun start() {
            spoke = false
            quietMs = 0
            totalMs = 0
        }

        /** True once the person has paused long enough, never started, or talked too long. */
        fun step(rms: Float, noise: Float, endSilence: Float, waitMs: Int): Boolean {
            totalMs += CHUNK_MS
            if (rms > maxOf(noise * 2.5f, .015f)) {
                spoke = true
                quietMs = 0
            } else {
                quietMs += CHUNK_MS
            }
            return (spoke && quietMs >= endSilence * 1000) || (!spoke && totalMs >= waitMs) || totalMs >= MAX_REQUEST_MS
        }
    }

    /** Measures the energy at each band's frequency (Goertzel). */
    private fun analyse(samples: FloatArray): Voiceprint {
        val bands = FloatArray(BANDS)
        var peak = 0
        for (b in 0 until BANDS) {
            val coeff = coeffs[b]
            var s1 = 0f
            var s2 = 0f
            for (x in samples) {
                val s0 = x + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            bands[b] = min(1f, sqrt(sqrt(max(power, 0f))) * 2f / sqrt(samples.size.toFloat()))
            if (bands[b] > bands[peak]) peak = b
        }
        val level = min(1f, sqrt(samples.sumOf { (it * it).toDouble() }.toFloat() / samples.size) * 4f)
        return Voiceprint(bands, peak / (BANDS - 1f), level)
    }

    private fun transcribe(recognizer: OfflineRecognizer, chunks: List<FloatArray>): String {
        val audio = FloatArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { it.copyInto(audio, at); at += it.size }
        val stream = recognizer.createStream()
        stream.acceptWaveform(audio, RATE)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        return text
    }

    private fun kwsConfig(keywordsFile: String, threshold: Float) = KeywordSpotterConfig(
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig("kws/encoder.onnx", "kws/decoder.onnx", "kws/joiner.onnx"),
            tokens = "kws/tokens.txt",
            modelType = "zipformer2",
        ),
        keywordsFile = keywordsFile,
        keywordsScore = 2.0f,
        keywordsThreshold = threshold,
    )

    /** Parakeet if it was copied onto the phone, else the built-in Whisper. */
    private fun finalRecognizer(): OfflineRecognizer {
        val dir = modelDir?.let { java.io.File(it, "parakeet") }
        if (dir != null && java.io.File(dir, "encoder.int8.onnx").exists()) {
            val parakeet = runCatching {
                OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig("$dir/encoder.int8.onnx", "$dir/decoder.int8.onnx", "$dir/joiner.int8.onnx"),
                    tokens = "$dir/tokens.txt",
                    modelType = "nemo_transducer",
                    numThreads = 4,
                )))
            }.getOrNull()
            if (parakeet != null) return parakeet
        }
        return OfflineRecognizer(assets, whisperConfig())
    }

    private fun whisperConfig() = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(encoder = "whisper/encoder.onnx", decoder = "whisper/decoder.onnx"),
            tokens = "whisper/tokens.txt",
            modelType = "whisper",
            numThreads = 4,
        ),
    )

    private fun asrConfig(endSilence: Float) = OnlineRecognizerConfig(
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig("asr/encoder.onnx", "asr/decoder.onnx", "asr/joiner.onnx"),
            tokens = "asr/tokens.txt",
            modelType = "zipformer2",
            numThreads = 2,
        ),
        // Give up after 5s of silence; end the request after endSilence.
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 5.0f, 0.0f),
            rule2 = EndpointRule(true, endSilence, 0.0f),
            rule3 = EndpointRule(false, 0.0f, MAX_REQUEST_MS / 1000f),
        ),
    )

    companion object {
        const val RATE = 16000
        const val BANDS = 24
        private const val LOW_HZ = 90.0
        private const val HIGH_HZ = 3200.0
        private val coeffs = FloatArray(BANDS) {
            val hz = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(it / (BANDS - 1.0))
            (2 * cos(2 * PI * hz / RATE)).toFloat()
        }
        private const val CHUNK = 1600 // 100 ms
        private const val CATCH_UP_CHUNKS = 8
        private const val CHUNK_MS = 100
        /** Longest request, including the wait before speaking. */
        private const val MAX_REQUEST_MS = 60_000
    }
}
