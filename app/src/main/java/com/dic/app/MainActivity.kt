package com.dic.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import com.dic.app.Ui.f
import com.dic.app.Ui.i
import com.dic.app.Ui.selected
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var cfg: Settings
    private lateinit var storage: Storage
    private lateinit var force: ForceServer
    private val resolutions = arrayOf(Size(640, 480), Size(1280, 720), Size(1920, 1080))

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var liveChart: ChartView
    private lateinit var btnRun: Ui.Btn
    private lateinit var btnRoi: Ui.Btn
    private lateinit var btnGrid: Ui.Btn
    private lateinit var btnSub: Ui.Btn
    private lateinit var btnField: Ui.Btn
    private lateinit var btnStrain: Ui.Btn
    private lateinit var btnScale: Ui.Btn
    private lateinit var btnReset: Ui.Btn
    private lateinit var btnForce: Ui.Btn
    private lateinit var btnTorch: Ui.Btn
    private lateinit var btnAf: Ui.Btn
    private lateinit var btnMf: Ui.Btn
    private lateinit var btnLock: Ui.Btn
    private lateinit var seekFocus: SeekBar
    private lateinit var lblFocus: TextView
    private lateinit var rulerPanel: LinearLayout
    private lateinit var rulerLen: EditText
    private lateinit var rulerInfo: TextView

    private var camera: Camera? = null
    private var lens: LensInfo? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val handle = DicNative.create()

    // focus
    private var manualFocus = false
    private var focusLocked = false
    private var minFocusDist = 0f
    private var manualDist = 0f
    @Volatile private var lastLensDist = 0f
    private var lockedDist = 0f
    private var torch = false

    // orientation
    private var sensors: SensorManager? = null
    private val grav = FloatArray(3)
    @Volatile private var tilt: Tilt? = null

    // experiment
    @Volatile private var running = false
    @Volatile private var wantReference = false
    private var frame = 0
    private var t0 = 0L
    private var out = FloatArray(0)
    private var disp = FloatArray(0)
    private var gridXY = FloatArray(0)
    private var nx = 0; private var ny = 0
    private var writer: BufferedWriter? = null
    private var csvName = ""
    private var procMs = 0f; private var fps = 0f; private var lastTs = 0L
    private var valid = 0
    private var sharp = 0f
    private var frameW = 0; private var frameH = 0
    private var showLive = true
    private val liveX = ArrayList<Float>(); private val liveF = ArrayList<Float>()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            cfg.folderUri = uri.toString(); cfg.save()
            toast("Saving to " + storage.folderLabel())
        }
    }
    private val askHotspotPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startHotspot() else toast("Location / nearby-devices permission is required for the hotspot")
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Settings(this); storage = Storage(this, cfg)
        force = ForceServer(this).apply { port = cfg.forcePort; factor = cfg.forceFactor; unit = cfg.forceUnit }
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == 1) { if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else toast("Camera permission is required") }
    }

    private fun dp(v: Int) = Ui.dpi(this, v)

    // ================= UI =================
    private fun buildUi() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val railW = dp(132); val barH = dp(56)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = OverlayView(this).apply {
            roi.set(cfg.roi[0], cfg.roi[1], cfg.roi[2], cfg.roi[3])
            subset = cfg.subset; step = cfg.step; showScalebar = cfg.showScalebar
            onTap = { x, y -> focusAt(x, y) }
            onRoiChanged = { cfg.roi = floatArrayOf(roi.left, roi.top, roi.right, roi.bottom); cfg.save() }
            onRulerChanged = { updateRulerInfo() }
        }
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { leftMargin = railW; bottomMargin = barH })
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { leftMargin = railW; bottomMargin = barH })

        // ---- left rail ----
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        fun add(v: View) = rail.addView(v, Ui.lp(this, 4))
        btnRun = Ui.Btn(this, "▶  START", Ui.GO) { if (running) stopRun() else startRun() }.apply { textSize = 16f; setPadding(dp(8), dp(14), dp(8), dp(14)) }
        add(btnRun)
        rail.addView(Ui.section(this, "Setup"))
        btnRoi = Ui.Btn(this, "ROI") { toggleRoi() }; add(btnRoi)
        btnGrid = Ui.Btn(this, "Grid") { overlay.showGrid = !overlay.showGrid; updateToggles() }; add(btnGrid)
        btnSub = Ui.Btn(this, "Subsets") { overlay.showSubset = !overlay.showSubset; updateToggles() }; add(btnSub)
        add(Ui.Btn(this, "Calibrate") { calibrationDialog() })
        rail.addView(Ui.section(this, "View"))
        btnField = Ui.Btn(this, "Field: |U|") { nextField() }; add(btnField)
        btnStrain = Ui.Btn(this, "Strain: subset") { nextStrain() }; add(btnStrain)
        btnScale = Ui.Btn(this, "Scale bar") { overlay.showScalebar = !overlay.showScalebar; cfg.showScalebar = overlay.showScalebar; cfg.save(); updateToggles() }; add(btnScale)
        add(Ui.Btn(this, "Display…") { viewDialog() })
        btnReset = Ui.Btn(this, "Reset reference") { if (running && !cfg.incremental) wantReference = true }; add(btnReset)
        rail.addView(Ui.section(this, "Data"))
        btnForce = Ui.Btn(this, "Force sensor") { forceDialog() }; add(btnForce)
        add(Ui.Btn(this, "Results") { resultsDialog() })
        add(Ui.Btn(this, "Player") { openPlayer(null) })
        rail.addView(Ui.section(this, "Other"))
        add(Ui.Btn(this, "Settings") { settingsDialog() })
        btnTorch = Ui.Btn(this, "Torch") { torch = !torch; camera?.cameraControl?.enableTorch(torch); updateToggles() }; add(btnTorch)
        root.addView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(rail); isVerticalScrollBarEnabled = false },
            FrameLayout.LayoutParams(railW, MATCH_PARENT, Gravity.START))

        // ---- bottom focus bar ----
        btnAf = Ui.Btn(this, "Auto") { setFocusMode(false) }
        btnMf = Ui.Btn(this, "Manual") { setFocusMode(true) }
        btnLock = Ui.Btn(this, "Lock focus") { toggleLock() }
        seekFocus = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                    if (!user) return
                    // non-linear: fine control near infinity/far range
                    val t = p / 1000f
                    manualDist = minFocusDist * t * t; applyCameraOptions(); updateFocusLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        lblFocus = TextView(this).apply { setTextColor(Ui.TEXT); textSize = 13f; gravity = Gravity.CENTER; minWidth = dp(84) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(10), 0, dp(10), 0)
            addView(TextView(context).apply { text = "FOCUS"; setTextColor(Ui.MUTED); textSize = 11f; setPadding(0, 0, dp(8), 0) })
            addView(btnAf, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) })
            addView(btnMf)
            addView(seekFocus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(lblFocus)
            addView(btnLock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, barH, Gravity.BOTTOM).apply { leftMargin = railW })

        // ---- live force chart ----
        liveChart = ChartView(this).apply { title = "Live: force vs displacement"; visibility = View.GONE }
        root.addView(liveChart, FrameLayout.LayoutParams(dp(300), dp(180), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = barH + dp(70); rightMargin = dp(70) })

        // ---- ruler panel (calibration) ----
        rulerInfo = TextView(this).apply { setTextColor(Ui.TEXT); textSize = 13f }
        rulerLen = EditText(this).apply {
            hint = "length"; textSize = 15f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); minWidth = dp(90)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rulerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Ui.rounded(Ui.BG, Ui.dp(context, 12f))
            setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
            addView(rulerInfo, LinearLayout.LayoutParams(dp(260), WRAP_CONTENT))
            addView(rulerLen); addView(TextView(context).apply { text = "mm"; setTextColor(Ui.MUTED); setPadding(dp(4), 0, dp(10), 0) })
            addView(Ui.Btn(context, "Apply", Ui.GO) { applyRuler() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
            addView(Ui.Btn(context, "Cancel") { endRuler() })
        }
        root.addView(rulerPanel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10); leftMargin = railW })

        setContentView(root)
        force.onUpdate = { updateToggles() }
        updateToggles(); updateFocusUi()
    }

    private fun updateToggles() {
        btnGrid.on = overlay.showGrid; btnSub.on = overlay.showSubset; btnRoi.on = overlay.roiEditing
        btnScale.on = overlay.showScalebar; btnTorch.on = torch
        btnForce.on = force.running
        btnStrain.text = "Strain: " + when (cfg.strain) { StrainMethod.SUBSET -> "subset"; StrainMethod.MESH -> "mesh"; StrainMethod.FIT -> "local fit" }
        btnReset.isEnabled = !cfg.incremental; btnReset.alpha = if (cfg.incremental) 0.4f else 1f
        overlay.subset = cfg.subset; overlay.step = cfg.step
        overlay.mmPerPx = scale()
        liveChart.visibility = if (showLive && force.running) View.VISIBLE else View.GONE
        overlay.invalidate()
    }

    // ================= camera =================
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
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                minFocusDist = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                lens = runCatching {
                    val mgr = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    LensInfo(mgr.getCameraCharacteristics(info.cameraId))
                }.getOrNull()
                cam.cameraControl.enableTorch(torch)
            }
            applyCameraOptions(); updateFocusUi()
        }, ContextCompat.getMainExecutor(this))
    }

    /** All Camera2 overrides in one place (see README: focus workflow). */
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
        if (focusLocked) { toast("Focus is locked — unlock to change it"); return }
        if (manualFocus) { toast("Manual focus: use the slider"); return }
        val pt = preview.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
    }

    private fun setFocusMode(manual: Boolean) {
        if (focusLocked) { toast("Unlock focus first"); return }
        if (manual && minFocusDist <= 0f) { toast("This camera has a fixed-focus lens"); return }
        manualFocus = manual
        if (manual) { manualDist = lastLensDist.coerceIn(0f, minFocusDist); camera?.cameraControl?.cancelFocusAndMetering() }
        applyCameraOptions(); updateFocusUi()
    }

    private fun toggleLock() {
        focusLocked = !focusLocked
        if (focusLocked) lockedDist = if (manualFocus) manualDist else lastLensDist.coerceIn(0f, minFocusDist)
        applyCameraOptions(); updateFocusUi()
    }

    private fun currentDist() = when { focusLocked -> lockedDist; manualFocus -> manualDist; else -> lastLensDist }

    private fun updateFocusUi() {
        btnAf.on = !manualFocus; btnMf.on = manualFocus
        btnLock.text = if (focusLocked) "🔒 Locked" else "Lock focus"
        btnLock.color(if (focusLocked) Ui.STOP else Ui.BTN)
        seekFocus.isEnabled = manualFocus && !focusLocked
        btnAf.alpha = if (focusLocked) 0.4f else 1f; btnMf.alpha = btnAf.alpha
        if (minFocusDist > 0f) seekFocus.progress = (Math.sqrt((currentDist() / minFocusDist).toDouble().coerceIn(0.0, 1.0)) * 1000).toInt()
        updateFocusLabel()
    }

    private fun updateFocusLabel() {
        val d = currentDist()
        lblFocus.text = when { minFocusDist <= 0f -> "fixed"; d <= 0.001f -> "∞"; 100f / d >= 100 -> "%.2f m".format(1f / d); else -> "%.1f cm".format(100f / d) }
    }

    // ================= calibration =================
    /** Effective mm/px for the current analysis resolution (0 = uncalibrated). */
    private fun scale(): Float {
        if (cfg.mmPerPx <= 0f) return 0f
        val w = if (frameW > 0) frameW else resolutions[cfg.resIndex].width
        return if (cfg.calRes > 0) cfg.mmPerPx * cfg.calRes / w else cfg.mmPerPx
    }

    private fun focusChanged(): Boolean {
        if (cfg.mmPerPx <= 0f || cfg.calFocus <= 0f || minFocusDist <= 0f) return false
        val d = currentDist()
        return d > 0f && abs(d - cfg.calFocus) / cfg.calFocus > 0.02f
    }

    private fun calibrationDialog() {
        if (running) { toast("Stop the measurement first"); return }
        val f = Ui.Form(this)
        val sc = scale()
        f.header("Current")
        f.note(if (sc > 0) "Scale: ${Ui.fmt(sc)} mm/px  (${1f / sc} px/mm)\nMethod: ${cfg.calSource}" else "Not calibrated — results in pixels", if (sc > 0) Ui.OK else Ui.WARN, 14f)
        if (cfg.calFocus > 0) f.note("Calibrated at focus ${"%.1f".format(100f / cfg.calFocus)} cm; now ${"%.1f".format(100f / currentDist().coerceAtLeast(1e-3f))} cm" +
            if (focusChanged()) " — focus changed, recalibrate!" else "", if (focusChanged()) Ui.BAD else Ui.MUTED)
        if (!focusLocked) f.note("Tip: lock focus before calibrating — the scale depends on the focus distance.", Ui.WARN)

        // lens model
        val est = lens?.estimate(currentDist(), frameW.coerceAtLeast(1), frameH.coerceAtLeast(1))
        val t = tilt
        f.header("Lens model & depth of field")
        val l = lens
        if (l != null) f.note("f = ${l.focal} mm, f/${l.aperture}, sensor ${l.sensorW}×${l.sensorH} mm, focus distance ${l.focusCalName}" + if (l.fx > 0) ", fx = ${l.fx.toInt()} px" else "")
        if (est != null) {
            f.note("Object distance Z ≈ ${Ui.fmt(est.zMm)} mm  →  ${Ui.fmt(est.mmPerPx)} mm/px\n" +
                "Field of view ${Ui.fmt(est.fovW)} × ${Ui.fmt(est.fovH)} mm\n" +
                "Depth of field ${Ui.fmt(est.dofNear)} … ${if (est.dofFar.isFinite()) Ui.fmt(est.dofFar) else "∞"} mm  (${Ui.fmt(est.dofFar - est.dofNear)} mm)\n" +
                "Out-of-plane motion Δz gives a pseudo-strain ≈ Δz/Z (0.1 mm → ${Ui.fmt(0.1f / est.zMm)})" +
                (if (t != null) "\nTilt ${"%.1f".format(t.outOfPlane)}° → scale varies ±${"%.2f".format(100 * t.scaleError(est.fovW, est.zMm))} % across the image" else ""),
                Ui.TEXT)
            if (!est.reliable) f.note("This phone reports an uncalibrated focus distance — the lens estimate is only a rough guess. Use the ruler.", Ui.WARN)
        } else f.note("Lens metadata not available (fixed focus, infinity, or missing camera data).")

        var dlg: AlertDialog? = null
        f.header("Calibrate")
        f.button("Ruler: mark a known length  (recommended)") { dlg?.dismiss(); startRuler() }
        if (est != null) f.button("Use lens estimate  (${Ui.fmt(est.mmPerPx)} mm/px)") {
            setCalibration(est.mmPerPx, "lens model (${est.source})"); dlg?.dismiss()
        }
        val eMan = f.num("Manual scale", if (sc > 0) sc else 0f, "mm/px")
        f.button("Apply manual scale") { val v = eMan.f(0f); if (v > 0) { setCalibration(v, "manual"); dlg?.dismiss() } }
        f.button("Clear calibration") { cfg.mmPerPx = 0f; cfg.calSource = ""; cfg.calFocus = 0f; cfg.save(); updateToggles(); dlg?.dismiss() }
        dlg = AlertDialog.Builder(this).setTitle("Calibration").setView(f.view()).setNegativeButton("Close", null).show()
    }

    private fun setCalibration(mmPerPx: Float, src: String) {
        cfg.mmPerPx = mmPerPx; cfg.calSource = src; cfg.calFocus = currentDist(); cfg.calRes = frameW; cfg.save()
        toast("Scale ${Ui.fmt(mmPerPx)} mm/px"); updateToggles()
    }

    private fun startRuler() {
        overlay.roiEditing = false
        overlay.rulerA.set(overlay.imgW * 0.3f, overlay.imgH * 0.5f); overlay.rulerB.set(overlay.imgW * 0.7f, overlay.imgH * 0.5f)
        overlay.rulerMode = true
        rulerPanel.visibility = View.VISIBLE
        updateRulerInfo(); updateToggles()
    }

    private fun updateRulerInfo() {
        val px = hypot(overlay.rulerB.x - overlay.rulerA.x, overlay.rulerB.y - overlay.rulerA.y)
        rulerInfo.text = "Drag the two markers onto a known length.\nDistance: ${"%.1f".format(px)} px. Enter it in mm:"
    }

    private fun applyRuler() {
        val px = hypot(overlay.rulerB.x - overlay.rulerA.x, overlay.rulerB.y - overlay.rulerA.y)
        val mm = rulerLen.f(0f)
        if (mm <= 0f || px < 10f) { toast("Enter the real length (mm) and make the line longer"); return }
        setCalibration(mm / px, "ruler ${Ui.fmt(mm)} mm = ${"%.1f".format(px)} px")
        endRuler()
    }

    private fun endRuler() { overlay.rulerMode = false; rulerPanel.visibility = View.GONE; overlay.invalidate() }

    // orientation
    override fun onSensorChanged(e: SensorEvent) {
        val a = if (e.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
        for (i in 0..2) grav[i] += a * (e.values[i] - grav[i])
        tilt = Tilt.from(grav[0], grav[1], grav[2])
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ================= frame processing =================
    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(img: ImageProxy) {
        try {
            val w = img.width; val h = img.height
            val pl = img.planes[0]
            val buf = pl.buffer; val stride = pl.rowStride
            frameW = w; frameH = h
            if (overlay.imgW != w || overlay.imgH != h || overlay.flip != (img.imageInfo.rotationDegrees == 180)) {
                overlay.imgW = w; overlay.imgH = h; overlay.flip = img.imageInfo.rotationDegrees == 180
                runOnUiThread { updateToggles() }
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
                    out = FloatArray(nx * ny * 7); disp = FloatArray(nx * ny * 7)
                    for (i in 0 until nx * ny) out[i * 7 + 6] = 1f
                    overlay.setGrid(nx, ny, gridXY, cfg.step)
                    if (frame == 0) { writeHeader(w, h); liveX.clear(); liveF.clear() }
                    else writer?.write("# reference reset at frame $frame\n")
                    postProcess(); writeFrame()
                } else if (nx * ny > 0) {
                    procMs = DicNative.process(handle, buf, w, h, stride, out)
                    frame++
                    valid = (0 until nx * ny).count { out[it * 7].isFinite() }
                    postProcess()
                    overlay.setResult(disp)
                    if (frame % cfg.everyN == 0) writeFrame()
                    updateLive()
                }
            }
            runOnUiThread { if (!manualFocus && !focusLocked) updateFocusLabel() }
            updateInfo()
        } catch (e: Exception) {
            runOnUiThread { toast("Error: ${e.message}") }
        } finally { img.close() }
    }

    /** px → physical units and the selected strain method. */
    private fun postProcess() {
        val k = scale().let { if (it > 0) it else 1f }
        Strain.compute(cfg.strain, nx, ny, cfg.step.toFloat(), out, disp, cfg.fitWin)
        for (i in 0 until nx * ny) { disp[i * 7] *= k; disp[i * 7 + 1] *= k }
        overlay.unitPerPx = k; overlay.unit = if (scale() > 0) "mm" else "px"
    }

    private fun updateLive() {
        val f = force.force
        if (!force.running || !f.isFinite()) return
        val ci = if (cfg.axisY) 1 else 0
        var s = 0.0; var n = 0
        for (i in 0 until nx * ny) { val u = disp[i * 7 + ci]; if (u.isFinite()) { s += u; n++ } }
        if (n == 0) return
        liveX += (s / n).toFloat(); liveF += f
        if (liveX.size > 4000) { liveX.removeAt(0); liveF.removeAt(0) }
        liveChart.xLabel = "mean ${if (cfg.axisY) "v" else "u"} [${overlay.unit}]"; liveChart.yLabel = "F [${force.unit}]"
        liveChart.series = listOf(ChartView.Series(liveX.toFloatArray(), liveF.toFloatArray(), 0xFF4FC3F7.toInt()))
        liveChart.update()
    }

    private fun updateInfo() {
        val L = ArrayList<Pair<String, Int>>()
        L += "${overlay.imgW}×${overlay.imgH}   ${"%.0f".format(fps)} fps" + (if (running) "   DIC ${"%.1f".format(procMs)} ms" else "") to Ui.TEXT
        L += "Sharpness ${"%.0f".format(sharp)}" + (if (focusLocked) "   focus locked" else "   focus NOT locked") to (if (focusLocked) Ui.OK else Ui.WARN)
        L += (if (cfg.incremental) "Frame-to-frame" else "Vs first frame") + "  ·  strain: ${cfg.strain.title}" to Ui.MUTED
        val sc = scale()
        L += when {
            sc <= 0 -> "Not calibrated (px)" to Ui.WARN
            focusChanged() -> "Scale ${Ui.fmt(sc)} mm/px — focus changed, recalibrate" to Ui.BAD
            else -> "Scale ${Ui.fmt(sc)} mm/px" to Ui.OK
        }
        tilt?.let { t ->
            val a = abs(t.outOfPlane)
            L += "Tilt ${"%.1f".format(t.outOfPlane)}° (${if (t.lookingDown) "looking down" else "facing wall"})   rotation ${"%.1f".format(t.inPlane)}°" to
                (if (a < 1f) Ui.OK else if (a < 3f) Ui.WARN else Ui.BAD)
        }
        if (force.running) {
            val f = force.force
            L += (if (f.isFinite()) "Force ${Ui.fmt(f)} ${force.unit}  (${"%.0f".format(force.rate)} Hz)" else "Force: waiting for sensor… (${force.clients} connected)") to (if (f.isFinite()) Ui.WARN else Ui.MUTED)
        }
        if (running) {
            L += "Frame $frame   nodes ${nx}×$ny   valid $valid" to Ui.TEXT
            if (valid > 0) {
                var su = 0.0; var sv = 0.0
                for (i in 0 until nx * ny) if (disp[i * 7].isFinite()) { su += disp[i * 7]; sv += disp[i * 7 + 1] }
                L += "Mean u = ${Ui.fmt((su / valid).toFloat())}   v = ${Ui.fmt((sv / valid).toFloat())} ${overlay.unit}" to Ui.TEXT
            }
        } else {
            L += "Nodes: ${overlay.previewGrid().size / 2}" to Ui.MUTED
            L += when {
                overlay.rulerMode -> "Calibration: place the markers"
                overlay.roiEditing -> "Drag the ROI corners / frame, then tap ROI again"
                !focusLocked -> "Tap to focus (or Manual), then Lock focus"
                else -> "Ready — press START"
            } to 0xFF80D8FF.toInt()
        }
        overlay.info = L
        overlay.postInvalidate()
    }

    private fun writeHeader(w: Int, h: Int) {
        writer?.apply {
            write("# DIC v3\n")
            write("# mode=${if (cfg.incremental) "incremental" else "lagrangian"}\n")
            write("# image=$w,$h\n")
            write("# grid=$nx,$ny,${cfg.step},${cfg.subset}\n")
            write("# search=${cfg.search} minZNCC=${cfg.minZncc}\n")
            write("# scale_mm_per_px=${scale()}\n")
            write("# calibration=${cfg.calSource}\n")
            write("# focus_diopters=${currentDist()}\n")
            write("# strain=${cfg.strain.name}\n")
            write("# force_unit=${force.unit}\n")
            write("frame,t_ms,x_px,y_px,u,v,exx,eyy,exy,zncc,ux,uy,vx,vy,force\n")
        }
    }

    private fun writeFrame() {
        val wr = writer ?: return
        val t = ((System.nanoTime() - t0) / 1_000_000L).toString()
        val f = if (force.running) force.force.toString() else "NaN"
        val sb = StringBuilder(nx * ny * 120)
        for (i in 0 until nx * ny) {
            val b = i * 7
            sb.append(frame).append(',').append(t).append(',')
                .append(gridXY[2 * i]).append(',').append(gridXY[2 * i + 1]).append(',')
                .append(disp[b]).append(',').append(disp[b + 1]).append(',')
                .append(OverlayView.value(Field.EXX, disp, i)).append(',').append(OverlayView.value(Field.EYY, disp, i)).append(',')
                .append(OverlayView.value(Field.EXY, disp, i)).append(',').append(out[b + 6]).append(',')
                .append(out[b + 2]).append(',').append(out[b + 3]).append(',').append(out[b + 4]).append(',').append(out[b + 5]).append(',')
                .append(f).append('\n')
        }
        wr.write(sb.toString())
    }

    // ================= controls =================
    private fun startRun() {
        if (overlay.rulerMode) endRuler()
        if (!focusLocked) {
            AlertDialog.Builder(this).setTitle("Focus is not locked")
                .setMessage("Autofocus may change the magnification during the test and corrupt the result.")
                .setPositiveButton("Lock & start") { _, _ -> toggleLock(); doStart() }
                .setNeutralButton("Start anyway") { _, _ -> doStart() }
                .setNegativeButton("Cancel", null).show()
        } else doStart()
    }

    private fun doStart() {
        csvName = "dic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        try { writer = storage.create(csvName).first.bufferedWriter() }
        catch (e: Exception) { toast("Cannot create file: ${e.message}"); return }
        frame = 0; t0 = System.nanoTime()
        overlay.roiEditing = false
        wantReference = true
        running = true
        btnRun.text = "■  STOP"; btnRun.color(Ui.STOP)
        updateToggles()
    }

    private fun stopRun() {
        running = false
        exec.execute {
            writer?.flush(); writer?.close(); writer = null
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Recording saved").setMessage("$csvName\n$frame frames\n${storage.folderLabel()}")
                    .setPositiveButton("Open in player") { _, _ -> storage.list().firstOrNull { it.name == csvName }?.let { openPlayer(it.uri) } }
                    .setNegativeButton("OK", null).show()
            }
        }
        btnRun.text = "▶  START"; btnRun.color(Ui.GO)
    }

    private fun toggleRoi() {
        if (running) { toast("Stop the measurement first"); return }
        if (overlay.rulerMode) endRuler()
        overlay.roiEditing = !overlay.roiEditing
        overlay.clearField(); updateToggles()
    }

    private fun refreshField() { if (running && nx > 0) exec.execute { postProcess(); overlay.setResult(disp) } }

    private fun nextField() {
        overlay.field = Field.entries[(overlay.field.ordinal + 1) % Field.entries.size]
        btnField.text = "Field: " + overlay.field.title
        refreshField()
    }

    private fun nextStrain() {
        cfg.strain = StrainMethod.entries[(cfg.strain.ordinal + 1) % StrainMethod.entries.size]; cfg.save()
        updateToggles(); refreshField()
    }

    private fun settingsDialog() {
        if (running) { toast("Stop the measurement first"); return }
        val f = Ui.Form(this)
        f.header("Correlation mode")
        val rg = f.radio(listOf("Versus first frame (0→1, 0→2, …)", "Frame to frame (1→2, 2→3, …), accumulated"), if (cfg.incremental) 1 else 0)
        f.note("Versus first frame: no error accumulation, but large deformations decorrelate.\nFrame to frame: follows large deformations; small errors accumulate.")
        f.header("DIC")
        val eS = f.num("Subset size (odd)", cfg.subset, "px")
        val eSt = f.num("Grid step", cfg.step, "px")
        val eSe = f.num("Search radius (max motion per frame)", cfg.search, "px")
        val eZ = f.num("Min. ZNCC", cfg.minZncc, "0…1")
        val eN = f.num("Record every N-th frame", cfg.everyN, "")
        f.header("Strain")
        val rgS = f.radio(StrainMethod.entries.map { it.title }, cfg.strain.ordinal)
        val eW = f.num("Local fit window half-size", cfg.fitWin, "nodes")
        f.note("Subset: IC-GN shape-function gradients (no smoothing).\nMesh: bilinear Q4 elements on the grid, nodal averaging.\nLocal fit: plane fit over (2N+1)² nodes — smoother, less noise.")
        f.header("Camera")
        val rgR = f.radio(resolutions.map { "${it.width}×${it.height}" + if (it.width == 1280) "  (default)" else "" }, cfg.resIndex)
        f.header("Specimen (stress)")
        val eWi = f.num("Width", cfg.width, "mm"); val eTh = f.num("Thickness", cfg.thickness, "mm")
        val rgA = f.radio(listOf("Loading along image Y", "Loading along image X"), if (cfg.axisY) 0 else 1)
        f.header("Save folder")
        f.note(storage.folderLabel(), Ui.TEXT)
        f.button("Choose folder…") { pickFolder.launch(null) }
        f.button("Use app folder") { cfg.folderUri = null; cfg.save(); toast(storage.folderLabel()) }

        val dlg = AlertDialog.Builder(this).setTitle("Settings").setView(f.view())
            .setPositiveButton("OK") { _, _ ->
                cfg.incremental = rg.selected() == 1
                cfg.subset = (eS.i(cfg.subset).coerceIn(11, 101)) or 1
                cfg.step = eSt.i(cfg.step).coerceIn(2, 100)
                cfg.search = eSe.i(cfg.search).coerceIn(1, 60)
                cfg.minZncc = eZ.f(cfg.minZncc).coerceIn(0f, 0.99f)
                cfg.everyN = eN.i(cfg.everyN).coerceAtLeast(1)
                cfg.strain = StrainMethod.entries[rgS.selected()]
                cfg.fitWin = eW.i(cfg.fitWin).coerceIn(1, 10)
                cfg.width = eWi.f(cfg.width); cfg.thickness = eTh.f(cfg.thickness); cfg.axisY = rgA.selected() == 0
                val resChanged = rgR.selected() != cfg.resIndex
                cfg.resIndex = rgR.selected()
                applySettings(resChanged)
            }
            .setNeutralButton("Defaults", null)
            .setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val old = cfg.resIndex
                cfg.resetDefaults(); applySettings(old != cfg.resIndex)
                toast("Defaults restored"); dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun applySettings(resChanged: Boolean) {
        cfg.save(); updateToggles()
        if (resChanged) startCamera()
    }

    private fun viewDialog() {
        val ov = overlay
        val f = Ui.Form(this)
        val cbV = f.check("Displacement vectors", ov.showVectors)
        val cbM = f.check("Deformed mesh", ov.showMesh)
        val cbL = f.check("Live force–displacement plot", showLive)
        val eK = f.num("Vector / mesh magnification", ov.vecScale, "×")
        f.header("Colour range")
        val cbMin = f.check("Limit minimum", ov.limitMin); val eMin = f.num("Minimum", if (ov.limitMin) ov.fixMin else ov.lastMin)
        val cbMax = f.check("Limit maximum", ov.limitMax); val eMax = f.num("Maximum", if (ov.limitMax) ov.fixMax else ov.lastMax)
        AlertDialog.Builder(this).setTitle("Display").setView(f.view())
            .setPositiveButton("OK") { _, _ ->
                ov.showVectors = cbV.isChecked; ov.showMesh = cbM.isChecked; showLive = cbL.isChecked
                ov.vecScale = eK.f(ov.vecScale)
                ov.limitMin = cbMin.isChecked; ov.limitMax = cbMax.isChecked
                ov.fixMin = eMin.f(0f); ov.fixMax = eMax.f(1f)
                updateToggles(); refreshField()
            }.setNeutralButton("Auto range") { _, _ -> ov.limitMin = false; ov.limitMax = false; refreshField() }
            .show()
    }

    // ================= force sensor =================
    private fun forceDialog() {
        val f = Ui.Form(this)
        f.header("Status")
        val status = f.note("", Ui.TEXT, 14f)
        fun refresh() {
            val sb = StringBuilder()
            sb.append(if (force.hotspotOn) "Hotspot ON\n   SSID: ${force.ssid}\n   Password: ${force.pass}\n" else "Hotspot off (device may also join the same Wi-Fi)\n")
            sb.append(if (force.running) "Receiver ON, port ${force.port} (TCP & UDP)\n   clients: ${force.clients}, ${"%.0f".format(force.rate)} Hz\n" else "Receiver off\n")
            val v = force.force
            sb.append("   last value: ${if (v.isFinite()) "${Ui.fmt(v)} ${force.unit}  (raw ${Ui.fmt(force.raw)})" else "—"}\n")
            sb.append("Phone IP: " + force.addresses().joinToString("; ").ifEmpty { "—" })
            status.text = sb
        }
        refresh()
        val prev = force.onUpdate
        force.onUpdate = { prev?.invoke(); refresh() }
        f.header("Connection")
        f.button(if (force.hotspotOn) "Stop hotspot" else "Start local hotspot (no internet)") { if (force.hotspotOn) { force.stopHotspot(); refresh() } else requestHotspot { refresh() } }
        f.button(if (force.running) "Stop receiver" else "Start receiver") { if (force.running) force.stop() else force.start(); refresh(); updateToggles() }
        f.header("Scaling")
        val eF = f.num("Factor (raw → force)", force.factor, "")
        val eU = f.text("Unit", force.unit)
        val eP = f.num("Port", force.port, "")
        f.button("Tare (zero now)") { force.factor = eF.f(force.factor); force.tare(); refresh() }
        f.header("Protocol")
        f.note("The device sends one number per line over TCP or UDP to the phone IP and port, e.g. \"123.4\\n\" or \"F=123.4\".\n" +
            "An ESP32 + HX711 example sketch is in the repository: firmware/force_sender.\n" +
            "Values are time-stamped on arrival and stored with each DIC frame.")
        AlertDialog.Builder(this).setTitle("Force sensor").setView(f.view())
            .setPositiveButton("Close") { _, _ ->
                force.factor = eF.f(force.factor); force.unit = eU.text.toString().ifBlank { "N" }
                val p = eP.i(force.port).coerceIn(1024, 65535)
                if (p != force.port) { val was = force.running; force.stop(); force.port = p; if (was) force.start() }
                cfg.forceFactor = force.factor; cfg.forceUnit = force.unit; cfg.forcePort = force.port; cfg.save()
            }
            .setOnDismissListener { force.onUpdate = prev; updateToggles() }
            .show()
    }

    private var afterHotspot: (() -> Unit)? = null
    private fun requestHotspot(done: () -> Unit) {
        afterHotspot = done
        val perms = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startHotspot()
        else askHotspotPerm.launch(perms.toTypedArray())
    }

    private fun startHotspot() {
        force.startHotspot { err ->
            if (err != null) toast(err)
            else { if (!force.running) force.start(); toast("Hotspot ${force.ssid} started") }
            afterHotspot?.invoke(); updateToggles()
        }
    }

    // ================= files =================
    private fun resultsDialog() {
        val items = try { storage.list() } catch (e: Exception) { emptyList() }
        val b = AlertDialog.Builder(this).setTitle("Results — " + storage.folderLabel())
        if (items.isEmpty()) b.setMessage("No recordings yet")
        else b.setItems(items.map { "${it.name}   ${it.size / 1024} KB" }.toTypedArray()) { _, i ->
            val it = items[i]
            AlertDialog.Builder(this).setTitle(it.name).setItems(arrayOf("Open in player", "Material analysis", "Share…", "Delete")) { _, a ->
                when (a) {
                    0 -> openPlayer(it.uri)
                    1 -> startActivity(Intent(this, AnalysisActivity::class.java).setData(it.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"; putExtra(Intent.EXTRA_STREAM, it.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share"))
                    3 -> AlertDialog.Builder(this).setMessage("Delete ${it.name}?").setPositiveButton("Delete") { _, _ -> storage.delete(it) }.setNegativeButton("Cancel", null).show()
                }
            }.show()
        }
        b.setPositiveButton("Change folder…") { _, _ -> pickFolder.launch(null) }.setNegativeButton("Close", null).show()
    }

    private fun openPlayer(uri: Uri?) {
        if (running) { toast("Stop the measurement first"); return }
        startActivity(Intent(this, PlayerActivity::class.java).apply { if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        sensors?.let { sm ->
            (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        applyCameraOptions()
    }

    override fun onPause() {
        super.onPause()
        sensors?.unregisterListener(this)
        if (running) stopRun()
    }

    override fun onDestroy() {
        super.onDestroy()
        force.stop(); force.stopHotspot()
        exec.execute { writer?.close(); DicNative.destroy(handle) }
        exec.shutdown()
    }
}
