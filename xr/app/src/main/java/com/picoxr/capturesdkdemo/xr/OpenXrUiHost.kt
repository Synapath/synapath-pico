package com.picoxr.capturesdkdemo.xr

import android.app.Activity
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.widget.*
import androidx.annotation.Keep
import com.picoxr.capturesdkdemo.pipeline.HoldToStopView

/** Reuses the main-branch Android views and callbacks inside a head-follow XR quad.
 * SDK recording surfaces and MP4 bytes never pass through this display bridge.
 */
@Keep
class OpenXrUiHost(private val owner: Activity, private val preview: SurfaceView,
                   private val stopRecording: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val panel = (owner.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
    private val root = FrameLayout(owner)
    private val hud = LinearLayout(owner)
    private val timer = TextView(owner)
    private val circle = HoldToStopView(owner, {
        Log.i(TAG, "XR_HOLD_COMPLETED duration_ms=2500")
        stopRecording()
    })
    private fun uiBitmap() = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        // Match View software-layer caches to the overridden UI density. Using
        // the device's 640-dpi bitmap default with 240-dpi views scales cached
        // children by 2.67× inside their original clips (a quarter-circle HUD).
        density = owner.resources.displayMetrics.densityDpi
    }
    private var frontBitmap = uiBitmap()
    private var backBitmap = uiBitmap()
    private val previewBitmap = Bitmap.createBitmap(1280, 480, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(backBitmap)
    @Volatile private var revision = 0
    @Volatile private var active = false
    private var xrThread: Thread? = null
    private var recording = false
    private var saving = false
    private var since = 0L
    private var pointerX = -1f
    private var pointerY = -1f
    private var touchDown = false
    private var downTime = 0L
    private var pixelCopyPending = false
    private var previewAvailable = false
    private var snapshots = 0
    private var previewCopies = 0
    private var lastCopyError = -1
    private var lastInputDown = false // XR-thread field.
    private var lastInputX = -1f
    private var lastInputY = -1f
    private external fun nativeRun()
    private external fun nativeStop()
    private external fun nativePrepare()

    init {
        (panel.parent as ViewGroup).removeView(panel)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        hud.orientation = LinearLayout.VERTICAL; hud.gravity = Gravity.CENTER
        timer.textSize = 28f; timer.setTextColor(Color.WHITE); timer.gravity = Gravity.CENTER
        timer.setShadowLayer(5f, 0f, 2f, Color.BLACK)
        hud.addView(timer, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16) })
        hud.addView(circle, LinearLayout.LayoutParams(dp(88), dp(88)))
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        hud.visibility = View.GONE
        // The immersive Android backing window is 4320×2160 on this device.
        // Keep UI layout at its intended size rather than shrinking a desktop-sized
        // layout into the XR texture and making labels unreadably small.
        owner.setContentView(root, ViewGroup.LayoutParams(WIDTH, HEIGHT))
        owner.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun dp(value: Int) = (value * owner.resources.displayMetrics.density).toInt()
    @Keep private fun getActivity(): Activity = owner
    @Keep private fun onXrStatus(value: String) {
        Log.i(TAG, value)
        if (value.startsWith("XR_ERROR")) ui.post {
            Toast.makeText(owner, value, Toast.LENGTH_LONG).show()
        }
    }
    @Keep private fun onGlReady() { Log.i(TAG, "MAIN_UI_XR_READY") }
    @Keep private fun onGlShutdown() { Log.i(TAG, "MAIN_UI_XR_SHUTDOWN") }
    @Keep private fun getHudBitmap(): Bitmap = frontBitmap // Native locks this host's monitor during upload.

    @Keep private fun onXrFrame(down: Boolean, x: Float, y: Float): Int {
        // Coalesce stationary controller poses, but never discard press/release edges.
        if (down != lastInputDown || kotlin.math.abs(x-lastInputX) > .5f || kotlin.math.abs(y-lastInputY) > .5f) {
            if (down != lastInputDown) Log.i(TAG, "XR_INPUT down=$down x=$x y=$y")
            lastInputDown = down; lastInputX = x; lastInputY = y
            ui.post { if (active) dispatchPointer(down, x, y) }
        }
        return revision
    }
    private fun dispatchPointer(down: Boolean, xrX: Float, xrY: Float) {
        if (root.width == 0 || root.height == 0) return
        pointerX = xrX; pointerY = xrY
        val x = xrX * root.width / WIDTH
        val y = xrY * root.height / HEIGHT
        var inside = xrX in 0f..WIDTH.toFloat() && xrY in 0f..HEIGHT.toFloat()
        if (recording || saving) {
            val rect = Rect(); circle.getHitRect(rect)
            val xy = IntArray(2); circle.getLocationInWindow(xy)
            val origin = IntArray(2); root.getLocationInWindow(origin)
            val cx = xy[0] - origin[0] + circle.width / 2f
            val cy = xy[1] - origin[1] + circle.height / 2f
            val radius = minOf(circle.width, circle.height) / 2f
            inside = inside && (x-cx)*(x-cx)+(y-cy)*(y-cy) <= radius*radius && !saving
        }
        val action = when {
            touchDown && (!inside || !down) -> if (!inside) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
            !touchDown && down && inside -> MotionEvent.ACTION_DOWN
            touchDown -> MotionEvent.ACTION_MOVE
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        MotionEvent.obtain(downTime, now, action, x.coerceAtLeast(0f), y.coerceAtLeast(0f), 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN
            val consumed = root.dispatchTouchEvent(it)
            if (action != MotionEvent.ACTION_MOVE) Log.i(TAG, "XR_TOUCH action=$action x=$x y=$y consumed=$consumed root=${root.width}x${root.height}")
            it.recycle()
        }
        touchDown = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
    }
    fun setRecording(value: Boolean) {
        if (recording == value && !saving) return
        cancelPointer()
        recording = value; saving = false
        if (value) { since = SystemClock.elapsedRealtime(); circle.reset(); circle.isEnabled = true }
        panel.visibility = if (value) View.INVISIBLE else View.VISIBLE
        hud.visibility = if (value) View.VISIBLE else View.GONE
        previewAvailable = false
        Log.i(TAG, "MAIN_UI_HUD recording=$value")
    }
    fun setSaving() {
        saving = true; cancelPointer(); circle.isEnabled = false
        panel.visibility = View.INVISIBLE; hud.visibility = View.VISIBLE; timer.text = "正在保存…"
    }
    private fun cancelPointer() {
        if (touchDown) dispatchPointer(false, -1f, -1f)
        circle.cancelHold(); touchDown = false
    }
    fun resume() {
        if (xrThread?.isAlive == true) return
        active = true; lastInputDown = true; lastInputX = -1f; lastInputY = -1f
        nativePrepare()
        xrThread = Thread({ nativeRun() }, "SynapathMainUIXR").also { it.start() }
        ui.removeCallbacks(snapshotTick); ui.post(snapshotTick)
    }
    fun pause() {
        active = false; cancelPointer(); ui.removeCallbacks(snapshotTick); nativeStop()
        xrThread?.join(2_000)
        if (xrThread?.isAlive == true) Log.e(TAG, "XR thread did not stop within 2 seconds")
    }
    private val snapshotTick = object : Runnable {
        override fun run() {
            if (!active) return
            if (root.width > 0 && root.height > 0) {
                if (recording && !saving) {
                    val elapsed = (SystemClock.elapsedRealtime() - since)/1000
                    timer.text = "●  %02d:%02d".format(elapsed/60, elapsed%60)
                }
                if (!recording && !saving && preview.isShown && preview.holder.surface.isValid && !pixelCopyPending) {
                    pixelCopyPending = true
                    try {
                        PixelCopy.request(preview.holder.surface, previewBitmap, { result ->
                            pixelCopyPending = false; previewAvailable = result == PixelCopy.SUCCESS
                            if (previewAvailable) previewCopies++
                            else if (lastCopyError != result) { lastCopyError = result; Log.i(TAG, "PREVIEW_COPY result=$result") }
                        }, ui)
                    } catch (_: IllegalArgumentException) { pixelCopyPending = false; previewAvailable = false }
                }
                run {
                    // Rasterize into the back buffer without blocking the XR
                    // thread; only publishing the finished bitmap needs its lock.
                    canvas.setBitmap(backBitmap)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    canvas.save(); canvas.scale(WIDTH.toFloat()/root.width, HEIGHT.toFloat()/root.height)
                    root.draw(canvas)
                    val messageVisible = owner.findViewById<View>(com.picoxr.capturesdkdemo.R.id.preview_message).visibility == View.VISIBLE
                    if (!recording && !saving && previewAvailable && !messageVisible) {
                        val xy = IntArray(2); val origin = IntArray(2)
                        preview.getLocationInWindow(xy); root.getLocationInWindow(origin)
                        val left = xy[0]-origin[0]; val top = xy[1]-origin[1]
                        canvas.drawBitmap(previewBitmap, null, Rect(left,top,left+preview.width,top+preview.height), null)
                    }
                    canvas.restore()
                    // The reticle is drawn natively at XR frame cadence, never
                    // baked into this slower Android-page snapshot.
                    synchronized(this@OpenXrUiHost) {
                        val old = frontBitmap; frontBitmap = backBitmap; backBitmap = old
                        revision++
                    }
                    snapshots++
                    if (snapshots % 100 == 0) Log.i(TAG, "MAIN_UI_SNAPSHOTS count=$snapshots preview_copies=$previewCopies root=${root.width}x${root.height} recording=$recording saving=$saving")
                }
            }
            ui.postDelayed(this, if (recording) 50 else 100)
        }
    }
    companion object {
        private const val TAG = "SynapathOpenXR"
        const val WIDTH = 1600; const val HEIGHT = 900
        init { System.loadLibrary("synapath_openxr") }
    }
}
