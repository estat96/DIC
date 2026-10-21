package com.dic.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Поле, которое показывается поверх изображения. */
enum class Field(val title: String) {
    MAG("|U|"), U("u"), V("v"), EXX("εxx"), EYY("εyy"), EXY("εxy"), VM("ε экв."), ZNCC("ZNCC")
}

/**
 * Слой поверх камеры (или пустого фона в плеере): ROI, сетка, подмножества,
 * цветовая карта поля, векторы, шкала.
 * Данные на узел — 7 float: u, v, ux, uy, vx, vy, zncc (ux.. — градиент смещений, F − I).
 */
class OverlayView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var imgW = 1280; var imgH = 720
    var flip = false
    var roi = RectF(0.3f, 0.3f, 0.7f, 0.7f)    // в долях кадра
    var roiEditing = true
    var showRoi = true
    var onTap: ((Float, Float) -> Unit)? = null

    // предпросмотр сетки/подмножеств (до и во время эксперимента)
    var showGrid = false
    var showSubset = false
    var subset = 31
    var step = 10

    var field = Field.MAG
    var unit = "px"
    var showVectors = true
    var vecScale = 5f
    var limitMin = false; var limitMax = false
    var fixMin = 0f; var fixMax = 1f
    var info = ""
    var marker: Int = -1                        // выбранный узел (плеер)

    private var nx = 0; private var ny = 0
    private var xy = FloatArray(0); private var res = FloatArray(0)
    private var cellPx = 10f
    private var bmp: Bitmap? = null
    var lastMin = 0f; var lastMax = 0f

    private val lut = IntArray(256) { jet(it / 255f) }
    private val pRoi = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW; isAntiAlias = true }
    private val pShade = Paint().apply { color = 0x66000000 }
    private val pVec = Paint().apply { color = Color.WHITE; strokeWidth = 2f; isAntiAlias = true }
    private val pGrid = Paint().apply { color = Color.CYAN; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
    private val pSub = Paint().apply { style = Paint.Style.STROKE; color = 0xAAFF80FF.toInt(); strokeWidth = 2f }
    private val pSubMain = Paint().apply { style = Paint.Style.STROKE; color = Color.MAGENTA; strokeWidth = 4f }
    private val pMark = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 5f; isAntiAlias = true }
    private val pText = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val pBmp = Paint().apply { isFilterBitmap = true; alpha = 170 }

    // ---------- отображение кадр <-> экран (FIT_CENTER) ----------
    private fun scale() = min(width / imgW.toFloat(), height / imgH.toFloat())
    private fun ox() = (width - imgW * scale()) / 2
    private fun oy() = (height - imgH * scale()) / 2
    fun toView(x: Float, y: Float): PointF {
        val ix = if (flip) imgW - x else x; val iy = if (flip) imgH - y else y
        return PointF(ox() + ix * scale(), oy() + iy * scale())
    }
    fun toImage(vx: Float, vy: Float): PointF {
        val ix = (vx - ox()) / scale(); val iy = (vy - oy()) / scale()
        return if (flip) PointF(imgW - ix, imgH - iy) else PointF(ix, iy)
    }
    fun roiPixels(): Rect = Rect((roi.left * imgW).toInt(), (roi.top * imgH).toInt(), (roi.right * imgW).toInt(), (roi.bottom * imgH).toInt())

    /** Узлы сетки так же, как их строит нативный код. */
    fun previewGrid(): FloatArray {
        val r = roiPixels(); val R = max(5, subset / 2); val m = R + 2
        val x0 = max(r.left, m); val y0 = max(r.top, m); val x1 = min(r.right, imgW - m - 1); val y1 = min(r.bottom, imgH - m - 1)
        if (x1 <= x0 || y1 <= y0) return FloatArray(0)
        val gx = (x1 - x0) / step + 1; val gy = (y1 - y0) / step + 1
        val o = FloatArray(gx * gy * 2)
        var k = 0
        for (j in 0 until gy) for (i in 0 until gx) { o[k++] = (x0 + i * step).toFloat(); o[k++] = (y0 + j * step).toFloat() }
        return o
    }

    fun setGrid(nx: Int, ny: Int, xy: FloatArray, step: Int) {
        this.nx = nx; this.ny = ny; this.xy = xy; cellPx = step.toFloat()
        res = FloatArray(0); bmp = if (nx > 0 && ny > 0) Bitmap.createBitmap(nx, ny, Bitmap.Config.ARGB_8888) else null
        postInvalidate()
    }
    fun clearField() { nx = 0; ny = 0; bmp = null; res = FloatArray(0); postInvalidate() }
    fun gridSize() = nx to ny
    fun gridXY() = xy

    fun value(f: Field, r: FloatArray, i: Int): Float {
        val b = i * 7
        val u = r[b]; val v = r[b + 1]; val ux = r[b + 2]; val uy = r[b + 3]; val vx = r[b + 4]; val vy = r[b + 5]
        // деформации Грина–Лагранжа
        val exx = ux + 0.5f * (ux * ux + vx * vx)
        val eyy = vy + 0.5f * (uy * uy + vy * vy)
        val exy = 0.5f * (uy + vx + ux * uy + vx * vy)
        return when (f) {
            Field.MAG -> hypot(u, v); Field.U -> u; Field.V -> v
            Field.EXX -> exx; Field.EYY -> eyy; Field.EXY -> exy
            Field.VM -> (sqrt(2f) / 3f) * sqrt((exx - eyy).pow(2) + exx * exx + eyy * eyy + 6 * exy * exy)
            Field.ZNCC -> r[b + 6]
        }
    }

    fun setResult(r: FloatArray) {
        val b = bmp ?: return
        val n = nx * ny
        if (r.size < n * 7) return
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        val vals = FloatArray(n)
        for (i in 0 until n) { val v = value(field, r, i); vals[i] = v; if (v.isFinite()) { mn = min(mn, v); mx = max(mx, v) } }
        if (mn > mx) { mn = 0f; mx = 1f }
        if (limitMin) mn = fixMin
        if (limitMax) mx = fixMax
        if (mx - mn < 1e-9f) mx = mn + 1e-6f
        lastMin = mn; lastMax = mx
        val px = IntArray(n)
        for (i in 0 until n) {
            val v = vals[i]
            // значения за пределами шкалы окрашиваются крайним цветом
            px[i] = if (!v.isFinite()) 0 else lut[(((v - mn) / (mx - mn)).coerceIn(0f, 1f) * 255).toInt()]
        }
        b.setPixels(px, 0, nx, 0, 0, nx, ny)
        res = r
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = scale()
        bmp?.let { b ->
            if (xy.size >= 2) {
                val a = toView(xy[0] - cellPx / 2, xy[1] - cellPx / 2)
                val z = toView(xy[xy.size - 2] + cellPx / 2, xy[xy.size - 1] + cellPx / 2)
                c.drawBitmap(b, null, RectF(min(a.x, z.x), min(a.y, z.y), max(a.x, z.x), max(a.y, z.y)), pBmp)
            }
            if (showVectors && res.isNotEmpty()) {
                val every = max(1, (nx / 18f).roundToInt())
                val sgn = if (flip) -1 else 1
                for (j in 0 until ny step every) for (i in 0 until nx step every) {
                    val k = j * nx + i
                    val u = res[k * 7]; val v = res[k * 7 + 1]
                    if (!u.isFinite()) continue
                    val p0 = toView(xy[2 * k], xy[2 * k + 1])
                    val ex = p0.x + sgn * u * vecScale * s; val ey = p0.y + sgn * v * vecScale * s
                    c.drawLine(p0.x, p0.y, ex, ey, pVec); c.drawCircle(ex, ey, 3f, pVec)
                }
            }
            drawColorbar(c)
        }
        // сетка и подмножества
        if (showGrid || showSubset) {
            val g = if (nx > 0) xy else previewGrid()
            val n = g.size / 2
            if (showGrid && n > 0) {
                val pts = FloatArray(g.size)
                for (i in 0 until n) { val p = toView(g[2 * i], g[2 * i + 1]); pts[2 * i] = p.x; pts[2 * i + 1] = p.y }
                pGrid.strokeWidth = (step * s * 0.35f).coerceIn(2f, 8f)
                c.drawPoints(pts, pGrid)
            }
            if (showSubset && n > 0) {
                val h = (2 * max(5, subset / 2) + 1) / 2f * s
                // все подмножества тонко (прорежено, если их много), центральное — жирно
                val every = max(1, ceil(sqrt(n / 60f)).toInt())
                val cols = (g.size / 2).let { cnt -> var k = 1; while (k < cnt && g[2 * k + 1] == g[1]) k++; k }
                for (i in 0 until n) {
                    if ((i % cols) % every != 0 || (i / cols) % every != 0) continue
                    val p = toView(g[2 * i], g[2 * i + 1]); c.drawRect(p.x - h, p.y - h, p.x + h, p.y + h, pSub)
                }
                val pc = toView(g[2 * ((n - 1) / 2)], g[2 * ((n - 1) / 2) + 1])
                c.drawRect(pc.x - h, pc.y - h, pc.x + h, pc.y + h, pSubMain)
                pText.textAlign = Paint.Align.CENTER
                c.drawText("${2 * max(5, subset / 2) + 1}px", pc.x, pc.y - h - 10, pText)
                pText.textAlign = Paint.Align.LEFT
            }
        }
        if (marker >= 0 && marker * 2 + 1 < xy.size) {
            val p = toView(xy[2 * marker], xy[2 * marker + 1]); c.drawCircle(p.x, p.y, 14f, pMark)
        }
        if (showRoi) {
            val r = roiPixels()
            val a = toView(r.left.toFloat(), r.top.toFloat()); val z = toView(r.right.toFloat(), r.bottom.toFloat())
            val vr = RectF(min(a.x, z.x), min(a.y, z.y), max(a.x, z.x), max(a.y, z.y))
            if (roiEditing) {
                c.drawRect(0f, 0f, width.toFloat(), vr.top, pShade); c.drawRect(0f, vr.bottom, width.toFloat(), height.toFloat(), pShade)
                c.drawRect(0f, vr.top, vr.left, vr.bottom, pShade); c.drawRect(vr.right, vr.top, width.toFloat(), vr.bottom, pShade)
                for (p in listOf(PointF(vr.left, vr.top), PointF(vr.right, vr.top), PointF(vr.left, vr.bottom), PointF(vr.right, vr.bottom)))
                    c.drawCircle(p.x, p.y, 18f, pRoi)
            }
            c.drawRect(vr, pRoi)
        }
        var y = 44f
        val x = if (showRoi) 260f else 16f
        for (line in info.split('\n')) { c.drawText(line, x, y, pText); y += 40f }
    }

    private fun drawColorbar(c: Canvas) {
        val x = width - 70f; val top = 80f; val h = height - 200f
        val p = Paint()
        for (i in 0 until 256) { p.color = lut[255 - i]; c.drawRect(x, top + h * i / 256f, x + 30f, top + h * (i + 1) / 256f + 1, p) }
        pText.textAlign = Paint.Align.RIGHT
        c.drawText((if (limitMax) "≥" else "") + fmt(lastMax), x - 8, top + 24, pText)
        c.drawText((if (limitMin) "≤" else "") + fmt(lastMin), x - 8, top + h, pText)
        val isStrain = field == Field.EXX || field == Field.EYY || field == Field.EXY || field == Field.VM || field == Field.ZNCC
        c.drawText(field.title + if (isStrain) "" else ", $unit", width - 16f, top + h + 50, pText)
        pText.textAlign = Paint.Align.LEFT
    }

    // ---------- жесты ----------
    private var drag = -1; private var lx = 0f; private var ly = 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!roiEditing) {
            if (e.action == MotionEvent.ACTION_UP) onTap?.invoke(e.x, e.y)
            return true
        }
        val ip = toImage(e.x, e.y)
        val px = (ip.x / imgW).coerceIn(0f, 1f); val py = (ip.y / imgH).coerceIn(0f, 1f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 75f / (scale() * imgW)
                val corners = listOf(roi.left to roi.top, roi.right to roi.top, roi.left to roi.bottom, roi.right to roi.bottom)
                drag = corners.indexOfFirst { abs(it.first - px) < tol && abs(it.second - py) < tol * imgW / imgH }
                if (drag < 0) drag = if (roi.contains(px, py)) 4 else 5
                if (drag == 5) roi.set(px, py, px, py)
                lx = px; ly = py
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) {
                    0 -> { roi.left = px; roi.top = py }; 1 -> { roi.right = px; roi.top = py }
                    2 -> { roi.left = px; roi.bottom = py }; 3, 5 -> { roi.right = px; roi.bottom = py }
                    4 -> { val dx = (px - lx).coerceIn(-roi.left, 1 - roi.right); val dy = (py - ly).coerceIn(-roi.top, 1 - roi.bottom); roi.offset(dx, dy) }
                }
                lx = px; ly = py; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                roi.sort(); if (roi.width() < 0.05f || roi.height() < 0.05f) roi.set(0.3f, 0.3f, 0.7f, 0.7f)
                onRoiChanged?.invoke(); invalidate()
            }
        }
        return true
    }
    var onRoiChanged: (() -> Unit)? = null

    companion object {
        fun jet(t: Float): Int {
            val r = (1.5f - abs(4 * t - 3)).coerceIn(0f, 1f); val g = (1.5f - abs(4 * t - 2)).coerceIn(0f, 1f); val b = (1.5f - abs(4 * t - 1)).coerceIn(0f, 1f)
            return Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
        fun fmt(v: Float) = if (!v.isFinite()) "—" else if (abs(v) < 0.01f && v != 0f) "%.2e".format(v) else "%.4f".format(v)
    }
}
