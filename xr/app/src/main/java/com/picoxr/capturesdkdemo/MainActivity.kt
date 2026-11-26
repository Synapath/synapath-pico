package com.picoxr.capturesdkdemo

import android.Manifest
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
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

    private lateinit var imageReader: ImageReader

    private var pxrCapture: PXRCapture? = null

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
    private var previewAttached = false
    private var calibrationJson: JSONObject? = null
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
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "onCreate")
        surfaceView = findViewById(R.id.surfaceView)
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
            Manifest.permission.CAMERA
        )
        val checkCode: Int = PermissionUtils.checkPermission(this, permissions, true)
        if (checkCode == PermissionUtils.CHECK_RESULT_OK) {
            Log.i(TAG, "checkPermission OK")
            initPXRCapture(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        initPXRCapture(false)
    }

    private fun initPXRCapture(check:Boolean){
        Log.i(TAG, "initPXRCapture")
        pxrCapture = PXRCapture()
        val cameraParams= mutableMapOf<String, String>(
            PXRCapture.KEY_MCTF to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_EIS to PXRCapture.VALUE_TRUE,
            PXRCapture.KEY_MFNR to PXRCapture.VALUE_TRUE
        )
        pxrCapture?.setCallback(object :PXRCaptureCallBack{
            override fun captureOnStateChanged(state: Int) {

                Log.i(TAG, "captureOnStateChanged state: $state")
                captureState = state
                runOnUiThread {
                    renderCaptureState(state)
                    if (state == PXRCapture.PXRCaptureState.CAPTURE_STATE_VIDEO_CAPTURING.ordinal) {
                        resumePreviewAfterRecord = true
                    } else if (state == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal && resumePreviewAfterRecord) {
                        resumePreviewAfterRecord = false
                        uiHandler.postDelayed({
                            if (captureState == PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal && !isDestroyed) startPreview()
                        }, 150)
                    }
                }
                if (captureState == PXRCapture.PXRCaptureState.CAPTURE_STATE_CAMERA_OPENED.ordinal){
                    captureConfig(check)
                }
            }

            override fun captureOnError(error: Int) {
                Log.i(TAG, "captureOnError state: $error")
                runOnUiThread {
                    recordingSince = 0L
                    uiHandler.removeCallbacks(recordingTimer)
                    findViewById<TextView>(R.id.capture_status).text = "相机错误 $error"
                    findViewById<TextView>(R.id.session_status).text = "采集失败，请重新打开应用"
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
                Log.i(TAG, "SDK 0.1.1 characteristics: $characteristics")
            }

            override fun captureOnTakePictureTimeOut() {
                Log.i(TAG, "captureOnTakePictureTimeOut")
            }

        })
        pxrCapture?.openCameraAsync(cameraParams)

    }

    private fun releaseCamera(){
        pxrCapture?.run {
            stopPreview()
            stopRecord()
            reset()
            closeCamera()
            release()
        }
        pxrCapture = null
    }

    private fun captureConfig(check: Boolean){
        Log.i(TAG, "captureConfig: $check")
        val configureParams = mutableMapOf(
            PXRCapture.KEY_ENABLE_MVHEVC to PXRCapture.VALUE_FALSE,
            PXRCapture.KEY_VIDEO_FPS to  "60",
            PXRCapture.KEY_VIDEO_WIDTH to "2048",
            PXRCapture.KEY_VIDEO_HEIGHT to "${1536/2}",
            PXRCapture.KEY_VIDEO_BITRATE to "${10 * 1024 * 1024}",
        )
        if (!check){
            configureParams.put("output-camera-raw-data", PXRCapture.VALUE_TRUE)
        }
        pxrCapture?.configure(configureParams)
    }

    private fun getCameraParams() {
        val cameraIntrinsics = DoubleArray(4)
        val len = IntArray(1)
        pxrCapture?.getCameraIntrinsics(
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            VIDEO_H_FOV,
            VIDEO_V_FOV,
            cameraIntrinsics,
            len
        )

        val leftCameraExtrinsics = DoubleArray(16)
        val leftCameraExtrinsicsLen = IntArray(1)
        val rightCameraExtrinsics = DoubleArray(16)
        val rightCameraExtrinsicsLen = IntArray(1)
        pxrCapture?.getCameraExtrinsics(
            leftCameraExtrinsics, leftCameraExtrinsicsLen,
            rightCameraExtrinsics, rightCameraExtrinsicsLen
        )

        Log.i(
            TAG,
            "cameraIntrinsics len: " + len[0] + " value: " + cameraIntrinsics.contentToString()
        )
        Log.i(
            TAG,
            "leftCameraExtrinsics len: " + leftCameraExtrinsicsLen[0] + " value: " + leftCameraExtrinsics.contentToString()
        )
        Log.i(
            TAG,
            "rightCameraExtrinsics len: " + rightCameraExtrinsicsLen[0] + " value: " + rightCameraExtrinsics.contentToString()
        )

        val intrinsicCount = len[0].coerceIn(0, cameraIntrinsics.size)
        val leftCount = leftCameraExtrinsicsLen[0].coerceIn(0, leftCameraExtrinsics.size)
        val rightCount = rightCameraExtrinsicsLen[0].coerceIn(0, rightCameraExtrinsics.size)
        calibrationJson = JSONObject().apply {
            put("source", "PXRCapture spatial-video parameter queries")
            put("query_width", VIDEO_WIDTH); put("query_height", VIDEO_HEIGHT)
            put("query_hfov", VIDEO_H_FOV); put("query_vfov", VIDEO_V_FOV)
            put("intrinsics", JSONArray(cameraIntrinsics.take(intrinsicCount)))
            put("left_extrinsics", JSONArray(leftCameraExtrinsics.take(leftCount)))
            put("right_extrinsics", JSONArray(rightCameraExtrinsics.take(rightCount)))
        }
        renderCalibration(cameraIntrinsics.take(intrinsicCount),
            leftCameraExtrinsics.take(leftCount), rightCameraExtrinsics.take(rightCount))
    }

    private fun renderCalibration(intrinsics: List<Double>, left: List<Double>, right: List<Double>) {
        val cards = findViewById<LinearLayout>(R.id.calibration_cards)
        cards.removeAllViews()
        tvCameraParam.text = "空间视频参数 · 2048 × 1536 · 数值显示已舍入，JSON 保留原精度"
        fun card(title: String, values: List<Double>, matrix: Boolean) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 14, 18, 14)
                setBackgroundResource(R.drawable.capture_inset)
            }
            cards.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            container.addView(TextView(this).apply {
                text = "$title   ·   ${values.size} 项"
                setTextColor(Color.parseColor("#EAAE50")); textSize = 15f
            })
            val formatted = if (matrix && values.size == 16) {
                values.chunked(4).joinToString("\n") { row ->
                    row.joinToString("   ") { String.format(Locale.US, "% .5f", it) }
                }
            } else values.mapIndexed { index, value ->
                "P$index    ${String.format(Locale.US, "%.5f", value)}"
            }.joinToString("\n")
            container.addView(TextView(this).apply {
                text = formatted.ifEmpty { "SDK 尚未返回有效数据" }
                typeface = Typeface.MONOSPACE; textSize = 14f
                setTextColor(Color.parseColor("#EAF0F5")); setPadding(0, 10, 0, 0)
                setTextIsSelectable(true)
            })
        }
        card("空间视频内参", intrinsics, false)
        card("左相机外参 · 4 × 4 数组视图", left, true)
        card("右相机外参 · 4 × 4 数组视图", right, true)
        findViewById<Button>(R.id.btn_export_calibration).isEnabled = true
    }

    private fun exportCalibration() {
        val json = calibrationJson ?: return
        try {
            val output = File(getExternalFilesDir(null), "camera-calibration.json")
            output.writeText(json.toString(2))
            tvCameraParam.text = "原始精度已导出至 ${output.absolutePath}"
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
        stopPreview()
        val mParentDir = Environment.getExternalStorageDirectory()
            .absolutePath + PATH_ON_MOVIES_STORAGE
        val fileName = "DemoPicture-${System.currentTimeMillis()}.mp4"
        val filePath = mParentDir + fileName
        lastRecordPath = filePath
        findViewById<TextView>(R.id.session_path).text = filePath
        Log.i(TAG, "startRecord filePath: $filePath")
        val result = pxrCapture?.startRecord(filePath)
        Log.i(TAG, "startRecord result=$result")
        if (result == null || result <= 0) {
            findViewById<TextView>(R.id.session_status).text = "启动录像失败，请重试"
            File(filePath).takeIf { it.exists() && it.length() == 0L }?.delete()
            startPreview()
        }
    }

    private fun stopRecord(){
        pxrCapture?.stopRecord()
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
        Log.i(TAG, "startPreview attached=$previewAttached result=$result buffer=${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} view=${surfaceView.width}x${surfaceView.height}")
    }

    private fun stopPreview(){
        pxrCapture?.stopPreview()
        previewAttached = false
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val TAG = "PXRMainActivity"
        const val RECORD_MODE: Int = 0
        const val PREVIEW_MODE: Int = 1
        const val PERFORMANCE_MODE: Int = 2
        const val PREVIEW_WIDTH: Int = 1280
        const val PREVIEW_HEIGHT: Int = 480
        const val VIDEO_WIDTH: Int = 2048
        const val VIDEO_HEIGHT: Int = 1536
        const val VIDEO_H_FOV: Double = 76.35
        const val VIDEO_V_FOV: Double = 61.05

        const val PATH_ON_MOVIES_STORAGE = "/Movies/"
        const val PATH_ON_PICTURE_STORAGE = "/Pictures/"
    }

}