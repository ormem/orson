package app.ormem.orson

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Ormem's tree rings, from the website's hero animation, drawn on the GPU and reacting to the voice like a circular equaliser. */
class RingsView(context: Context) : GLSurfaceView(context) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, MUTED }

    @Volatile var mode = Mode.IDLE

    /** Where the pith sits, as a fraction of the screen. */
    @Volatile var centreX = .5f
    @Volatile var centreY = .36f

    /** Latest voice reading. Any thread. */
    @Volatile var voice = Speech.Voiceprint(FloatArray(Speech.BANDS), 0f, 0f)

    init {
        setEGLContextClientVersion(2)
        setRenderer(Renderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private val shown = FloatArray(Speech.BANDS)
        private val bandBytes = ByteBuffer.allocateDirect(Speech.BANDS)
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private var program = 0
        private var texture = 0
        private var width = 1f
        private var height = 1f
        private var pitch = 0f
        private var level = 0f
        private var pull = 0f
        private var dim = 0f
        private var flow = 1f
        private var grow = 0f
        private var lastFrame = 0L
        private val started = System.nanoTime()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VERTEX, FRAGMENT)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            for ((name, value) in listOf(
                GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR,
                GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
                GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE,
                GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE,
            )) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, name, value)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            width = w.toFloat()
            height = h.toFloat()
        }

        private fun step(dt: Float) {
            val v = voice
            val m = mode
            val active = m == Mode.LISTENING
            val targetLevel = if (m == Mode.SPEAKING) Orson.speakingLevel else if (active) v.level else 0f
            if (active && v.level > .02f) pitch += (v.pitch - pitch) * min(1f, 12 * dt)
            level += (targetLevel - level) * min(1f, 14 * dt)
            for (b in shown.indices) {
                val target = if (active) v.bands[b] else 0f
                val rate = if (target > shown[b]) 26f else 5f // fast rise, slow fall
                shown[b] += (target - shown[b]) * min(1f, rate * dt)
            }
            val ease = min(1f, 3 * dt)
            pull += ((if (m == Mode.THINKING) 1f else 0f) - pull) * ease
            dim += ((if (m == Mode.MUTED) 1f else 0f) - dim) * ease
            flow += ((when (m) { Mode.MUTED -> 0f; Mode.THINKING -> .4f; else -> 1f }) - flow) * ease
            grow += dt / SECONDS_PER_RING * flow
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0f else min(.1f, (now - lastFrame) / 1e9f)
            lastFrame = now
            step(dt)

            bandBytes.position(0)
            for (v in shown) bandBytes.put((v.coerceIn(0f, 1f) * 255).toInt().toByte())
            bandBytes.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, Speech.BANDS, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bandBytes)

            GLES20.glUseProgram(program)
            fun set(name: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), v)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "res"), width, height)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "centre"), centreX, centreY)
            set("time", ((now - started) / 1_000_000 % 3_600_000) / 1000f)
            set("whole", floor(grow))
            set("shift", grow - floor(grow))
            set("pull", pull)
            set("dim", dim)
            set("pitch", pitch)
            set("level", level)
            set("speaking", if (mode == Mode.SPEAKING) 1f else 0f)
            set("px", resources.displayMetrics.density / 2)

            val pos = GLES20.glGetAttribLocation(program, "pos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun link(vertex: String, fragment: String): Int {
            val id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(id)
            return id
        }

        private fun compile(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("Orson", "shader: " + GLES20.glGetShaderInfoLog(id))
            return id
        }
    }

    private companion object {
        const val SECONDS_PER_RING = 6f

        fun hash(n: Double): Double {
            val x = sin(n * 127.1 + 311.7) * 43758.5453
            return x - floor(x)
        }

        // The site's slow waves, as constants baked into the shader.
        val WAVES = listOf(2 to .50, 3 to .34, 4 to .22, 5 to .14, 7 to .08, 11 to .035).mapIndexed { i, (k, a) ->
            "n += $a * sin($k.0 * a + ${hash(i + 1.0) * 6.2831853} + t * ${1.5 + hash(i + 9.0) * 4});"
        }.joinToString("\n")

        const val VERTEX = """
            attribute vec2 pos;
            void main() { gl_Position = vec4(pos, 0.0, 1.0); }
        """

        // One pass per pixel: unbend the wood, find the nearest rings, shade them.
        val FRAGMENT = """
            precision highp float;
            uniform vec2 res, centre;
            uniform float time, whole, shift, pull, dim, pitch, level, speaking, px;
            uniform sampler2D bands;
            const float RINGS = 24.0;

            float hash(float n) { return fract(sin(n * 127.1 + 311.7) * 43758.5453); }

            void main() {
                vec2 p = vec2(gl_FragCoord.x, res.y - gl_FragCoord.y);
                float rmax = max(res.x, res.y) * 0.62;
                vec2 c = res * centre;
                vec2 d = p - c;
                float r0 = length(d);
                d -= vec2(0.07, -0.04) * r0;                       // off-centre pith
                float r = length(d);
                float a = atan(d.y, d.x);
                float t = r / rmax;

                float n = 0.0;
                $WAVES
                float warp = 1.0 + (0.022 + 0.04 * t) * n + 0.16 * exp(-t * 5.0) * cos(2.0 * (a - 0.45));
                float q = r / warp;
                q /= 1.0 - pull * 0.22 * (1.0 + 0.5 * sin(time * 2.4 + t * 9.0));

                float u = pow(max(q, 0.0) / (rmax * 0.96), 1.0 / 0.86);
                float e = texture2D(bands, vec2(clamp(u, 0.0, 1.0), 0.5)).r;
                float wave = pow(max(0.0, sin(u * 15.0 - time * 7.0)), 3.0) * level * 1.1;
                e = max(e, speaking * wave);
                float k = 4.0 + floor(pitch * 14.0 + 0.5);         // higher pitch, tighter ripples
                q /= 1.0 + e * 0.1 + e * 0.085 * sin(k * a + time * 9.0 + u * 17.0);
                u = pow(max(q, 0.0) / (rmax * 0.96), 1.0 / 0.86);

                float base = 0.62 * (1.0 - dim * 0.65);
                float ink = 0.0;
                float j0 = floor(u * RINGS - shift + 0.5);
                for (int i = -1; i <= 1; i++) {
                    float j = j0 + float(i);
                    float id = j - whole;
                    float uj = (j + shift) / RINGS;
                    if (uj <= 0.0) continue;
                    float rj = rmax * 0.96 * pow(uj, 0.86) + (hash(id) - 0.5) * (rmax / RINGS) * 0.4;
                    bool late = hash(id + 0.5) > 0.72;
                    float w = (late ? 3.0 + hash(id + 2.0) * 1.5 : 1.4 + hash(id + 3.0)) * (1.0 + e * 1.3) * px * 2.0;
                    float dist = abs(q - rj) * warp;
                    float line = 1.0 - smoothstep(w * 0.5 - 0.8, w * 0.5 + 0.8, dist);
                    float fade = smoothstep(0.0, 0.1, uj) * (1.0 - smoothstep(0.72, 1.0, uj));
                    ink = max(ink, line * min(1.0, base * fade * (late ? 1.15 : 0.8) * (1.0 + e * 1.7)));
                }

                // Core, after the logo's tilted seed.
                float pulse = 1.0 + level * 0.9 + 0.06 * sin(time * 1.2);
                vec2 o = p - c;
                float cs = cos(0.45), sn = sin(0.45);
                vec2 e2 = vec2(o.x * cs + o.y * sn, -o.x * sn + o.y * cs) / (rmax * pulse * vec2(0.014, 0.022));
                ink = max(ink, (1.0 - smoothstep(0.9, 1.1, length(e2))) * 0.75 * (1.0 - dim * 0.5));

                vec3 paper = vec3(0.082, 0.090, 0.098);
                vec3 accent = vec3(0.576, 0.651, 0.863);
                gl_FragColor = vec4(mix(paper, accent, ink), 1.0);
            }
        """
    }
}
