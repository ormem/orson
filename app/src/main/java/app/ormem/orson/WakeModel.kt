package app.ormem.orson

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.res.AssetManager
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A wake word model from livekit-wakeword: one streaming ONNX file that takes 80 ms of audio
 * plus its own state, and returns a score and the next state.
 */
class WakeModel(assets: AssetManager, modelFile: String) {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        assets.open(modelFile).use { it.readBytes() },
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) },
    )
    private val stateNames = session.inputNames.filter { it != "chunk" }
    private val pending = ArrayList<Float>(STEP * 2)
    private var state: Map<String, OnnxTensor> = stateNames.associateWith { zeros(it) }
    private val warm: Map<String, OnnxTensor>

    init {
        // Start from the state left by silence, as in training.
        repeat(WARMUP_STEPS) { step(FloatArray(STEP)) }
        warm = state
    }

    /** Feeds samples (-1..1) and returns the highest wake score seen, 0..1. */
    fun feed(samples: FloatArray): Float {
        var best = 0f
        for (s in samples) pending.add(s)
        while (pending.size >= STEP) {
            best = maxOf(best, step(FloatArray(STEP) { pending[it] }))
            pending.subList(0, STEP).clear()
        }
        return best
    }

    /** Forgets recent speech so one wake word can't fire twice. */
    fun reset() {
        if (state !== warm) state.values.forEach { it.close() }
        state = warm
        pending.clear()
    }

    private fun step(chunk: FloatArray): Float {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, STEP.toLong()))
        val result = session.run(state + ("chunk" to input))
        input.close()
        val score = (result[0] as OnnxTensor).floatBuffer.get(0)
        val next = stateNames.associateWith { name ->
            val out = result.get("${name}_out").get() as OnnxTensor
            copy(out)
        }
        result.close()
        if (state !== warm) state.values.forEach { it.close() }
        state = next
        return score
    }

    private fun info(name: String) = session.inputInfo.getValue(name).info as TensorInfo

    private fun zeros(name: String): OnnxTensor {
        val info = info(name)
        val shape = info.shape.map { if (it < 0) 1L else it }.toLongArray()
        val size = shape.fold(1L) { a, b -> a * b }.toInt()
        return if (info.type == OnnxJavaType.INT64) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(size)), shape)
        else OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(size)), shape)
    }

    /** Results are freed with their run, so keep a copy for the next step. */
    private fun copy(t: OnnxTensor): OnnxTensor {
        val shape = (t.info as TensorInfo).shape
        return if ((t.info as TensorInfo).type == OnnxJavaType.INT64) {
            val b = t.longBuffer
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(b.remaining()).also { b.get(it) }), shape)
        } else {
            val b = t.floatBuffer
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(b.remaining()).also { b.get(it) }), shape)
        }
    }

    private companion object {
        const val STEP = 1280 // 80 ms
        const val WARMUP_STEPS = 25 // 2 s of silence
    }
}
