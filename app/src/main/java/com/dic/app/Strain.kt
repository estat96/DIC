package com.dic.app

/**
 * Strain post-processing of a displacement grid.
 *
 * Node record = 7 floats: u, v, ux, uy, vx, vy, zncc  (ux = du/dx …, dimensionless).
 *  - SUBSET: gradients from the IC-GN affine subset shape function (as computed by the DIC core).
 *  - MESH:   Q4 bilinear finite-element mesh built on the DIC grid. Element strains are evaluated
 *            at the element centre (one-point Gauss) and averaged to nodes (nodal smoothing).
 *  - FIT:    local least-squares plane fit of u and v over an N×N node window
 *            (strain-window method, smoother — good for noisy, small strains).
 */
enum class StrainMethod(val title: String) { SUBSET("Subset (IC-GN)"), MESH("Mesh (Q4 FE)"), FIT("Local fit") }

object Strain {
    /**
     * @param h grid spacing expressed in the same length unit as u, v
     *          (step_px when u is in px; step_px*mm_per_px when u is in mm)
     * @param win half-window (FIT only): window is (2·win+1)²
     */
    fun compute(method: StrainMethod, nx: Int, ny: Int, h: Float, src: FloatArray, dst: FloatArray, win: Int = 2) {
        val n = nx * ny
        if (dst !== src) System.arraycopy(src, 0, dst, 0, n * 7)
        if (method == StrainMethod.SUBSET || n == 0) return
        if (method == StrainMethod.MESH) mesh(nx, ny, h, src, dst) else fit(nx, ny, h, src, dst, win)
    }

    private fun ok(a: FloatArray, i: Int) = a[i * 7].isFinite() && a[i * 7 + 1].isFinite()

    private fun mesh(nx: Int, ny: Int, h: Float, s: FloatArray, d: FloatArray) {
        val n = nx * ny
        val acc = DoubleArray(n * 4); val cnt = IntArray(n)
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            val a = j * nx + i; val b = a + 1; val c = a + nx + 1; val e = a + nx   // 00, 10, 11, 01
            if (!ok(s, a) || !ok(s, b) || !ok(s, c) || !ok(s, e)) continue
            // dN/dx at the element centre for a square element of size h
            val ux = ((s[b * 7] + s[c * 7]) - (s[a * 7] + s[e * 7])) / (2 * h)
            val uy = ((s[e * 7] + s[c * 7]) - (s[a * 7] + s[b * 7])) / (2 * h)
            val vx = ((s[b * 7 + 1] + s[c * 7 + 1]) - (s[a * 7 + 1] + s[e * 7 + 1])) / (2 * h)
            val vy = ((s[e * 7 + 1] + s[c * 7 + 1]) - (s[a * 7 + 1] + s[b * 7 + 1])) / (2 * h)
            for (k in intArrayOf(a, b, c, e)) {
                acc[k * 4] += ux.toDouble(); acc[k * 4 + 1] += uy.toDouble(); acc[k * 4 + 2] += vx.toDouble(); acc[k * 4 + 3] += vy.toDouble(); cnt[k]++
            }
        }
        for (k in 0 until n) {
            val b = k * 7
            if (cnt[k] == 0 || !ok(s, k)) { d[b + 2] = Float.NaN; d[b + 3] = Float.NaN; d[b + 4] = Float.NaN; d[b + 5] = Float.NaN; continue }
            d[b + 2] = (acc[k * 4] / cnt[k]).toFloat(); d[b + 3] = (acc[k * 4 + 1] / cnt[k]).toFloat()
            d[b + 4] = (acc[k * 4 + 2] / cnt[k]).toFloat(); d[b + 5] = (acc[k * 4 + 3] / cnt[k]).toFloat()
        }
    }

    private fun fit(nx: Int, ny: Int, h: Float, s: FloatArray, d: FloatArray, w: Int) {
        for (j in 0 until ny) for (i in 0 until nx) {
            val k = j * nx + i; val b = k * 7
            if (!ok(s, k)) { d[b + 2] = Float.NaN; d[b + 3] = Float.NaN; d[b + 4] = Float.NaN; d[b + 5] = Float.NaN; continue }
            // normal equations for f = a + bx + cy
            var sn = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            var su = 0.0; var sxu = 0.0; var syu = 0.0; var sv = 0.0; var sxv = 0.0; var syv = 0.0
            for (jj in maxOf(0, j - w)..minOf(ny - 1, j + w)) for (ii in maxOf(0, i - w)..minOf(nx - 1, i + w)) {
                val q = jj * nx + ii
                if (!ok(s, q)) continue
                val x = (ii - i) * h.toDouble(); val y = (jj - j) * h.toDouble()
                val u = s[q * 7].toDouble(); val v = s[q * 7 + 1].toDouble()
                sn += 1; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
                su += u; sxu += x * u; syu += y * u; sv += v; sxv += x * v; syv += y * v
            }
            if (sn < 6) { d[b + 2] = Float.NaN; d[b + 3] = Float.NaN; d[b + 4] = Float.NaN; d[b + 5] = Float.NaN; continue }
            val m = arrayOf(doubleArrayOf(sn, sx, sy), doubleArrayOf(sx, sxx, sxy), doubleArrayOf(sy, sxy, syy))
            val gu = solve3(m, doubleArrayOf(su, sxu, syu)); val gv = solve3(m, doubleArrayOf(sv, sxv, syv))
            if (gu == null || gv == null) { d[b + 2] = Float.NaN; d[b + 3] = Float.NaN; d[b + 4] = Float.NaN; d[b + 5] = Float.NaN; continue }
            d[b + 2] = gu[1].toFloat(); d[b + 3] = gu[2].toFloat(); d[b + 4] = gv[1].toFloat(); d[b + 5] = gv[2].toFloat()
        }
    }

    private fun solve3(m: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val D = det(m)
        if (kotlin.math.abs(D) < 1e-12) return null
        return DoubleArray(3) { c -> det(Array(3) { i -> DoubleArray(3) { j -> if (j == c) r[i] else m[i][j] } }) / D }
    }
}
