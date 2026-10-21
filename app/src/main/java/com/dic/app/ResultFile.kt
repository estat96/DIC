package com.dic.app

import android.content.Context
import android.net.Uri

/**
 * CSV result file (v3):
 *   # DIC v3
 *   # mode=lagrangian|incremental
 *   # image=W,H
 *   # grid=nx,ny,step,subset
 *   # scale_mm_per_px=…   (u, v are in mm when > 0, otherwise px)
 *   # strain=SUBSET|MESH|FIT
 *   # force_unit=N
 *   frame,t_ms,x_px,y_px,u,v,exx,eyy,exy,zncc,ux,uy,vx,vy,force
 * Every node of every recorded frame is written (NaN = rejected). Frame 0 is the reference.
 * ux…vy are the IC-GN subset displacement gradients (F − I); exx… use the selected strain method.
 */
class ResultData(
    val name: String, val imgW: Int, val imgH: Int, val nx: Int, val ny: Int, val step: Int,
    val mode: String, val scale: Float, val forceUnit: String, val xy: FloatArray,
    val frames: IntArray, val times: FloatArray, val forces: FloatArray, val vals: List<FloatArray>
) {
    val unit get() = if (scale > 0) "mm" else "px"
    val unitPerPx get() = if (scale > 0) scale else 1f
    val hasForce get() = forces.any { it.isFinite() }
    val n get() = nx * ny

    /** Values with the requested strain method applied (cached per method). */
    private val cache = HashMap<String, List<FloatArray>>()
    fun withStrain(m: StrainMethod, win: Int): List<FloatArray> = cache.getOrPut("$m$win") {
        if (m == StrainMethod.SUBSET) vals
        else vals.map { v -> FloatArray(v.size).also { Strain.compute(m, nx, ny, step * unitPerPx, v, it, win) } }
    }

    companion object {
        @Volatile var last: Pair<Uri, ResultData>? = null

        fun load(ctx: Context, uri: Uri): ResultData {
            last?.let { if (it.first == uri) return it.second }
            var imgW = 0; var imgH = 0; var nx = 0; var ny = 0; var step = 10; var mode = "lagrangian"; var scale = 0f; var fu = "N"
            val frames = ArrayList<Int>(); val times = ArrayList<Float>(); val forces = ArrayList<Float>(); val vals = ArrayList<FloatArray>()
            var xy = FloatArray(0)
            var curFrame = Int.MIN_VALUE; var row = 0; var buf = FloatArray(0)
            val name = (uri.lastPathSegment ?: "file").substringAfterLast('/').substringAfterLast(':')
            val f = FloatArray(15)
            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    if (line[0] == '#') {
                        val l = line.removePrefix("#").trim()
                        when {
                            l.startsWith("image=") -> l.removePrefix("image=").split(',').let { imgW = it[0].toInt(); imgH = it[1].toInt() }
                            l.startsWith("grid=") -> l.removePrefix("grid=").split(',').let { nx = it[0].toInt(); ny = it[1].toInt(); step = it[2].toInt() }
                            l.startsWith("mode=") -> mode = l.removePrefix("mode=")
                            l.startsWith("scale_mm_per_px=") -> scale = l.substringAfter('=').toFloatOrNull() ?: 0f
                            l.startsWith("force_unit=") -> fu = l.substringAfter('=')
                        }
                        continue
                    }
                    if (line[0] == 'f') continue
                    var k = 0; var st = 0
                    for (i in 0..line.length) {
                        if (i == line.length || line[i] == ',') {
                            if (k < 15) f[k++] = line.substring(st, i).toFloatOrNull() ?: Float.NaN
                            st = i + 1
                        }
                    }
                    val n = nx * ny
                    if (n == 0) error("no grid description (# grid=) — file from an old version")
                    if (xy.isEmpty()) xy = FloatArray(n * 2)
                    val fr = f[0].toInt()
                    if (fr != curFrame || row >= n) {
                        curFrame = fr; row = 0
                        buf = FloatArray(n * 7) { Float.NaN }
                        frames.add(fr); times.add(f[1]); vals.add(buf)
                        forces.add(if (k >= 15) f[14] else Float.NaN)
                    }
                    if (vals.size == 1) { xy[2 * row] = f[2]; xy[2 * row + 1] = f[3] }
                    val b = row * 7
                    buf[b] = f[4]; buf[b + 1] = f[5]; buf[b + 6] = f[9]
                    if (k >= 14) { buf[b + 2] = f[10]; buf[b + 3] = f[11]; buf[b + 4] = f[12]; buf[b + 5] = f[13] }
                    else { buf[b + 2] = f[6]; buf[b + 5] = f[7]; buf[b + 3] = f[8]; buf[b + 4] = f[8] }
                    row++
                }
            }
            if (vals.isEmpty()) error("no data")
            if (imgW == 0) {
                imgW = ((0 until nx * ny).maxOf { xy[2 * it] } + step).toInt(); imgH = ((0 until nx * ny).maxOf { xy[2 * it + 1] } + step).toInt()
            }
            return ResultData(name, imgW, imgH, nx, ny, step, mode, scale, fu, xy,
                frames.toIntArray(), times.toFloatArray(), forces.toFloatArray(), vals).also { last = uri to it }
        }
    }
}
