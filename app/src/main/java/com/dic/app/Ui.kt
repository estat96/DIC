package com.dic.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/** Shared look & feel: dark translucent panels, rounded buttons, sizes in dp/sp. */
object Ui {
    const val BG = 0xE6121418.toInt()
    const val PANEL = 0xCC1B1E24.toInt()
    const val BTN = 0xFF2A2F38.toInt()
    const val BTN_ON = 0xFF2F6FED.toInt()
    const val GO = 0xFF2E9E5B.toInt()
    const val STOP = 0xFFD64545.toInt()
    const val TEXT = 0xFFF1F3F5.toInt()
    const val MUTED = 0xFF9AA3AE.toInt()
    const val OK = 0xFF4CD08A.toInt()
    const val WARN = 0xFFF2C14E.toInt()
    const val BAD = 0xFFFF6B6B.toInt()

    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
    fun dpi(c: Context, v: Int) = dp(c, v.toFloat()).toInt()
    fun sp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.resources.displayMetrics)

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    /** Rounded flat button; [setOn] toggles the highlighted state. */
    class Btn(c: Context, label: String, private val base: Int = BTN, onClick: (Btn) -> Unit) : TextView(c) {
        var on = false
            set(v) { field = v; background = rounded(if (v) BTN_ON else base, dp(context, 10f)) }
        init {
            text = label; setTextColor(TEXT); textSize = 14f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val p = dpi(c, 10); setPadding(p, dpi(c, 9), p, dpi(c, 9))
            background = rounded(base, dp(c, 10f)); isClickable = true; isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(Color.WHITE, dp(c, 10f)))
            setOnClickListener { onClick(this) }
        }
        fun color(c: Int) { background = rounded(c, dp(context, 10f)) }
    }

    fun section(c: Context, t: String) = TextView(c).apply {
        text = t.uppercase(); setTextColor(MUTED); textSize = 11f; letterSpacing = 0.08f
        setPadding(dpi(c, 4), dpi(c, 12), 0, dpi(c, 4))
    }

    fun lp(c: Context, topDp: Int = 4) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(c, topDp) }

    /** Small form builder for dialogs. */
    class Form(val c: Context) {
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(c, 20), dpi(c, 8), dpi(c, 20), dpi(c, 8)) }
        fun header(t: String) = root.addView(section(c, t))
        fun note(t: String, color: Int = MUTED, size: Float = 13f): TextView =
            TextView(c).apply { text = t; setTextColor(color); textSize = size; setPadding(0, dpi(c, 2), 0, dpi(c, 6)) }.also { root.addView(it) }
        fun num(label: String, v: Number, unit: String = ""): EditText {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(c).apply { text = label; textSize = 14f; setTextColor(TEXT) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val e = EditText(c).apply {
                setText(fmtNum(v)); textSize = 15f; setTextColor(TEXT); gravity = Gravity.END; setSelectAllOnFocus(true)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            row.addView(e, LinearLayout.LayoutParams(dpi(c, 110), WRAP_CONTENT))
            row.addView(TextView(c).apply { text = unit; textSize = 13f; setTextColor(MUTED); setPadding(dpi(c, 6), 0, 0, 0) }, LinearLayout.LayoutParams(dpi(c, 56), WRAP_CONTENT))
            root.addView(row)
            return e
        }
        fun text(label: String, v: String): EditText {
            root.addView(TextView(c).apply { text = label; textSize = 14f; setTextColor(TEXT) })
            return EditText(c).apply { setText(v); textSize = 15f; setTextColor(TEXT) }.also { root.addView(it) }
        }
        fun check(label: String, v: Boolean) = CheckBox(c).apply { text = label; isChecked = v; textSize = 14f; setTextColor(TEXT) }.also { root.addView(it) }
        fun radio(options: List<String>, sel: Int): RadioGroup {
            val g = RadioGroup(c)
            options.forEachIndexed { i, s -> g.addView(RadioButton(c).apply { text = s; id = i + 1; textSize = 14f; setTextColor(TEXT) }) }
            g.check(sel + 1); root.addView(g); return g
        }
        fun button(t: String, f: () -> Unit) = Btn(c, t) { f() }.also { root.addView(it, lp(c, 6)) }
        fun view() = ScrollView(c).apply { addView(root) }
    }

    fun RadioGroup.selected() = checkedRadioButtonId - 1
    fun EditText.f(def: Float) = text.toString().replace(',', '.').toFloatOrNull() ?: def
    fun EditText.i(def: Int) = text.toString().toIntOrNull() ?: def

    fun fmtNum(v: Number): String = when (v) {
        is Float -> if (v == v.toInt().toFloat() && kotlin.math.abs(v) < 1e6) v.toInt().toString() else "%.6g".format(v)
        is Double -> fmtNum(v.toFloat())
        else -> v.toString()
    }

    /** Engineering-style number formatting for labels. */
    fun fmt(v: Float): String {
        if (!v.isFinite()) return "—"
        val a = kotlin.math.abs(v)
        return when {
            a == 0f -> "0"
            a < 1e-3f -> "%.2e".format(v)
            a < 1f -> "%.4f".format(v)
            a < 100f -> "%.3f".format(v)
            a < 1e5f -> "%.1f".format(v)
            else -> "%.3e".format(v)
        }
    }
}
