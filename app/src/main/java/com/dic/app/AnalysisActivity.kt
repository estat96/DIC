package com.dic.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.dic.app.Ui.f
import com.dic.app.Ui.selected
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tensile-test style analysis of a result file with force data:
 *  - force vs elongation (virtual extensometer) or vs mean displacement,
 *  - engineering stress vs strain, transverse vs axial strain,
 *  - Young's modulus E (linear fit), Poisson's ratio ν, offset yield Rp0.2, UTS Rm, uniform and final strain.
 */
object Mech {
    class Curves(val t: FloatArray, val force: FloatArray, val elong: FloatArray, val axial: FloatArray,
                 val trans: FloatArray, val stress: FloatArray, val gaugeL0: Float)

    class Props(val e: Float, val r2: Float, val nu: Float, val rp02: Float, val rp02Strain: Float,
                val rm: Float, val ag: Float, val a: Float, val fitFrom: Float, val fitTo: Float, val eIntercept: Float)

    /**
     * @param axisY loading direction along image y
     * @param extensometer axial strain from a virtual extensometer (top/bottom bands of the ROI);
     *        otherwise ROI-average strain from the selected strain method.
     */
    fun curves(d: ResultData, vals: List<FloatArray>, axisY: Boolean, extensometer: Boolean, area: Float): Curves {
        val nf = vals.size
        val elong = FloatArray(nf); val ax = FloatArray(nf); val tr = FloatArray(nf); val st = FloatArray(nf)
        // bands: first/last 15% of grid lines across the loading axis
        val lines = if (axisY) d.ny else d.nx
        val band = max(1, (lines * 0.15f).toInt())
        fun lineOf(k: Int) = if (axisY) k / d.nx else k % d.nx
        val posPx = { k: Int -> if (axisY) d.xy[2 * k + 1] else d.xy[2 * k] }
        var p0 = 0.0; var c0 = 0; var p1 = 0.0; var c1 = 0
        for (k in 0 until d.n) { val l = lineOf(k); if (l < band) { p0 += posPx(k); c0++ } else if (l >= lines - band) { p1 += posPx(k); c1++ } }
        val l0 = ((p1 / max(1, c1) - p0 / max(1, c0)) * d.unitPerPx).toFloat()
        val ci = if (axisY) 1 else 0
        for (f in 0 until nf) {
            val v = vals[f]
            var a0 = 0.0; var n0 = 0; var a1 = 0.0; var n1 = 0
            var ea = 0.0; var et = 0.0; var ne = 0
            for (k in 0 until d.n) {
                val u = v[k * 7 + ci]
                if (u.isFinite()) { val l = lineOf(k); if (l < band) { a0 += u; n0++ } else if (l >= lines - band) { a1 += u; n1++ } }
                val exx = OverlayView.value(Field.EXX, v, k); val eyy = OverlayView.value(Field.EYY, v, k)
                if (exx.isFinite() && eyy.isFinite()) { ea += if (axisY) eyy else exx; et += if (axisY) exx else eyy; ne++ }
            }
            elong[f] = if (n0 > 0 && n1 > 0) (a1 / n1 - a0 / n0).toFloat() else Float.NaN
            ax[f] = if (extensometer) elong[f] / l0 else if (ne > 0) (ea / ne).toFloat() else Float.NaN
            tr[f] = if (ne > 0) (et / ne).toFloat() else Float.NaN
            st[f] = d.forces[f] / area
        }
        return Curves(d.times, d.forces, elong, ax, tr, st, l0)
    }

