package com.picoxr.capturesdkdemo.pipeline

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.opengl.GLSurfaceView
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.pxr.capturelib.*

class PipelineActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: PreviewRecorder
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var hidePanel: CheckBox
    private lateinit var mainPanel: LinearLayout
    private lateinit var hud: LinearLayout
    private lateinit var timer: TextView
    private lateinit var stopCircle: HoldToStopView
    private val ui = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("PicoPipelineCamera")
    private lateinit var cameraWorker: Handler
    private var capture: PXRCapture? = null
    private var surface: Surface? = null
    private var active = false
    private var configured = false
    private var previewReady = false
    private var starting = false
    private var recording = false
    private var saving = false
    private var recordingSince = 0L
    private var transparentRecording = true
    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording || saving) return
            val elapsed = (SystemClock.elapsedRealtime() - recordingSince) / 1000
            val next = "●  %02d:%02d".format(elapsed / 60, elapsed % 60)
            if (timer.text.toString() != next) { timer.text = next; requestHudRefresh() }
            ui.postDelayed(this, 200)
        }
    }
    private var hudRefreshPending = false
    private val hudRefresh = Runnable {
        hudRefreshPending = false
        if (!active || !recording || hud.visibility != View.VISIBLE || hud.width == 0 || hud.height == 0) return@Runnable
        val bitmap = Bitmap.createBitmap(hud.width, hud.height, Bitmap.Config.ARGB_8888)
        hud.draw(Canvas(bitmap))
        val location = IntArray(2); hud.getLocationInWindow(location)
        gl.queueEvent { renderer.updateHudOverlay(bitmap, location[0], location[1]) }
    }
    private fun requestHudRefresh() {
        if (!::hud.isInitialized || hudRefreshPending) return
        hudRefreshPending = true
        ui.postDelayed(hudRefresh, 50) // Coalesce hold progress updates to at most 20 Hz.
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setShadowLayer(dp(3).toFloat(), 0f, 1f, Color.BLACK)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        cameraThread.start(); cameraWorker = Handler(cameraThread.looper)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setEGLConfigChooser { egl, display ->
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
                val count = IntArray(1)
                check(egl.eglChooseConfig(display, intArrayOf(
                    0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8,
                    0x3040, 4, 0x3142, 1, 0x3038), configs, 1, count) && count[0] > 0) { "No RGBA recordable EGL config" }
                configs[0]!!
            }
        }
        renderer = PreviewRecorder({ gl.requestRender() }, { s -> cameraWorker.post { surface = s; openCamera() } },
            { message -> runOnUiThread { status.text = message; Log.i(TAG, message) } },
            { event -> runOnUiThread { onRecordingEvent(event) } })
        gl.setRenderer(renderer); gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        // GL is a sibling, never a child of the panel hidden while recording.
        mainPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.rgb(11, 16, 22))
        }
        header.addView(text("SYNAPATH · 透视录制", 24f).apply { setTextColor(Color.rgb(234, 174, 80)) })
        status = text("正在连接相机…", 15f)
        header.addView(status); mainPanel.addView(header)
        mainPanel.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11, 16, 22))
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        hidePanel = CheckBox(this).apply {
            text = "录制时隐藏面板，保留透视视野"; setTextColor(Color.WHITE); isChecked = true
        }
        footer.addView(hidePanel)
        footer.addView(text("关闭此选项可保留边录边预览 · 按住中央圆圈 2.5 秒停止并保存", 14f))
        start = Button(this).apply {
            text = "开始录制"; isEnabled = false
            setOnClickListener {
                if (starting || recording || saving || !previewReady) return@setOnClickListener
                starting = true; isEnabled = false; hidePanel.isEnabled = false
                transparentRecording = hidePanel.isChecked
                status.text = "正在开始录制…"
                val file = File(getExternalFilesDir(null), "AppPipeline-${System.currentTimeMillis()}.mp4")
                gl.queueEvent { renderer.startRecording(file) }
            }
        }
        footer.addView(start); mainPanel.addView(footer)
        root.addView(mainPanel, FrameLayout.LayoutParams(-1, -1))
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
        }
        timer = text("●  00:00", 28f).apply { gravity = Gravity.CENTER }
        hud.addView(timer, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16) })
        stopCircle = HoldToStopView(this, { requestStop() }, { requestHudRefresh() })
        hud.addView(stopCircle, LinearLayout.LayoutParams(dp(88), dp(88)))
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 12)
    }
    private fun requestStop() {
        if (!recording || saving) return
        saving = true; stopCircle.isEnabled = false
        timer.text = "正在保存…"; ui.removeCallbacks(timerTick); requestHudRefresh()
        Log.i(TAG, "HUD_HOLD_COMPLETED duration_ms=${HoldToStopView.HOLD_MS}")
        gl.queueEvent { renderer.stopRecording() }
    }
    private fun onRecordingEvent(event: PreviewRecorder.RecordingEvent) {
        Log.i(TAG, "RECORD_EVENT phase=${event.phase} frames=${event.frames} file=${event.file} message=${event.message}")
        when (event.phase) {
            PreviewRecorder.RecordingPhase.STARTED -> {
                starting = false; recording = true; saving = false
                recordingSince = SystemClock.elapsedRealtime()
                mainPanel.visibility = if (transparentRecording) View.GONE else View.VISIBLE
                hud.visibility = View.VISIBLE
                stopCircle.reset(); stopCircle.isEnabled = true; stopCircle.requestFocus()
                gl.queueEvent { renderer.setPreviewVisible(!transparentRecording) }
                ui.removeCallbacks(timerTick); if (active) ui.post(timerTick)
                hud.post { requestHudRefresh() }
                Log.i(TAG, "HUD_ENTER transparent=$transparentRecording")
            }
            PreviewRecorder.RecordingPhase.SAVING -> {
                saving = true; stopCircle.cancelHold(); stopCircle.isEnabled = false
                timer.text = "正在保存…"; ui.removeCallbacks(timerTick); requestHudRefresh()
            }
            PreviewRecorder.RecordingPhase.SAVED, PreviewRecorder.RecordingPhase.FAILED -> {
                starting = false; recording = false; saving = false
                ui.removeCallbacks(timerTick); stopCircle.cancelHold()
                ui.removeCallbacks(hudRefresh); hudRefreshPending = false
                gl.queueEvent { renderer.hideHudOverlay() }
                hud.visibility = View.GONE; mainPanel.visibility = View.VISIBLE
                start.isEnabled = previewReady && active; hidePanel.isEnabled = true
                gl.queueEvent { renderer.setPreviewVisible(true) }
                status.text = if (event.phase == PreviewRecorder.RecordingPhase.SAVED)
                    "已保存 ${event.frames} 帧 · ${event.file?.name}\n${event.file?.parent}"
                else "录制失败：${event.message}"
                Log.i(TAG, "HUD_EXIT phase=${event.phase}")
            }
        }
    }
    override fun onResume() {
        super.onResume(); active = true; gl.onResume()
        cameraWorker.post { openCamera() }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); cameraWorker.post { openCamera() }
    }
    private fun openCamera() {
        if (!active || capture != null || surface?.isValid != true || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        configured = false
        val current = PXRCapture(); capture = current
        current.setCallback(object : PXRCaptureCallBack {
            override fun captureOnStateChanged(state: Int) { cameraWorker.post {
                if (capture !== current) return@post
                Log.i(TAG, "SDK_STATE $state")
                when (state) {
                    PXRCapture.PXRCaptureState.CAPTURE_STATE_CAMERA_OPENED.ordinal -> if (!configured) {
                        configured = true
                        checkResult("configure", current.configure(mapOf(
                            PXRCapture.KEY_VIDEO_FPS to "60", PXRCapture.KEY_VIDEO_WIDTH to "2048",
                            PXRCapture.KEY_VIDEO_HEIGHT to "768", PXRCapture.KEY_ENABLE_MVHEVC to PXRCapture.VALUE_FALSE,
                            PXRCapture.KEY_OUTPUT_CAMERA_RAW_DATA to PXRCapture.VALUE_TRUE)))
                    }
                    PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal -> {
                        val result = current.startPreview(surface, PXRCapture.PXRCaptureRenderMode.PXRCapture_RenderMode_3D.ordinal, 2048, 768)
                        checkResult("startPreview", result)
                        runOnUiThread { previewReady = result > 0; start.isEnabled = previewReady && !starting && !recording && !saving; status.text = "实时预览已连接 · 60 FPS 请求 · 无声视频" }
                    }
                }
            } }
            override fun captureOnError(error: Int) {
                runOnUiThread {
                    previewReady = false; start.isEnabled = false; status.text = "SDK 错误 $error"
                    if (recording) { saving = true; gl.queueEvent { renderer.stopRecording("SDK 错误 $error") } }
                }
                Log.e(TAG, "SDK error $error")
            }
            override fun captureOnEnviromentInfoChanged(a: Int, b: Int) {}
            override fun captureOnTakePictureComplete(a: Float, b: Float, c: Float, d: Float, e: Float) {}
            override fun captureOnTakePictureTimeOut() {}
            override fun captureOnCharacteristicsCallback(c: PXRCharacteristics) { Log.i(TAG, "calibration ${c.all.keys}") }
        })
        checkResult("openCamera", current.openCameraAsync(mapOf(PXRCapture.KEY_MCTF to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_EIS to PXRCapture.VALUE_TRUE, PXRCapture.KEY_MFNR to PXRCapture.VALUE_TRUE)))
    }
    private fun checkResult(operation: String, result: Int) {
        Log.i(TAG, "$operation=$result")
        if (result <= 0) runOnUiThread { status.text = "$operation 失败：$result" }
    }
    override fun onPause() {
        active = false; previewReady = false; start.isEnabled = false
        ui.removeCallbacks(timerTick); stopCircle.cancelHold()
        ui.removeCallbacks(hudRefresh); hudRefreshPending = false
        val done = CountDownLatch(1)
        cameraWorker.post {
            val old = capture; capture = null
            try { old?.stopPreview(); old?.reset(); old?.closeCamera(); old?.release() }
            catch (e: Exception) { Log.e(TAG, "close SDK", e) }
            gl.queueEvent { try { renderer.release() } finally { done.countDown() } }
        }
        if (!done.await(8, TimeUnit.SECONDS)) Log.e(TAG, "Pause cleanup timeout")
        surface = null; gl.onPause(); super.onPause()
    }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); cameraThread.quitSafely(); super.onDestroy() }
    companion object { private const val TAG = "PicoPipeline" }
}
