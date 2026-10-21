package com.dic.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Просмотр сохранённого CSV: карты полей по кадрам, проигрывание, история выбранной точки. */
class PlayerActivity : AppCompatActivity() {

    private class Data(
        val name: String, val imgW: Int, val imgH: Int, val nx: Int, val ny: Int, val step: Int,
        val mode: String, val unit: String, val xy: FloatArray,
        val frames: IntArray, val times: FloatArray, val vals: List<FloatArray>
    )

    private lateinit var overlay: OverlayView
    private lateinit var chart: ChartView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnField: Button
    private lateinit var lblInfo: TextView
    private var data: Data? = null
    private var cur = 0
    private var playing = false
    private var fpsPlay = 10
    private var globalRange = false
    private val h = Handler(Looper.getMainLooper())

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) load(u) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF202124.toInt()) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun b(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; textSize = 13f; setOnClickListener { f() } }
        top.addView(b("Открыть…") { chooseFile() })
        btnField = b("Поле: |U|") { nextField() }; top.addView(btnField)
        top.addView(b("Вид…") { viewDialog() })
        top.addView(b("Скорость") { speedDialog() })
        lblInfo = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(16, 0, 0, 0) }
        top.addView(lblInfo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(b("✕") { finish() })
        root.addView(top)

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        overlay = OverlayView(this).apply {
            showRoi = false; roiEditing = false
            onTap = { x, y -> pick(x, y) }
        }
        chart = ChartView(this)
        mid.addView(overlay, LinearLayout.LayoutParams(0, MATCH_PARENT, 2.2f))
        mid.addView(chart, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(mid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(b("⏮") { show(0) })
        bottom.addView(b("◀") { show(cur - 1) })
        btnPlay = b("▶") { togglePlay() }; bottom.addView(btnPlay)
        bottom.addView(b("▶|") { show(cur + 1) })
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { if (user) show(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        bottom.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottom.addView(b("⏭") { show(Int.MAX_VALUE) })
        root.addView(bottom)
        setContentView(root)

        val u = intent.data
        if (u != null) load(u) else chooseFile()
    }

    private fun chooseFile() {
        val st = Storage(this, Settings(this))
        val items = try { st.list() } catch (e: Exception) { emptyList() }
        val names = items.map { "${it.name}  (${it.size / 1024} КБ)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Открыть результат").apply {
            if (items.isEmpty()) setMessage("В папке сохранения нет файлов.\n" + st.folderLabel())
            else setItems(names) { _, i -> load(items[i].uri) }
        }.setPositiveButton("Другой файл…") { _, _ -> openDoc.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
            .setNegativeButton("Отмена", null).show()
    }

    // ----------------- загрузка -----------------
    private fun load(uri: Uri) {
        val pd = AlertDialog.Builder(this).setMessage("Загрузка…").setCancelable(false).show()
        thread {
            try {
                val d = parse(uri)
                runOnUiThread { pd.dismiss(); setData(d) }
            } catch (e: Exception) {
                runOnUiThread { pd.dismiss(); Toast.makeText(this, "Не удалось прочитать файл: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun parse(uri: Uri): Data {
        var imgW = 0; var imgH = 0; var nx = 0; var ny = 0; var step = 10; var mode = "lagrangian"; var scale = 0f
        val frames = ArrayList<Int>(); val times = ArrayList<Float>(); val vals = ArrayList<FloatArray>()
        var xy = FloatArray(0)
        var curFrame = Int.MIN_VALUE; var row = 0; var buf = FloatArray(0)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val f = FloatArray(14)
        contentResolver.openInputStream(uri)!!.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                if (line[0] == '#') {
                    val l = line.removePrefix("#").trim()
                    when {
                        l.startsWith("image=") -> l.removePrefix("image=").split(',').let { imgW = it[0].toInt(); imgH = it[1].toInt() }
                        l.startsWith("grid=") -> l.removePrefix("grid=").split(',').let { nx = it[0].toInt(); ny = it[1].toInt(); step = it[2].toInt() }
                        l.startsWith("mode=") -> mode = l.removePrefix("mode=")
                        l.startsWith("scale_mm_per_px=") -> scale = l.substringAfter('=').toFloatOrNull() ?: 0f
                        l.startsWith("reference reset") -> { /* в режиме «к 1-му кадру» — новый отсчёт */ }
                    }
                    continue
                }
                if (line[0] == 'f') continue   // строка заголовка столбцов
                // быстрый разбор чисел
                var k = 0; var st = 0
                for (i in 0..line.length) {
                    if (i == line.length || line[i] == ',') {
                        if (k < 14) f[k++] = line.substring(st, i).toFloatOrNull() ?: Float.NaN
                        st = i + 1
                    }
                }
                val n = nx * ny
                if (n == 0) error("нет описания сетки (# grid=) — файл старой версии")
                if (xy.isEmpty()) xy = FloatArray(n * 2)
                val fr = f[0].toInt()
                if (fr != curFrame || row >= n) {
                    curFrame = fr; row = 0
                    buf = FloatArray(n * 7) { Float.NaN }
                    frames.add(fr); times.add(f[1]); vals.add(buf)
                }
                if (vals.size == 1) { xy[2 * row] = f[2]; xy[2 * row + 1] = f[3] }
                val b = row * 7
                buf[b] = f[4]; buf[b + 1] = f[5]; buf[b + 6] = f[9]
                if (k >= 14) { buf[b + 2] = f[10]; buf[b + 3] = f[11]; buf[b + 4] = f[12]; buf[b + 5] = f[13] }
                else { buf[b + 2] = f[6]; buf[b + 5] = f[7]; buf[b + 3] = f[8]; buf[b + 4] = f[8] }
                row++
            }
        }
        if (vals.isEmpty()) error("нет данных")
        if (imgW == 0) { imgW = (xy.filterIndexed { i, _ -> i % 2 == 0 }.max() + step).toInt(); imgH = (xy.filterIndexed { i, _ -> i % 2 == 1 }.max() + step).toInt() }
        return Data(name, imgW, imgH, nx, ny, step, mode, if (scale > 0) "мм" else "px", xy,
            frames.toIntArray(), times.toFloatArray(), vals)
    }

    private fun setData(d: Data) {
        data = d
        overlay.imgW = d.imgW; overlay.imgH = d.imgH; overlay.flip = false; overlay.unit = d.unit
        overlay.setGrid(d.nx, d.ny, d.xy, d.step)
        overlay.marker = -1
        seek.max = d.vals.size - 1
        cur = 0; show(d.vals.size - 1)
    }

    // ----------------- отображение -----------------
    private fun show(i: Int) {
        val d = data ?: return
        cur = i.coerceIn(0, d.vals.size - 1)
        seek.progress = cur
        if (globalRange) {
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (v in d.vals) for (k in 0 until d.nx * d.ny) { val x = overlay.value(overlay.field, v, k); if (x.isFinite()) { mn = min(mn, x); mx = max(mx, x) } }
            overlay.limitMin = true; overlay.limitMax = true; overlay.fixMin = mn; overlay.fixMax = mx
        }
        val v = d.vals[cur]
        overlay.setResult(v)
        // статистика кадра
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var s = 0.0; var n = 0
        for (k in 0 until d.nx * d.ny) { val x = overlay.value(overlay.field, v, k); if (x.isFinite()) { mn = min(mn, x); mx = max(mx, x); s += x; n++ } }
        val sb = StringBuilder("${d.name} · ${if (d.mode == "incremental") "кадр→кадр" else "к 1-му кадру"}\n")
        sb.append("кадр ${d.frames[cur]} (${cur + 1}/${d.vals.size})  t=%.2f с  узлов %d/%d".format(d.times[cur] / 1000f, n, d.nx * d.ny))
        overlay.info = if (n > 0) "${overlay.field.title}: мин %s  ср %s  макс %s".format(OverlayView.fmt(mn), OverlayView.fmt((s / n).toFloat()), OverlayView.fmt(mx)) else ""
        if (overlay.marker >= 0) {
            val m = overlay.marker
            overlay.info += "\nточка (%.0f, %.0f): %s = %s".format(d.xy[2 * m], d.xy[2 * m + 1], overlay.field.title, OverlayView.fmt(overlay.value(overlay.field, v, m)))
        }
        lblInfo.text = sb
        updateChart()
        overlay.invalidate()
    }

    private fun pick(x: Float, y: Float) {
        val d = data ?: return
        val p = overlay.toImage(x, y)
        var best = -1; var bd = Float.MAX_VALUE
        for (k in 0 until d.nx * d.ny) {
            val dx = d.xy[2 * k] - p.x; val dy = d.xy[2 * k + 1] - p.y
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = k }
        }
        overlay.marker = if (bd < (d.step * 3f).let { it * it }) best else -1
        show(cur)
    }

    private fun updateChart() {
        val d = data ?: return
        val m = overlay.marker
        val series: FloatArray
        val title: String
        if (m >= 0) {
            series = FloatArray(d.vals.size) { overlay.value(overlay.field, d.vals[it], m) }
            title = "${overlay.field.title} в точке"
        } else {   // среднее по ROI
            series = FloatArray(d.vals.size) { f ->
                var s = 0.0; var n = 0
                for (k in 0 until d.nx * d.ny) { val x = overlay.value(overlay.field, d.vals[f], k); if (x.isFinite()) { s += x; n++ } }
                if (n > 0) (s / n).toFloat() else Float.NaN
            }
            title = "${overlay.field.title}, среднее по ROI (тап по карте — точка)"
        }
        chart.set(d.times.map { it / 1000f }.toFloatArray(), series, cur, title)
    }

    private fun nextField() {
        val v = Field.entries
        overlay.field = v[(overlay.field.ordinal + 1) % v.size]
        btnField.text = "Поле: " + overlay.field.title
        show(cur)
    }

    private fun togglePlay() {
        playing = !playing
        btnPlay.text = if (playing) "⏸" else "▶"
        if (playing) { if (cur >= (data?.vals?.size ?: 1) - 1) cur = -1; tick() }
    }

    private fun tick() {
        if (!playing) return
        val d = data ?: return
        if (cur + 1 >= d.vals.size) { playing = false; btnPlay.text = "▶"; return }
        show(cur + 1)
        h.postDelayed({ tick() }, 1000L / fpsPlay)
    }

    private fun speedDialog() {
        val opts = arrayOf(1, 2, 5, 10, 20, 30)
        AlertDialog.Builder(this).setTitle("Кадров в секунду")
            .setItems(opts.map { "$it" }.toTypedArray()) { _, i -> fpsPlay = opts[i] }.show()
    }

    private fun viewDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        fun num(label: String, v: Float): EditText {
            ll.addView(TextView(this).apply { text = label })
            return EditText(this).apply { setText("$v"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED; ll.addView(this) }
        }
        val ov = overlay
        val cbG = CheckBox(this).apply { text = "Единая шкала по всем кадрам"; isChecked = globalRange }; ll.addView(cbG)
        val cbV = CheckBox(this).apply { text = "Векторы смещений"; isChecked = ov.showVectors }; ll.addView(cbV)
        val eK = num("Масштаб векторов (×)", ov.vecScale)
        val cbMin = CheckBox(this).apply { text = "Ограничить минимум"; isChecked = ov.limitMin && !globalRange }; ll.addView(cbMin)
        val eMin = num("Минимум", if (ov.limitMin) ov.fixMin else ov.lastMin)
        val cbMax = CheckBox(this).apply { text = "Ограничить максимум"; isChecked = ov.limitMax && !globalRange }; ll.addView(cbMax)
        val eMax = num("Максимум", if (ov.limitMax) ov.fixMax else ov.lastMax)
        val cbGrid = CheckBox(this).apply { text = "Показать сетку"; isChecked = ov.showGrid }; ll.addView(cbGrid)
        AlertDialog.Builder(this).setTitle("Отображение").setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("OK") { _, _ ->
                globalRange = cbG.isChecked
                ov.showVectors = cbV.isChecked; ov.vecScale = eK.text.toString().toFloatOrNull() ?: ov.vecScale
                ov.limitMin = cbMin.isChecked; ov.limitMax = cbMax.isChecked
                ov.fixMin = eMin.text.toString().toFloatOrNull() ?: 0f; ov.fixMax = eMax.text.toString().toFloatOrNull() ?: 1f
                ov.showGrid = cbGrid.isChecked; ov.step = data?.step ?: ov.step
                show(cur)
            }.setNeutralButton("Авто шкала") { _, _ -> globalRange = false; ov.limitMin = false; ov.limitMax = false; show(cur) }
            .show()
    }

    override fun onPause() { super.onPause(); playing = false; btnPlay.text = "▶" }
}

/** Простой график: значение во времени и текущий кадр. */
class ChartView(ctx: Context) : View(ctx) {
    private var t = FloatArray(0); private var y = FloatArray(0); private var cur = 0; private var title = ""
    private val pAx = Paint().apply { color = 0xFF888888.toInt(); strokeWidth = 2f }
    private val pLine = Paint().apply { color = 0xFF4FC3F7.toInt(); strokeWidth = 4f; isAntiAlias = true }
    private val pCur = Paint().apply { color = Color.YELLOW; strokeWidth = 3f }
    private val pTxt = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true }

    fun set(t: FloatArray, y: FloatArray, cur: Int, title: String) { this.t = t; this.y = y; this.cur = cur; this.title = title; invalidate() }

    override fun onDraw(c: Canvas) {
        val l = 110f; val r = width - 20f; val tp = 70f; val b = height - 60f
        // заголовок с переносом
        var yy = 30f
        title.chunked(max(10, (width / 14f).toInt())).forEach { c.drawText(it, 10f, yy, pTxt); yy += 28f }
        if (t.isEmpty()) return
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in y) if (v.isFinite()) { mn = min(mn, v); mx = max(mx, v) }
        if (mn > mx) return
        if (mx - mn < 1e-9f) { mn -= 1e-6f; mx += 1e-6f }
        val t0 = t.first(); val t1 = max(t.last(), t0 + 1e-3f)
        fun X(v: Float) = l + (v - t0) / (t1 - t0) * (r - l)
        fun Y(v: Float) = b - (v - mn) / (mx - mn) * (b - tp)
        c.drawLine(l, tp, l, b, pAx); c.drawLine(l, b, r, b, pAx)
        c.drawText(OverlayView.fmt(mx), 4f, tp + 10, pTxt); c.drawText(OverlayView.fmt(mn), 4f, b, pTxt)
        c.drawText("%.1f".format(t0), l, b + 32, pTxt); c.drawText("%.1f с".format(t1), r - 90, b + 32, pTxt)
        var px = Float.NaN; var py = Float.NaN
        for (i in t.indices) {
            if (!y[i].isFinite()) { px = Float.NaN; continue }
            val x = X(t[i]); val yv = Y(y[i])
            if (!px.isNaN()) c.drawLine(px, py, x, yv, pLine)
            px = x; py = yv
        }
        if (cur in t.indices) {
            val x = X(t[cur]); c.drawLine(x, tp, x, b, pCur)
            if (y[cur].isFinite()) c.drawCircle(x, Y(y[cur]), 7f, pCur)
        }
    }
}
