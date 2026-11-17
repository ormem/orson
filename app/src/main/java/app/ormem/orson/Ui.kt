package app.ormem.orson

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** View builders for Orson's screens, in the style of ormem.app: hairline tracks with pills lifted inside. */
object Ui {
    val BG = Color.parseColor("#151719")
    val CARD = Color.parseColor("#1D2023")
    val TEXT = Color.parseColor("#F2EFE8")
    val MUTED = Color.parseColor("#B6B4AE")
    val QUIET = Color.parseColor("#A3A5A8")
    val ACCENT = Color.parseColor("#93A6DC")
    val ACCENT_STRONG = Color.parseColor("#B3C1EA")
    val SOFT = Color.parseColor("#293149")
    val DANGER = Color.parseColor("#E8A598")
    val HAIR = Color.argb(20, 255, 255, 255)
    val LINE = Color.argb(26, 255, 255, 255)
    val LIFT = Color.argb(18, 255, 255, 255)
    val GLASS = Color.argb(158, 21, 23, 25)

    private const val ROUND = 999f

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: String = "", size: Float = 16f, color: Int = TEXT, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    fun serif(c: Context, s: String = "", size: Float) = text(c, s, size).apply { typeface = Typeface.SERIF }

    fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun flat(b: Button) = b.apply {
        isAllCaps = false
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
    }

    /** A pill for a track; selected ones are lifted. */
    fun pill(c: Context, label: String, onClick: () -> Unit) = flat(Button(c)).apply {
        text = label
        textSize = 14f
        setTextColor(QUIET)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = null
        setPadding(dp(c, 10), 0, dp(c, 10), 0)
        setOnClickListener { onClick() }
    }

    fun select(b: Button, on: Boolean, color: Int = TEXT) {
        b.setTextColor(if (on) color else QUIET)
        b.background = if (on) rounded(LIFT, ROUND, LINE, 1) else null
    }

    /** The hairline bar that holds pills, in a row or (for a side rail) a column. */
    fun track(c: Context, pills: List<View>, vertical: Boolean = false, height: Int = 40) = LinearLayout(c).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = rounded(Color.argb(209, 29, 32, 35), dp(c, if (vertical) 20 else 999).toFloat(), Color.argb(36, 255, 255, 255), 1)
        val pad = dp(c, 4)
        setPadding(pad, pad, pad, pad)
        pills.forEach {
            addView(it, if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, height))
            else LinearLayout.LayoutParams(0, dp(c, height), 1f))
        }
    }

    /** Small standalone pill, such as Settings or Done. */
    fun chip(c: Context, label: String, onClick: () -> Unit) = flat(Button(c)).apply {
        text = label
        textSize = 13f
        setTextColor(TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(GLASS, ROUND, HAIR, 1)
        setPadding(dp(c, 16), 0, dp(c, 16), 0)
        setOnClickListener { onClick() }
    }

    /** Full-width outlined button. */
    fun wide(c: Context, label: String, danger: Boolean = false, onClick: () -> Unit) = flat(Button(c)).apply {
        text = label
        textSize = 14f
        setTextColor(if (danger) DANGER else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(Color.TRANSPARENT, ROUND, LINE, 1)
        setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
        setOnClickListener { onClick() }
    }

    fun field(c: Context, hint: String, secret: Boolean = false, number: Boolean = false) = EditText(c).apply {
        this.hint = hint
        setHintTextColor(QUIET)
        setTextColor(TEXT)
        textSize = 15f
        isSingleLine = true
        background = rounded(LIFT, dp(c, 14).toFloat(), HAIR, 1)
        setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        inputType = when {
            number && secret -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
    }

    fun toggle(c: Context, label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(c).apply {
        text = label
        textSize = 14f
        setTextColor(TEXT)
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(ACCENT_STRONG, QUIET))
        trackTintList = ColorStateList(states, intArrayOf(ACCENT, LINE))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun slider(c: Context, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(c).apply {
        this.max = max
        progress = value
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(ACCENT_STRONG)
        progressBackgroundTintList = ColorStateList.valueOf(LINE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) onChange(v) }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
    }

    /** Label on the left, value on the right. */
    fun line(c: Context, label: String, value: String = ""): LinearLayout = row(c,
        text(c, label, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
        text(c, value, 14f, QUIET),
    )

    /** Scrolls once its content passes [maxHeight]. */
    class CappedScroll(c: Context) : ScrollView(c) {
        var maxHeight = Int.MAX_VALUE

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    /** A card whose rows are split by hairlines. */
    private class Card(c: Context) : LinearLayout(c) {
        init {
            orientation = VERTICAL
            background = rounded(Color.argb(5, 255, 255, 255), dp(c, 18).toFloat(), HAIR, 1)
            setPadding(dp(c, 14), dp(c, 2), dp(c, 14), dp(c, 2))
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setColor(HAIR); setSize(0, 1) }
        }

        override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
            if (child is Padded) return super.addView(child, index, params)
            val row = Padded(context)
            row.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            super.addView(row, index, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private class Padded(c: Context) : FrameLayout(c) {
        init {
            minimumHeight = dp(c, 46)
            setPadding(0, dp(c, 8), 0, dp(c, 8))
        }
    }

    /** A titled card; returns its body to add rows to. */
    fun section(parent: LinearLayout, title: String): LinearLayout {
        val c = parent.context
        parent.addView(text(c, title, 12f, QUIET, bold = true).apply {
            setPadding(dp(c, 4), dp(c, 14), 0, dp(c, 6))
        })
        val body = Card(c)
        parent.addView(body)
        return body
    }

    fun column(c: Context, spacing: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        if (spacing > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize(0, dp(c, spacing)) }
        }
    }

    fun row(c: Context, vararg views: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize(dp(c, 8), 0) }
        views.forEach { addView(it) }
    }

    /** One-of-many choice shown as a track of pills. */
    fun <T> choice(c: Context, options: List<T>, label: (T) -> String, selected: T, onPick: (T) -> Unit): View {
        val buttons = mutableListOf<Button>()
        fun paint(current: T) = buttons.forEachIndexed { i, b -> select(b, options[i] == current) }
        options.forEach { opt ->
            buttons += pill(c, label(opt)) { paint(opt); onPick(opt) }.apply { textSize = 13f }
        }
        paint(selected)
        val rows = buttons.chunked(4).map { track(c, it, height = 34) }
        return if (rows.size == 1) rows[0] else column(c, spacing = 6).apply { rows.forEach { addView(it) } }
    }

    /** A centered card over the screen. Buttons close it unless their action says otherwise. */
    fun sheet(a: Activity, title: String, message: String?, content: View?, build: (Dialog, LinearLayout) -> Unit): Dialog {
        val dialog = Dialog(a)
        val pad = dp(a, 20)
        val body = column(a, spacing = 12).apply {
            background = rounded(CARD, dp(a, 22).toFloat(), LINE, 1)
            setPadding(pad, pad, pad, pad)
            addView(serif(a, title, 20f))
            if (message != null) addView(text(a, message, 14f, MUTED))
            if (content != null) addView(content)
        }
        build(dialog, body)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(body)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(minOf(a.resources.displayMetrics.widthPixels - dp(a, 48), dp(a, 340)), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        return dialog
    }
}
