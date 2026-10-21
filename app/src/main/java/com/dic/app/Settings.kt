package com.dic.app

import android.content.Context

/** Persistent parameters. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("dic", Context.MODE_PRIVATE)

    // DIC
    var subset = 31; var step = 10; var search = 10
    var minZncc = 0.7f; var everyN = 1
    var resIndex = 1
    var incremental = false
    var strain = StrainMethod.SUBSET
    var fitWin = 2

    // calibration
    var mmPerPx = 0f
    var calSource = ""          // "ruler", "lens", "manual"
    var calFocus = 0f           // lens focus distance (diopters) at calibration
    var calRes = 0              // analysed image width at calibration

    // force
    var forcePort = 5000; var forceFactor = 1f; var forceUnit = "N"

    // specimen (for stress)
    var width = 10f; var thickness = 2f; var axisY = true

    // view / misc
    var showScalebar = true
    var folderUri: String? = null
    var roi = floatArrayOf(0.3f, 0.3f, 0.7f, 0.7f)

    init { load() }

    fun load() {
        subset = sp.getInt("subset", 31); step = sp.getInt("step", 10); search = sp.getInt("search", 10)
        minZncc = sp.getFloat("minZncc", 0.7f); everyN = sp.getInt("everyN", 1)
        resIndex = sp.getInt("res", 1); incremental = sp.getBoolean("inc", false)
        strain = runCatching { StrainMethod.valueOf(sp.getString("strain", "SUBSET")!!) }.getOrDefault(StrainMethod.SUBSET)
        fitWin = sp.getInt("fitWin", 2)
        mmPerPx = sp.getFloat("mmPerPx", 0f); calSource = sp.getString("calSrc", "")!!
        calFocus = sp.getFloat("calFocus", 0f); calRes = sp.getInt("calRes", 0)
        forcePort = sp.getInt("fPort", 5000); forceFactor = sp.getFloat("fFactor", 1f); forceUnit = sp.getString("fUnit", "N")!!
        width = sp.getFloat("spW", 10f); thickness = sp.getFloat("spT", 2f); axisY = sp.getBoolean("axisY", true)
        showScalebar = sp.getBoolean("scalebar", true)
        folderUri = sp.getString("folder", null)
        roi = floatArrayOf(sp.getFloat("r0", .3f), sp.getFloat("r1", .3f), sp.getFloat("r2", .7f), sp.getFloat("r3", .7f))
    }

    fun save() = sp.edit()
        .putInt("subset", subset).putInt("step", step).putInt("search", search)
        .putFloat("minZncc", minZncc).putInt("everyN", everyN)
        .putInt("res", resIndex).putBoolean("inc", incremental).putString("strain", strain.name).putInt("fitWin", fitWin)
        .putFloat("mmPerPx", mmPerPx).putString("calSrc", calSource).putFloat("calFocus", calFocus).putInt("calRes", calRes)
        .putInt("fPort", forcePort).putFloat("fFactor", forceFactor).putString("fUnit", forceUnit)
        .putFloat("spW", width).putFloat("spT", thickness).putBoolean("axisY", axisY)
        .putBoolean("scalebar", showScalebar).putString("folder", folderUri)
        .putFloat("r0", roi[0]).putFloat("r1", roi[1]).putFloat("r2", roi[2]).putFloat("r3", roi[3]).apply()

    /** Reset DIC parameters (calibration, folder, ROI, force and specimen are kept). */
    fun resetDefaults() {
        subset = 31; step = 10; search = 10; minZncc = 0.7f; everyN = 1; resIndex = 1
        incremental = false; strain = StrainMethod.SUBSET; fitWin = 2
        save()
    }
}
