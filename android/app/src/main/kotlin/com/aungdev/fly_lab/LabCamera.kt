package com.aungdev.fly_lab

// Fly Lab camera (15 Nov 2026) - step 1: the Fly GPUPixel beauty camera
// (copied from fly/android/.../BeautyLab.kt, recording left out for now)
// + person segmentation (MediaPipe ImageSegmenter, selfie_segmenter.tflite)
// + the first background effect (blur), to see on Ko's phone how clean and
// how fast the person cut-out is before more backgrounds are built.
//
// Pipeline:
//   camera thread: Camera2 (YUV, <= 1280x720) -> RGBA upright -> FaceDetector
//     -> GPUPixel beauty chain -> Bitmap `frame`
//     -> draw(): background (blur / mask view / none) + the person cut out
//        with the latest mask (saveLayer + DST_IN) on the Flutter texture.
//   seg thread: every time it is free it takes a small copy (144x256) of the
//     latest frame and runs the segmenter (VIDEO mode, GPU delegate if
//     possible, else CPU). The person-confidence mask becomes an ALPHA_8
//     bitmap (smoothstep for a soft edge) that draw() stretches over the
//     frame. So the preview never waits for the model; the mask can lag a
//     frame or two behind fast movement.
// Channel "flylab/camera": start {front} -> {textureId, width, height, front},
//   switchCamera, set {smooth, white, ...}, setBackground {mode: none / blur /
//   mask}, stats -> {fps, segMs, segFps, delegate}, stop.

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import com.pixpark.gpupixel.GPUPixelFilter
import com.pixpark.gpupixel.GPUPixelSinkRawData
import com.pixpark.gpupixel.GPUPixelSourceRawData
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LabCameraRegistrar {
    fun register(activity: Activity, engine: FlutterEngine) {
        val cam = LabCamera(activity, engine.renderer)
        MethodChannel(engine.dartExecutor.binaryMessenger, "flylab/camera")
            .setMethodCallHandler(cam)
    }
}

