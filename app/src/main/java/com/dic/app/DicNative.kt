package com.dic.app

import java.nio.ByteBuffer

object DicNative {
    init { System.loadLibrary("dic") }
    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(h: Long)
    @JvmStatic external fun setReference(h: Long, y: ByteBuffer, w: Int, hh: Int, stride: Int,
        x0: Int, y0: Int, x1: Int, y1: Int, subset: Int, step: Int, search: Int, minZncc: Float): IntArray
    @JvmStatic external fun gridCoords(h: Long, xy: FloatArray)
    @JvmStatic external fun process(h: Long, y: ByteBuffer, w: Int, hh: Int, stride: Int, out: FloatArray): Float
    @JvmStatic external fun sharpness(y: ByteBuffer, w: Int, h: Int, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float
}
