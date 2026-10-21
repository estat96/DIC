package com.dic.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dic.app.Ui.f
import com.dic.app.Ui.i
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Result viewer: field maps per frame, playback, point / ROI history, link to material analysis. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var cfg: Settings
    private lateinit var overlay: OverlayView
    private lateinit var chart: ChartView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Ui.Btn
    private lateinit var btnField: Ui.Btn
    private lateinit var btnStrain: Ui.Btn
    private lateinit var lblTitle: TextView
    private var data: ResultData? = null
    private var vals: List<FloatArray> = emptyList()
    private var uri: Uri? = null
    private var cur = 0
    private var playing = false
    private var fpsPlay = 10
    private var globalRange = false
    private val h = Handler(Looper.getMainLooper())

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) load(u) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Settings(this)
        val dp = { v: Int -> Ui.dpi(this, v) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        fun add(r: LinearLayout, v: android.view.View, w: Float = 0f) =
            r.addView(v, LinearLayout.LayoutParams(if (w > 0) 0 else WRAP_CONTENT, WRAP_CONTENT, w).apply { marginEnd = dp(6) })

        val top = row()
        add(top, Ui.Btn(this, "Open") { chooseFile() })
        btnField = Ui.Btn(this, "Field: |U|") { nextField() }; add(top, btnField)
        btnStrain = Ui.Btn(this, "Strain: ${cfg.strain.title}") { nextStrain() }; add(top, btnStrain)
        add(top, Ui.Btn(this, "Display") { viewDialog() })
        add(top, Ui.Btn(this, "Analysis", Ui.GO) { openAnalysis() })
        lblTitle = TextView(this).apply { setTextColor(Ui.MUTED); textSize = 12f; maxLines = 2 }
        add(top, lblTitle, 1f)
        add(top, Ui.Btn(this, "Close") { finish() })
        root.addView(top)

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        overlay = OverlayView(this).apply { showRoi = false; roiEditing = false; showScalebar = cfg.showScalebar; onTap = { x, y -> pick(x, y) } }
        chart = ChartView(this)
        mid.addView(overlay, LinearLayout.LayoutParams(0, MATCH_PARENT, 2.1f))
        mid.addView(chart, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) })
        root.addView(mid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bottom = row()
        add(bottom, Ui.Btn(this, "⏮") { show(0) })
        add(bottom, Ui.Btn(this, "◀") { show(cur - 1) })
        btnPlay = Ui.Btn(this, "▶ Play") { togglePlay() }; add(bottom, btnPlay)
        add(bottom, Ui.Btn(this, "▶") { show(cur + 1) })
        add(bottom, Ui.Btn(this, "⏭") { show(Int.MAX_VALUE) })
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { if (user) show(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        add(bottom, seek, 1f)
        add(bottom, Ui.Btn(this, "Speed") { speedDialog() })
        root.addView(bottom)
        setContentView(root)

        val u = intent.data
        if (u != null) load(u) else chooseFile()
    }

    private fun chooseFile() {
        val st = Storage(this, cfg)
        val items = try { st.list() } catch (e: Exception) { emptyList() }
        AlertDialog.Builder(this).setTitle("Open result").apply {
            if (items.isEmpty()) setMessage("No files in the save folder.\n" + st.folderLabel())
            else setItems(items.map { "${it.name}   ${it.size / 1024} KB" }.toTypedArray()) { _, i -> load(items[i].uri) }
        }.setPositiveButton("Browse…") { _, _ -> openDoc.launch(arrayOf("*/*")) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun load(u: Uri) {
        val pd = AlertDialog.Builder(this).setMessage("Loading…").setCancelable(false).show()
        thread {
            try {
                val d = ResultData.load(this, u)
                runOnUiThread { pd.dismiss(); uri = u; setData(d) }
            } catch (e: Exception) {
                runOnUiThread { pd.dismiss(); Toast.makeText(this, "Cannot read file: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun setData(d: ResultData) {
        data = d
        overlay.imgW = d.imgW; overlay.imgH = d.imgH; overlay.flip = false
        overlay.unit = d.unit; overlay.unitPerPx = d.unitPerPx; overlay.mmPerPx = d.scale; overlay.step = d.step
        overlay.setGrid(d.nx, d.ny, d.xy, d.step)
        overlay.marker = -1
        seek.max = d.vals.size - 1
        applyStrain()
        show(d.vals.size - 1)
    }

    private fun applyStrain() { data?.let { vals = it.withStrain(cfg.strain, cfg.fitWin) } }

    private fun show(i: Int) {
        val d = data ?: return
        cur = i.coerceIn(0, vals.size - 1)
        seek.progress = cur
        if (globalRange) {
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (v in vals) for (k in 0 until d.n) { val x = OverlayView.value(overlay.field, v, k); if (x.isFinite()) { mn = min(mn, x); mx = max(mx, x) } }
            if (mn <= mx) { overlay.limitMin = true; overlay.limitMax = true; overlay.fixMin = mn; overlay.fixMax = mx }
        }
        val v = vals[cur]
        overlay.setResult(v)
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var s = 0.0; var n = 0
        for (k in 0 until d.n) { val x = OverlayView.value(overlay.field, v, k); if (x.isFinite()) { mn = min(mn, x); mx = max(mx, x); s += x; n++ } }
        val lines = arrayListOf(
            "Frame ${d.frames[cur]}  (${cur + 1}/${vals.size})   t = ${"%.2f".format(d.times[cur] / 1000f)} s" to Ui.TEXT,
            "Valid nodes $n / ${d.n}" to Ui.MUTED)
        if (n > 0) lines += "${overlay.field.title}: min ${Ui.fmt(mn)}  mean ${Ui.fmt((s / n).toFloat())}  max ${Ui.fmt(mx)}" to Ui.TEXT
        if (d.forces[cur].isFinite()) lines += "F = ${Ui.fmt(d.forces[cur])} ${d.forceUnit}" to Ui.WARN
        if (overlay.marker >= 0) {
            val m = overlay.marker
            lines += "Point (${d.xy[2 * m].toInt()}, ${d.xy[2 * m + 1].toInt()}) px: ${overlay.field.title} = ${Ui.fmt(OverlayView.value(overlay.field, v, m))}" to 0xFF80D8FF.toInt()
        }
        overlay.info = lines
        lblTitle.text = "${d.name}\n${if (d.mode == "incremental") "frame-to-frame" else "vs first frame"} · ${d.nx}×${d.ny} nodes · ${d.unit}"
        updateChart()
    }

    private fun pick(x: Float, y: Float) {
        val d = data ?: return
        val p = overlay.toImage(x, y)
        var best = -1; var bd = Float.MAX_VALUE
        for (k in 0 until d.n) { val dx = d.xy[2 * k] - p.x; val dy = d.xy[2 * k + 1] - p.y; val dd = dx * dx + dy * dy; if (dd < bd) { bd = dd; best = k } }
        overlay.marker = if (bd < (d.step * 3f).let { it * it } && overlay.marker != best) best else -1
        show(cur)
    }

    private fun updateChart() {
        val d = data ?: return
        val m = overlay.marker
        val t = FloatArray(vals.size) { d.times[it] / 1000f }
        val y = if (m >= 0) FloatArray(vals.size) { OverlayView.value(overlay.field, vals[it], m) }
        else FloatArray(vals.size) { f ->
            var s = 0.0; var n = 0
            for (k in 0 until d.n) { val x = OverlayView.value(overlay.field, vals[f], k); if (x.isFinite()) { s += x; n++ } }
            if (n > 0) (s / n).toFloat() else Float.NaN
        }
        chart.title = if (m >= 0) "${overlay.field.title} at point" else "${overlay.field.title}, ROI mean (tap map for a point)"
        chart.xLabel = "t [s]"; chart.yLabel = overlay.field.title + if (overlay.field.isStrain) "" else " [${d.unit}]"
        chart.series = listOf(ChartView.Series(t, y, 0xFF4FC3F7.toInt()))
        chart.cursor = cur
        chart.update()
    }

    private fun nextField() {
        overlay.field = Field.entries[(overlay.field.ordinal + 1) % Field.entries.size]
        btnField.text = "Field: " + overlay.field.title
        show(cur)
    }

    private fun nextStrain() {
        cfg.strain = StrainMethod.entries[(cfg.strain.ordinal + 1) % StrainMethod.entries.size]; cfg.save()
        btnStrain.text = "Strain: " + cfg.strain.title
        applyStrain(); show(cur)
    }

    private fun togglePlay() {
        playing = !playing
        btnPlay.text = if (playing) "⏸ Pause" else "▶ Play"
        if (playing) { if (cur >= vals.size - 1) cur = -1; tick() }
    }

    private fun tick() {
        if (!playing) return
        if (cur + 1 >= vals.size) { playing = false; btnPlay.text = "▶ Play"; return }
        show(cur + 1)
        h.postDelayed({ tick() }, 1000L / fpsPlay)
    }

    private fun speedDialog() {
        val opts = arrayOf(1, 2, 5, 10, 20, 30)
        AlertDialog.Builder(this).setTitle("Playback, frames/s").setItems(opts.map { "$it" }.toTypedArray()) { _, i -> fpsPlay = opts[i] }.show()
    }

    private fun viewDialog() {
        val ov = overlay
        val f = Ui.Form(this)
        val cbG = f.check("Same colour range for all frames", globalRange)
        val cbV = f.check("Displacement vectors", ov.showVectors)
        val cbM = f.check("Deformed mesh", ov.showMesh)
        val cbGr = f.check("Grid nodes", ov.showGrid)
        val cbS = f.check("Scale bar", ov.showScalebar)
        val eK = f.num("Vector / mesh magnification", ov.vecScale, "×")
        f.header("Colour range")
        val cbMin = f.check("Limit minimum", ov.limitMin && !globalRange); val eMin = f.num("Minimum", if (ov.limitMin) ov.fixMin else ov.lastMin)
        val cbMax = f.check("Limit maximum", ov.limitMax && !globalRange); val eMax = f.num("Maximum", if (ov.limitMax) ov.fixMax else ov.lastMax)
        f.header("Local fit strain")
        val eW = f.num("Window half-size", cfg.fitWin, "nodes")
        AlertDialog.Builder(this).setTitle("Display").setView(f.view())
            .setPositiveButton("OK") { _, _ ->
                globalRange = cbG.isChecked
                ov.showVectors = cbV.isChecked; ov.showMesh = cbM.isChecked; ov.showGrid = cbGr.isChecked; ov.showScalebar = cbS.isChecked
                ov.vecScale = eK.f(ov.vecScale)
                ov.limitMin = cbMin.isChecked; ov.limitMax = cbMax.isChecked; ov.fixMin = eMin.f(0f); ov.fixMax = eMax.f(1f)
                val w = eW.i(cfg.fitWin).coerceIn(1, 10)
                if (w != cfg.fitWin) { cfg.fitWin = w; cfg.save(); applyStrain() }
                show(cur)
            }.setNeutralButton("Auto range") { _, _ -> globalRange = false; ov.limitMin = false; ov.limitMax = false; show(cur) }
            .show()
    }

    private fun openAnalysis() {
        val u = uri ?: return
        if (data?.hasForce != true) {
            AlertDialog.Builder(this).setMessage("This recording has no force data. Stress–strain and material properties need a force sensor; strain curves are still shown.")
                .setPositiveButton("Open anyway") { _, _ -> startActivity(Intent(this, AnalysisActivity::class.java).setData(u)) }
                .setNegativeButton("Cancel", null).show()
        } else startActivity(Intent(this, AnalysisActivity::class.java).setData(u))
    }

    override fun onPause() { super.onPause(); playing = false; btnPlay.text = "▶ Play" }
}
