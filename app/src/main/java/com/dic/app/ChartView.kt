package com.dic.app

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Lightweight XY chart: multiple series (lines / dashed / points), nice ticks, cursor, legend. */
class ChartView(ctx: Context) : View(ctx) {
    class Series(val x: FloatArray, val y: FloatArray, val color: Int, val dashed: Boolean = false,
                 val points: Boolean = false, val label: String = "", val width: Float = 2.5f)

    var title = ""
    var xLabel = ""; var yLabel = ""
    var series = listOf<Series>()
    var cursor = -1                 // index into series[0]
    var notes = listOf<String>()    // text block, top-left inside the plot
    var bounds: RectF? = null       // fixed range (left=xmin, right=xmax, top=ymax, bottom=ymin); null = auto
    var transparent = false

    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val pAx = Paint().apply { color = 0xFF5C6370.toInt(); strokeWidth = 1f * d }
    private val pGrid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f * d }
    private val pTxt = Paint().apply { color = Ui.TEXT; textSize = 11f * sd; isAntiAlias = true }
    private val pMuted = Paint().apply { color = Ui.MUTED; textSize = 11f * sd; isAntiAlias = true }
    private val pTitle = Paint().apply { color = Ui.TEXT; textSize = 13f * sd; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val pLine = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val pCur = Paint().apply { color = Ui.WARN; strokeWidth = 1.5f * d; isAntiAlias = true }
    private val pBg = Paint().apply { color = Ui.PANEL }

    fun update() = postInvalidate()

    override fun onDraw(c: Canvas) {
        if (!transparent) c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 10 * d, 10 * d, pBg)
        val l = 52 * d; val r = width - 12 * d; val t = (if (title.isEmpty()) 10 else 30) * d; val b = height - 34 * d
        if (title.isNotEmpty()) c.drawText(title, 10 * d, 20 * d, pTitle)
        if (r - l < 20 || b - t < 20) return
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in series) for (i in s.x.indices) {
            val x = s.x[i]; val y = s.y.getOrElse(i) { Float.NaN }
            if (!x.isFinite() || !y.isFinite()) continue
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        bounds?.let { x0 = it.left; x1 = it.right; y0 = it.bottom; y1 = it.top }
        if (x0 > x1 || y0 > y1) { c.drawText("No data", l + 10 * d, (t + b) / 2, pMuted); drawLabels(c, l, r, t, b); return }
        if (x1 - x0 < 1e-12f) { x0 -= 1f; x1 += 1f }
        if (y1 - y0 < 1e-12f) { y0 -= 1f; y1 += 1f }
        val py = (y1 - y0) * 0.06f; y0 -= py; y1 += py
        fun X(v: Float) = l + (v - x0) / (x1 - x0) * (r - l)
        fun Y(v: Float) = b - (v - y0) / (y1 - y0) * (b - t)
        // ticks
        for (v in ticks(x0, x1)) { val x = X(v); c.drawLine(x, t, x, b, pGrid); val s = Ui.fmt(v); c.drawText(s, x - pTxt.measureText(s) / 2, b + 14 * d, pTxt) }
        for (v in ticks(y0, y1)) { val y = Y(v); c.drawLine(l, y, r, y, pGrid); val s = Ui.fmt(v); c.drawText(s, l - pTxt.measureText(s) - 4 * d, y + 4 * d, pTxt) }
        c.drawLine(l, t, l, b, pAx); c.drawLine(l, b, r, b, pAx)
        c.save(); c.clipRect(l, t, r, b)
        for (s in series) {
            pLine.color = s.color; pLine.strokeWidth = s.width * d
            pLine.pathEffect = if (s.dashed) DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f) else null
            if (s.points) {
                pLine.style = Paint.Style.FILL
                for (i in s.x.indices) if (s.x[i].isFinite() && s.y[i].isFinite()) c.drawCircle(X(s.x[i]), Y(s.y[i]), 2.2f * d, pLine)
                pLine.style = Paint.Style.STROKE
            } else {
                val path = Path(); var pen = false
                for (i in s.x.indices) {
                    if (!s.x[i].isFinite() || !s.y[i].isFinite()) { pen = false; continue }
                    if (pen) path.lineTo(X(s.x[i]), Y(s.y[i])) else { path.moveTo(X(s.x[i]), Y(s.y[i])); pen = true }
                }
                c.drawPath(path, pLine)
            }
        }
        series.firstOrNull()?.let { s ->
            if (cursor in s.x.indices && s.x[cursor].isFinite()) {
                val x = X(s.x[cursor]); c.drawLine(x, t, x, b, pCur)
                if (s.y[cursor].isFinite()) c.drawCircle(x, Y(s.y[cursor]), 4 * d, pCur)
            }
        }
        c.restore()
        // legend + notes
        var ly = t + 14 * d
        for (s in series) if (s.label.isNotEmpty()) {
            pLine.color = s.color; pLine.strokeWidth = 3 * d; pLine.pathEffect = if (s.dashed) DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) else null
            c.drawLine(r - 120 * d, ly - 4 * d, r - 100 * d, ly - 4 * d, pLine)
            c.drawText(s.label, r - 96 * d, ly, pTxt); ly += 15 * d
        }
        pLine.pathEffect = null
        var ny = t + 14 * d
        for (n in notes) { c.drawText(n, l + 8 * d, ny, pTxt); ny += 15 * d }
        drawLabels(c, l, r, t, b)
    }

    private fun drawLabels(c: Canvas, l: Float, r: Float, t: Float, b: Float) {
        c.drawText(xLabel, r - pMuted.measureText(xLabel), b + 28 * d, pMuted)
        c.save(); c.rotate(-90f, 12 * d, (t + b) / 2)
        c.drawText(yLabel, 12 * d - pMuted.measureText(yLabel) / 2, (t + b) / 2, pMuted); c.restore()
    }

    private fun ticks(a: Float, b: Float, n: Int = 5): List<Float> {
        val raw = (b - a) / n
        val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val st = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
        val out = ArrayList<Float>()
        var v = ceil(a / st) * st
        while (v <= b + st * 1e-3f && out.size < 20) { out += if (abs(v) < st * 1e-6f) 0f else v; v += st }
        return out
    }
}
