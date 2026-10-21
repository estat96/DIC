package com.dic.app

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.*

/**
 * Pixel → physical size calibration.
 *
 * Methods:
 *  1. RULER  – user marks a known length on the specimen (most accurate, recommended).
 *  2. LENS   – pinhole estimate from camera metadata: mm/px = Z · p / f, where Z is the focus
 *              (object) distance reported by the lens, p the effective pixel pitch of the analysed
 *              image and f the focal length. Accuracy depends on how well the phone calibrates its
 *              focus distance (LENS_INFO_FOCUS_DISTANCE_CALIBRATION).
 *  3. MANUAL – typed mm/px.
 *
 * Scale is only valid for the focus distance it was obtained at, so the focus distance at
 * calibration time is stored and compared with the current locked focus.
 */
class LensInfo(ch: CameraCharacteristics) {
    val focal: Float = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f       // mm
    val aperture: Float = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f       // f-number
    val sensorW: Float = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f                     // mm
    val sensorH: Float = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.height ?: 0f
    val pixW: Int = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: 0
    val activeW: Int = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.width() ?: pixW
    val activeH: Int = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.height() ?: 0
    val minFocus: Float = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f                   // diopters
    val focusCal: Int = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ?: 0
    /** fx in active-array pixels from intrinsic calibration (0 if not provided). */
    val fx: Float = runCatching { ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.get(0) ?: 0f }.getOrDefault(0f)

    val focusCalName get() = when (focusCal) {
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
        else -> "uncalibrated"
    }
    val pitch get() = if (pixW > 0) sensorW / pixW else 0f   // mm per sensor pixel

    data class Estimate(
        val zMm: Float, val mmPerPx: Float, val fovW: Float, val fovH: Float,
        val dofNear: Float, val dofFar: Float, val reliable: Boolean, val source: String
    )

    /**
     * @param diopters current lens focus distance (1/m)
     * @param imgW analysed image width; the analysed stream is assumed to use the full sensor width.
     */
    fun estimate(diopters: Float, imgW: Int, imgH: Int): Estimate? {
        if (diopters <= 0.01f || focal <= 0f || imgW <= 0) return null
        val z = 1000f / diopters                                  // mm, object distance
        val binning = activeW.toFloat() / imgW                     // sensor pixels per image pixel
        val mmPerPx: Float
        val src: String
        if (fx > 0f) { mmPerPx = z / fx * binning; src = "intrinsic fx" }
        else if (pitch > 0f) {
            // thin lens: magnification m = f/(z−f)
            mmPerPx = pitch * binning * (z - focal) / focal; src = "focal length + sensor size"
        } else return null
        // depth of field, circle of confusion = 2 image pixels
        val c = 2f * mmPerPx * focal / max(z - focal, 1e-3f)       // CoC on the sensor, mm
        val n = if (aperture > 0f) aperture else 1.8f
        val hyper = focal * focal / (n * c) + focal
        val near = z * (hyper - focal) / (hyper + z - 2 * focal)
        val far = if (z < hyper) z * (hyper - focal) / (hyper - z) else Float.POSITIVE_INFINITY
        return Estimate(z, mmPerPx, mmPerPx * imgW, mmPerPx * imgH, near, far,
            focusCal != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED, src)
    }
}

/** Phone orientation from gravity (device axes: x right, y up, z out of the screen; landscape app). */
data class Tilt(val outOfPlane: Float, val inPlane: Float, val lookingDown: Boolean) {
    companion object {
        fun from(gx: Float, gy: Float, gz: Float): Tilt {
            val g = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-3f)
            val down = abs(gz) > 0.7f * g
            return if (down) {
                // camera looks down on a horizontal specimen: tilt = angle between optical axis and vertical
                val t = Math.toDegrees(acos((abs(gz) / g).toDouble())).toFloat()
                Tilt(t, Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat(), true)
            } else {
                // camera looks at a vertical specimen: optical axis should be horizontal
                val t = Math.toDegrees(asin((gz / g).toDouble())).toFloat()
                // in-plane: image x axis vs horizontal (landscape → gravity along device x)
                val r = Math.toDegrees(atan2(gy.toDouble(), abs(gx).toDouble())).toFloat()
                Tilt(t, r, false)
            }
        }
    }
    /** Relative scale variation across a field of width [fovMm] at distance [zMm]: ±(W/2)·tanθ / Z. */
    fun scaleError(fovMm: Float, zMm: Float) = if (zMm > 0) (fovMm / 2) * tan(Math.toRadians(abs(outOfPlane).toDouble())).toFloat() / zMm else Float.NaN
}
