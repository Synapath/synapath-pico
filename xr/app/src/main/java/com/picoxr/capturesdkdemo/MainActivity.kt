package com.picoxr.capturesdkdemo

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import java.util.concurrent.Executors
import com.picoxr.capturesdkdemo.timeline.PicoTimeline
import com.picoxr.capturesdkdemo.xr.OpenXrUiHost
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.graphics.Typeface
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.Locale
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.picoxr.capturesdkdemo.permissions.PermissionUtils
import com.pxr.capturelib.PXRCapture
import com.pxr.capturelib.PXRCaptureCallBack
import com.pxr.capturelib.PXRCharacteristics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { densityDpi = 240; fontScale = 1f }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    private lateinit var xrUi: OpenXrUiHost
    private var debugReceiver: BroadcastReceiver? = null

    private lateinit var imageReader: ImageReader

    @Volatile private var pxrCapture: PXRCapture? = null

    private lateinit var btnCapture: Button
    private lateinit var btnStartRecord: Button
    private lateinit var btnStopRecord: Button
    private lateinit var btnStartPreview: Button
    private lateinit var btnStopPreview: Button
    private lateinit var btnGetCameraParam:Button
    private lateinit var tvCameraParam:TextView
    private lateinit var checkBox:CheckBox
    private lateinit var surfaceView: SurfaceView

    private var captureState:Int = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var recordingSince = 0L
    private var lastRecordPath = ""
    private var pendingRecordPath = ""
    private val finalizeWorker = Executors.newSingleThreadExecutor()
    @Volatile private var finalizing = false

    private fun renderFinalizing() {
        if (::xrUi.isInitialized) xrUi.setSaving()
        findViewById<TextView>(R.id.preview_message).apply {
            visibility = View.VISIBLE
            text = "正在保存 RGB / ToF / Pose\n保留相机原始时间轴，完成后自动恢复预览"
        }
        btnStartRecord.isEnabled = false
        btnStopRecord.isEnabled = false
        btnCapture.isEnabled = false
        btnStartPreview.isEnabled = false
        btnStopPreview.isEnabled = false
        btnGetCameraParam.isEnabled = false
        checkBox.isEnabled = false
        findViewById<TextView>(R.id.capture_status).text = "正在校验保存"
        findViewById<TextView>(R.id.session_status).text = "保留原始时间戳，正在校验录像完整性…"
    }
    private var previewAttached = false
    private var calibrationJson: JSONObject? = null
    private var calibrationCharacteristics: JSONObject? = null
    @Volatile private var configuredUndistorted = true
    private var originalCalibration: JSONObject? = null
    @Volatile private var probingOriginalCalibration = false
    private var probeConfigurationReady = false
    private var pendingCalibration: JSONObject? = null
    private var resumePreviewAfterRecord = false
    private val recordingTimer = object : Runnable {
        override fun run() {
            if (recordingSince == 0L) return
            val elapsed = (SystemClock.elapsedRealtime() - recordingSince) / 1000
            findViewById<TextView>(R.id.session_status).text =
                "正在录制  %02d:%02d".format(elapsed / 60, elapsed % 60)
            findViewById<TextView>(R.id.preview_message).text =
                "正在录制  %02d:%02d\nSDK 录像时暂停预览，保存完成后自动恢复".format(elapsed / 60, elapsed % 60)
            uiHandler.postDelayed(this, 500)
        }
    }

    private fun setupNavigation() {
        val tabs = listOf(R.id.nav_preview, R.id.nav_output, R.id.nav_calibration, R.id.nav_device)
        val pages = listOf(R.id.page_preview, R.id.page_output, R.id.page_calibration, R.id.page_device)
        fun select(index: Int) {
            tabs.forEachIndexed { i, id ->
                findViewById<Button>(id).apply {
                    setBackgroundResource(if (i == index) R.drawable.capture_nav_active else R.drawable.capture_nav_idle)
                    setTextColor(Color.parseColor(if (i == index) "#EAAE50" else "#BBC9D5"))
                    isSelected = i == index
                }
                findViewById<View>(pages[i]).visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }
        tabs.forEachIndexed { index, id -> findViewById<Button>(id).setOnClickListener { select(index) } }
        select(0)
        findViewById<TextView>(R.id.device_info).text =
            "设备  ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid  ${Build.VERSION.RELEASE}\nSDK  capturelib-0.1.1\n应用版本  ${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    private fun renderCaptureState(state: Int) {
        if (finalizing) { renderFinalizing(); return }
        if (probingOriginalCalibration) {
            listOf(btnStartRecord, btnStopRecord, btnCapture, btnStartPreview, btnStopPreview, btnGetCameraParam).forEach { it.isEnabled = false }
            checkBox.isEnabled = false
            findViewById<Button>(R.id.btn_export_calibration).isEnabled = false
            findViewById<TextView>(R.id.capture_status).text = "正在读取两套标定"
            findViewById<TextView>(R.id.preview_message).apply { visibility = View.VISIBLE; text = "正在读取原始相机标定\n完成后恢复去畸变模式" }
            return
        }
        btnStopRecord.isEnabled = true
        val recording = state == PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_CAPTURING.ordinal
        val previewing = state == PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_PREVIEWING.ordinal
        findViewById<TextView>(R.id.preview_message).apply {
            visibility = if (previewing) View.GONE else View.VISIBLE
            text = if (recording) "正在录制\n保存完成后自动恢复预览" else "相机预览\n点击右上角开启预览"
        }
        val ready = state == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal ||
            state == PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_PREVIEWING.ordinal
        findViewById<TextView>(R.id.capture_status).text = when (state) {
            PXRCapture.PXRCaptureState.CAPTURE_STATE_CAMERA_OPENING.ordinal -> "正在连接相机"
            PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal -> "相机已就绪"
            PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_PREVIEWING.ordinal -> "预览中"
            PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_CAPTURING.ordinal -> "●  录制中"
            PXRCapture.PXRCaptureState.CAPTURE_STATE_PHOTO_CAPTURING.ordinal -> "拍照中"
            else -> "相机状态 $state"
        }
        btnStartRecord.visibility = if (recording) View.GONE else View.VISIBLE
        btnStopRecord.visibility = if (recording) View.VISIBLE else View.GONE
        btnStartRecord.isEnabled = ready
        btnCapture.isEnabled = ready
        btnStartPreview.isEnabled = state == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal
        btnStopPreview.isEnabled = state == PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_PREVIEWING.ordinal
        checkBox.isEnabled = ready
        btnGetCameraParam.isEnabled = ready
        if (recording && recordingSince == 0L) {
            recordingSince = SystemClock.elapsedRealtime()
            uiHandler.post(recordingTimer)
        } else if (!recording && recordingSince != 0L) {
            recordingSince = 0L
            uiHandler.removeCallbacks(recordingTimer)
            findViewById<TextView>(R.id.session_status).text = "录制结束 · 可在文件管理器查看视频"
        }
        if (::xrUi.isInitialized) xrUi.setRecording(recording)
        if (!recording && resumePreviewAfterRecord && state == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal) {
            uiHandler.postDelayed({
                if (!finalizing && !isDestroyed && resumePreviewAfterRecord &&
                    captureState == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal &&
                    surfaceView.holder.surface.isValid) startPreview()
            }, 250)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "onCreate")
        surfaceView = findViewById(R.id.surfaceView)
        xrUi = OpenXrUiHost(this, surfaceView) { stopRecord() }
        // Keep the SDK render target aligned and independent from fractional
        // layout sizes (e.g. 813x305 px in the current floating window).
        surfaceView.holder.setFixedSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        btnCapture = findViewById(R.id.btn_capture)
        btnStartRecord = findViewById(R.id.btn_start_record)
        btnStopRecord = findViewById(R.id.btn_stop_record)
        btnStartPreview = findViewById(R.id.btn_start_preview)
        btnStopPreview = findViewById(R.id.btn_stop_preview)
        btnGetCameraParam = findViewById(R.id.btn_get_camera_param)
        tvCameraParam = findViewById(R.id.tv_camera_param)
        checkBox = findViewById(R.id.check_box)
        setupNavigation()
        renderCaptureState(0)

        btnCapture.setOnClickListener { takePicture() }
        btnStartPreview.setOnClickListener { startPreview() }
        btnStopPreview.setOnClickListener { stopPreview() }
        btnStartRecord.setOnClickListener { startRecord() }
        btnStopRecord.setOnClickListener { stopRecord() }
        btnGetCameraParam.setOnClickListener { getCameraParams() }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Shell-only automation for controlled A/B tests; absent in release
            // and protected by the signature-level DUMP sender permission.
            debugReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getStringExtra("command") != "record" || !btnStartRecord.isEnabled ||
                        finalizing || pendingRecordPath.isNotEmpty()) { resultCode = 1; return }
                    startRecord()
                    val path = pendingRecordPath
                    resultCode = if (path.isNotEmpty()) 0 else 2
                    val duration = intent.getLongExtra("duration_ms", 10000).coerceIn(1000, 30000)
                    if (path.isNotEmpty()) uiHandler.postDelayed({
                        if (!isDestroyed && pendingRecordPath == path && !finalizing) stopRecord()
                    }, duration)
                }
            }
            registerReceiver(debugReceiver, IntentFilter("com.synapath.picosensors.openxr.DEBUG_CAPTURE"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
        }
        findViewById<Button>(R.id.btn_export_calibration).setOnClickListener { exportCalibration() }
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            GlobalScope.launch(Dispatchers.Default) {
                releaseCamera()
                initPXRCapture(isChecked)
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback2 {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                if (resumePreviewAfterRecord && !finalizing && captureState == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal)
                    uiHandler.post { if (!isDestroyed && !finalizing) startPreview() }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.i(TAG, "surfaceChanged $format $width $height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
            }

            override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceRedrawNeeded")
            }

        })

        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            "com.picovr.permission.SPATIAL_DATA"
        )
        val checkCode: Int = PermissionUtils.checkPermission(this, permissions, true)
        if (checkCode == PermissionUtils.CHECK_RESULT_OK) {
            Log.i(TAG, "checkPermission OK")
            initPXRCapture(checkBox.isChecked)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, "com.picovr.permission.SPATIAL_DATA")
        if (required.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            if (pxrCapture == null && !isDestroyed) initPXRCapture(checkBox.isChecked)
        } else {
            findViewById<TextView>(R.id.capture_status).text = "采集权限未完整授予"
            findViewById<TextView>(R.id.session_status).text = "需要相机、麦克风和空间数据权限，请在系统设置中授予后重新打开"
        }
    }

    private fun initPXRCapture(undistorted: Boolean, preservedOriginal: JSONObject? = null, skipOriginalProbe: Boolean = false){
        Log.i(TAG, "initPXRCapture")
        val capture = PXRCapture()
        pxrCapture = capture
        configuredUndistorted = false
        probingOriginalCalibration = undistorted && !skipOriginalProbe
        probeConfigurationReady = false
        runOnUiThread {
            originalCalibration = preservedOriginal?.let { JSONObject(it.toString()) }
            calibrationCharacteristics = null
            calibrationJson = null
            findViewById<LinearLayout>(R.id.calibration_cards).removeAllViews()
            findViewById<Button>(R.id.btn_export_calibration).isEnabled = false
            tvCameraParam.text = "正在读取原始标定与当前输出参数…"
        }
        val cameraParams= mutableMapOf<String, String>(
            PXRCapture.KEY_MCTF to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_EIS to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_MFNR to PXRCapture.VALUE_TRUE
        )
        pxrCapture?.setCallback(object :PXRCaptureCallBack{
            override fun captureOnStateChanged(state: Int) {
                if (pxrCapture !== capture) return

                Log.i(TAG, "captureOnStateChanged state: $state")
                captureState = state
                runOnUiThread {
                    if (pxrCapture !== capture) return@runOnUiThread
                    renderCaptureState(state)
                    if (state == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal && !finalizing) {
                        if (probingOriginalCalibration) {
                            probeConfigurationReady = true
                            finishOriginalCalibrationProbe(capture)
                        } else uiHandler.post {
                            if (pxrCapture === capture && !finalizing && recordingSince == 0L) getCameraParams()
                        }
                    }
                    if (state == PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_CAPTURING.ordinal) {
                        resumePreviewAfterRecord = true
                    } else if (state == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal && resumePreviewAfterRecord) {
                        uiHandler.postDelayed({
                            if (!finalizing && captureState == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal && !isDestroyed) startPreview()
                        }, 150)
                    }
                }
                if (captureState == PXRCapture.PXRCaptureState.CAPTURE_STATE_CAMERA_OPENED.ordinal){
                    captureConfig(if (probingOriginalCalibration) false else undistorted)
                }
            }

            override fun captureOnError(error: Int) {
                Log.i(TAG, "captureOnError state: $error")
                runOnUiThread {
                    recordingSince = 0L
                    uiHandler.removeCallbacks(recordingTimer)
                    findViewById<TextView>(R.id.capture_status).text = "相机错误 $error"
                    findViewById<TextView>(R.id.session_status).text = "采集失败，请重新打开应用"
                    xrUi.setRecording(false)
                }
            }

            override fun captureOnEnviromentInfoChanged(lut: Int, distance: Int) {

            }

            override fun captureOnTakePictureComplete(
                disparityScale:Float, disparityShift:Float, hfov:Float, vFov:Float, cameraIpd:Float
            ) {
                Log.i(
                    TAG,
                    "captureOnTakePictureComplete disparityScale: $disparityScale disparityShift: $disparityShift"
                )
            }

            override fun captureOnCharacteristicsCallback(characteristics: PXRCharacteristics) {
                if (pxrCapture !== capture) return
                val callbackUndistorted = configuredUndistorted
                val snapshot = CalibrationSnapshot.characteristics(characteristics, callbackUndistorted, RECORD_WIDTH, RECORD_HEIGHT)
                runOnUiThread {
                    if (pxrCapture !== capture) return@runOnUiThread
                    calibrationCharacteristics = snapshot
                    if (!callbackUndistorted) originalCalibration = JSONObject(snapshot.toString())
                    if (probingOriginalCalibration) {
                        finishOriginalCalibrationProbe(capture)
                        return@runOnUiThread
                    }
                    if (!finalizing && recordingSince == 0L && captureState in listOf(
                            PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal,
                            PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_PREVIEWING.ordinal)) {
                        uiHandler.post { if (pxrCapture === capture && !finalizing && recordingSince == 0L) getCameraParams() }
                    }
                }
                Log.i(TAG, "CALIBRATION_CHARACTERISTICS $snapshot")
            }

            override fun captureOnTakePictureTimeOut() {
                Log.i(TAG, "captureOnTakePictureTimeOut")
            }

        })
        pxrCapture?.openCameraAsync(cameraParams)
        uiHandler.postDelayed({
            if (pxrCapture === capture && probingOriginalCalibration && probeConfigurationReady) {
                Log.w(TAG, "Original calibration callback timed out; restore requested output with unavailable flag")
                finishOriginalCalibrationProbe(capture, true)
            }
        }, 5000)
    }

    private fun finishOriginalCalibrationProbe(capture: PXRCapture, timeout: Boolean = false) {
        if (!probingOriginalCalibration || !probeConfigurationReady || (!timeout && originalCalibration == null)) return
        probingOriginalCalibration = false
        probeConfigurationReady = false
        calibrationCharacteristics = null
        calibrationJson = null
        uiHandler.post {
            if (pxrCapture === capture) {
                Log.i(TAG, "CALIBRATION_PROBE_COMPLETE rawAvailable=${originalCalibration?.optBoolean("available")} restoringUndistorted=true")
                val original = originalCalibration?.let { JSONObject(it.toString()) }
                releaseCamera()
                // reset alone retains RAW-only characteristic keys in this firmware.
                // Reopen a fresh client so output distortion/model fields cannot be stale.
                initPXRCapture(true, original, true)
            }
        }
    }

    private fun releaseCamera(){
        val capture = pxrCapture
        pxrCapture = null // Ignore reset/close callbacks from the old session.
        capture?.run {
            stopPreview()
            stopRecord()
            reset()
            closeCamera()
            release()
        }
        pxrCapture = null
    }

    private fun captureConfig(undistorted: Boolean){
        configuredUndistorted = undistorted
        Log.i(TAG, "captureConfig undistorted=$undistorted outputCameraRawData=${!undistorted}")
        val configureParams = mutableMapOf(
            PXRCapture.KEY_ENABLE_MVHEVC to PXRCapture.VALUE_FALSE,
            PXRCapture.KEY_OUTPUT_CAMERA_RAW_DATA to
                if (undistorted) PXRCapture.VALUE_FALSE else PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_VIDEO_FPS to  "60",
            PXRCapture.KEY_WRITE_DEPTH_DATA to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_WRITE_POSE_DATA to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_WRITE_CAMERA_PARAMS_DATA to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_VIDEO_WIDTH to RECORD_WIDTH.toString(),
            PXRCapture.KEY_VIDEO_HEIGHT to RECORD_HEIGHT.toString(),
            PXRCapture.KEY_VIDEO_BITRATE to "${10 * 1024 * 1024}",
        )
        pxrCapture?.configure(configureParams)
    }

    private fun getCameraParams() {
        val capture = pxrCapture ?: return
        if (finalizing || recordingSince != 0L || captureState !in listOf(
                PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal,
                PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_PREVIEWING.ordinal)) return
        if (probingOriginalCalibration) return
        val output = CalibrationSnapshot.output(calibrationCharacteristics, configuredUndistorted, RECORD_WIDTH, RECORD_HEIGHT)
        val raw = originalCalibration
            ?: JSONObject().put("available", false).put("status", "RAW-mode SDK callback not received")
        val snapshot = JSONObject().put("schema", 2).put("sdk", "capturelib-0.1.1")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("firmware", Build.DISPLAY)
            .put("captured_at_unix_ms", System.currentTimeMillis())
            .put("raw_camera_calibration", JSONObject(raw.toString()))
            .put("output_image_parameters", output)
        calibrationJson = snapshot
        renderCalibration(snapshot)
        Log.i(TAG, "CALIBRATION_SNAPSHOT rawAvailable=${raw.optBoolean("available")} outputAvailable=${output.optBoolean("available")} outputMode=${output.optString("image_mode")}")
    }

    private fun renderCalibration(snapshot: JSONObject) {
        val cards = findViewById<LinearLayout>(R.id.calibration_cards)
        cards.removeAllViews()
        tvCameraParam.text = "原始标定 + 输出参数 · 双目 ${RECORD_WIDTH}×${RECORD_HEIGHT} / 单眼 ${RECORD_WIDTH / 2}×${RECORD_HEIGHT}\n显示已舍入，JSON 保留完整精度与原始标定字节"
        fun card(title: String, body: String) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 14, 18, 14)
                setBackgroundResource(R.drawable.capture_inset)
            }
            cards.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            container.addView(TextView(this).apply {
                text = title; textSize = 15f; setTextColor(Color.parseColor("#EAAE50"))
            })
            container.addView(TextView(this).apply {
                text = body; textSize = 14f; setPadding(0, 10, 0, 0)
                setTextColor(Color.parseColor("#EAF0F5")); setTextIsSelectable(true)
            })
        }
        fun values(array: JSONArray?): String {
            if (array == null || array.length() == 0) return "SDK 未提供"
            return (0 until array.length()).chunked(4).joinToString("\n") { row ->
                row.joinToString("   ") { i ->
                    val value = array.optDouble(i)
                    if (value.isFinite()) String.format(Locale.US, "%.5f", value) else "无效"
                }
            }
        }
        val raw = snapshot.getJSONObject("raw_camera_calibration")
        card("原始模式标定 · SDK 回调 · 单眼 ${RECORD_WIDTH / 2}×${RECORD_HEIGHT}", "模型：${raw.optString("camera_model", "未知")}\n畸变模型：${raw.optString("distortion_model", "未知")}\n内参与畸变系数对应原始相机，不直接用于去畸变输出")
        listOf("intrinsics" to "原始内参", "extrinsics" to "原始外参", "distortion_coefficients" to "原始畸变系数").forEach { (key, label) ->
            val array = raw.optJSONObject(key)?.optJSONArray("packed_values")
            card("$label · SDK 打包数组（${array?.length() ?: 0} 项）", values(array))
        }
        val output = snapshot.getJSONObject("output_image_parameters")
        val cameras = output.optJSONArray("cameras")
        card("当前输出 · ${if (configuredUndistorted) "去畸变" else "相机 RAW"}",
            "直接读取该模式的 SDK 回调参数；未使用固定 FOV 推算\n单眼 ${RECORD_WIDTH / 2}×${RECORD_HEIGHT} · 物理投影仍待独立验证")
        if (cameras != null) for (i in 0 until cameras.length()) {
            val camera = cameras.getJSONObject(i)
            card("输出内参 · SDK 相机索引 $i", "fx %.5f    fy %.5f\ncx %.5f    cy %.5f".format(
                camera.getDouble("fx"), camera.getDouble("fy"), camera.getDouble("cx"), camera.getDouble("cy")))
        } else card("输出内参", "SDK 未返回可解析的双目标定")
        card("输出外参 · SDK 打包数组", values(output.optJSONObject("extrinsics")?.optJSONArray("packed_values")))
        findViewById<Button>(R.id.btn_export_calibration).isEnabled = true
    }

    private fun publishCalibration(json: JSONObject, name: String): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Synapath/Calibrations")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建标定文件")
        try {
            contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(json.toString(2)) }
            check(contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) == 1) { "无法发布标定文件" }
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun exportCalibration() {
        getCameraParams()
        val json = calibrationJson ?: return
        try {
            val name = "camera-calibration-${System.currentTimeMillis()}.json"
            val uri = publishCalibration(json, name)
            tvCameraParam.text = "两套参数已导出至 /sdcard/Download/Synapath/Calibrations/$name"
            Log.i(TAG, "CALIBRATION_EXPORTED uri=$uri name=$name")
        } catch (error: Exception) {
            tvCameraParam.text = "导出失败：${error.message}"
        }
    }

    private fun takePicture(){
        pxrCapture?.stopPreview()
        pxrCapture?.stopRecord()
        val mParentDir = Environment.getExternalStorageDirectory()
            .absolutePath + MainActivity.PATH_ON_PICTURE_STORAGE
        val fileName = "DemoPicture-${System.currentTimeMillis()}.jpeg"
        val filePath = mParentDir + fileName
        Log.i(TAG, "takePicture filePath: $filePath")
        pxrCapture?.takePicture(filePath)
    }

    private fun startRecord(){
        if (probingOriginalCalibration) return
        getCameraParams()
        val snapshot = calibrationJson
        if (snapshot == null || !snapshot.getJSONObject("raw_camera_calibration").optBoolean("available") ||
            !snapshot.getJSONObject("output_image_parameters").optBoolean("available")) {
            findViewById<TextView>(R.id.session_status).text = "两套标定尚未就绪，请稍候或重新打开相机"
            return
        }
        pendingCalibration = JSONObject(snapshot.toString())
        stopPreview()
        val pending = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ".SynapathPending")
        if (!pending.exists() && !pending.mkdirs()) {
            findViewById<TextView>(R.id.session_status).text = "无法创建临时录制目录"
            return
        }
        val stamp = System.currentTimeMillis()
        val filePath = File(pending, "raw-$stamp.mp4").absolutePath
        pendingRecordPath = filePath
        lastRecordPath = "Synapath-$stamp.mp4"
        findViewById<TextView>(R.id.session_path).text = filePath
        Log.i(TAG, "startRecord filePath: $filePath")
        val result = pxrCapture?.startRecord(filePath)
        Log.i(TAG, "startRecord result=$result")
        if (result == null || result <= 0) {
            pendingRecordPath = ""
            findViewById<TextView>(R.id.session_status).text = "启动录像失败，请重试"
            File(filePath).takeIf { it.exists() && it.length() == 0L }?.delete()
            startPreview()
        }
    }

    private fun stopRecord(){
        if (finalizing || pendingRecordPath.isEmpty()) return
        finalizing = true
        recordingSince = 0L
        uiHandler.removeCallbacks(recordingTimer)
        renderFinalizing()
        val source = File(pendingRecordPath)
        val finalName = lastRecordPath
        val recordingCalibration = pendingCalibration?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("schema", 2).put("status", "calibration_unavailable_at_record_start")
        finalizeWorker.execute {
            var uri: android.net.Uri? = null
            var calibrationUri: android.net.Uri? = null
            try {
                val result = pxrCapture?.stopRecord() ?: 0
                check(result > 0) { "SDK stopRecord=$result" }
                // PXR already maps all streams to a common timeline. Inspect it
                // without cropping, independently zeroing tracks, or remuxing.
                val timeline = PicoTimeline.inspect(source)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Synapath")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建输出视频")
                val sourceHash = contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    PicoTimeline.copyUnchanged(source, output)
                }
                val publishedHash = contentResolver.openInputStream(uri)!!.use { PicoTimeline.sha256(it) }
                check(sourceHash == publishedHash) { "录像完整性校验失败" }
                val calibrationName = finalName.removeSuffix(".mp4") + ".calibration.json"
                recordingCalibration.put("video_name", finalName)
                    .put("source_mp4_sha256", sourceHash)
                    .put("timeline_vendor_tbtm_us", timeline.baseUs)
                calibrationUri = publishCalibration(recordingCalibration, calibrationName)
                val summary = JSONObject(timeline.json())
                    .put("calibration_uri", calibrationUri.toString())
                    .put("calibration_path", "/sdcard/Download/Synapath/Calibrations/$calibrationName")
                    .put("source_file", source.absolutePath)
                    .put("published_name", finalName)
                    .put("sha256", sourceHash)
                    .put("published_byte_identical", true)
                File(getExternalFilesDir("timelines"), finalName.removeSuffix(".mp4") + ".json")
                    .writeText(summary.toString(2))
                check(contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null) == 1) { "无法发布录像" }
                Log.i(TAG, "PXR_TIMELINE_SAVED uri=$uri name=$finalName summary=$summary")
                pendingRecordPath = ""
                finalizing = false
                runOnUiThread {
                    renderCaptureState(captureState)
                    findViewById<TextView>(R.id.session_path).text = "/sdcard/Movies/Synapath/$finalName"
                    findViewById<TextView>(R.id.session_status).text =
                        if (timeline.sharedTimebase)
                            "已保存 · 共同时钟已核对 · RGB ${timeline.samples("rgb")} / ToF ${timeline.samples("depth")} / Pose ${timeline.samples("pose")} · 时间戳原样保留"
                        else "已保存 · 时间基准或传感器轨道需检查 · 原始录像完整保留"
                }
            } catch (error: Exception) {
                uri?.let { contentResolver.delete(it, null, null) }
                calibrationUri?.let { contentResolver.delete(it, null, null) }
                Log.e(TAG, "Save verification failed; original retained at $source", error)
                finalizing = false
                runOnUiThread {
                    renderCaptureState(captureState)
                    findViewById<TextView>(R.id.session_status).text = "校验保存失败，原始录像已保留：${error.message}"
                    findViewById<TextView>(R.id.session_path).text = source.absolutePath
                }
            }
        }
    }

    private fun startPreview(){
        if (!surfaceView.holder.surface.isValid) {
            findViewById<TextView>(R.id.session_status).text = "预览画布尚未就绪，请稍后重试"
            return
        }
        val result = pxrCapture?.startPreview(surfaceView.holder.surface,
            PXRCapture.PXRCaptureRenderMode.PXRCapture_RenderMode_3D.ordinal,
            PREVIEW_WIDTH, PREVIEW_HEIGHT)
        previewAttached = result != null && result > 0
        if (previewAttached) resumePreviewAfterRecord = false
        Log.i(TAG, "startPreview attached=$previewAttached result=$result buffer=${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} view=${surfaceView.width}x${surfaceView.height}")
    }

    private fun stopPreview(){
        pxrCapture?.stopPreview()
        previewAttached = false
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
        if (::xrUi.isInitialized) xrUi.pause()
        uiHandler.removeCallbacksAndMessages(null)
        finalizeWorker.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::xrUi.isInitialized) xrUi.resume()
    }

    override fun onPause() {
        if (::xrUi.isInitialized) xrUi.pause()
        super.onPause()
    }

    companion object {
        const val TAG = "PXRMainActivity"
        const val RECORD_MODE: Int = 0
        const val PREVIEW_MODE: Int = 1
        const val PERFORMANCE_MODE: Int = 2
        const val PREVIEW_WIDTH: Int = 1280
        const val PREVIEW_HEIGHT: Int = 480
        const val RECORD_WIDTH: Int = 2048
        const val RECORD_HEIGHT: Int = 768

        const val PATH_ON_MOVIES_STORAGE = "/Movies/"
        const val PATH_ON_PICTURE_STORAGE = "/Pictures/"
    }

}
