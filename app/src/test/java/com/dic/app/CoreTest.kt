package com.dic.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CoreTest {
    private fun field(nx: Int, ny: Int, h: Float, f: (Float, Float) -> Pair<Float, Float>, noise: Float): FloatArray {
        val s = FloatArray(nx * ny * 7); val r = Random(1)
        for (j in 0 until ny) for (i in 0 until nx) {
            val k = j * nx + i; val (u, v) = f(i * h, j * h)
            s[k * 7] = u + noise * r.nextGaussian().toFloat(); s[k * 7 + 1] = v + noise * r.nextGaussian().toFloat(); s[k * 7 + 6] = 1f
        }
        return s
    }

    @Test fun strainMethodsRecoverLinearField() {
        val nx = 21; val ny = 15; val h = 10f
        val s = field(nx, ny, h, { x, y -> (0.01f * x + 0.002f * y) to (-0.003f * x + 0.02f * y) }, 0.005f)
        s[(7 * nx + 10) * 7] = Float.NaN
        for (m in listOf(StrainMethod.MESH, StrainMethod.FIT)) {
            val d = FloatArray(s.size); Strain.compute(m, nx, ny, h, s, d, 2)
            var c = 0; val e = DoubleArray(4)
            for (k in 0 until nx * ny) if (d[k * 7 + 2].isFinite()) {
                e[0] += Math.abs(d[k * 7 + 2] - 0.01); e[1] += Math.abs(d[k * 7 + 3] - 0.002)
                e[2] += Math.abs(d[k * 7 + 4] + 0.003); e[3] += Math.abs(d[k * 7 + 5] - 0.02); c++
            }
            println("$m valid $c mean err ${e.map { it / c }}")
            assertTrue("$m too few valid nodes: $c", c > nx * ny * 0.9)
            for (x in e) assertTrue("$m gradient error ${x / c}", x / c < 1.5e-3)
        }
    }

    @Test fun tensileProperties() {
        // linear-elastic E = 200 GPa up to 300 MPa, then linear hardening (Et = 2 GPa) to 450 MPa; ν = 0.3
        val nx = 11; val ny = 21; val step = 10; val scale = 0.01f; val area = 20f
        val xy = FloatArray(nx * ny * 2)
        for (j in 0 until ny) for (i in 0 until nx) { val k = j * nx + i; xy[2 * k] = 100f + i * step; xy[2 * k + 1] = 100f + j * step }
        val nf = 300
        val vals = ArrayList<FloatArray>(); val forces = FloatArray(nf); val times = FloatArray(nf) { it * 100f }
        for (f in 0 until nf) {
            val eps = 0.08f * f / (nf - 1)
            val sig = if (eps <= 300f / 200000f) 200000f * eps else 300f + 2000f * (eps - 300f / 200000f)
            forces[f] = sig.coerceAtMost(450f) * area
            val epsT = -0.3f * eps
            val v = FloatArray(nx * ny * 7)
            for (k in 0 until nx * ny) {
                val x = (xy[2 * k] - 100f) * scale; val y = (xy[2 * k + 1] - 100f) * scale
                v[k * 7] = epsT * x; v[k * 7 + 1] = eps * y; v[k * 7 + 2] = epsT; v[k * 7 + 5] = eps; v[k * 7 + 6] = 1f
            }
            vals += v
        }
        val d = ResultData("t", 400, 400, nx, ny, step, "lagrangian", scale, "N", xy, IntArray(nf) { it }, times, forces, vals)
        val c = Mech.curves(d, d.withStrain(StrainMethod.MESH, 2), axisY = true, extensometer = true, area = area)
        val p = Mech.props(c, 0f, 0f)!!
        println("E=${p.e} nu=${p.nu} Rp=${p.rp02} Rm=${p.rm} L0=${c.gaugeL0}")
        assertEquals(200000f, p.e, 2000f)
        assertEquals(0.3f, p.nu, 0.01f)
        assertEquals(304f, p.rp02, 3f)          // 300 + Et·(0.002·E/(E−Et))
        assertEquals(450f, p.rm, 0.5f)
    }
}
