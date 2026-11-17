package app.ormem.orson

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.concurrent.thread

/** Reads an Ormem key from a QR code with the back camera. */
@Suppress("DEPRECATION")
class ScanActivity : Activity(), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private var holder: SurfaceHolder? = null
    @Volatile private var busy = false
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = SurfaceView(this).apply { holder.addCallback(this@ScanActivity) }
        val pad = Ui.dp(this, 16)
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(preview, FrameLayout.LayoutParams(fill, fill))
            addView(Ui.column(context, spacing = 10).apply {
                background = Ui.rounded(Ui.GLASS, Ui.dp(context, 22).toFloat(), Ui.HAIR, 1)
                setPadding(pad, pad, pad, pad)
                addView(Ui.serif(context, "Scan your key", 20f))
                addView(Ui.text(context, "Point the camera at the QR code on your Ormem Account page.", 14f, Ui.MUTED))
                addView(Ui.wide(context, "Cancel") { finish() })
            }, FrameLayout.LayoutParams(fill, wrap, Gravity.BOTTOM).apply { setMargins(pad, pad, pad, pad) })
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else finish()
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        holder = h
        start()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(h: SurfaceHolder) {
        holder = null
        stop()
    }

    override fun onPause() {
        stop()
        super.onPause()
    }

    private fun start() {
        val h = holder ?: return
        if (camera != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val cam = runCatching { Camera.open() }.getOrNull() ?: return finish()
        camera = cam
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
        cam.setDisplayOrientation(rotation)
        val params = cam.parameters
        if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in params.supportedFocusModes) {
            params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
        }
        cam.parameters = params
        cam.setPreviewDisplay(h)
        cam.setPreviewCallback { data, c -> if (!busy && !done) decode(data, c.parameters.previewSize) }
        cam.startPreview()
    }

    private fun stop() {
        camera?.run {
            setPreviewCallback(null)
            stopPreview()
            release()
        }
        camera = null
    }

    private fun decode(data: ByteArray, size: Camera.Size) {
        busy = true
        thread {
            val text = runCatching {
                val source = PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false)
                QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text.trim()
            }.getOrNull()
            busy = false
            if (text != null && text.startsWith("om_") && !done) {
                done = true
                Prefs(this).key = text
                runOnUiThread { finish() }
            }
        }
    }
}