class LabCamera(
    private val activity: Activity,
    private val textures: TextureRegistry,
) : MethodChannel.MethodCallHandler {

    private val tag = "LabCamera"
    private val main = Handler(Looper.getMainLooper())

    // Camera thread.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 270
    @Volatile private var isFront = true

    // Output texture.
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var surface: Surface? = null
    private var frame: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val maskViewPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.rgb(0, 229, 255)
    }

    // GPUPixel (camera thread only).
    private var source: GPUPixelSourceRawData? = null
    private var lipstick: GPUPixelFilter? = null
    private var blusher: GPUPixelFilter? = null
    private var beauty: GPUPixelFilter? = null
    private var reshape: GPUPixelFilter? = null
    private var sink: GPUPixelSinkRawData? = null
    private var detector: FaceDetector? = null
    @Volatile private var params = mapOf(
        "smooth" to 0.5, "white" to 0.2, "thin" to 0.0,
        "eyes" to 0.0, "lips" to 0.0, "blush" to 0.0,
    )
    @Volatile private var paramsDirty = true
    @Volatile private var running = false

    // Background effect: "none", "blur", "mask".
    @Volatile private var bgMode = "blur"

    // Segmentation (seg thread).
    private var segThread: HandlerThread? = null
    private var segHandler: Handler? = null
    private var segmenter: ImageSegmenter? = null
    @Volatile private var segBusy = false
    @Volatile private var delegateName = "-"
    private val segW = 144
    private val segH = 256
    private val maskBuffers = arrayOfNulls<Bitmap>(2)
    private var maskWrite = 0
    @Volatile private var mask: Bitmap? = null
    private val maskBytes = ByteArray(segW * segH)

    // Blur (API 31+: GPU RenderEffect; older: down / up scaling).
    private var blurNode: Any? = null
    private var smallBlur: Bitmap? = null

    // Stats.
    @Volatile private var lastFps = 0.0
    @Volatile private var lastSegMs = 0.0
    @Volatile private var lastSegFps = 0.0
    private var frames = 0
    private var fpsStart = 0L
    private var segRuns = 0
    private var segStart = 0L

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> start(result, call.argument<Boolean>("front") ?: true)
            "switchCamera" -> {
                val h = handler
                if (h == null || !running) {
                    result.error("busy", "Cannot switch now", null)
                    return
                }
                h.post {
                    try {
                        closeCamera()
                        val size = openCamera(!isFront, h)
                        main.post {
                            result.success(mapOf("width" to size.height,
                                "height" to size.width, "front" to isFront))
                        }
                    } catch (e: Throwable) {
                        Log.e(tag, "switch", e)
                        main.post { result.error("switch_failed", e.toString(), null) }
                    }
                }
            }
            "set" -> {
                val m = HashMap(params)
                for (k in m.keys.toList()) call.argument<Double>(k)?.let { m[k] = it }
                params = m
                paramsDirty = true
                result.success(null)
            }
            "setBackground" -> {
                bgMode = call.argument<String>("mode") ?: "none"
                result.success(null)
            }
            "stats" -> result.success(mapOf(
                "fps" to lastFps, "segMs" to lastSegMs,
                "segFps" to lastSegFps, "delegate" to delegateName,
                "lastCrash" to (prefs.getString("lastCrash", "") ?: "")))
            "clearCrash" -> {
                prefs.edit().remove("lastCrash").remove("gpuBad")
                    .remove("gpuTrying").apply()
                result.success(null)
            }
            "stop" -> {
                stop()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    // ------------------------------------------------------------ start
    private fun start(result: MethodChannel.Result, front: Boolean) {
        if (running) stop()
        try {
            GPUPixel.Init(activity)
            val t = HandlerThread("LabCamera").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            val st = HandlerThread("LabSeg").also { it.start() }
            segThread = st
            val sh = Handler(st.looper)
            segHandler = sh
            sh.post { createSegmenter() }
            val e = textures.createSurfaceTexture()
            entry = e
            surface = Surface(e.surfaceTexture())
            h.post { buildChain() }
            running = true
            val size = openCamera(front, h)
            e.surfaceTexture().setDefaultBufferSize(size.height, size.width)
            result.success(mapOf("textureId" to e.id(), "width" to size.height,
                "height" to size.width, "front" to isFront))
        } catch (ex: Throwable) {
            Log.e(tag, "start failed", ex)
            stop()
            result.error("start_failed", ex.toString(), null)
        }
    }

    /** GPU delegate first (faster), CPU if this phone's GPU can't run it.
     *  16 Nov 2026 (first test: "FAILED" on Ko's phone): the reason is now
     *  shown in the read-out ("FAILED: <error>") instead of a bare FAILED.
     *  16 Nov 2026 (third test: the app closed itself right after opening,
     *  once the Flogger keep rule let MediaPipe get further): a crash in
     *  the GPU delegate is NATIVE - try/catch can't stop it. So a "trying
     *  GPU" flag is saved before the GPU is tried and cleared after the
     *  first good mask; if the app died while it was set, the next start
     *  skips the GPU and uses the CPU ("CPU (GPU crashed)"). */
    private val prefs by lazy {
        activity.getSharedPreferences("flylab", Context.MODE_PRIVATE)
    }
    @Volatile private var gpuTrial = false

    private fun createSegmenter() {
        var lastError = ""
        val gpuCrashed = prefs.getBoolean("gpuTrying", false) ||
            prefs.getBoolean("gpuBad", false)
        if (prefs.getBoolean("gpuTrying", false)) {
            prefs.edit().putBoolean("gpuTrying", false)
                .putBoolean("gpuBad", true).commit()
        }
        val order = if (gpuCrashed) listOf(Delegate.CPU)
                    else listOf(Delegate.GPU, Delegate.CPU)
        for (d in order) {
            if (d == Delegate.GPU) {
                prefs.edit().putBoolean("gpuTrying", true).commit()
                gpuTrial = true
            }
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath("selfie_segmenter.tflite")
                    .setDelegate(d)
                    .build()
                val opts = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
                segmenter = ImageSegmenter.createFromOptions(activity, opts)
                delegateName = if (d == Delegate.CPU && gpuCrashed)
                    "CPU (GPU crashed)" else d.name
                Log.i(tag, "segmenter on $d")
                return
            } catch (e: Throwable) {
                Log.e(tag, "segmenter $d failed", e)
                if (d == Delegate.GPU) {
                    prefs.edit().putBoolean("gpuTrying", false).commit()
                    gpuTrial = false
                }
                var c: Throwable = e
                while (c.cause != null && c.cause !== c) c = c.cause!!
                lastError = "${c.javaClass.simpleName}: ${c.message ?: ""}".take(160)
            }
        }
        delegateName = "FAILED: $lastError"
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(front: Boolean, h: Handler): Size {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT
                   else CameraCharacteristics.LENS_FACING_BACK
        var id: String? = null
        for (cid in manager.cameraIdList) {
            if (manager.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING) == want) {
                id = cid
                break
            }
        }
        if (id == null) id = manager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("No camera")
        val chars = manager.getCameraCharacteristics(id)
        isFront = chars.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: if (isFront) 270 else 90
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == 30 }?.let { r -> r.firstOrNull { it.lower == 24 } ?: r.firstOrNull() }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = chooseSize(map?.getOutputSizes(SurfaceTexture::class.java))
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ onFrame(it) }, h)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                startSession(device, r, h, fps)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.e(tag, "camera error $error")
                device.close()
                camera = null
            }
        }, h)
        return size
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { camera?.close() } catch (_: Throwable) {}
        camera = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
    }

    private fun chooseSize(choices: Array<Size>?): Size {
        if (choices == null || choices.isEmpty()) return Size(1280, 720)
        val sorted = choices.sortedByDescending { it.width.toLong() * it.height }
        return sorted.firstOrNull { it.width <= 1280 && it.height <= 720 } ?: sorted.last()
    }

    private fun buildChain() {
        source = GPUPixelSourceRawData.Create()
        lipstick = GPUPixelFilter.Create(GPUPixelFilter.LIPSTICK_FILTER)
        blusher = GPUPixelFilter.Create(GPUPixelFilter.BLUSHER_FILTER)
        beauty = GPUPixelFilter.Create(GPUPixelFilter.BEAUTY_FACE_FILTER)
        reshape = GPUPixelFilter.Create(GPUPixelFilter.FACE_RESHAPE_FILTER)
        sink = GPUPixelSinkRawData.Create()
        detector = FaceDetector.Create()
        source!!.AddSink(lipstick!!)
        lipstick!!.AddSink(blusher!!)
        blusher!!.AddSink(beauty!!)
        beauty!!.AddSink(reshape!!)
        reshape!!.AddSink(sink!!)
        paramsDirty = true
    }

    private fun startSession(
        device: CameraDevice, r: ImageReader, h: Handler,
        fps: android.util.Range<Int>?,
    ) {
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(r.surface)
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        b.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        b.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON)
                        fps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        try { s.setRepeatingRequest(b.build(), null, h) } catch (e: Throwable) {
                            Log.e(tag, "repeat", e)
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(tag, "session config failed")
                    }
                }, h)
        } catch (ex: Throwable) {
            Log.e(tag, "session", ex)
        }
    }

    private fun applyParams() {
        val p = params
        beauty?.SetProperty("skin_smoothing", (p["smooth"] ?: 0.0).toFloat())
        beauty?.SetProperty("whiteness", ((p["white"] ?: 0.0) * 0.4).toFloat())
        reshape?.SetProperty("thin_face", ((p["thin"] ?: 0.0) / 12.0).toFloat())
        reshape?.SetProperty("big_eye", ((p["eyes"] ?: 0.0) / 3.0).toFloat())
        lipstick?.SetProperty("blend_level", ((p["lips"] ?: 0.0) * 0.6).toFloat())
        blusher?.SetProperty("blend_level", (p["blush"] ?: 0.0).toFloat())
    }

    // ------------------------------------------------------------ frames
    private fun onFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        try {
            if (!running || source == null) return
            if (paramsDirty) {
                paramsDirty = false
                applyParams()
            }
            val w = image.width
            val h = image.height
            val rgba = GPUPixel.YUV_420_888toRGBA(image)
            val rotation = GPUPixel.calculateRotation(activity, sensorOrientation, isFront)
            val upright = GPUPixel.rotateRgbaImage(rgba, w, h, rotation)
            val ow = if (rotation == 90 || rotation == 270) h else w
            val oh = if (rotation == 90 || rotation == 270) w else h
            val landmarks = detector?.detect(upright, ow, oh, ow * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_VIDEO, FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA)
            if (landmarks != null && landmarks.isNotEmpty()) {
                reshape?.SetProperty("face_landmark", landmarks)
                lipstick?.SetProperty("face_landmark", landmarks)
                blusher?.SetProperty("face_landmark", landmarks)
            }
            source!!.ProcessData(upright, ow, oh, ow * 4, GPUPixelSourceRawData.FRAME_TYPE_RGBA)
            val out = sink?.GetRgbaBuffer() ?: return
            val sw = sink!!.GetWidth()
            val sh = sink!!.GetHeight()
            if (sw <= 0 || sh <= 0 || out.size < sw * sh * 4) return
            var b = frame
            if (b == null || b.width != sw || b.height != sh) {
                b?.recycle()
                b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                frame = b
                entry?.surfaceTexture()?.setDefaultBufferSize(sw, sh)
            }
            b!!.copyPixelsFromBuffer(ByteBuffer.wrap(out, 0, sw * sh * 4))
            if (bgMode != "none") requestSegmentation(b)
            draw(b)
            countFps()
        } catch (ex: Throwable) {
            Log.e(tag, "frame", ex)
        } finally {
            image.close()
        }
    }

    /** Hands a small copy of the frame to the seg thread if it is free. */
    private fun requestSegmentation(b: Bitmap) {
        val sh = segHandler ?: return
        if (segBusy || segmenter == null) return
        segBusy = true
        val small = Bitmap.createScaledBitmap(b, segW, segH, true)
        sh.post {
            try {
                runSegmentation(small)
            } catch (e: Throwable) {
                Log.e(tag, "segment", e)
            } finally {
                small.recycle()
                segBusy = false
            }
        }
    }

    private fun runSegmentation(small: Bitmap) {
        val seg = segmenter ?: return
        val t0 = SystemClock.uptimeMillis()
        val result = seg.segmentForVideo(BitmapImageBuilder(small).build(), t0)
        val masks = result.confidenceMasks().orElse(null) ?: return
        if (masks.isEmpty()) return
        // selfie_segmenter: one mask = person confidence (if a model gives
        // two, index 1 is the person).
        val m = if (masks.size > 1) masks[1] else masks[0]
        val buf = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        val n = minOf(fb.remaining(), maskBytes.size)
        for (i in 0 until n) {
            // smoothstep(0.35, 0.65) - firm inside, soft 1-2 px edge.
            var x = (fb.get(i) - 0.35f) / 0.3f
            x = if (x < 0f) 0f else if (x > 1f) 1f else x
            maskBytes[i] = (x * x * (3f - 2f * x) * 255f).toInt().toByte()
        }
        var mb = maskBuffers[maskWrite]
        if (mb == null) {
            mb = Bitmap.createBitmap(segW, segH, Bitmap.Config.ALPHA_8)
            maskBuffers[maskWrite] = mb
        }
        mb!!.copyPixelsFromBuffer(ByteBuffer.wrap(maskBytes))
        mask = mb
        maskWrite = 1 - maskWrite
        if (gpuTrial) {
            // The GPU made a good mask - it works on this phone.
            gpuTrial = false
            prefs.edit().putBoolean("gpuTrying", false).apply()
        }
        val ms = (SystemClock.uptimeMillis() - t0).toDouble()
        lastSegMs = if (lastSegMs == 0.0) ms else lastSegMs * 0.8 + ms * 0.2
        val now = SystemClock.uptimeMillis()
        if (segStart == 0L) segStart = now
        segRuns++
        if (now - segStart >= 1000) {
            lastSegFps = segRuns * 1000.0 / (now - segStart)
            segRuns = 0
            segStart = now
        }
    }

    private fun draw(b: Bitmap) {
        val s = surface ?: return
        val canvas = try { s.lockHardwareCanvas() } catch (_: Throwable) { null } ?: return
        try {
            val dst = Rect(0, 0, canvas.width, canvas.height)
            val m = mask
            val mode = bgMode
            if (mode == "none" || m == null) {
                canvas.drawBitmap(b, null, dst, paint)
            } else {
                // 1) background
                when (mode) {
                    "blur" -> drawBlur(canvas, b, dst)
                    "mask" -> {
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(m, null, dst, maskViewPaint)
                    }
                    else -> canvas.drawColor(Color.BLACK)
                }
                // 2) the person, cut out by the mask
                if (mode != "mask") {
                    val sc = canvas.saveLayer(null, null)
                    canvas.drawBitmap(b, null, dst, paint)
                    canvas.drawBitmap(m, null, dst, maskPaint)
                    canvas.restoreToCount(sc)
                }
            }
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawBlur(canvas: Canvas, b: Bitmap, dst: Rect) {
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
            var node = blurNode as RenderNode?
            if (node == null) {
                node = RenderNode("labBlur")
                blurNode = node
            }
            node.setPosition(0, 0, dst.width(), dst.height())
            node.setRenderEffect(RenderEffect.createBlurEffect(
                dst.width() / 18f, dst.width() / 18f, Shader.TileMode.CLAMP))
            val rc = node.beginRecording()
            rc.drawBitmap(b, null, dst, paint)
            node.endRecording()
            canvas.drawRenderNode(node)
        } else {
            var sb = smallBlur
            if (sb == null) {
                sb = Bitmap.createBitmap(36, 64, Bitmap.Config.ARGB_8888)
                smallBlur = sb
            }
            Canvas(sb!!).drawBitmap(b, null, Rect(0, 0, 36, 64), paint)
            canvas.drawBitmap(sb, null, dst, paint)
        }
    }

    private fun countFps() {
        val now = SystemClock.uptimeMillis()
        if (fpsStart == 0L) fpsStart = now
        frames++
        if (now - fpsStart >= 1000) {
            lastFps = frames * 1000.0 / (now - fpsStart)
            frames = 0
            fpsStart = now
        }
    }

    // ------------------------------------------------------------- stop
    private fun stop() {
        running = false
        closeCamera()
        val sh = segHandler
        val st = segThread
        sh?.post {
            try { segmenter?.close() } catch (_: Throwable) {}
            segmenter = null
            st?.quitSafely()
        }
        segHandler = null
        segThread = null
        val h = handler
        val t = thread
        if (h != null) {
            h.post {
                try {
                    source?.Destroy(); lipstick?.Destroy(); blusher?.Destroy()
                    beauty?.Destroy(); reshape?.Destroy(); sink?.Destroy()
                    detector?.destroy()
                } catch (_: Throwable) {}
                source = null; lipstick = null; blusher = null
                beauty = null; reshape = null; sink = null; detector = null
                main.post {
                    try { surface?.release() } catch (_: Throwable) {}
                    surface = null
                    try { entry?.release() } catch (_: Throwable) {}
                    entry = null
                    frame?.recycle()
                    frame = null
                    mask = null
                }
                t?.quitSafely()
            }
        } else {
            try { surface?.release() } catch (_: Throwable) {}
            surface = null
            try { entry?.release() } catch (_: Throwable) {}
            entry = null
        }
        handler = null
        thread = null
    }
}
