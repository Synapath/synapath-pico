package com.picoxr.capturesdkdemo.hud

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Display-only HUD. Never receives camera images or changes SDK recording. */
class RecordingHud(private val activity: Activity, private val panel: View, stop: () -> Unit) {
    val root = FrameLayout(activity)
    private val ui = Handler(Looper.getMainLooper())
    private val renderer = OverlayRenderer()
    private val gl = GLSurfaceView(activity)
    private val controls = LinearLayout(activity)
    private val timer = TextView(activity)
    private val circle = HoldToStopView(activity, stop)
    private var shown = false
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private val refresh = object : Runnable {
        override fun run() {
            if (!shown) return
            if (controls.width > 0 && controls.height > 0) {
                val bitmap = Bitmap.createBitmap(controls.width, controls.height, Bitmap.Config.ARGB_8888)
                controls.draw(Canvas(bitmap))
                gl.queueEvent { renderer.replace(bitmap) }
                gl.requestRender()
            }
            ui.postDelayed(this, 50)
        }
    }
    init {
        activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        activity.window.setFormat(PixelFormat.TRANSLUCENT)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.setBackgroundColor(Color.TRANSPARENT)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        gl.setEGLContextClientVersion(2)
        gl.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        gl.holder.setFormat(PixelFormat.TRANSLUCENT)
        gl.setZOrderOnTop(true)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        gl.visibility = View.GONE
        // GL draws the same controls to a real alpha surface for PICO's compositor.
        root.addView(gl, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER
        controls.visibility = View.GONE
        timer.textSize = 28f
        timer.setTextColor(Color.WHITE)
        timer.gravity = Gravity.CENTER
        timer.setShadowLayer(dp(3).toFloat(), 0f, 1f, Color.BLACK)
        controls.addView(timer, LinearLayout.LayoutParams(-1, dp(56)))
        controls.addView(circle, LinearLayout.LayoutParams(dp(88), dp(88)))
        root.addView(controls, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
    }
    fun showRecording() {
        if (shown) return
        shown = true
        panel.visibility = View.INVISIBLE
        gl.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        timer.text = "●  00:00"
        circle.reset(); circle.isEnabled = true; circle.requestFocus()
        ui.post(refresh)
    }
    fun updateTime(start: Long) {
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000
        timer.text = "●  %02d:%02d".format(seconds / 60, seconds % 60)
    }
    fun showSaving() {
        if (!shown) return
        circle.cancelHold(); circle.isEnabled = false
        timer.text = "正在保存…"
    }
    fun hide() {
        shown = false
        ui.removeCallbacks(refresh)
        circle.cancelHold()
        controls.visibility = View.GONE
        gl.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }
    fun release() { hide(); ui.removeCallbacksAndMessages(null) }

    private class OverlayRenderer : GLSurfaceView.Renderer {
        private var texture = 0
        private var program = 0
        private var bitmap: Bitmap? = null
        private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
        }
        fun replace(next: Bitmap) { bitmap?.recycle(); bitmap = next }
        private fun shader(type: Int, source: String) = glCreateShader(type).also {
            glShaderSource(it, source); glCompileShader(it)
        }
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = glCreateProgram()
            glAttachShader(program, shader(GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.); uv=t;}"))
            glAttachShader(program, shader(GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 uv; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,uv);}"))
            glLinkProgram(program)
            val ids = IntArray(1); glGenTextures(1, ids, 0); texture = ids[0]
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { glViewport(0, 0, w, h) }
        override fun onDrawFrame(gl: GL10?) {
            glClearColor(0f, 0f, 0f, 0f); glClear(GL_COLOR_BUFFER_BIT)
            val current = bitmap ?: return
            glUseProgram(program); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, current, 0)
            glUniform1i(glGetUniformLocation(program, "image"), 0)
            val p = glGetAttribLocation(program, "p"); val t = glGetAttribLocation(program, "t")
            vertices.position(0); glVertexAttribPointer(p, 2, GL_FLOAT, false, 16, vertices); glEnableVertexAttribArray(p)
            vertices.position(2); glVertexAttribPointer(t, 2, GL_FLOAT, false, 16, vertices); glEnableVertexAttribArray(t)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }
}
