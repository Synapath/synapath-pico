package com.picoxr.capturesdkdemo.pipeline

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.Keep
import com.pxr.capturelib.*
import java.io.File
import java.io.BufferedWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Experimental XR front end. The original 2D activity remains available for A/B tests. */
@Keep
class OpenXrActivity : Activity() {
    private val cameraThread = HandlerThread("OpenXrCapture")
    private lateinit var camera: Handler
    @Volatile private var active = false
    @Volatile private var ready = false
    @Volatile private var message = "正在连接相机"
    private var capture: PXRCapture? = null // camera looper only
    private var xrThread: Thread? = null
    private var recorder: PreviewRecorder? = null // XR GL thread only
    private var recording = false
    private var saving = false
    private var since = 0L
    private var firstFrameAt = 0L
    private var rawTimestamps: BufferedWriter? = null
    private var pressedAt = 0L
    private var previousDown = true // Require release after entering XR / after saving.
    private var frameCount = 0
    private var autoRecordMs = 0L // Explicit adb-only test option; manual recording is the default.
    private var autoStarted = false
    private var autoFinished = false
    private val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var revision = 0
    private var lastHud = ""
    private external fun nativeRun()
    private external fun nativePrepare()
    private external fun nativeStop()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Synapath OpenXR 实验版\n正在进入头部跟随 HUD…" })
        cameraThread.start(); camera = Handler(cameraThread.looper)
        autoRecordMs = intent.getLongExtra("auto_record_ms", 0L).coerceIn(0, 60_000)
    }
    override fun onResume() {
        super.onResume(); active = true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 31)
        else startXr()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (active && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startXr()
    }
    private fun startXr() {
        if (xrThread?.isAlive == true) return
        autoStarted = false; autoFinished = false
        previousDown = true; pressedAt = 0
        nativePrepare()
        xrThread = Thread({ nativeRun() }, "SynapathOpenXR").also { it.start() }
    }
    @Keep private fun onXrStatus(value: String) { Log.i(TAG, value); message = value }

    // These callbacks run on the native XR rendering thread with its EGL context current.
    @Keep private fun onGlReady() {
        recorder = PreviewRecorder({}, { s -> camera.post { openCamera(s) } }, { Log.i(TAG, it) }, { event ->
            Log.i(TAG, "RECORD_EVENT phase=${event.phase} frames=${event.frames} file=${event.file} message=${event.message}")
            when (event.phase) {
                PreviewRecorder.RecordingPhase.STARTED -> {
                    recording = true; saving = false; since = SystemClock.elapsedRealtime(); firstFrameAt = 0; frameCount = 0
                    try { rawTimestamps = File(event.file!!.parentFile, event.file.nameWithoutExtension + ".source.csv").bufferedWriter().also { it.write("source_mono_ns\n") } }
                    catch (e: Exception) { Log.e(TAG, "Source audit unavailable", e) }
                }
                PreviewRecorder.RecordingPhase.SAVING -> saving = true
                PreviewRecorder.RecordingPhase.SAVED, PreviewRecorder.RecordingPhase.FAILED -> {
                    recording = false; saving = false; previousDown = true; pressedAt = 0
                    rawTimestamps?.close(); rawTimestamps = null
                    frameCount = event.frames
                    message = if (event.phase == PreviewRecorder.RecordingPhase.SAVED) "已保存 ${event.frames} 帧" else "失败：${event.message}"
                    if (autoStarted) autoFinished = true
                }
            }
        }, { sourceNs ->
            if (recording && !saving) {
                if (firstFrameAt == 0L) { firstFrameAt = SystemClock.elapsedRealtime(); since = firstFrameAt }
                try { rawTimestamps?.write("$sourceNs\n") } catch (e: Exception) { Log.e(TAG, "Source audit write", e) }
            }
        }).also { it.onSurfaceCreated(null, null); it.onSurfaceChanged(null, 640, 640); it.setPreviewVisible(false) }
    }
    private fun startRecording() {
        if (!ready || recording || saving) return
        pressedAt = 0
        recorder?.startRecording(File(getExternalFilesDir(null), "OpenXR-${System.currentTimeMillis()}.mp4"))
    }
    @Keep private fun onXrFrame(down: Boolean, cursorX: Float, cursorY: Float): Int {
        recorder?.onDrawFrame(null)
        val now = SystemClock.elapsedRealtime()
        val hit = cursorX >= 0 && (cursorX - 320) * (cursorX - 320) + (cursorY - 360) * (cursorY - 360) <= 68 * 68
        if (autoRecordMs > 0 && ready && !autoStarted && !autoFinished) { autoStarted = true; startRecording() }
        if (autoStarted && recording && !saving && firstFrameAt != 0L && now - firstFrameAt >= autoRecordMs) recorder?.stopRecording()
        if (autoStarted && recording && firstFrameAt == 0L && now - since > 10_000) recorder?.stopRecording("相机首帧超时")
        if (!autoStarted && !saving) {
            if (!recording) {
                if (down && !previousDown && hit) startRecording()
            } else if (down && hit && !previousDown) pressedAt = now
            if (pressedAt > 0 && (!down || !hit)) pressedAt = 0
            if (pressedAt > 0 && now - pressedAt >= 2_500) {
                pressedAt = 0
                Log.i(TAG, "XR_HOLD_COMPLETED duration_ms=2500")
                recorder?.stopRecording()
            }
        }
        previousDown = down
        val progress = if (pressedAt > 0) ((now - pressedAt) / 50).toInt().coerceAtMost(50) else 0
        val elapsed = if (recording) (now - since) / 1000 else 0
        val cx = if (cursorX >= 0) cursorX.toInt() else -1
        val cy = if (cursorY >= 0) cursorY.toInt() else -1
        val key = "$recording/$saving/$ready/$elapsed/$progress/$message/$cx/$cy"
        if (key != lastHud) {
            lastHud = key; canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            paint.reset(); paint.isAntiAlias = true; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            paint.textSize = 46f; paint.setShadowLayer(5f, 0f, 2f, Color.BLACK)
            val label = when { saving -> "正在保存…"; recording -> "● %02d:%02d".format(elapsed / 60, elapsed % 60); !ready -> "正在连接相机"; else -> "开始录制" }
            canvas.drawText(label, 320f, 235f, paint)
            paint.clearShadowLayer(); paint.style = Paint.Style.FILL; paint.color = 0xAA101820.toInt()
            canvas.drawCircle(320f, 360f, 68f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = Color.rgb(245, 92, 85)
            canvas.drawCircle(320f, 360f, 68f, paint)
            if (progress > 0) { paint.color = Color.WHITE; paint.strokeWidth = 7f; canvas.drawArc(245f, 285f, 395f, 435f, -90f, progress * 7.2f, false, paint) }
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            if (recording) canvas.drawRoundRect(299f, 339f, 341f, 381f, 4f, 4f, paint)
            else { paint.color = Color.rgb(245, 92, 85); canvas.drawCircle(320f, 360f, 24f, paint) }
            // No explanatory text underneath the recording circle.
            if (!recording) { paint.color = Color.WHITE; paint.textSize = 24f; canvas.drawText(if (ready) message else "PXRCaptureSDK", 320f, 480f, paint) }
            if (cx in 0..639 && cy in 0..639) { paint.color = Color.WHITE; canvas.drawCircle(cx.toFloat(), cy.toFloat(), 6f, paint) }
            revision++
        }
        return revision
    }
    @Keep private fun getHudBitmap(): Bitmap = bitmap
    @Keep private fun onGlShutdown() {
        ready = false
        val done = CountDownLatch(1)
        camera.post {
            val old = capture; capture = null
            try { old?.stopPreview(); old?.reset(); old?.closeCamera(); old?.release() }
            catch (e: Exception) { Log.e(TAG, "SDK shutdown", e) }
            finally { done.countDown() }
        }
        if (!done.await(6, TimeUnit.SECONDS)) Log.e(TAG, "SDK shutdown timeout")
        recorder?.release(); recorder = null
        rawTimestamps?.close(); rawTimestamps = null
    }
    private fun openCamera(surface: Surface) {
        if (!active || capture != null) return
        val current = PXRCapture(); capture = current
        var configured = false
        current.setCallback(object : PXRCaptureCallBack {
            override fun captureOnStateChanged(state: Int) { camera.post {
                if (!active || capture !== current) return@post
                Log.i(TAG, "SDK_STATE $state")
                when (state) {
                    PXRCapture.PXRCaptureState.CAPTURE_STATE_CAMERA_OPENED.ordinal -> if (!configured) {
                        configured = true
                        checkResult("configure", current.configure(mapOf(PXRCapture.KEY_VIDEO_FPS to "60",
                            PXRCapture.KEY_VIDEO_WIDTH to "2048", PXRCapture.KEY_VIDEO_HEIGHT to "768",
                            PXRCapture.KEY_ENABLE_MVHEVC to PXRCapture.VALUE_FALSE,
                            PXRCapture.KEY_OUTPUT_CAMERA_RAW_DATA to PXRCapture.VALUE_TRUE)))
                    }
                    PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal -> {
                        val result = current.startPreview(surface, PXRCapture.PXRCaptureRenderMode.PXRCapture_RenderMode_3D.ordinal, 2048, 768)
                        ready = result > 0; checkResult("startPreview", result)
                        if (ready) message = "PXRCaptureSDK · 请求 60 FPS"
                    }
                }
            } }
            override fun captureOnError(error: Int) { ready = false; message = "SDK 错误 $error"; Log.e(TAG, message) }
            override fun captureOnEnviromentInfoChanged(a: Int, b: Int) {}
            override fun captureOnTakePictureComplete(a: Float, b: Float, c: Float, d: Float, e: Float) {}
            override fun captureOnTakePictureTimeOut() {}
            override fun captureOnCharacteristicsCallback(c: PXRCharacteristics) {}
        })
        checkResult("openCamera", current.openCameraAsync(mapOf(PXRCapture.KEY_MCTF to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_EIS to PXRCapture.VALUE_TRUE, PXRCapture.KEY_MFNR to PXRCapture.VALUE_TRUE)))
    }
    private fun checkResult(operation: String, result: Int) {
        Log.i(TAG, "$operation=$result"); if (result <= 0) { ready = false; message = "$operation 失败 $result" }
    }
    override fun onPause() {
        active = false; nativeStop()
        xrThread?.join(8_000)
        if (xrThread?.isAlive == true) Log.e(TAG, "XR shutdown timeout")
        super.onPause()
    }
    override fun onDestroy() { cameraThread.quitSafely(); super.onDestroy() }
    companion object { private const val TAG = "SynapathOpenXR"; init { System.loadLibrary("synapath_openxr") } }
}
