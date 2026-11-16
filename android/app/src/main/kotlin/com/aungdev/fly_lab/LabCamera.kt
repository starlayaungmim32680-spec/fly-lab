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
//   mask / city / warp / matrix / grid / aurora / chrome}, stats -> {fps,
//   segMs, segFps, delegate, lastCrash}, clearCrash, stop.
//
// Step 3 (16 Nov 2026):
//   - 6 futuristic animated backgrounds (LabBackgrounds.kt, GPU shaders,
//     Android 13+) with a neon rim glow around the person (Android 12+).
//   - Halo fix for Blur: step 1 blurred the WHOLE frame, so the person's
//     own colours smeared into the background (grey halo around the hair).
//     Now the seg thread builds a "clean" blurred background from the small
//     frame copy with the person's pixels weighted out (a weighted box blur:
//     sum(colour * w) / sum(w), w = 1 - person), drawn stretched behind
//     the person - so nothing of the person is left in it.
//   - The mask edge is a little tighter: smoothstep(0.45, 0.8), so less of
//     the old background shows as a light fringe around the hair.
//
// Edge polish (16 Nov 2026, Ko's step-3 test: thin white fringe + stair-
// stepped edge on the bright shader backgrounds):
//   - The person confidence is smoothed over time (each new mask moves the
//     old one 65% of the way), so the edge stops shimmering; big changes
//     (> 0.5) are taken at once so fast moves don't leave a ghost.
//   - A thin NEON OUTLINE is drawn ON TOP of the edge band (4x(1-x) of a
//     wide smoothstep) in the background's glow colour, added to the
//     picture (ADD). It covers exactly the pixels where the old room still
//     shows (the white fringe) and the stair steps, the way TikTok's
//     cut-out effects do.
//
// White fringe REMOVAL (16 Nov 2026, Ko: "no white at all - fix the base
// first, then everything else gets easier"): on Android 13+ the person is
// no longer cut out with DST_IN but drawn by a GPU shader (CUTOUT) that
//   1) pulls the edge further inside the person (a = smoothstep(lo, hi,
//      mask)), and
//   2) "de-colours" every edge pixel: an edge pixel is a mix of the person
//      and the room behind, C = a*F + (1-a)*B. B (the room) is known - it
//      is the clean room estimate the seg thread already builds (person
//      weighted out) - so the person's own colour is F = (C - (1-a)*B) / a.
//      The white wall is subtracted out of the hair instead of being
//      hidden. The room estimate is now built for every background mode.
//
// Motion fix (16 Nov 2026, Ko: "when I move, a white shadow of the room
// comes along - I want only my real self + the new background"): the
// shadow was TIMING, not colour. The mask is ~40 ms of work, so the newest
// camera frame was drawn with a mask made from an OLDER frame - when the
// body moved, the old mask no longer covered it and the room showed where
// the body had just been. Now every frame that is shown is shown WITH ITS
// OWN mask: the frame is held (a full copy) until the segmenter has made
// the mask for exactly that frame, then both are drawn together; frames
// that arrive while the segmenter is busy are skipped. The picture is a
// few tens of ms later and runs at the mask rate (~18-22 fps instead of
// 24-25), but body and mask always match. The time smoothing is now only
// for tiny changes (< 0.12, the shimmer) so it can't drag the edge behind
// a moving body, and the room estimate is rebuilt every 3rd mask only.
//
// 16 Nov 2026, Ko's test of the motion fix: "better", but (1) "no colour
// ring around the body please" and (2) the read-out showed camera 7 fps.
//   (1) The neon rim glow and the neon outline are OFF (NEON = false) -
//       just the real person on the new background.
//   (2) 7 fps came from the hand-over: the seg thread waited for the
//       camera thread to draw before it took the next frame, and every
//       frame that came in meanwhile was thrown away. Now it is a 2-step
//       pipeline: while the segmenter works on frame N, the camera thread
//       already beautifies the newest frame and parks it ("pending", the
//       newest replaces an older one); the moment the mask for N is done,
//       N is sent to be drawn and the segmenter goes straight on with the
//       parked frame. So it runs at the segmenter's speed (~25/s). The
//       room estimate is built AFTER the frame is sent to be drawn, and
//       the mask / edge bitmaps have 3 buffers so one being drawn is never
//       overwritten.
//
// 16 Nov 2026, Ko's speed-fix test (camera 20 fps, mask 23 ms - good; no
// ring - good): "fast moves still leave a white shadow". The mask now
// matches the frame, so this is the CAMERA's own motion blur: while the
// shutter is open the moving hair smears over the white wall, so those
// pixels really are half hair / half wall. Two fixes:
//   1) Colour key at the edge (CUTOUT): wherever the mask is not solid
//      (< 0.98), a pixel that looks like the room behind it (colour
//      distance to the room estimate < ~0.2) is dropped. The ML mask says
//      WHERE the person is, the colour says which edge pixels are really
//      wall - so the smeared white goes, the solid person stays.
//   2) Shorter shutter while an effect is on: the camera is asked for a
//      fixed 30 fps (max 1/30 s exposure) instead of 24-30 (1/24 s), so
//      there is less smear to begin with. Needs light - a bright room / the
//      Ring light helps the most.
//
// 16 Nov 2026, Ko's smear-fix test (02:12 screenshot): a head-shaped HOLE
// showing the room - the mask and the frame were from different moments
// again. Real bug in the pipeline: the camera thread (busy, 16 fps) fell
// behind with the draws, the seg thread made 3+ masks meanwhile and the 3
// shared mask buffers were overwritten - an old frame got drawn with a NEWER
// mask. Fixes: (1) each frame now carries its OWN copy of its mask / edge
// (37 KB each, nothing is shared); (2) only the newest finished frame is
// drawn - if the camera thread is behind, older finished frames are dropped
// instead of queuing up (no growing delay); (3) while a frame is already
// parked for the segmenter, new camera frames are skipped BEFORE the
// beauty step, so the camera thread isn't overloaded.
//
// QUALITY PASS (16 Nov 2026, Ko: "no more white shadow - now make it the
// best: pretty like TikTok, bright and clear even in a dark room, and
// smooth when I move"; read-out showed camera 11 fps):
//   1) Smoothness: drawing moved to its OWN thread (LabDraw). Before, the
//      camera thread did beauty AND drawing, so it was the bottleneck
//      (11 fps). Now camera / segmenter / drawing are 3 stations working
//      at the same time.
//   2) Dark rooms: (a) Fly's low-light camera logic is back - when the
//      camera's ISO stays high it goes from a fixed 30 fps to 24-30 fps
//      (longer shutter = brighter) with high-quality noise reduction, back
//      when it is bright again; (b) the person is brightened by the GPU:
//      the seg thread measures the person's average brightness and the
//      CUTOUT shader lifts the shadows (f = 1 - (1 - f)^gain, gain up to
//      1.8, smoothed over time) - dark faces come up, bright ones don't
//      burn out.
//   3) Prettier: beauty presets from Fly's Record camera (Natural / Glow /
//      Doll / Glam - same values), chosen from the Lab screen; default Glow.
//   4) The colour key at the edge is gentler (distance 0.05-0.14, only where
//      the mask is fuzzy, < 0.92) - it was biting into arms.
//
// HAIR PASS (16 Nov 2026, Ko: "all good now, but the hair on top is a big
// black lump and its edge keeps moving"):
//   - The black lump: the de-colouring (F = (C - (1-a)B)/a) took too much
//     of the bright wall out of the hair edge, so hair went to pure black
//     with no strands. Now it is only 55% strong and can never make a pixel
//     darker than half its real colour.
//   - The softer cut: the edge is cut a little wider and softer (lo 0.25,
//     hi 0.9), so hair ends fade instead of a hard "helmet" line.
//   - The moving edge: in a dark room camera noise makes the mask edge jump
//     a little every frame. The mask is now smoothed in SPACE (3x3 average
//     before the edge is made - rounder, steadier outline) and in TIME for
//     changes up to 0.25 (was 0.12); bigger changes (real moves) still go
//     through at once.
//   - The low-light lift measures the TOP half of the person (face / hair),
//     not the white T-shirt, so dark faces really get lifted.

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
import android.graphics.RuntimeShader
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

    // Drawing thread (quality pass).
    private var drawThread: HandlerThread? = null
    private var drawHandler: Handler? = null

    // Low light (from Fly's BeautyLab.kt).
    private var builder: CaptureRequest.Builder? = null
    private var fpsNormal: android.util.Range<Int>? = null
    private var fpsLow: android.util.Range<Int>? = null
    private var isoMax = 0
    @Volatile private var lowLight = false
    private var darkFrames = 0
    private var brightFrames = 0

    // Person brightness lift for the CUTOUT shader (1 = none).
    @Volatile private var personGain = 1f

    // Segmentation (seg thread).
    private var segThread: HandlerThread? = null
    private var segHandler: Handler? = null
    private var segmenter: ImageSegmenter? = null
    @Volatile private var segBusy = false
    @Volatile private var delegateName = "-"
    private val segW = 144
    private val segH = 256
    private val maskBuffers = arrayOfNulls<Bitmap>(3)
    private var maskWrite = 0
    @Volatile private var mask: Bitmap? = null
    private val maskBytes = ByteArray(segW * segH)
    // Edge polish: time-smoothed confidence + the outline band.
    private val smoothConf = FloatArray(segW * segH)
    private val rawConf = FloatArray(segW * segH)
    private val spaceConf = FloatArray(segW * segH)
    private var smoothReady = false
    private val edgeBytes = ByteArray(segW * segH)
    private val edgeBuffers = arrayOfNulls<Bitmap>(3)
    @Volatile private var edge: Bitmap? = null
    private val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    // Clean blur background (seg thread builds it, draw() stretches it).
    private val bgBuffers = arrayOfNulls<Bitmap>(2)
    private var bgWrite = 0
    @Volatile private var bgBlur: Bitmap? = null
    private val px = IntArray(segW * segH)
    private val chR = FloatArray(segW * segH)
    private val chG = FloatArray(segW * segH)
    private val chB = FloatArray(segW * segH)
    private val chW = FloatArray(segW * segH)
    private val tmp = FloatArray(segW * segH)
    private val outPx = IntArray(segW * segH)

    // Ko (16 Nov 2026): no colour ring around the body - neon rim glow and
    // outline off. Set to true to bring them back.
    private val neon = false

    // Futuristic backgrounds (API 33+) + neon rim (API 31+).
    private val shaderCache = HashMap<String, Any>()
    private val shaderPaint = Paint()
    private var glowNode: Any? = null
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val t0 = SystemClock.uptimeMillis()

    // Fringe-free cut-out (Android 13+).
    private var cutoutShader: Any? = null
    private val cutoutPaint = Paint()

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
                "low" to lowLight, "gain" to personGain.toDouble(),
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
            val dt = HandlerThread("LabDraw").also { it.start() }
            drawThread = dt
            drawHandler = Handler(dt.looper)
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
        // Fixed 30 fps first (shutter <= 1/30 s = less motion smear), then
        // 24-30, then any range up to 30.
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val fps = ranges
            ?.filter { it.upper == 30 }?.let { r ->
                r.firstOrNull { it.lower == 30 } ?: r.firstOrNull { it.lower == 24 }
                    ?: r.firstOrNull()
            }
        fpsNormal = fps
        // Low light: 24-30 (longer shutter, brighter), else the lowest
        // range up to 30.
        fpsLow = ranges?.filter { it.upper == 30 && it.lower == 24 }?.firstOrNull()
            ?: ranges?.filter { it.upper == 30 }?.minByOrNull { it.lower } ?: fps
        isoMax = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 0
        darkFrames = 0
        brightFrames = 0
        lowLight = false
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
                        builder = b
                        repeatRequest(s, b)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(tag, "session config failed")
                    }
                }, h)
        } catch (ex: Throwable) {
            Log.e(tag, "session", ex)
        }
    }

    /** Sets the light-dependent parts (fps range, noise reduction) and
     *  repeats - from Fly's BeautyLab.kt. */
    private fun repeatRequest(s: CameraCaptureSession, b: CaptureRequest.Builder) {
        try {
            val low = lowLight
            (if (low) fpsLow ?: fpsNormal else fpsNormal)?.let {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            b.set(CaptureRequest.NOISE_REDUCTION_MODE,
                if (low) CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                else CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            b.set(CaptureRequest.EDGE_MODE,
                if (low) CaptureRequest.EDGE_MODE_FAST
                else CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            s.setRepeatingRequest(b.build(), lightWatch, handler)
        } catch (e: Throwable) {
            Log.e(tag, "repeat", e)
        }
    }

    /** Watches the ISO the camera picks to switch low light on / off. */
    private val lightWatch = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val hi = if (isoMax > 0) minOf(isoMax / 2, 1600) else 1600
            val lo = hi * 3 / 10
            val dark = iso >= hi || (exp >= 28_000_000L && iso >= hi / 2)
            var change = false
            if (!lowLight) {
                darkFrames = if (dark) darkFrames + 1 else 0
                if (darkFrames >= 20) change = true
            } else {
                brightFrames = if (iso <= lo) brightFrames + 1 else 0
                if (brightFrames >= 45) change = true
            }
            if (!change) return
            lowLight = !lowLight
            darkFrames = 0
            brightFrames = 0
            val b = builder ?: return
            repeatRequest(s, b)
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
            // Segmenter busy and a frame already parked: skip this one
            // before the (costly) beauty step.
            if (bgMode != "none" && segmenter != null) {
                val skip = synchronized(segLock) { segBusy && pendingFull != null }
                if (skip) return
            }
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
            if (bgMode == "none" || segmenter == null) {
                // Drawn on the draw thread too (a copy - b is reused).
                handOverForDraw(b.copy(Bitmap.Config.ARGB_8888, false), null, null)
            } else {
                // Shown later, together with its own mask (motion fix).
                requestSegmentation(b)
            }
        } catch (ex: Throwable) {
            Log.e(tag, "frame", ex)
        } finally {
            image.close()
        }
    }

    // Pipeline: one frame in the segmenter + at most one parked frame.
    private val segLock = Any()
    private var pendingFull: Bitmap? = null
    private var pendingSmall: Bitmap? = null

    /** Hands this frame to the segmenter, or parks it (newest wins) if the
     *  segmenter is busy. It is drawn later together with its own mask. */
    private fun requestSegmentation(b: Bitmap) {
        val sh = segHandler ?: return
        if (segmenter == null) return
        val full = b.copy(Bitmap.Config.ARGB_8888, false)
        val small = Bitmap.createScaledBitmap(b, segW, segH, true)
        var start = false
        synchronized(segLock) {
            if (!segBusy) {
                segBusy = true
                start = true
            } else {
                pendingFull?.recycle()
                pendingSmall?.recycle()
                pendingFull = full
                pendingSmall = small
            }
        }
        if (start) sh.post { segLoop(full, small) }
    }

    /** Seg thread: mask -> send the frame to be drawn -> room estimate ->
     *  next parked frame, until none is parked. */
    private fun segLoop(firstFull: Bitmap, firstSmall: Bitmap) {
        var full = firstFull
        var small = firstSmall
        while (true) {
            var ok = false
            try {
                ok = runSegmentation(small)
            } catch (e: Throwable) {
                Log.e(tag, "segment", e)
            }
            if (ok) {
                try { measurePerson(small) } catch (ex: Throwable) { Log.e(tag, "luma", ex) }
            }
            // This frame's OWN mask / edge (copies - nothing shared).
            val m = if (ok) mask?.copy(Bitmap.Config.ALPHA_8, false) else null
            val e = if (ok) edge?.copy(Bitmap.Config.ALPHA_8, false) else null
            handOverForDraw(full, m, e)
            // The clean room estimate (Blur background + the colour the
            // cut-out removes from the edge) - every 3rd mask is enough.
            try {
                if (ok && bgMode != "none" && bgMode != "mask") {
                    if (bgBlur == null || roomCount % 3 == 0) buildCleanBlur(small)
                    roomCount++
                }
            } catch (ex: Throwable) {
                Log.e(tag, "room", ex)
            }
            small.recycle()
            synchronized(segLock) {
                val pf = pendingFull
                val ps = pendingSmall
                if (pf == null || ps == null || !running) {
                    pf?.recycle()
                    ps?.recycle()
                    pendingFull = null
                    pendingSmall = null
                    segBusy = false
                    return
                }
                pendingFull = null
                pendingSmall = null
                full = pf
                small = ps
            }
        }
    }

    private var roomCount = 0
    private val lumaPx = IntArray(144 * 256)

    /** The person's average brightness -> how much the CUTOUT shader
     *  lifts the shadows (dark room = more), smoothed over time. */
    private fun measurePerson(small: Bitmap) {
        small.getPixels(lumaPx, 0, segW, 0, 0, segW, segH)
        var sum = 0f
        var cnt = 0
        var i = 0
        // Top half only: face and hair, not the T-shirt.
        val n = segW * segH / 2
        while (i < n) {
            if ((maskBytes[i].toInt() and 0xFF) > 160) {
                val c = lumaPx[i]
                sum += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) +
                    0.114f * (c and 0xFF)
                cnt++
            }
            i += 2
        }
        if (cnt < 30) return
        val mean = sum / cnt / 255f
        // Aim for ~0.5 average brightness, never darken, at most 1.8x.
        val want = (0.5f / mean.coerceAtLeast(0.05f)).coerceIn(1f, 1.8f)
        personGain = personGain * 0.85f + want * 0.15f
    }

    // Only the newest finished frame waits to be drawn.
    private val drawLock = Any()
    private var readyFull: Bitmap? = null
    private var readyMask: Bitmap? = null
    private var readyEdge: Bitmap? = null
    private var drawScheduled = false

    private fun handOverForDraw(full: Bitmap, m: Bitmap?, e: Bitmap?) {
        val ch = drawHandler
        if (ch == null) {
            full.recycle(); m?.recycle(); e?.recycle()
            return
        }
        var post = false
        synchronized(drawLock) {
            // An older finished frame not drawn yet: drop it (newest wins).
            readyFull?.recycle(); readyMask?.recycle(); readyEdge?.recycle()
            readyFull = full; readyMask = m; readyEdge = e
            if (!drawScheduled) {
                drawScheduled = true
                post = true
            }
        }
        if (post) ch.post { drawReady() }
    }

    private fun drawReady() {
        val f: Bitmap?
        val m: Bitmap?
        val e: Bitmap?
        synchronized(drawLock) {
            f = readyFull; m = readyMask; e = readyEdge
            readyFull = null; readyMask = null; readyEdge = null
            drawScheduled = false
        }
        if (f == null) return
        try {
            if (running) {
                draw(f, m, e)
                countFps()
            }
        } catch (ex: Throwable) {
            Log.e(tag, "paired draw", ex)
        } finally {
            f.recycle(); m?.recycle(); e?.recycle()
        }
    }

    /** Makes the mask (and edge band) for this small frame. True if done. */
    private fun runSegmentation(small: Bitmap): Boolean {
        val seg = segmenter ?: return false
        val t0 = SystemClock.uptimeMillis()
        val result = seg.segmentForVideo(BitmapImageBuilder(small).build(), t0)
        val masks = result.confidenceMasks().orElse(null) ?: return false
        if (masks.isEmpty()) return false
        // selfie_segmenter: one mask = person confidence (if a model gives
        // two, index 1 is the person).
        val m = if (masks.size > 1) masks[1] else masks[0]
        val buf = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        val n = minOf(fb.remaining(), maskBytes.size)
        // Hair pass: 3x3 average in space first (steadier, rounder edge).
        for (i in 0 until n) rawConf[i] = fb.get(i)
        for (y in 0 until segH) {
            val y0 = if (y > 0) y - 1 else y
            val y1 = if (y < segH - 1) y + 1 else y
            for (x in 0 until segW) {
                val x0 = if (x > 0) x - 1 else x
                val x1 = if (x < segW - 1) x + 1 else x
                spaceConf[y * segW + x] = (
                    rawConf[y0 * segW + x0] + rawConf[y0 * segW + x] + rawConf[y0 * segW + x1] +
                    rawConf[y * segW + x0] + rawConf[y * segW + x] + rawConf[y * segW + x1] +
                    rawConf[y1 * segW + x0] + rawConf[y1 * segW + x] + rawConf[y1 * segW + x1]
                ) / 9f
            }
        }
        for (i in 0 until n) {
            // Smooth over time (edge shimmer), but follow big jumps at once.
            val c = spaceConf[i]
            val prev = smoothConf[i]
            // Only tiny changes are smoothed (shimmer); anything bigger is
            // a real move and is taken at once (motion fix).
            val sc = if (!smoothReady || kotlin.math.abs(c - prev) > 0.25f) c
                     else prev + (c - prev) * 0.5f
            smoothConf[i] = sc
            // smoothstep(0.45, 0.8) - firm inside, soft edge, a little
            // inside the person so the old background doesn't fringe.
            var x = (sc - 0.45f) / 0.35f
            x = if (x < 0f) 0f else if (x > 1f) 1f else x
            maskBytes[i] = (x * x * (3f - 2f * x) * 255f).toInt().toByte()
            // Outline band: 4e(1-e) of a wider smoothstep(0.15, 0.9).
            var e = (sc - 0.15f) / 0.75f
            e = if (e < 0f) 0f else if (e > 1f) 1f else e
            e = e * e * (3f - 2f * e)
            val band = 4f * e * (1f - e)
            edgeBytes[i] = (band * band * 255f).toInt().toByte()
        }
        smoothReady = true
        var mb = maskBuffers[maskWrite]
        if (mb == null) {
            mb = Bitmap.createBitmap(segW, segH, Bitmap.Config.ALPHA_8)
            maskBuffers[maskWrite] = mb
        }
        mb!!.copyPixelsFromBuffer(ByteBuffer.wrap(maskBytes))
        var eb = edgeBuffers[maskWrite]
        if (eb == null) {
            eb = Bitmap.createBitmap(segW, segH, Bitmap.Config.ALPHA_8)
            edgeBuffers[maskWrite] = eb
        }
        eb!!.copyPixelsFromBuffer(ByteBuffer.wrap(edgeBytes))
        mask = mb
        edge = eb
        maskWrite = (maskWrite + 1) % 3

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
        return true
    }

    /** Background-only blur: the person is weighted out before blurring. */
    private fun buildCleanBlur(small: Bitmap) {
        val n = segW * segH
        small.getPixels(px, 0, segW, 0, 0, segW, segH)
        var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
        for (i in 0 until n) {
            val a = (maskBytes[i].toInt() and 0xFF) / 255f
            // Anything that is even a little "person" counts as person.
            val w = if (a > 0.08f) 0f else 1f - a
            val c = px[i]
            val r = ((c shr 16) and 0xFF) * w
            val g = ((c shr 8) and 0xFF) * w
            val bl = (c and 0xFF) * w
            chR[i] = r; chG[i] = g; chB[i] = bl; chW[i] = w
            sr += r; sg += g; sb += bl; sw += w
        }
        // Two box passes per direction ~ a soft Gaussian, radius 7 px of
        // 144 (~35 px on a 720-wide frame).
        for (ch in arrayOf(chR, chG, chB, chW)) {
            for (pass in 0 until 2) {
                boxH(ch, 7)
                boxV(ch, 7)
            }
        }
        val avgR = if (sw > 0f) sr / sw else 40f
        val avgG = if (sw > 0f) sg / sw else 40f
        val avgB = if (sw > 0f) sb / sw else 40f
        for (i in 0 until n) {
            val w = chW[i]
            val r: Float; val g: Float; val bl: Float
            if (w > 0.03f) {
                r = chR[i] / w; g = chG[i] / w; bl = chB[i] / w
            } else {
                r = avgR; g = avgG; bl = avgB
            }
            outPx[i] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
        }
        var ob = bgBuffers[bgWrite]
        if (ob == null) {
            ob = Bitmap.createBitmap(segW, segH, Bitmap.Config.ARGB_8888)
            bgBuffers[bgWrite] = ob
        }
        ob!!.setPixels(outPx, 0, segW, 0, 0, segW, segH)
        bgBlur = ob
        bgWrite = 1 - bgWrite
    }

    /** Horizontal box blur (running sum, edges clamped), in place. */
    private fun boxH(a: FloatArray, r: Int) {
        val w = segW
        val div = (2 * r + 1).toFloat()
        for (y in 0 until segH) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += a[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc / div
                val add = a[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = a[row + (x - r).coerceAtLeast(0)]
                acc += add - sub
            }
        }
        System.arraycopy(tmp, 0, a, 0, segW * segH)
    }

    /** Vertical box blur (running sum, edges clamped), in place. */
    private fun boxV(a: FloatArray, r: Int) {
        val w = segW
        val h = segH
        val div = (2 * r + 1).toFloat()
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += a[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y * w + x] = acc / div
                val add = a[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = a[(y - r).coerceAtLeast(0) * w + x]
                acc += add - sub
            }
        }
        System.arraycopy(tmp, 0, a, 0, segW * segH)
    }

    /** Draws a frame. m / e = the mask and edge made from THIS frame
     *  (null = no effect, the frame as it is). */
    private fun draw(b: Bitmap, m: Bitmap?, e: Bitmap?) {
        val s = surface ?: return
        val canvas = try { s.lockHardwareCanvas() } catch (_: Throwable) { null } ?: return
        try {
            val dst = Rect(0, 0, canvas.width, canvas.height)
            val mode = bgMode
            if (mode == "none" || m == null) {
                canvas.drawBitmap(b, null, dst, paint)
            } else {
                // 1) background
                when (mode) {
                    "blur" -> {
                        val bg = bgBlur
                        if (bg != null) canvas.drawBitmap(bg, null, dst, paint)
                        else canvas.drawBitmap(b, null, dst, paint)
                    }
                    "mask" -> {
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(m, null, dst, maskViewPaint)
                    }
                    else -> {
                        drawShaderBackground(canvas, dst, mode)
                        if (neon) drawGlow(canvas, m, dst, LabBackgrounds.glow[mode] ?: Color.CYAN)
                    }
                }
                // 2) the person, cut out by the mask (fringe-free on 13+)
                if (mode != "mask" && !drawCutout(canvas, b, m, dst)) {
                    val sc = canvas.saveLayer(null, null)
                    canvas.drawBitmap(b, null, dst, paint)
                    canvas.drawBitmap(m, null, dst, maskPaint)
                    canvas.restoreToCount(sc)
                }
                // 3) neon outline on the edge (shader backgrounds only)
                if (neon && e != null && LabBackgrounds.glow.containsKey(mode)) {
                    edgePaint.color = LabBackgrounds.glow[mode] ?: Color.CYAN
                    edgePaint.alpha = 235
                    canvas.drawBitmap(e, null, dst, edgePaint)
                }
            }
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    /** The person, cut out with the room colour removed from the edge
     *  (Android 13+). Returns false if it can't (then DST_IN is used). */
    private fun drawCutout(canvas: Canvas, b: Bitmap, m: Bitmap, dst: Rect): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val room = bgBlur ?: return false
        return try {
            var sh = cutoutShader as RuntimeShader?
            if (sh == null) {
                sh = RuntimeShader(CUTOUT)
                cutoutShader = sh
            }
            sh.setInputShader("img", bitmapShader(b, dst, false))
            sh.setInputShader("msk", bitmapShader(m, dst, true))
            sh.setInputShader("room", bitmapShader(room, dst, true))
            // Edge pulled in: mask alpha 0.35 -> 0, 0.95 -> 1.
            sh.setFloatUniform("lo", 0.25f)
            sh.setFloatUniform("hi", 0.9f)
            sh.setFloatUniform("gain", personGain)
            cutoutPaint.shader = sh
            canvas.drawRect(dst, cutoutPaint)
            true
        } catch (e: Throwable) {
            Log.e(tag, "cutout", e)
            cutoutShader = null
            false
        }
    }

    /** A bitmap stretched over dst (linear filtering for the small ones). */
    private fun bitmapShader(bm: Bitmap, dst: Rect, linear: Boolean): Shader {
        val bs = android.graphics.BitmapShader(bm,
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (Build.VERSION.SDK_INT >= 33) {
            bs.filterMode = if (linear) android.graphics.BitmapShader.FILTER_MODE_LINEAR
                            else android.graphics.BitmapShader.FILTER_MODE_DEFAULT
        }
        val mx = android.graphics.Matrix()
        mx.setScale(dst.width().toFloat() / bm.width, dst.height().toFloat() / bm.height)
        mx.postTranslate(dst.left.toFloat(), dst.top.toFloat())
        bs.setLocalMatrix(mx)
        return bs
    }

    /** A futuristic GPU background (Android 13+; older: dark gradient). */
    private fun drawShaderBackground(canvas: Canvas, dst: Rect, mode: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val src = LabBackgrounds.shaders[mode]
            if (src != null) {
                try {
                    val sh = shaderCache.getOrPut(mode) { RuntimeShader(src) } as RuntimeShader
                    sh.setFloatUniform("iResolution",
                        dst.width().toFloat(), dst.height().toFloat())
                    sh.setFloatUniform("iTime",
                        (SystemClock.uptimeMillis() - t0) / 1000f)
                    shaderPaint.shader = sh
                    canvas.drawRect(dst, shaderPaint)
                    return
                } catch (e: Throwable) {
                    Log.e(tag, "shader $mode", e)
                    shaderCache.remove(mode)
                }
            }
        }
        canvas.drawColor(Color.rgb(10, 4, 30))
    }

    /** Neon rim: the person's mask, tinted and blurred, behind the person
     *  (Android 12+ RenderEffect; nothing on older phones). */
    private fun drawGlow(canvas: Canvas, m: Bitmap, dst: Rect, color: Int) {
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) return
        try {
            var node = glowNode as RenderNode?
            if (node == null) {
                node = RenderNode("labGlow")
                glowNode = node
            }
            node.setPosition(0, 0, dst.width(), dst.height())
            val r = dst.width() / 45f
            node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
            glowPaint.color = color
            val rc = node.beginRecording()
            // Slightly larger than the person, so the light shows around it.
            val grow = dst.width() / 90
            rc.drawBitmap(m, null, Rect(dst.left - grow, dst.top - grow,
                dst.right + grow, dst.bottom + grow), glowPaint)
            node.endRecording()
            canvas.drawRenderNode(node)
        } catch (e: Throwable) {
            Log.e(tag, "glow", e)
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
        val dt = drawThread
        drawHandler?.post { dt?.quitSafely() }
        drawHandler = null
        drawThread = null
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
                    edge = null
                    smoothReady = false
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

    companion object {
        /** Fringe-free cut-out: edge pulled in + the room colour removed
         *  from the edge pixels (F = (C - (1-a) B) / a), premultiplied. */
        private const val CUTOUT = """
uniform shader img;
uniform shader msk;
uniform shader room;
uniform float lo;
uniform float hi;
uniform float gain;
half4 main(float2 p) {
    float m = float(msk.eval(p).a);
    float a = smoothstep(lo, hi, m);
    if (a <= 0.001) { return half4(0.0); }
    float3 c = float3(img.eval(p).rgb);
    float3 bg = float3(room.eval(p).rgb);
    // Colour key at the edge: where the mask isn't solid, a pixel that
    // looks like the room behind it is the room (e.g. motion-smeared hair
    // over a white wall) - drop it.
    float edgeZone = 1.0 - smoothstep(0.75, 0.92, m);
    float key = smoothstep(0.05, 0.14, length(c - bg));
    a *= mix(1.0, key, edgeZone);
    if (a <= 0.001) { return half4(0.0); }
    float3 f = (c - (1.0 - a) * bg) / max(a, 0.3);
    f = clamp(f, 0.0, 1.0);
    // Hair pass: only 55% of the de-colouring, and never darker than half
    // the real colour (it turned hair edges into a black lump).
    f = max(mix(c, f, 0.55), c * 0.5);
    // Where the person is solid keep the real colour exactly.
    f = mix(f, c, smoothstep(0.85, 1.0, a));
    // Low-light lift: shadows up, highlights stay (gain 1 = none).
    f = 1.0 - pow(clamp(1.0 - f, 0.0, 1.0), float3(gain));
    return half4(half3(f * a), half(a));
}
"""
    }
}