    /** Linear least squares y = a + b·x over indices with x in [x0, x1]. */
    private fun fit(x: FloatArray, y: FloatArray, x0: Float, x1: Float, until: Int): Triple<Float, Float, Float>? {
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0..until) {
            if (!x[i].isFinite() || !y[i].isFinite() || x[i] < x0 || x[i] > x1) continue
            n++; sx += x[i]; sy += y[i]; sxx += x[i].toDouble() * x[i]; sxy += x[i].toDouble() * y[i]; syy += y[i].toDouble() * y[i]
        }
        if (n < 3) return null
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-30) return null
        val b = (n * sxy - sx * sy) / den; val a = (sy - b * sx) / n
        val ssTot = syy - sy * sy / n; val ssRes = syy - a * sy - b * sxy
        return Triple(a.toFloat(), b.toFloat(), if (ssTot > 0) (1 - ssRes / ssTot).toFloat() else 1f)
    }

    /**
     * @param from/to strain range for the elastic fit; ≤0 → automatic (stress between 10 % and 40 % of Rm, before Rm).
     */
    fun props(c: Curves, from: Float, to: Float, offset: Float = 0.002f): Props? {
        val e = c.axial; val s = c.stress
        var im = -1
        for (i in s.indices) if (s[i].isFinite() && e[i].isFinite() && (im < 0 || s[i] > s[im])) im = i
        if (im < 0) return null
        val rm = s[im]
        var f0 = from; var f1 = to
        if (f1 <= f0) {
            // automatic elastic range from stress levels
            var lo = Float.NaN; var hi = Float.NaN
            for (i in 0..im) {
                if (!s[i].isFinite() || !e[i].isFinite()) continue
                if (lo.isNaN() && s[i] >= 0.1f * rm) lo = e[i]
                if (s[i] <= 0.4f * rm) hi = e[i]
            }
            f0 = lo; f1 = hi
            if (!(f1 > f0)) { f0 = Float.NEGATIVE_INFINITY; f1 = e[im] }
        }
        val (a, E, r2) = fit(e, s, min(f0, f1), max(f0, f1), im) ?: return null
        val nu = fit(e, c.trans, min(f0, f1), max(f0, f1), im)?.let { -it.second } ?: Float.NaN
        // Rp0.2: first crossing of the curve with the offset line σ = a + E(ε − offset)
        var rp = Float.NaN; var rpE = Float.NaN
        var prev = Float.NaN
        for (i in 0 until s.size) {
            if (!s[i].isFinite() || !e[i].isFinite()) continue
            val g = s[i] - (a + E * (e[i] - offset))
            if (prev.isFinite() && prev > 0 && g <= 0) {
                val j = (0 until i).last { s[it].isFinite() && e[it].isFinite() }
                val t = prev / (prev - g)
                rp = s[j] + t * (s[i] - s[j]); rpE = e[j] + t * (e[i] - e[j]); break
            }
            prev = g
        }
        val last = e.indices.lastOrNull { e[it].isFinite() && s[it].isFinite() }
        return Props(E, r2, nu, rp, rpE, rm, e[im], last?.let { e[it] } ?: Float.NaN, min(f0, f1), max(f0, f1), a)
    }
}

