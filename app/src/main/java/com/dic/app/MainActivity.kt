package com.dic.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.hypot

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    // параметры DIC (меняются в «Настройках»)
    private var subset = 31
    private var step = 10
    private var search = 10
    private var minZncc = 0.7f
    private var mmPerPx = 0f          // 0 = выводить в пикселях
    private var resolution = Size(1280, 720)
    private var everyN = 1            // записывать каждый N-й кадр

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var btnRun: Button
    private lateinit var btnRoi: Button
    private lateinit var btnLock: Button
    private lateinit var btnField: Button

    private var camera: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val handle = DicNative.create()

    @Volatile private var state = State.PREVIEW
    @Volatile private var wantReference = false
    private var locked = false
    private var torch = false
    private var frame = 0
    private var t0 = 0L
    private var out = FloatArray(0)
    private var gridXY = FloatArray(0)
    private var nx = 0; private var ny = 0
    private var writer: BufferedWriter? = null
    private var csvFile: File? = null
    private var procMs = 0f; private var fps = 0f; private var lastTs = 0L
    private var valid = 0
    private var sharp = 0f

    enum class State { PREVIEW, RUNNING }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Нужен доступ к камере", Toast.LENGTH_LONG).show()
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = OverlayView(this)
        overlay.onTapFocus = { x, y -> focusAt(x, y) }
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        btnRoi = btn("ROI: правка") { toggleRoi() }
        btnLock = btn("Фокус: авто") { toggleLock() }
        btnRun = btn("▶ Старт") { if (state == State.RUNNING) stopRun() else startRun() }.apply { setBackgroundColor(0xFF2E7D32.toInt()); setTextColor(Color.WHITE) }
        btnField = btn("Поле: |U|") { nextField() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0x88000000.toInt()); setPadding(8, 8, 8, 8)
            addView(btnRun); addView(btnRoi); addView(btnLock); addView(btnField)
            addView(btn("Новый эталон") { if (state == State.RUNNING) wantReference = true })
            addView(btn("Вид…") { viewDialog() })
            addView(btn("Настройки") { settingsDialog() })
            addView(btn("Вспышка") { torch = !torch; camera?.cameraControl?.enableTorch(torch) })
            addView(btn("Экспорт") { share() })
        }
        val scroll = ScrollView(this).apply { addView(bar) }
        root.addView(scroll, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START))
        setContentView(root)
        overlay.roiEditing = true
    }

    // ---------------- камера ----------------
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val sel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(resolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val pv = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()).build()
            pv.surfaceProvider = preview.surfaceProvider
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(sel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            an.setAnalyzer(exec) { analyze(it) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusAt(x: Float, y: Float) {
        if (locked) { toast("Фокус заблокирован"); return }
        val pt = preview.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
    }

    /** Блокировка фокуса и экспозиции: для DIC важно, чтобы яркость и масштаб не «плавали». */
    private fun toggleLock() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        locked = !locked
        if (locked) {
            val r = overlay.roiPixels()
            val p = overlay.toView(r.exactCenterX(), r.exactCenterY())
            val pt = preview.meteringPointFactory.createPoint(p.x, p.y)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
            ).addListener({
                c2.setCaptureRequestOptions(CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    .build())
            }, ContextCompat.getMainExecutor(this))
            btnLock.text = "Фокус: 🔒"
        } else {
            c2.clearCaptureRequestOptions()
            cam.cameraControl.cancelFocusAndMetering()
            btnLock.text = "Фокус: авто"
        }
    }

    // ---------------- обработка кадров (поток анализа) ----------------
    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(img: ImageProxy) {
        try {
            val w = img.width; val h = img.height
            val pl = img.planes[0]
            val buf = pl.buffer; val stride = pl.rowStride
            if (overlay.imgW != w || overlay.imgH != h || overlay.flip != (img.imageInfo.rotationDegrees == 180)) {
                overlay.imgW = w; overlay.imgH = h; overlay.flip = img.imageInfo.rotationDegrees == 180
                overlay.postInvalidate()
            }
            val now = System.nanoTime()
            if (lastTs != 0L) fps = 0.9f * fps + 0.1f * (1e9f / (now - lastTs))
            lastTs = now
            val r = overlay.roiPixels()
            sharp = DicNative.sharpness(buf, w, h, stride, r.left, r.top, r.right, r.bottom)

            if (state == State.RUNNING) {
                if (wantReference) {
                    wantReference = false
                    val g = DicNative.setReference(handle, buf, w, h, stride, r.left, r.top, r.right, r.bottom, subset, step, search, minZncc)
                    nx = g[0]; ny = g[1]
                    gridXY = FloatArray(nx * ny * 2); DicNative.gridCoords(handle, gridXY)
                    out = FloatArray(nx * ny * 7)
                    overlay.setGrid(nx, ny, gridXY, step)
                    writer?.write("# reference frame=$frame nx=$nx ny=$ny\n")
                } else if (nx * ny > 0) {
                    procMs = DicNative.process(handle, buf, w, h, stride, out)
                    frame++
                    valid = (0 until nx * ny).count { out[it * 7].isFinite() }
                    overlay.setResult(out)
                    if (frame % everyN == 0) writeFrame()
                }
            }
            updateInfo()
        } catch (e: Exception) {
            runOnUiThread { toast("Ошибка: ${e.message}") }
        } finally { img.close() }
    }

    private fun updateInfo() {
        val sb = StringBuilder()
        sb.append("%dx%d  %.0f fps  резкость %.0f".format(overlay.imgW, overlay.imgH, fps, sharp))
        if (state == State.RUNNING) {
            sb.append("\nкадр $frame  сетка ${nx}x$ny  ок $valid  DIC %.1f мс".format(procMs))
            if (valid > 0) {
                var su = 0.0; var sv = 0.0
                for (i in 0 until nx * ny) if (out[i * 7].isFinite()) { su += out[i * 7]; sv += out[i * 7 + 1] }
                val k = if (mmPerPx > 0) mmPerPx else 1f; val un = if (mmPerPx > 0) "мм" else "px"
                sb.append("\nсредн. u=%.4f v=%.4f %s".format(su / valid * k, sv / valid * k, un))
            }
        } else sb.append(if (overlay.roiEditing) "\nВыделите ROI пальцем; затем фокус и 🔒" else "\nТап по экрану — фокус")
        overlay.info = sb.toString()
        overlay.postInvalidate()
    }

    private fun writeFrame() {
        val wr = writer ?: return
        val t = (System.nanoTime() - t0) / 1e6
        val k = if (mmPerPx > 0) mmPerPx else 1f
        val sb = StringBuilder(nx * ny * 80)
        for (i in 0 until nx * ny) {
            val b = i * 7
            if (!out[b].isFinite()) continue
            sb.append(frame).append(',').append("%.1f".format(t)).append(',')
                .append(gridXY[2 * i]).append(',').append(gridXY[2 * i + 1]).append(',')
                .append(out[b] * k).append(',').append(out[b + 1] * k).append(',')
                .append(overlay.value(Field.EXX, out, i)).append(',').append(overlay.value(Field.EYY, out, i)).append(',')
                .append(overlay.value(Field.EXY, out, i)).append(',').append(out[b + 6]).append('\n')
        }
        wr.write(sb.toString())
    }

    // ---------------- управление ----------------
    private fun startRun() {
        val dir = File(filesDir, "results").apply { mkdirs() }
        val name = "dic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        csvFile = File(dir, name)
        writer = csvFile!!.bufferedWriter().also {
            it.write("# DIC subset=$subset step=$step search=$search minZNCC=$minZncc scale_mm_per_px=$mmPerPx\n")
            it.write("frame,t_ms,x_px,y_px,u,v,exx,eyy,exy,zncc\n")
        }
        frame = 0; t0 = System.nanoTime()
        overlay.roiEditing = false; btnRoi.text = "ROI: зафикс."
        if (!locked) toggleLock()
        wantReference = true
        state = State.RUNNING
        btnRun.text = "■ Стоп"; btnRun.setBackgroundColor(0xFFC62828.toInt())
    }

    private fun stopRun() {
        state = State.PREVIEW
        exec.execute { writer?.flush(); writer?.close(); writer = null }
        btnRun.text = "▶ Старт"; btnRun.setBackgroundColor(0xFF2E7D32.toInt())
        toast("Сохранено: ${csvFile?.name} ($frame кадров)")
    }

    private fun toggleRoi() {
        if (state == State.RUNNING) { toast("Остановите измерение"); return }
        overlay.roiEditing = !overlay.roiEditing
        btnRoi.text = if (overlay.roiEditing) "ROI: правка" else "ROI: зафикс."
        overlay.clearField(); overlay.invalidate()
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
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val eS = numField("Размер подмножества, px (нечётн.)", "$subset", ll)
        val eSt = numField("Шаг сетки, px", "$step", ll)
        val eSe = numField("Радиус поиска, px (макс. смещение за кадр)", "$search", ll)
        val eZ = numField("Мин. ZNCC (0..1)", "$minZncc", ll)
        val eMm = numField("Масштаб, мм/px (0 = в пикселях)", "$mmPerPx", ll)
        val eN = numField("Записывать каждый N-й кадр", "$everyN", ll)
        val res = arrayOf(Size(640, 480), Size(1280, 720), Size(1920, 1080))
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, res.map { "${it.width}x${it.height}" })
            setSelection(res.indexOfFirst { it == resolution }.coerceAtLeast(0))
        }
        ll.addView(TextView(this).apply { text = "Разрешение анализа (выше = точнее, медленнее)" }); ll.addView(sp)
        AlertDialog.Builder(this).setTitle("Параметры DIC").setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("OK") { _, _ ->
                subset = (eS.text.toString().toIntOrNull() ?: subset).coerceIn(11, 101)
                step = (eSt.text.toString().toIntOrNull() ?: step).coerceIn(2, 100)
                search = (eSe.text.toString().toIntOrNull() ?: search).coerceIn(1, 60)
                minZncc = (eZ.text.toString().toFloatOrNull() ?: minZncc).coerceIn(0f, 0.99f)
                mmPerPx = (eMm.text.toString().toFloatOrNull() ?: mmPerPx).coerceAtLeast(0f)
                everyN = (eN.text.toString().toIntOrNull() ?: everyN).coerceAtLeast(1)
                val r = res[sp.selectedItemPosition]
                if (r != resolution) { resolution = r; if (state == State.PREVIEW) startCamera() }
                if (state == State.RUNNING) wantReference = true
            }.setNegativeButton("Отмена", null).show()
    }

    private fun viewDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val cbV = CheckBox(this).apply { text = "Векторы смещений"; isChecked = this@MainActivity.overlay.showVectors }; ll.addView(cbV)
        val eK = numField("Масштаб векторов (×)", "${overlay.vecScale}", ll)
        val cbA = CheckBox(this).apply { text = "Автодиапазон шкалы"; isChecked = this@MainActivity.overlay.autoRange }; ll.addView(cbA)
        val eMin = numField("Мин. шкалы", "${overlay.lastMin}", ll)
        val eMax = numField("Макс. шкалы", "${overlay.lastMax}", ll)
        AlertDialog.Builder(this).setTitle("Отображение").setView(ll)
            .setPositiveButton("OK") { _, _ ->
                overlay.showVectors = cbV.isChecked
                overlay.vecScale = eK.text.toString().toFloatOrNull() ?: overlay.vecScale
                overlay.autoRange = cbA.isChecked
                overlay.fixMin = eMin.text.toString().toFloatOrNull() ?: 0f
                overlay.fixMax = eMax.text.toString().toFloatOrNull() ?: 1f
            }.show()
    }

    private fun share() {
        val files = File(filesDir, "results").listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) { toast("Нет результатов"); return }
        val names = files.map { "${it.name}  (${it.length() / 1024} КБ)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Результаты (CSV)").setItems(names) { _, i ->
            val uri = FileProvider.getUriForFile(this, "com.dic.app.files", files[i])
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Отправить"))
        }.setNeutralButton("Удалить все") { _, _ -> files.forEach { it.delete() } }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        exec.execute { writer?.close(); DicNative.destroy(handle) }
        exec.shutdown()
    }
}
