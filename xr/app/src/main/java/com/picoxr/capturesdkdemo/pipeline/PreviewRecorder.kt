package com.picoxr.capturesdkdemo.pipeline

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One SDK SurfaceTexture, two GPU outputs. All methods execute on the GL thread. */
class PreviewRecorder(private val view: GLSurfaceView, private val ready: (Surface) -> Unit,
                      private val report: (String) -> Unit,
                      private val recordingEvent: (RecordingEvent) -> Unit = {}) : GLSurfaceView.Renderer {
    enum class RecordingPhase { STARTED, SAVING, SAVED, FAILED }
    data class RecordingEvent(val phase: RecordingPhase, val file: File?, val frames: Int = 0, val message: String? = null)
    private var previewVisible = true
    private var hudProgram = 0
    private var hudTexture = 0
    private var hudX = 0; private var hudY = 0; private var hudWidth = 0; private var hudHeight = 0
    private var hudVisible = false
    private val hudVertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
    }
    fun updateHudOverlay(bitmap: Bitmap, x: Int, y: Int) {
        try {
            hudX=x; hudY=y; hudWidth=bitmap.width; hudHeight=bitmap.height
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            hudVisible=true; view.requestRender()
        } finally { bitmap.recycle() }
    }
    fun hideHudOverlay() { hudVisible=false; view.requestRender() }
    fun setPreviewVisible(visible: Boolean) { previewVisible = visible; view.requestRender() }
    companion object { const val WIDTH=2048; const val HEIGHT=768 }
    private var cameraTexture: SurfaceTexture?=null
    private var cameraSurface: Surface?=null
    private val pending=AtomicBoolean(false)
    private var texture=0; private var program=0; private var width=1; private var height=1
    private var lastSource=0L; private var sourceCount=0L
    private var encoder: Encoder?=null
    private val matrix=FloatArray(16)
    private val vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f));position(0)
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);texture=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val vertex="attribute vec4 pos; attribute vec2 uv; uniform mat4 transform; varying vec2 tex; void main(){gl_Position=pos; tex=(transform*vec4(uv,0.,1.)).xy;}"
        val fragment="#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 tex; void main(){gl_FragColor=texture2D(image,tex);}"
        fun shader(type:Int,source:String):Int { val id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,source);GLES20.glCompileShader(id);val ok=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){GLES20.glGetShaderInfoLog(id)};return id }
        val vs=shader(GLES20.GL_VERTEX_SHADER,vertex);val fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment)
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program)
        val ok=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);check(ok[0]!=0){GLES20.glGetProgramInfoLog(program)}
        GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs)
        val overlayVertex="attribute vec4 pos; attribute vec2 uv; varying vec2 tex; void main(){gl_Position=pos;tex=uv;}"
        val overlayFragment="precision mediump float; uniform sampler2D image; varying vec2 tex; void main(){gl_FragColor=texture2D(image,tex);}"
        val ovs=shader(GLES20.GL_VERTEX_SHADER,overlayVertex); val ofs=shader(GLES20.GL_FRAGMENT_SHADER,overlayFragment)
        hudProgram=GLES20.glCreateProgram(); GLES20.glAttachShader(hudProgram,ovs); GLES20.glAttachShader(hudProgram,ofs); GLES20.glLinkProgram(hudProgram)
        GLES20.glGetProgramiv(hudProgram,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]!=0){GLES20.glGetProgramInfoLog(hudProgram)}
        GLES20.glDeleteShader(ovs); GLES20.glDeleteShader(ofs)
        GLES20.glGenTextures(1,ids,0);hudTexture=ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,hudTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        hudVisible=false
        cameraTexture=SurfaceTexture(texture).apply {
            setDefaultBufferSize(WIDTH,HEIGHT)
            setOnFrameAvailableListener { pending.set(true);view.requestRender() }
        }
        cameraSurface=Surface(cameraTexture); ready(cameraSurface!!)
    }
    override fun onSurfaceChanged(gl: GL10?,w:Int,h:Int) {width=w;height=h}
    override fun onDrawFrame(gl: GL10?) {
        val st=cameraTexture?:return
        val fresh=pending.getAndSet(false)
        if(fresh) {st.updateTexImage();st.getTransformMatrix(matrix)}
        if(lastSource==0L && !fresh) { clearWindow(); return }
        if (previewVisible) draw(width,height,true) else clearWindow()
        drawHudOverlay() // Display surface only; never called on the encoder surface.
        val ts=st.timestamp
        if(fresh && ts>lastSource) {
            lastSource=ts;sourceCount++
            if(sourceCount%60L==0L) Log.i("PicoPipeline","PREVIEW source_count=$sourceCount source_ns=$ts recording=${encoder!=null}")
            try { encoder?.frame(ts) { draw(WIDTH,HEIGHT,false) } }
            catch(e:Exception) { stopRecording("编码失败：${e.message}") }
        }
    }
    private fun drawHudOverlay() {
        if (!hudVisible || hudWidth <= 0 || hudHeight <= 0) return
        GLES20.glViewport(hudX, height-hudY-hudHeight, hudWidth, hudHeight)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(hudProgram)
        val p=GLES20.glGetAttribLocation(hudProgram,"pos"); val uv=GLES20.glGetAttribLocation(hudProgram,"uv")
        hudVertices.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,hudVertices); GLES20.glEnableVertexAttribArray(p)
        hudVertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,hudVertices); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,hudTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hudProgram,"image"),0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); GLES20.glDisable(GLES20.GL_BLEND)
    }
    private fun clearWindow() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }
    private fun draw(w:Int,h:Int,letterbox:Boolean) {
        GLES20.glViewport(0,0,w,h);GLES20.glClearColor(0f,0f,0f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if(letterbox) {val ratio=WIDTH.toFloat()/HEIGHT;val rw=minOf(w,(h*ratio).toInt());val rh=(rw/ratio).toInt();GLES20.glViewport((w-rw)/2,(h-rh)/2,rw,rh)}
        GLES20.glUseProgram(program)
        val p=GLES20.glGetAttribLocation(program,"pos");val uv=GLES20.glGetAttribLocation(program,"uv")
        vertices.position(0);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(p)
        vertices.position(2);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(uv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,matrix,0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }
    fun startRecording(file:File) {
        if(encoder!=null)return
        try {
            encoder=Encoder(file)
            report("录制中 · 无声视频\n${file.name}")
            recordingEvent(RecordingEvent(RecordingPhase.STARTED, file))
        } catch(e:Exception) {
            report("启动编码失败：${e.message}");Log.e("PicoPipeline","start encoder",e)
            recordingEvent(RecordingEvent(RecordingPhase.FAILED, file, message=e.message))
        }
    }
    fun stopRecording(failure: String? = null) {
        val old=encoder?:return;encoder=null
        recordingEvent(RecordingEvent(RecordingPhase.SAVING, old.file))
        try {
            val count=old.finish()
            check(count > 0) { "没有有效相机帧" }
            if (failure == null) {
                report("已保存 $count 帧 · 预览继续\n${old.file.name}")
                recordingEvent(RecordingEvent(RecordingPhase.SAVED, old.file, count))
            } else recordingEvent(RecordingEvent(RecordingPhase.FAILED, old.file, count, failure))
        } catch(e:Exception) {
            report("结束编码失败：${e.message}");Log.e("PicoPipeline","finish",e)
            recordingEvent(RecordingEvent(RecordingPhase.FAILED, old.file, message=e.message))
        }
    }
    fun release() {
        stopRecording();cameraSurface?.release();cameraTexture?.release();cameraSurface=null;cameraTexture=null
        hudVisible=false
        GLES20.glDeleteTextures(2,intArrayOf(texture,hudTexture),0);GLES20.glDeleteProgram(program);GLES20.glDeleteProgram(hudProgram)
    }
    private class Encoder(val file:File) {
        private val codec=MediaCodec.createEncoderByType("video/avc")
        private var input:Surface?=null
        private val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val display=EGL14.eglGetCurrentDisplay()
        private val context=EGL14.eglGetCurrentContext()
        private var output=EGL14.EGL_NO_SURFACE
        private var started=false;private var track=-1
        private var first=-1L;private var next=0L
        private val info=MediaCodec.BufferInfo()
        private var written=0
        private val submittedPts=java.util.ArrayDeque<Long>()
        private val timestamps=File(file.parentFile,file.nameWithoutExtension+".frames.csv").bufferedWriter()
        init {
            timestamps.write("source_mono_ns,encoder_presentation_ns\n")
            try {
                val format=MediaFormat.createVideoFormat("video/avc",WIDTH,HEIGHT).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE,10*1024*1024)
                    setInteger(MediaFormat.KEY_FRAME_RATE,60);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
                }
                codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                input=codec.createInputSurface();codec.start()
                val id=IntArray(1);check(EGL14.eglQueryContext(display,context,EGL14.EGL_CONFIG_ID,id,0))
                val configs=arrayOfNulls<android.opengl.EGLConfig>(1);val count=IntArray(1)
                check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_CONFIG_ID,id[0],EGL14.EGL_NONE),0,configs,0,1,count,0))
                output=EGL14.eglCreateWindowSurface(display,configs[0],input,intArrayOf(EGL14.EGL_NONE),0)
                check(output!=EGL14.EGL_NO_SURFACE){"Encoder EGL surface: ${EGL14.eglGetError()}"}
            }catch(e:Exception){close();throw e}
        }
        fun frame(ts:Long,draw:()->Unit) {
            // Select fresh source frames at up to 60 Hz. Preserve source cadence;
            // never duplicate a texture or synthesize a regular exposure clock.
            if(first>=0 && ts+500_000L<next)return
            if(first<0){first=ts;next=ts}
            next+=1_000_000_000L/60
            if(ts-next>1_000_000_000L)next=ts+1_000_000_000L/60
            val originalDraw=EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val originalRead=EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            try {
                check(EGL14.eglMakeCurrent(display,output,output,context))
                draw();check(EGLExt.eglPresentationTimeANDROID(display,output,ts-first))
                check(EGL14.eglSwapBuffers(display,output))
            }finally{check(EGL14.eglMakeCurrent(display,originalDraw,originalRead,context))}
            submittedPts.addLast((ts-first)/1000L)
            timestamps.write("$ts,${ts-first}\n")
            drain(false)
        }
        private fun drain(eos:Boolean) {
            val deadline=System.nanoTime()+5_000_000_000L
            while(true){
                val index=codec.dequeueOutputBuffer(info,if(eos)10_000 else 0)
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER){if(!eos)return;check(System.nanoTime()<deadline){"Encoder EOS timeout"};continue}
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){check(!started);track=muxer.addTrack(codec.outputFormat);muxer.start();started=true;continue}
                if(index>=0){
                    try {
                        if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0)info.size=0
                        if(info.size>0){
                            check(started);check(submittedPts.isNotEmpty()) { "Unexpected extra encoder frame" }
                            // Some vendor codecs regularize output PTS to configured FPS.
                            // No B frames: restore each submitted source timestamp at muxing.
                            info.presentationTimeUs=submittedPts.removeFirst()
                            val b=codec.getOutputBuffer(index)!!;b.position(info.offset);b.limit(info.offset+info.size)
                            muxer.writeSampleData(track,b,info);written++
                        }
                    }finally{codec.releaseOutputBuffer(index,false)}
                    if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)return
                }
            }
        }
        fun finish():Int {try{codec.signalEndOfInputStream();drain(true);return written}finally{close()}}
        private fun close(){
            timestamps.close()
            try{codec.stop()}catch(_:Exception){};codec.release()
            try{if(started)muxer.stop()}finally{muxer.release();input?.release();if(output!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,output)}
        }
    }
}
