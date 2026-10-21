package com.dic.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Displayed quantity. */
enum class Field(val title: String, val isStrain: Boolean) {
    MAG("|U|", false), U("u", false), V("v", false),
    EXX("εxx", true), EYY("εyy", true), EXY("εxy", true), E1("ε1", true), E2("ε2", true), VM("εeq", true), ZNCC("ZNCC", true)
}

/**
 * Drawing layer above the camera preview (or an empty background in the player):
 * ROI, grid / subsets / deformed mesh, field colour map, vectors, colour bar, scale bar,
 * calibration ruler and a status box.
 * Node record = 7 floats: u, v, ux, uy, vx, vy, zncc (u, v in [unit]; gradients dimensionless).
 */
class OverlayView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity

    var imgW = 1280; var imgH = 720
    var flip = false
    var roi = RectF(0.3f, 0.3f, 0.7f, 0.7f)
    var roiEditing = false
    var showRoi = true
    var onTap: ((Float, Float) -> Unit)? = null
    var onRoiChanged: (() -> Unit)? = null

    var showGrid = false
    var showSubset = false
    var showMesh = false            // deformed Q4 mesh
    var subset = 31
    var step = 10

    var field = Field.MAG
    var unit = "px"
    var unitPerPx = 1f              // u,v unit per image pixel (mm/px or 1)
    var showVectors = true
    var vecScale = 5f
    var limitMin = false; var limitMax = false
    var fixMin = 0f; var fixMax = 1f

    var showScalebar = true
    var mmPerPx = 0f                // 0 → scale bar in px

    // layout insets so nothing hides under the side rail / bottom bar
    var leftInset = 0f; var bottomInset = 0f; var topInset = 0f

    /** Status lines with colours. */
    var info: List<Pair<String, Int>> = emptyList()

    // ruler (calibration)
    var rulerMode = false
    val rulerA = PointF(); val rulerB = PointF()
    var onRulerChanged: (() -> Unit)? = null

    var marker = -1

    private var nx = 0; private var ny = 0
    private var xy = FloatArray(0); private var res = FloatArray(0)
    private var cellPx = 10f
    private var bmp: Bitmap? = null
    var lastMin = 0f; var lastMax = 0f

    private val lut = IntArray(256) { jet(it / 255f) }
    private val pRoi = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Ui.WARN; isAntiAlias = true }
    private val pShade = Paint().apply { color = 0x77000000 }
    private val pVec = Paint().apply { color = Color.WHITE; strokeWidth = 1.5f * d; isAntiAlias = true }
    private val pGrid = Paint().apply { color = 0xFF4DD0E1.toInt(); strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
    private val pMesh = Paint().apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 1f * d; style = Paint.Style.STROKE; isAntiAlias = true }
    private val pSub = Paint().apply { style = Paint.Style.STROKE; color = 0x99FF80FF.toInt(); strokeWidth = 1f * d }
    private val pSubMain = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFF4FD8.toInt(); strokeWidth = 2f * d }
    private val pMark = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * d; isAntiAlias = true }
    private val pText = Paint().apply { color = Ui.TEXT; textSize = 13f * sd; isAntiAlias = true }
    private val pSmall = Paint().apply { color = Ui.TEXT; textSize = 11f * sd; isAntiAlias = true }
    private val pBox = Paint().apply { color = 0xB0101216.toInt(); isAntiAlias = true }
    private val pBmp = Paint().apply { isFilterBitmap = true; alpha = 175 }
    private val pBar = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val pRuler = Paint().apply { color = 0xFF00E5FF.toInt(); strokeWidth = 2.5f * d; isAntiAlias = true }

    // ---------- image <-> view (FIT_CENTER) ----------
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

    /** Grid nodes exactly as the native core builds them. */
    fun previewGrid(): FloatArray {
        val r = roiPixels(); val R = max(5, subset / 2); val m = R + 2
        val x0 = max(r.left, m); val y0 = max(r.top, m); val x1 = min(r.right, imgW - m - 1); val y1 = min(r.bottom, imgH - m - 1)
        if (x1 <= x0 || y1 <= y0) return FloatArray(0)
        val gx = (x1 - x0) / step + 1; val gy = (y1 - y0) / step + 1
        val o = FloatArray(gx * gy * 2); var k = 0
        for (j in 0 until gy) for (i in 0 until gx) { o[k++] = (x0 + i * step).toFloat(); o[k++] = (y0 + j * step).toFloat() }
        return o
    }
    private fun previewCols(): Int {
        val r = roiPixels(); val R = max(5, subset / 2); val m = R + 2
        val x0 = max(r.left, m); val x1 = min(r.right, imgW - m - 1)
        return if (x1 > x0) (x1 - x0) / step + 1 else 1
    }

    fun setGrid(nx: Int, ny: Int, xy: FloatArray, step: Int) {
        this.nx = nx; this.ny = ny; this.xy = xy; cellPx = step.toFloat()
        res = FloatArray(0); bmp = if (nx > 0 && ny > 0) Bitmap.createBitmap(nx, ny, Bitmap.Config.ARGB_8888) else null
        postInvalidate()
    }
    fun clearField() { nx = 0; ny = 0; bmp = null; res = FloatArray(0); postInvalidate() }
    val hasField get() = bmp != null

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
        for (i in 0 until n) { val v = vals[i]; px[i] = if (!v.isFinite()) 0 else lut[(((v - mn) / (mx - mn)).coerceIn(0f, 1f) * 255).toInt()] }
        b.setPixels(px, 0, nx, 0, 0, nx, ny)
        res = r.copyOf()
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
            if (showVectors && res.isNotEmpty()) drawVectors(c, s)
            if (showMesh && res.isNotEmpty()) drawMesh(c)
            drawColorbar(c)
        }
        if (showGrid || showSubset) drawGridPreview(c, s)
        if (marker >= 0 && marker * 2 + 1 < xy.size) { val p = toView(xy[2 * marker], xy[2 * marker + 1]); c.drawCircle(p.x, p.y, 9 * d, pMark) }
        if (showRoi) drawRoi(c)
        if (rulerMode) drawRuler(c)
        if (showScalebar) drawScalebar(c, s)
        drawInfo(c)
    }

    private fun drawVectors(c: Canvas, s: Float) {
        val every = max(1, (nx / 18f).roundToInt())
        val sgn = if (flip) -1 else 1
        val k0 = vecScale * s / unitPerPx
        for (j in 0 until ny step every) for (i in 0 until nx step every) {
            val k = j * nx + i
            val u = res[k * 7]; val v = res[k * 7 + 1]
            if (!u.isFinite()) continue
            val p0 = toView(xy[2 * k], xy[2 * k + 1])
            val ex = p0.x + sgn * u * k0; val ey = p0.y + sgn * v * k0
            c.drawLine(p0.x, p0.y, ex, ey, pVec); c.drawCircle(ex, ey, 2 * d, pVec)
        }
    }

    /** Deformed Q4 mesh, displacements magnified by vecScale. */
    private fun drawMesh(c: Canvas) {
        val every = max(1, (nx / 30f).roundToInt())
        val k0 = vecScale / unitPerPx
        fun pt(k: Int): PointF? { val u = res[k * 7]; val v = res[k * 7 + 1]; if (!u.isFinite()) return null; return toView(xy[2 * k] + u * k0, xy[2 * k + 1] + v * k0) }
        for (j in 0 until ny step every) for (i in 0 until nx step every) {
            val a = pt(j * nx + i) ?: continue
            if (i + every < nx) pt(j * nx + i + every)?.let { c.drawLine(a.x, a.y, it.x, it.y, pMesh) }
            if (j + every < ny) pt((j + every) * nx + i)?.let { c.drawLine(a.x, a.y, it.x, it.y, pMesh) }
        }
    }

    private fun drawGridPreview(c: Canvas, s: Float) {
        val g = if (nx > 0) xy else previewGrid()
        val cols = if (nx > 0) nx else previewCols()
        val n = g.size / 2
        if (n == 0) return
        if (showGrid) {
            val pts = FloatArray(g.size)
            for (i in 0 until n) { val p = toView(g[2 * i], g[2 * i + 1]); pts[2 * i] = p.x; pts[2 * i + 1] = p.y }
            pGrid.strokeWidth = (step * s * 0.3f).coerceIn(1.5f * d, 4 * d)
            c.drawPoints(pts, pGrid)
        }
        if (showSubset) {
            val side = 2 * max(5, subset / 2) + 1
            val h = side / 2f * s
            val every = max(1, ceil(sqrt(n / 40f)).toInt())
            for (i in 0 until n) {
                if ((i % cols) % every != 0 || (i / cols) % every != 0) continue
                val p = toView(g[2 * i], g[2 * i + 1]); c.drawRect(p.x - h, p.y - h, p.x + h, p.y + h, pSub)
            }
            val mid = (n / cols / 2) * cols + cols / 2
            val pc = toView(g[2 * mid], g[2 * mid + 1])
            c.drawRect(pc.x - h, pc.y - h, pc.x + h, pc.y + h, pSubMain)
            label(c, "subset ${side}px" + if (mmPerPx > 0) " = ${Ui.fmt(side * mmPerPx)} mm" else "", pc.x - h, pc.y - h - 6 * d)
        }
    }

    private fun drawRoi(c: Canvas) {
        val r = roiPixels()
        val a = toView(r.left.toFloat(), r.top.toFloat()); val z = toView(r.right.toFloat(), r.bottom.toFloat())
        val vr = RectF(min(a.x, z.x), min(a.y, z.y), max(a.x, z.x), max(a.y, z.y))
        if (roiEditing) {
            c.drawRect(0f, 0f, width.toFloat(), vr.top, pShade); c.drawRect(0f, vr.bottom, width.toFloat(), height.toFloat(), pShade)
            c.drawRect(0f, vr.top, vr.left, vr.bottom, pShade); c.drawRect(vr.right, vr.top, width.toFloat(), vr.bottom, pShade)
            pRoi.style = Paint.Style.FILL
            for (p in listOf(PointF(vr.left, vr.top), PointF(vr.right, vr.top), PointF(vr.left, vr.bottom), PointF(vr.right, vr.bottom))) c.drawCircle(p.x, p.y, 7 * d, pRoi)
            pRoi.style = Paint.Style.STROKE
        }
        c.drawRect(vr, pRoi)
    }

    private fun drawRuler(c: Canvas) {
        val a = toView(rulerA.x, rulerA.y); val b = toView(rulerB.x, rulerB.y)
        c.drawLine(a.x, a.y, b.x, b.y, pRuler)
        for (p in listOf(a, b)) {
            pRuler.style = Paint.Style.STROKE; c.drawCircle(p.x, p.y, 12 * d, pRuler)
            c.drawLine(p.x - 16 * d, p.y, p.x + 16 * d, p.y, pRuler); c.drawLine(p.x, p.y - 16 * d, p.x, p.y + 16 * d, pRuler)
        }
        pRuler.style = Paint.Style.FILL
        val len = hypot(rulerB.x - rulerA.x, rulerB.y - rulerA.y)
        label(c, "%.1f px".format(len), (a.x + b.x) / 2 + 8 * d, (a.y + b.y) / 2 - 10 * d)
    }

    private fun drawScalebar(c: Canvas, s: Float) {
        val targetImg = imgW * 0.18f
        val (lenImg, txt) = if (mmPerPx > 0) {
            val mm = nice(targetImg * mmPerPx); (mm / mmPerPx) to (if (mm >= 1) "${Ui.fmtNum(mm)} mm" else "${Ui.fmtNum(mm * 1000)} µm")
        } else { val p = nice(targetImg); p to "${Ui.fmtNum(p)} px" }
        val lenV = lenImg * s
        val right = width - 70 * d; val y = height - bottomInset - 22 * d
        val w = max(lenV, pText.measureText(txt)) + 20 * d
        c.drawRoundRect(right - w, y - 26 * d, right, y + 12 * d, 8 * d, 8 * d, pBox)
        val x0 = right - 10 * d - lenV
        c.drawRect(x0, y - 3 * d, x0 + lenV, y + 3 * d, pBar)
        c.drawRect(x0, y - 8 * d, x0 + 2 * d, y + 8 * d, pBar); c.drawRect(x0 + lenV - 2 * d, y - 8 * d, x0 + lenV, y + 8 * d, pBar)
        c.drawText(txt, x0 + (lenV - pText.measureText(txt)) / 2, y - 10 * d, pText)
    }

    private fun drawColorbar(c: Canvas) {
        val bw = 14 * d; val x = width - bw - 16 * d; val top = topInset + 36 * d; val h = height - top - bottomInset - 80 * d
        if (h < 40 * d) return
        val p = Paint()
        for (i in 0 until 128) { p.color = lut[255 - i * 2]; c.drawRect(x, top + h * i / 128f, x + bw, top + h * (i + 1) / 128f + 1, p) }
        val title = field.title + if (field.isStrain) "" else " [$unit]"
        val tw = maxOf(pText.measureText(title), pSmall.measureText(Ui.fmt(lastMax)) + 12 * d)
        c.drawRoundRect(x - tw - 8 * d, top - 30 * d, x + bw + 6 * d, top - 6 * d, 6 * d, 6 * d, pBox)
        c.drawText(title, x + bw - pText.measureText(title), top - 12 * d, pText)
        pSmall.textAlign = Paint.Align.RIGHT
        for (q in 0..4) {
            val v = lastMax - (lastMax - lastMin) * q / 4f
            val y = top + h * q / 4f
            val t = (if (q == 0 && limitMax) "≥" else if (q == 4 && limitMin) "≤" else "") + Ui.fmt(v)
            c.drawRect(x - 4 * d - pSmall.measureText(t) - 4 * d, y - 9 * d, x - 2 * d, y + 5 * d, pBox)
            c.drawText(t, x - 5 * d, y + 3 * d, pSmall)
        }
        pSmall.textAlign = Paint.Align.LEFT
    }

    private fun drawInfo(c: Canvas) {
        if (info.isEmpty()) return
        val lh = 18 * sd
        val w = info.maxOf { pText.measureText(it.first) } + 20 * d
        val x = leftInset + 10 * d; val y = topInset + 10 * d
        c.drawRoundRect(x, y, x + w, y + info.size * lh + 12 * d, 10 * d, 10 * d, pBox)
        var ty = y + 6 * d + lh - 4 * d
        for ((t, col) in info) { pText.color = col; c.drawText(t, x + 10 * d, ty, pText); ty += lh }
        pText.color = Ui.TEXT
    }

    private fun label(c: Canvas, t: String, x: Float, y: Float) {
        val w = pSmall.measureText(t)
        c.drawRoundRect(x - 4 * d, y - 13 * d, x + w + 4 * d, y + 4 * d, 4 * d, 4 * d, pBox)
        c.drawText(t, x, y, pSmall)
    }

    // ---------- touch ----------
    private var drag = -1; private var lx = 0f; private var ly = 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val ip = toImage(e.x, e.y)
        if (rulerMode) {
            val x = ip.x.coerceIn(0f, imgW.toFloat()); val y = ip.y.coerceIn(0f, imgH.toFloat())
            when (e.action) {
                MotionEvent.ACTION_DOWN -> drag = if (hypot(x - rulerA.x, y - rulerA.y) < hypot(x - rulerB.x, y - rulerB.y)) 0 else 1
                MotionEvent.ACTION_MOVE -> { if (drag == 0) rulerA.set(x, y) else rulerB.set(x, y); onRulerChanged?.invoke(); invalidate() }
            }
            return true
        }
        if (!roiEditing) {
            if (e.action == MotionEvent.ACTION_UP) onTap?.invoke(e.x, e.y)
            return true
        }
        val px = (ip.x / imgW).coerceIn(0f, 1f); val py = (ip.y / imgH).coerceIn(0f, 1f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 28 * d / (scale() * imgW)
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

    companion object {
        fun jet(t: Float): Int {
            val r = (1.5f - abs(4 * t - 3)).coerceIn(0f, 1f); val g = (1.5f - abs(4 * t - 2)).coerceIn(0f, 1f); val b = (1.5f - abs(4 * t - 1)).coerceIn(0f, 1f)
            return Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
        fun nice(v: Float): Float {
            val m = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
            return listOf(1f, 2f, 5f, 10f).map { it * m }.last { it <= v * 1.0001f }
        }

        /** Value of a field at node i (Green–Lagrange strains from the displacement gradient). */
        fun value(f: Field, r: FloatArray, i: Int): Float {
            val b = i * 7
            val u = r[b]; val v = r[b + 1]; val ux = r[b + 2]; val uy = r[b + 3]; val vx = r[b + 4]; val vy = r[b + 5]
            val exx = ux + 0.5f * (ux * ux + vx * vx)
            val eyy = vy + 0.5f * (uy * uy + vy * vy)
            val exy = 0.5f * (uy + vx + ux * uy + vx * vy)
            return when (f) {
                Field.MAG -> hypot(u, v); Field.U -> u; Field.V -> v
                Field.EXX -> exx; Field.EYY -> eyy; Field.EXY -> exy
                Field.E1 -> (exx + eyy) / 2 + sqrt(((exx - eyy) / 2).pow(2) + exy * exy)
                Field.E2 -> (exx + eyy) / 2 - sqrt(((exx - eyy) / 2).pow(2) + exy * exy)
                Field.VM -> (sqrt(2f) / 3f) * sqrt((exx - eyy).pow(2) + exx * exx + eyy * eyy + 6 * exy * exy)
                Field.ZNCC -> r[b + 6]
            }
        }
    }
    fun value(f: Field, r: FloatArray, i: Int) = Companion.value(f, r, i)
}
