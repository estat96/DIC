package com.dic.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Поле, которое показывается поверх изображения. */
enum class Field(val title: String) {
    MAG("|U|, px"), U("u, px"), V("v, px"), EXX("εxx"), EYY("εyy"), EXY("εxy"), VM("ε экв."), ZNCC("ZNCC")
}

class OverlayView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var imgW = 1280; var imgH = 720
    var flip = false                       // поворот анализируемого кадра на 180°
    var roi = RectF(0.3f, 0.3f, 0.7f, 0.7f) // в долях кадра
    var roiEditing = true
    var onTapFocus: ((Float, Float) -> Unit)? = null
    var field = Field.MAG
    var showVectors = true
    var vecScale = 5f
    var autoRange = true
    var fixMin = 0f; var fixMax = 1f
    var info = ""

    // данные сетки
    private var nx = 0; private var ny = 0
    private var xy = FloatArray(0); private var res = FloatArray(0)
    private var cellPx = 10f
    private var bmp: Bitmap? = null
    var lastMin = 0f; var lastMax = 0f

    private val lut = IntArray(256) { jet(it / 255f) }
    private val pRoi = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW; isAntiAlias = true }
    private val pShade = Paint().apply { color = 0x66000000 }
    private val pVec = Paint().apply { color = Color.WHITE; strokeWidth = 2f; isAntiAlias = true }
    private val pText = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val pBmp = Paint().apply { isFilterBitmap = true; alpha = 170 }

    // ---------- отображение кадр <-> экран (PreviewView FIT_CENTER) ----------
    private fun scale() = min(width / imgW.toFloat(), height / imgH.toFloat())
    private fun ox() = (width - imgW * scale()) / 2
    private fun oy() = (height - imgH * scale()) / 2
    fun toView(x: Float, y: Float): PointF {
        val ix = if (flip) imgW - x else x; val iy = if (flip) imgH - y else y
        return PointF(ox() + ix * scale(), oy() + iy * scale())
    }
    fun roiPixels(): Rect = Rect((roi.left * imgW).toInt(), (roi.top * imgH).toInt(), (roi.right * imgW).toInt(), (roi.bottom * imgH).toInt())

    fun setGrid(nx: Int, ny: Int, xy: FloatArray, step: Int) {
        this.nx = nx; this.ny = ny; this.xy = xy; cellPx = step.toFloat()
        res = FloatArray(0); bmp = if (nx > 0 && ny > 0) Bitmap.createBitmap(nx, ny, Bitmap.Config.ARGB_8888) else null
        postInvalidate()
    }
    fun clearField() { nx = 0; ny = 0; bmp = null; res = FloatArray(0); postInvalidate() }

    fun value(f: Field, r: FloatArray, i: Int): Float {
        val b = i * 7
        val u = r[b]; val v = r[b + 1]; val ux = r[b + 2]; val uy = r[b + 3]; val vx = r[b + 4]; val vy = r[b + 5]
        // деформации Грина–Лагранжа из градиентов IC-GN
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

    /** Вызывается из потока анализа. */
    fun setResult(r: FloatArray) {
        val b = bmp ?: return
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        val n = nx * ny
        val vals = FloatArray(n)
        for (i in 0 until n) { val v = value(field, r, i); vals[i] = v; if (v.isFinite()) { mn = min(mn, v); mx = max(mx, v) } }
        if (mn > mx) { mn = 0f; mx = 1f }
        if (!autoRange) { mn = fixMin; mx = fixMax }
        if (mx - mn < 1e-9f) mx = mn + 1e-6f
        lastMin = mn; lastMax = mx
        val px = IntArray(n)
        for (i in 0 until n) {
            val v = vals[i]
            px[i] = if (!v.isFinite()) 0 else lut[(((v - mn) / (mx - mn)).coerceIn(0f, 1f) * 255).toInt()]
        }
        b.setPixels(px, 0, nx, 0, 0, nx, ny)
        res = r
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = scale()
        // поле
        bmp?.let { b ->
            if (xy.size >= 2) {
                val a = toView(xy[0] - cellPx / 2, xy[1] - cellPx / 2)
                val z = toView(xy[xy.size - 2] + cellPx / 2, xy[xy.size - 1] + cellPx / 2)
                c.drawBitmap(b, null, RectF(min(a.x, z.x), min(a.y, z.y), max(a.x, z.x), max(a.y, z.y)), pBmp)
            }
            if (showVectors && res.isNotEmpty()) {
                val every = max(1, (nx / 18f).roundToInt())
                for (j in 0 until ny step every) for (i in 0 until nx step every) {
                    val k = j * nx + i
                    val u = res[k * 7]; val v = res[k * 7 + 1]
                    if (!u.isFinite()) continue
                    val p0 = toView(xy[2 * k], xy[2 * k + 1])
                    val sgn = if (flip) -1 else 1
                    val ex = p0.x + sgn * u * vecScale * s; val ey = p0.y + sgn * v * vecScale * s
                    c.drawLine(p0.x, p0.y, ex, ey, pVec)
                    c.drawCircle(ex, ey, 3f, pVec)
                }
            }
            drawColorbar(c)
        }
        // ROI
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
        var y = 44f
        for (line in info.split('\n')) { c.drawText(line, 16f, y, pText); y += 40f }
    }

    private fun drawColorbar(c: Canvas) {
        val x = width - 70f; val top = 80f; val h = height - 200f
        val p = Paint()
        for (i in 0 until 256) {
            p.color = lut[255 - i]
            c.drawRect(x, top + h * i / 256f, x + 30f, top + h * (i + 1) / 256f + 1, p)
        }
        val fmt = { v: Float -> if (abs(v) < 0.01f && v != 0f) "%.2e".format(v) else "%.3f".format(v) }
        pText.textAlign = Paint.Align.RIGHT
        c.drawText(fmt(lastMax), x - 8, top + 24, pText)
        c.drawText(fmt(lastMin), x - 8, top + h, pText)
        c.drawText(field.title, width - 16f, top + h + 50, pText)
        pText.textAlign = Paint.Align.LEFT
    }

    // ---------- жесты: в режиме ROI перетаскивание углов / рамки; иначе тап = фокус ----------
    private var drag = -1; private var lx = 0f; private var ly = 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val fx = { x: Float -> ((x - ox()) / scale() / imgW).let { if (flip) 1 - it else it } }
        val fy = { y: Float -> ((y - oy()) / scale() / imgH).let { if (flip) 1 - it else it } }
        if (!roiEditing) {
            if (e.action == MotionEvent.ACTION_UP) onTapFocus?.invoke(e.x, e.y)
            return true
        }
        val px = fx(e.x).coerceIn(0f, 1f); val py = fy(e.y).coerceIn(0f, 1f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 50f / (scale() * imgW)
                val corners = listOf(roi.left to roi.top, roi.right to roi.top, roi.left to roi.bottom, roi.right to roi.bottom)
                drag = corners.indexOfFirst { abs(it.first - px) < tol * 1.5f && abs(it.second - py) < tol * 1.5f * imgW / imgH }
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
            MotionEvent.ACTION_UP -> { roi.sort(); if (roi.width() < 0.05f || roi.height() < 0.05f) roi.set(0.3f, 0.3f, 0.7f, 0.7f); invalidate() }
        }
        return true
    }

    companion object {
        fun jet(t: Float): Int {
            val r = (1.5f - abs(4 * t - 3)).coerceIn(0f, 1f); val g = (1.5f - abs(4 * t - 2)).coerceIn(0f, 1f); val b = (1.5f - abs(4 * t - 1)).coerceIn(0f, 1f)
            return Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
    }
}