class AnalysisActivity : AppCompatActivity() {
    private lateinit var cfg: Settings
    private var data: ResultData? = null
    private lateinit var chart: ChartView
    private lateinit var results: TextView
    private lateinit var eW: EditText; private lateinit var eT: EditText
    private lateinit var eFrom: EditText; private lateinit var eTo: EditText
    private lateinit var rgAxis: RadioGroup; private lateinit var rgStrain: RadioGroup; private lateinit var rgMethod: RadioGroup
    private var tab = 1
    private val tabs = ArrayList<Ui.Btn>()
    private var summary = ""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Settings(this)
        val dp = { v: Int -> Ui.dpi(this, v) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.BG) }

        // ---- left: inputs ----
        val form = Ui.Form(this)
        form.root.addView(TextView(this).apply { text = "Material analysis"; textSize = 18f; setTextColor(Ui.TEXT) })
        form.header("Specimen")
        eW = form.num("Width", cfg.width, "mm")
        eT = form.num("Thickness", cfg.thickness, "mm")
        form.header("Loading axis")
        rgAxis = form.radio(listOf("Image Y (vertical)", "Image X (horizontal)"), if (cfg.axisY) 0 else 1)
        form.header("Axial strain")
        rgStrain = form.radio(listOf("Virtual extensometer (ROI ends)", "ROI average"), 0)
        form.header("Strain method")
        rgMethod = form.radio(StrainMethod.entries.map { it.title }, cfg.strain.ordinal)
        form.header("Elastic fit range (strain)")
        eFrom = form.num("From", 0, ""); eTo = form.num("To", 0, "")
        form.note("0 / 0 = automatic (10–40 % of Rm)")
        form.button("Recalculate") { compute() }
        form.button("Share results") { share() }
        form.button("Close") { finish() }
        root.addView(form.view(), LinearLayout.LayoutParams(dp(300), MATCH_PARENT))

        // ---- right: chart + results ----
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Force – elongation", "Stress – strain", "Transverse – axial").forEachIndexed { i, t ->
            val b = Ui.Btn(this, t) { tab = i; tabs.forEachIndexed { k, x -> x.on = k == i }; draw() }
            tabs += b; tabRow.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        tabs[tab].on = true
        right.addView(tabRow)
        chart = ChartView(this)
        right.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        results = TextView(this).apply { setTextColor(Ui.TEXT); textSize = 14f; setPadding(dp(8), dp(8), dp(8), 0); typeface = android.graphics.Typeface.MONOSPACE }
        right.addView(results)
        root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        setContentView(root)

        val uri = intent.data ?: run { finish(); return }
        results.text = "Loading…"
        thread {
            try {
                val d = ResultData.load(this, uri)
                runOnUiThread { data = d; compute() }
            } catch (e: Exception) { runOnUiThread { results.text = "Cannot read file: ${e.message}" } }
        }
    }

    private var curves: Mech.Curves? = null
    private var props: Mech.Props? = null

    private fun compute() {
        val d = data ?: return
        if (!d.hasForce) { results.text = "This file has no force data.\nConnect a force sensor (Force button) before recording."; }
        cfg.width = eW.f(cfg.width); cfg.thickness = eT.f(cfg.thickness); cfg.axisY = rgAxis.selected() == 0
        cfg.strain = StrainMethod.entries[rgMethod.selected()]; cfg.save()
        val area = cfg.width * cfg.thickness
        val vals = d.withStrain(cfg.strain, cfg.fitWin)
        val c = Mech.curves(d, vals, cfg.axisY, rgStrain.selected() == 0, area)
        curves = c
        props = if (d.hasForce) Mech.props(c, eFrom.f(0f), eTo.f(0f)) else null
        val p = props
        val su = if (d.forceUnit == "N") "MPa" else "${d.forceUnit}/mm²"
        summary = buildString {
            append("File: ${d.name}\n")
            append("A0 = ${Ui.fmt(area)} mm²   L0 = ${Ui.fmt(c.gaugeL0)} ${d.unit}   strain: ${cfg.strain.title}\n")
            if (d.unit == "px" && rgStrain.selected() == 0) append("Note: not calibrated — elongation in px (strain is unaffected)\n")
            if (p != null) {
                append("E      = ${Ui.fmt(p.e)} $su  (R² = ${"%.4f".format(p.r2)}, fit ε ${Ui.fmt(p.fitFrom)}…${Ui.fmt(p.fitTo)})\n")
                append("ν      = ${Ui.fmt(p.nu)}\n")
                append("Rp0.2  = ${Ui.fmt(p.rp02)} $su\n")
                append("Rm     = ${Ui.fmt(p.rm)} $su   at ε = ${Ui.fmt(p.ag)}\n")
                append("ε last = ${Ui.fmt(p.a)}")
            }
        }
        if (d.hasForce) results.text = summary
        draw()
    }

    private fun draw() {
        val d = data ?: return; val c = curves ?: return; val p = props
        val su = if (d.forceUnit == "N") "MPa" else "${d.forceUnit}/mm²"
        chart.bounds = null; chart.notes = emptyList()
        when (tab) {
            0 -> {
                chart.title = "Force vs elongation"; chart.xLabel = "Δl [${d.unit}]"; chart.yLabel = "F [${d.forceUnit}]"
                chart.series = listOf(ChartView.Series(c.elong, c.force, 0xFF4FC3F7.toInt(), label = "F"))
            }
            1 -> {
                chart.title = "Engineering stress vs strain"; chart.xLabel = "ε axial [–]"; chart.yLabel = "σ [$su]"
                val ser = arrayListOf(ChartView.Series(c.axial, c.stress, 0xFF4FC3F7.toInt(), label = "σ–ε"))
                if (p != null) {
                    val emax = c.axial.filter { it.isFinite() }.maxOrNull() ?: 0f
                    val xs = floatArrayOf(0f, min(emax, (p.rm - p.eIntercept) / p.e))
                    ser += ChartView.Series(xs, FloatArray(2) { p.eIntercept + p.e * xs[it] }, Ui.OK, dashed = true, label = "E fit")
                    if (p.rp02.isFinite()) {
                        val ox = floatArrayOf(0.002f, p.rp02Strain)
                        ser += ChartView.Series(ox, FloatArray(2) { p.eIntercept + p.e * (ox[it] - 0.002f) }, Ui.WARN, dashed = true, label = "0.2 % offset")
                        ser += ChartView.Series(floatArrayOf(p.rp02Strain), floatArrayOf(p.rp02), Ui.WARN, points = true)
                    }
                    ser += ChartView.Series(floatArrayOf(p.ag), floatArrayOf(p.rm), Ui.BAD, points = true, label = "Rm")
                    chart.notes = listOf("E = ${Ui.fmt(p.e)} $su", "Rp0.2 = ${Ui.fmt(p.rp02)} $su", "Rm = ${Ui.fmt(p.rm)} $su")
                }
                chart.series = ser
            }
            else -> {
                chart.title = "Transverse vs axial strain"; chart.xLabel = "ε axial [–]"; chart.yLabel = "ε transverse [–]"
                chart.series = listOf(ChartView.Series(c.axial, c.trans, 0xFF4FC3F7.toInt(), points = true, label = "data"))
                if (p != null) chart.notes = listOf("ν = ${Ui.fmt(p.nu)}")
            }
        }
        chart.update()
    }

    private fun share() {
        val c = curves ?: return
        val sb = StringBuilder(summary).append("\n\nt_s,force,elongation,axial_strain,transverse_strain,stress\n")
        for (i in c.t.indices) sb.append("${c.t[i] / 1000},${c.force[i]},${c.elong[i]},${c.axial[i]},${c.trans[i]},${c.stress[i]}\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) }, "Share"))
    }
}
