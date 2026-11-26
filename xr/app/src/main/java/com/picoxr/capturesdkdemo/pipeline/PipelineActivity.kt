package com.picoxr.capturesdkdemo.pipeline

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.*
import android.view.Surface
import android.widget.*
import android.graphics.Color
import android.util.Log
import com.pxr.capturelib.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PipelineActivity:Activity(){
    private lateinit var gl:GLSurfaceView
    private lateinit var renderer:PreviewRecorder
    private lateinit var status:TextView
    private lateinit var start:Button
    private lateinit var stop:Button
    private val cameraThread=HandlerThread("PicoPipelineCamera")
    private lateinit var cameraWorker:Handler
    private var capture:PXRCapture?=null
    private var surface:Surface?=null
    private var active=false
    private var configured=false
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        cameraThread.start();cameraWorker=Handler(cameraThread.looper)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,16,20,16);setBackgroundColor(Color.rgb(11,16,22))}
        root.addView(TextView(this).apply{text="SYNAPATH · 应用编码实验";textSize=24f;setTextColor(Color.rgb(234,174,80))})
        status=TextView(this).apply{text="等待相机 · SDK 单路预览 → GPU 分发 → 屏幕与 AVC 编码\n源时间戳保留；无声视频，最多选择 60 帧/秒";setTextColor(Color.WHITE);textSize=16f}
        root.addView(status)
        gl=GLSurfaceView(this);gl.setEGLContextClientVersion(2)
        gl.setEGLConfigChooser{egl,display ->
            val config=arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1);val count=IntArray(1)
            check(egl.eglChooseConfig(display,intArrayOf(0x3024,8,0x3023,8,0x3022,8,0x3040,4,0x3142,1,0x3038),config,1,count)&&count[0]>0){"No recordable EGL config"}
            config[0]!!
        }
        renderer=PreviewRecorder(gl,{s->cameraWorker.post{surface=s;openCamera()}},{message->runOnUiThread{status.text=message;Log.i("PicoPipeline",message)}})
        gl.setRenderer(renderer);gl.renderMode=GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(gl,LinearLayout.LayoutParams(-1,0,1f))
        val row=LinearLayout(this)
        start=Button(this).apply{text="开始录制（实验）";isEnabled=false;setOnClickListener{
            val file=File(getExternalFilesDir(null),"AppPipeline-${System.currentTimeMillis()}.mp4")
            gl.queueEvent{renderer.startRecording(file)}
        }}
        stop=Button(this).apply{text="停止并保存";setOnClickListener{gl.queueEvent{renderer.stopRecording()}}}
        row.addView(start);row.addView(stop);root.addView(row);setContentView(root)
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.CAMERA),12)
    }
    override fun onResume(){super.onResume();active=true;gl.onResume()}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);cameraWorker.post{openCamera()}}
    private fun openCamera(){
        if(!active||capture!=null||surface?.isValid!=true||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
        configured=false
        val current=PXRCapture();capture=current
        current.setCallback(object:PXRCaptureCallBack{
            override fun captureOnStateChanged(state:Int){cameraWorker.post{
                if(capture!==current)return@post
                Log.i("PicoPipeline","SDK_STATE $state")
                when(state){
                    PXRCapture.PXRCaptureState.CAPTURE_STATE_CAMERA_OPENED.ordinal -> if(!configured){configured=true
                        val result=current.configure(mapOf(PXRCapture.KEY_VIDEO_FPS to "60",PXRCapture.KEY_VIDEO_WIDTH to "2048",PXRCapture.KEY_VIDEO_HEIGHT to "768",PXRCapture.KEY_ENABLE_MVHEVC to PXRCapture.VALUE_FALSE,PXRCapture.KEY_OUTPUT_CAMERA_RAW_DATA to PXRCapture.VALUE_TRUE))
                        checkResult("configure",result)
                    }
                    PXRCapture.PXRCaptureState.CAPTURE_STATE_CONFIGURED.ordinal -> {
                        val result=current.startPreview(surface,PXRCapture.PXRCaptureRenderMode.PXRCapture_RenderMode_3D.ordinal,2048,768)
                        checkResult("startPreview",result)
                        runOnUiThread{start.isEnabled=result>0;status.text="实时预览已连接 · 应用编码实验（无声）"}
                    }
                }
            }}
            override fun captureOnError(error:Int){runOnUiThread{start.isEnabled=false;status.text="SDK错误 $error"};Log.e("PicoPipeline","SDK error $error")}
            override fun captureOnEnviromentInfoChanged(a:Int,b:Int){}
            override fun captureOnTakePictureComplete(a:Float,b:Float,c:Float,d:Float,e:Float){}
            override fun captureOnTakePictureTimeOut(){}
            override fun captureOnCharacteristicsCallback(c:PXRCharacteristics){Log.i("PicoPipeline","calibration ${c.all.keys}")}
        })
        checkResult("openCamera",current.openCameraAsync(mapOf(PXRCapture.KEY_MCTF to PXRCapture.VALUE_TRUE,PXRCapture.KEY_EIS to PXRCapture.VALUE_TRUE,PXRCapture.KEY_MFNR to PXRCapture.VALUE_TRUE)))
    }
    private fun checkResult(operation:String,result:Int){Log.i("PicoPipeline","$operation=$result");if(result<=0)runOnUiThread{status.text="$operation 失败：$result"}}
    override fun onPause(){
        active=false;start.isEnabled=false
        val done=CountDownLatch(1)
        cameraWorker.post{
            val old=capture;capture=null
            try{old?.stopPreview();old?.reset();old?.closeCamera();old?.release()}catch(e:Exception){Log.e("PicoPipeline","close SDK",e)}
            gl.queueEvent{try{renderer.release()}finally{done.countDown()}}
        }
        if(!done.await(8,TimeUnit.SECONDS))Log.e("PicoPipeline","Pause cleanup timeout")
        surface=null;gl.onPause();super.onPause()
    }
    override fun onDestroy(){cameraThread.quitSafely();super.onDestroy()}
}
