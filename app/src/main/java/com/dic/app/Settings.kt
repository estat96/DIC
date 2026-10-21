package com.dic.app

import android.content.Context

/** Параметры, сохраняемые между запусками. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("dic", Context.MODE_PRIVATE)
    var subset = 31; var step = 10; var search = 10
    var minZncc = 0.7f; var mmPerPx = 0f; var everyN = 1
    var resIndex = 1                 // 0: 640x480, 1: 1280x720, 2: 1920x1080
    var incremental = false          // false — к первому кадру, true — кадр к кадру
    var folderUri: String? = null    // папка SAF; null — папка приложения
    var roi = floatArrayOf(0.3f, 0.3f, 0.7f, 0.7f)

    init { load() }

    fun load() {
        subset = sp.getInt("subset", 31); step = sp.getInt("step", 10); search = sp.getInt("search", 10)
        minZncc = sp.getFloat("minZncc", 0.7f); mmPerPx = sp.getFloat("mmPerPx", 0f); everyN = sp.getInt("everyN", 1)
        resIndex = sp.getInt("res", 1); incremental = sp.getBoolean("inc", false)
        folderUri = sp.getString("folder", null)
        roi = floatArrayOf(sp.getFloat("r0", .3f), sp.getFloat("r1", .3f), sp.getFloat("r2", .7f), sp.getFloat("r3", .7f))
    }

    fun save() = sp.edit().putInt("subset", subset).putInt("step", step).putInt("search", search)
        .putFloat("minZncc", minZncc).putFloat("mmPerPx", mmPerPx).putInt("everyN", everyN)
        .putInt("res", resIndex).putBoolean("inc", incremental).putString("folder", folderUri)
        .putFloat("r0", roi[0]).putFloat("r1", roi[1]).putFloat("r2", roi[2]).putFloat("r3", roi[3]).apply()

    /** Сброс параметров расчёта к значениям по умолчанию (папка и ROI сохраняются). */
    fun resetDefaults() {
        subset = 31; step = 10; search = 10; minZncc = 0.7f; mmPerPx = 0f; everyN = 1; resIndex = 1; incremental = false
        save()
    }
}
