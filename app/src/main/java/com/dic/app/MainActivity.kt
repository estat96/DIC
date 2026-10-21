package com.dic.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var cfg: Settings
    private lateinit var storage: Storage
    private val resolutions = arrayOf(Size(640, 480), Size(1280, 720), Size(1920, 1080))

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var btnRun: Button
    private lateinit var btnRoi: Button
    private lateinit var btnField: Button
    private lateinit var btnGrid: Button
    private lateinit var btnSub: Button
    private lateinit var btnReset: Button
    private lateinit var btnAf: Button
    private lateinit var btnLock: Button
    private lateinit var seekFocus: SeekBar
    private lateinit var lblFocus: TextView

    private var camera: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val handle = DicNative.create()

    // ---- фокус ----
    private var manualFocus = false              // false: автофокус (тап), true: ползунок
    private var focusLocked = false
    private var minFocusDist = 0f                // диоптрии; 0 — фиксированный фокус
    private var manualDist = 0f                  // диоптрии
    @Volatile private var lastLensDist = 0f      // фактическая дистанция из результатов захвата
    private var lockedDist = 0f
    private var torch = false

    // ---- эксперимент ----
    @Volatile private var running = false
    @Volatile private var wantReference = false
    private var frame = 0
    private var t0 = 0L
    private var out = FloatArray(0)
    private var gridXY = FloatArray(0)
    private var nx = 0; private var ny = 0
    private var writer: BufferedWriter? = null
    private var csvName = ""
    private var procMs = 0f; private var fps = 0f; private var lastTs = 0L
    private var valid = 0
    private var sharp = 0f
    private var frameW = 0; private var frameH = 0

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            cfg.folderUri = uri.toString(); cfg.save()
            toast("Папка: " + storage.folderLabel())
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Settings(this); storage = Storage(this, cfg)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else toast("Нужен доступ к камере")
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; minHeight = 0; minimumHeight = 0; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = OverlayView(this).apply {
            roi.set(cfg.roi[0], cfg.roi[1], cfg.roi[2], cfg.roi[3])
            subset = cfg.subset; step = cfg.step
            unit = if (cfg.mmPerPx > 0) "мм" else "px"
            onTap = { x, y -> focusAt(x, y) }
            onRoiChanged = { cfg.roi = floatArrayOf(roi.left, roi.top, roi.right, roi.bottom); cfg.save() }
        }
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        btnRun = btn("▶ Старт") { if (running) stopRun() else startRun() }.apply { setBackgroundColor(0xFF2E7D32.toInt()); setTextColor(Color.WHITE) }
        btnRoi = btn("ROI: правка") { toggleRoi() }
        btnGrid = btn("Сетка: скрыта") { overlay.showGrid = !overlay.showGrid; updateToggles() }
        btnSub = btn("Подмнож.: скрыто") { overlay.showSubset = !overlay.showSubset; updateToggles() }
        btnField = btn("Поле: |U|") { nextField() }
        btnReset = btn("Сброс эталона") { if (running && !cfg.incremental) wantReference = true }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt()); setPadding(6, 6, 6, 6)
            addView(btnRun); addView(btnRoi); addView(btnGrid); addView(btnSub); addView(btnField); addView(btnReset)
            addView(btn("Вид…") { viewDialog() })
            addView(btn("Настройки") { settingsDialog() })
            addView(btn("Результаты") { resultsDialog() })
            addView(btn("Плеер") { openPlayer(null) })
            addView(btn("Вспышка") { torch = !torch; camera?.cameraControl?.enableTorch(torch) })
        }
        root.addView(ScrollView(this).apply { addView(bar) }, FrameLayout.LayoutParams(240, MATCH_PARENT, Gravity.START))

        // панель фокуса внизу
        btnAf = Button(this).apply { isAllCaps = false; setOnClickListener { toggleAfMode() } }
        btnLock = Button(this).apply { isAllCaps = false; setOnClickListener { toggleLock() } }
        seekFocus = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                    if (!user) return
                    manualDist = minFocusDist * p / 1000f; applyCameraOptions(); updateFocusLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        lblFocus = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(12, 0, 12, 0) }
        val fbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0x99000000.toInt()); setPadding(8, 0, 8, 0)
            addView(btnAf); addView(seekFocus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(lblFocus); addView(btnLock)
        }
        root.addView(fbar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { leftMargin = 240; rightMargin = 120 })
        setContentView(root)
        updateToggles(); updateFocusUi()
    }

    private fun updateToggles() {
        btnGrid.text = if (overlay.showGrid) "Сетка: видна" else "Сетка: скрыта"
        btnSub.text = if (overlay.showSubset) "Подмнож.: видно" else "Подмнож.: скрыто"
        btnRoi.text = if (overlay.roiEditing) "ROI: правка" else "ROI: зафикс."
        btnReset.isEnabled = !cfg.incremental
        overlay.invalidate()
    }

    // ================= камера =================
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val sel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(resolutions[cfg.resIndex], ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val pb = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
            // читаем фактическое положение линзы, чтобы зафиксировать именно его
            Camera2Interop.Extender(pb).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastLensDist = it }
                }
            })
            val pv = pb.build()
            pv.surfaceProvider = preview.surfaceProvider
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(sel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            an.setAnalyzer(exec) { analyze(it) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an).also { cam ->
                minFocusDist = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                cam.cameraControl.enableTorch(torch)
            }
            applyCameraOptions(); updateFocusUi()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Единая точка управления параметрами Camera2.
     * Авто и не зафиксировано — штатный непрерывный автофокус.
     * Ручной или зафиксировано — AF выключен, линза стоит на заданной дистанции;
     * при фиксации дополнительно блокируются экспозиция и баланс белого, выключается стабилизация.
     */
    private fun applyCameraOptions() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        if (!manualFocus && !focusLocked) { c2.clearCaptureRequestOptions(); return }
        val b = CaptureRequestOptions.Builder()
        if (minFocusDist > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, if (focusLocked) lockedDist else manualDist)
        }
        if (focusLocked) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        c2.setCaptureRequestOptions(b.build())
    }

    private fun focusAt(x: Float, y: Float) {
        if (focusLocked) { toast("Фокус зафиксирован — снимите 🔒, чтобы изменить"); return }
        if (manualFocus) { toast("Ручной фокус: используйте ползунок"); return }
        val pt = preview.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
    }

    private fun toggleAfMode() {
        if (focusLocked) { toast("Сначала снимите 🔒"); return }
        if (minFocusDist <= 0f) { toast("У этой камеры фиксированный фокус"); return }
        manualFocus = !manualFocus
        if (manualFocus) { manualDist = lastLensDist.coerceIn(0f, minFocusDist); camera?.cameraControl?.cancelFocusAndMetering() }
        applyCameraOptions(); updateFocusUi()
    }

    private fun toggleLock() {
        focusLocked = !focusLocked
        if (focusLocked) lockedDist = if (manualFocus) manualDist else lastLensDist.coerceIn(0f, minFocusDist)
        applyCameraOptions(); updateFocusUi()
    }

    private fun updateFocusUi() {
        btnAf.text = if (manualFocus) "Ручной" else "Авто"
        btnLock.text = if (focusLocked) "🔒 Зафикс." else "🔓 Зафиксировать"
        btnLock.setBackgroundColor(if (focusLocked) 0xFFC62828.toInt() else 0xFF455A64.toInt()); btnLock.setTextColor(Color.WHITE)
        seekFocus.isEnabled = manualFocus && !focusLocked
        btnAf.isEnabled = !focusLocked
        if (minFocusDist > 0f) seekFocus.progress = ((if (manualFocus) manualDist else lastLensDist) / minFocusDist * 1000).toInt()
        updateFocusLabel()
    }

    private fun updateFocusLabel() {
        val d = when { focusLocked -> lockedDist; manualFocus -> manualDist; else -> lastLensDist }
        lblFocus.text = when {
            minFocusDist <= 0f -> "фикс. фокус"
            d <= 0.001f -> "∞"
            else -> "%.1f см".format(100f / d)
        }
    }

    // ================= обработка кадров =================
    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(img: ImageProxy) {
        try {
            val w = img.width; val h = img.height
            val pl = img.planes[0]
            val buf = pl.buffer; val stride = pl.rowStride
            frameW = w; frameH = h
            if (overlay.imgW != w || overlay.imgH != h || overlay.flip != (img.imageInfo.rotationDegrees == 180)) {
                overlay.imgW = w; overlay.imgH = h; overlay.flip = img.imageInfo.rotationDegrees == 180
                overlay.postInvalidate()
            }
            val now = System.nanoTime()
            if (lastTs != 0L) fps = 0.9f * fps + 0.1f * (1e9f / (now - lastTs))
            lastTs = now
            val r = overlay.roiPixels()
            sharp = DicNative.sharpness(buf, w, h, stride, r.left, r.top, r.right, r.bottom)

            if (running) {
                if (wantReference) {
                    wantReference = false
                    DicNative.setIncremental(handle, cfg.incremental)
                    val g = DicNative.setReference(handle, buf, w, h, stride, r.left, r.top, r.right, r.bottom, cfg.subset, cfg.step, cfg.search, cfg.minZncc)
                    nx = g[0]; ny = g[1]
                    gridXY = FloatArray(nx * ny * 2); DicNative.gridCoords(handle, gridXY)
                    out = FloatArray(nx * ny * 7)
                    for (i in 0 until nx * ny) out[i * 7 + 6] = 1f   // эталон: нулевые смещения
                    overlay.setGrid(nx, ny, gridXY, cfg.step)
                    if (frame == 0) writeHeader(w, h)
                    else writer?.write("# reference reset at frame $frame\n")
                    writeFrame()
                } else if (nx * ny > 0) {
                    procMs = DicNative.process(handle, buf, w, h, stride, out)
                    frame++
                    valid = (0 until nx * ny).count { out[it * 7].isFinite() }
                    overlay.setResult(out)
                    if (frame % cfg.everyN == 0) writeFrame()
                }
            }
            runOnUiThread { if (!manualFocus && !focusLocked) updateFocusLabel() }
            updateInfo()
        } catch (e: Exception) {
            runOnUiThread { toast("Ошибка: ${e.message}") }
        } finally { img.close() }
    }

    private fun updateInfo() {
        val sb = StringBuilder()
        sb.append("%dx%d  %.0f fps  резкость %.0f".format(overlay.imgW, overlay.imgH, fps, sharp))
        sb.append(if (cfg.incremental) "\nрежим: кадр→кадр" else "\nрежим: к 1-му кадру")
        if (running) {
            sb.append("\nкадр $frame  сетка ${nx}x$ny  ок $valid  DIC %.1f мс".format(procMs))
            if (valid > 0) {
                var su = 0.0; var sv = 0.0
                for (i in 0 until nx * ny) if (out[i * 7].isFinite()) { su += out[i * 7]; sv += out[i * 7 + 1] }
                val k = if (cfg.mmPerPx > 0) cfg.mmPerPx else 1f
                sb.append("\nсредн. u=%.4f v=%.4f %s".format(su / valid * k, sv / valid * k, overlay.unit))
            }
        } else {
            val n = overlay.previewGrid().size / 2
            sb.append("\nузлов: $n")
            sb.append(when {
                overlay.roiEditing -> "\n1) ROI пальцем  2) фокус  3) 🔒  4) Старт"
                !focusLocked -> "\nТап — фокус; затем 🔒"
                else -> "\nГотово к старту"
            })
        }
        overlay.info = sb.toString()
        overlay.postInvalidate()
    }

    private fun writeHeader(w: Int, h: Int) {
        writer?.apply {
            write("# DIC v2\n")
            write("# mode=${if (cfg.incremental) "incremental" else "lagrangian"}\n")
            write("# image=$w,$h\n")
            write("# grid=$nx,$ny,${cfg.step},${cfg.subset}\n")
            write("# search=${cfg.search} minZNCC=${cfg.minZncc}\n")
            write("# scale_mm_per_px=${cfg.mmPerPx}\n")
            write("frame,t_ms,x_px,y_px,u,v,exx,eyy,exy,zncc,ux,uy,vx,vy\n")
        }
    }

    /** Пишутся все узлы (NaN — нет данных), чтобы файл легко читался плеером и в Python/Matlab. */
    private fun writeFrame() {
        val wr = writer ?: return
        val t = ((System.nanoTime() - t0) / 1_000_000L).toString()
        val k = if (cfg.mmPerPx > 0) cfg.mmPerPx else 1f
        val sb = StringBuilder(nx * ny * 110)
        for (i in 0 until nx * ny) {
            val b = i * 7
            sb.append(frame).append(',').append(t).append(',')
                .append(gridXY[2 * i]).append(',').append(gridXY[2 * i + 1]).append(',')
                .append(out[b] * k).append(',').append(out[b + 1] * k).append(',')
                .append(overlay.value(Field.EXX, out, i)).append(',').append(overlay.value(Field.EYY, out, i)).append(',')
                .append(overlay.value(Field.EXY, out, i)).append(',').append(out[b + 6]).append(',')
                .append(out[b + 2]).append(',').append(out[b + 3]).append(',').append(out[b + 4]).append(',').append(out[b + 5]).append('\n')
        }
        wr.write(sb.toString())
    }

    // ================= управление =================
    private fun startRun() {
        if (!focusLocked) {
            AlertDialog.Builder(this).setMessage("Фокус не зафиксирован. Во время эксперимента он может измениться и исказить результат.")
                .setPositiveButton("Зафиксировать и начать") { _, _ -> toggleLock(); doStart() }
                .setNeutralButton("Начать так") { _, _ -> doStart() }
                .setNegativeButton("Отмена", null).show()
        } else doStart()
    }

    private fun doStart() {
        csvName = "dic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        try {
            writer = storage.create(csvName).first.bufferedWriter()
        } catch (e: Exception) { toast("Не удалось создать файл: ${e.message}"); return }
        frame = 0; t0 = System.nanoTime()
        overlay.roiEditing = false
        wantReference = true
        running = true
        btnRun.text = "■ Стоп"; btnRun.setBackgroundColor(0xFFC62828.toInt())
        updateToggles()
    }

    private fun stopRun() {
        running = false
        exec.execute {
            writer?.flush(); writer?.close(); writer = null
            runOnUiThread { toast("Сохранено: $csvName ($frame кадров)") }
        }
        btnRun.text = "▶ Старт"; btnRun.setBackgroundColor(0xFF2E7D32.toInt())
    }

    private fun toggleRoi() {
        if (running) { toast("Остановите измерение"); return }
        overlay.roiEditing = !overlay.roiEditing
        overlay.clearField(); updateToggles()
    }

    private fun nextField() {
        val v = Field.entries
        overlay.field = v[(overlay.field.ordinal + 1) % v.size]
        btnField.text = "Поле: " + overlay.field.title
        if (out.isNotEmpty() && nx > 0) exec.execute { overlay.setResult(out) }
    }

    private fun numField(label: String, v: String, ll: LinearLayout): EditText {
        ll.addView(TextView(this).apply { text = label })
        return EditText(this).apply {
            setText(v); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            ll.addView(this)
        }
    }

    private fun settingsDialog() {
        if (running) { toast("Остановите измерение"); return }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        ll.addView(TextView(this).apply { text = "Режим корреляции" })
        val rg = RadioGroup(this)
        val rLag = RadioButton(this).apply { text = "Относительно первого кадра (0→1, 0→2, …)"; id = 1 }
        val rInc = RadioButton(this).apply { text = "Кадр к кадру (1→2, 2→3, …) с накоплением"; id = 2 }
        rg.addView(rLag); rg.addView(rInc); rg.check(if (cfg.incremental) 2 else 1); ll.addView(rg)
        ll.addView(TextView(this).apply {
            textSize = 12f
            text = "К первому кадру — нет накопления ошибки, но при больших деформациях корреляция теряется. " +
                "Кадр к кадру — выдерживает большие деформации, но ошибка накапливается."
        })
        val eS = numField("Размер подмножества, px (нечётн.)", "${cfg.subset}", ll)
        val eSt = numField("Шаг сетки, px", "${cfg.step}", ll)
        val eSe = numField("Радиус поиска, px (макс. смещение за кадр)", "${cfg.search}", ll)
        val eZ = numField("Мин. ZNCC (0..1)", "${cfg.minZncc}", ll)
        val eMm = numField("Масштаб, мм/px (0 = в пикселях)", "${cfg.mmPerPx}", ll)
        val eN = numField("Записывать каждый N-й кадр", "${cfg.everyN}", ll)
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, resolutions.map { "${it.width}x${it.height}" })
            setSelection(cfg.resIndex)
        }
        ll.addView(TextView(this).apply { text = "Разрешение анализа (выше = точнее, медленнее)" }); ll.addView(sp)
        ll.addView(TextView(this).apply { text = "\nПапка сохранения:\n" + storage.folderLabel() })
        ll.addView(btn("Выбрать папку…") { pickFolder.launch(null) })
        ll.addView(btn("Папка приложения (по умолч.)") { cfg.folderUri = null; cfg.save(); toast(storage.folderLabel()) })

        val dlg = AlertDialog.Builder(this).setTitle("Параметры DIC").setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("OK") { _, _ ->
                cfg.incremental = rg.checkedRadioButtonId == 2
                cfg.subset = ((eS.text.toString().toIntOrNull() ?: cfg.subset).coerceIn(11, 101)) or 1
                cfg.step = (eSt.text.toString().toIntOrNull() ?: cfg.step).coerceIn(2, 100)
                cfg.search = (eSe.text.toString().toIntOrNull() ?: cfg.search).coerceIn(1, 60)
                cfg.minZncc = (eZ.text.toString().toFloatOrNull() ?: cfg.minZncc).coerceIn(0f, 0.99f)
                cfg.mmPerPx = (eMm.text.toString().toFloatOrNull() ?: cfg.mmPerPx).coerceAtLeast(0f)
                cfg.everyN = (eN.text.toString().toIntOrNull() ?: cfg.everyN).coerceAtLeast(1)
                val ri = sp.selectedItemPosition
                val resChanged = ri != cfg.resIndex
                cfg.resIndex = ri
                applySettings(resChanged)
            }
            .setNeutralButton("По умолчанию", null)
            .setNegativeButton("Отмена", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val oldRes = cfg.resIndex
                cfg.resetDefaults(); applySettings(oldRes != cfg.resIndex)
                toast("Параметры сброшены"); dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun applySettings(resChanged: Boolean) {
        cfg.save()
        overlay.subset = cfg.subset; overlay.step = cfg.step
        overlay.unit = if (cfg.mmPerPx > 0) "мм" else "px"
        updateToggles()
        if (resChanged) startCamera()
    }

    private fun viewDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val cbV = CheckBox(this).apply { text = "Векторы смещений"; isChecked = this@MainActivity.overlay.showVectors }; ll.addView(cbV)
        val eK = numField("Масштаб векторов (×)", "${overlay.vecScale}", ll)
        val cbMin = CheckBox(this).apply { text = "Ограничить минимум шкалы"; isChecked = this@MainActivity.overlay.limitMin }; ll.addView(cbMin)
        val eMin = numField("Минимум", "${if (overlay.limitMin) overlay.fixMin else overlay.lastMin}", ll)
        val cbMax = CheckBox(this).apply { text = "Ограничить максимум шкалы"; isChecked = this@MainActivity.overlay.limitMax }; ll.addView(cbMax)
        val eMax = numField("Максимум", "${if (overlay.limitMax) overlay.fixMax else overlay.lastMax}", ll)
        AlertDialog.Builder(this).setTitle("Отображение").setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("OK") { _, _ ->
                overlay.showVectors = cbV.isChecked
                overlay.vecScale = eK.text.toString().toFloatOrNull() ?: overlay.vecScale
                overlay.limitMin = cbMin.isChecked; overlay.limitMax = cbMax.isChecked
                overlay.fixMin = eMin.text.toString().toFloatOrNull() ?: 0f
                overlay.fixMax = eMax.text.toString().toFloatOrNull() ?: 1f
                if (out.isNotEmpty() && nx > 0) exec.execute { overlay.setResult(out) }
            }.setNeutralButton("Авто шкала") { _, _ -> overlay.limitMin = false; overlay.limitMax = false }
            .show()
    }

    private fun resultsDialog() {
        val items = try { storage.list() } catch (e: Exception) { emptyList() }
        val names = items.map { "${it.name}  (${it.size / 1024} КБ)" }.toTypedArray()
        val b = AlertDialog.Builder(this).setTitle(storage.folderLabel())
        if (items.isEmpty()) b.setMessage("Нет результатов")
        else b.setItems(names) { _, i ->
            val it = items[i]
            AlertDialog.Builder(this).setTitle(it.name).setItems(arrayOf("Открыть в плеере", "Отправить…", "Удалить")) { _, a ->
                when (a) {
                    0 -> openPlayer(it.uri)
                    1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"; putExtra(Intent.EXTRA_STREAM, it.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Отправить"))
                    2 -> { storage.delete(it); toast("Удалено") }
                }
            }.show()
        }
        b.setPositiveButton("Выбрать папку…") { _, _ -> pickFolder.launch(null) }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun openPlayer(uri: Uri?) {
        if (running) { toast("Остановите измерение"); return }
        startActivity(Intent(this, PlayerActivity::class.java).apply { if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (running) stopRun()
    }

    override fun onResume() {
        super.onResume()
        applyCameraOptions()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.execute { writer?.close(); DicNative.destroy(handle) }
        exec.shutdown()
    }
}
