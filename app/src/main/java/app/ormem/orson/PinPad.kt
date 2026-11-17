package app.ormem.orson

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen number pad. Calls [onDone] as soon as [length] digits are in; return false to reject and clear. */
class PinPad(
    c: Context,
    title: String,
    private val length: Int,
    private val onDone: (String) -> Boolean,
    onCancel: () -> Unit,
) : LinearLayout(c) {

    private val entered = StringBuilder()
    private val dots = LinearLayout(c).apply { gravity = Gravity.CENTER }
    private val titleView = Ui.serif(c, title, 26f).apply { gravity = Gravity.CENTER }
    private val note = Ui.text(c, " ", 14f, Ui.DANGER).apply { gravity = Gravity.CENTER }

    init {
        setBackgroundColor(Ui.BG)
        gravity = Gravity.CENTER
        val landscape = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        orientation = if (landscape) HORIZONTAL else VERTICAL
        val pad = Ui.dp(c, 24)
        setPadding(pad, pad, pad, pad)

        val head = Ui.column(c, spacing = 18).apply {
            gravity = Gravity.CENTER
            addView(titleView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(dots)
            addView(note, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val keys = GridLayout(c).apply {
            columnCount = 3
            for (label in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Cancel", "0", "⌫")) {
                addView(key(c, label) {
                    when (label) {
                        "Cancel" -> onCancel()
                        "⌫" -> if (entered.isNotEmpty()) { entered.deleteCharAt(entered.lastIndex); paint() }
                        else -> press(label)
                    }
                })
            }
        }
        if (landscape) {
            addView(head, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(keys)
        } else {
            addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 36) })
            addView(keys, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        paint()
    }

    private fun key(c: Context, label: String, onClick: () -> Unit): Button {
        val digit = label.length == 1 && label[0].isDigit()
        val size = Ui.dp(c, if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 58 else 74)
        return Button(c).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            textSize = if (digit) 26f else 14f
            typeface = if (digit) Typeface.create("sans-serif-light", Typeface.NORMAL) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (digit) Ui.TEXT else Ui.QUIET)
            background = if (digit) Ui.rounded(Ui.LIFT, size / 2f, Ui.LINE, 1) else null
            contentDescription = if (label == "⌫") "Delete" else label
            setOnClickListener { onClick() }
            val gap = Ui.dp(c, 8)
            layoutParams = GridLayout.LayoutParams().apply { width = size; height = size; setMargins(gap, gap, gap, gap) }
        }
    }

    private fun press(digit: String) {
        if (entered.length >= length) return
        note.text = " "
        entered.append(digit)
        paint()
        if (entered.length == length) {
            val pin = entered.toString()
            // Let the last dot show before moving on.
            postDelayed({
                if (!onDone(pin)) {
                    entered.clear()
                    paint()
                    animate().translationX(24f).setDuration(50).withEndAction {
                        animate().translationX(-24f).setDuration(80).withEndAction { animate().translationX(0f).setDuration(50) }
                    }
                }
            }, 120)
        }
    }

    /** Shows a short message under the dots, such as "Wrong PIN". */
    fun say(message: String, title: String? = null) {
        note.text = message
        if (title != null) titleView.text = title
    }

    private fun paint() {
        dots.removeAllViews()
        val size = Ui.dp(context, 14)
        repeat(length) { i ->
            dots.addView(View(context).apply {
                background = Ui.rounded(if (i < entered.length) Ui.ACCENT_STRONG else Color.TRANSPARENT, size / 2f, Ui.QUIET, 2)
            }, LayoutParams(size, size).apply { setMargins(Ui.dp(context, 9), 0, Ui.dp(context, 9), 0) })
        }
    }
}
