package com.aungdev.fly_lab

// Fly Lab EDITOR step 1 (16 Nov 2026) - one photo -> a video.
// Ko's new direction (16 Nov 2026): instead of live camera effects, an
// editor - pick an existing photo / video, choose a template, get an mp4
// with the effects BAKED IN, post it to Fly. Step 1 proves the engine on
// Ko's phone with 3 templates from ONE photo (PhotoTemplates.kt).
//
//   Preview: own GL thread, OpenGL ES 2 on an EGL window surface made from
//            a Flutter texture (720x1280), redrawn ~30 times a second, the
//            template's time looping over the video length.
//   Export:  another GL thread renders the SAME shader frame by frame
//            (t = i / 30) into an H.264 MediaCodec input surface
//            (720x1280, 30 fps, 8 Mbit/s), MediaMuxer -> mp4 (no sound yet,
//            music is step 4), then it is copied into the phone's gallery
//            (Movies/FlyLab) so Ko can watch / share it.
//
// Channel "flylab/editor":
//   load {path}                     -> {textureId, width, height}
//   setTemplate {id}                -> null
//   export {id, seconds}            -> {uri, ms, bytes, frames}
//   progress                        -> 0..1 (while exporting)
//   dispose                         -> null

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object PhotoVideoRegistrar {
    fun register(activity: Activity, engine: FlutterEngine) {
        val ed = PhotoVideoEngine(activity, engine.renderer)
        MethodChannel(engine.dartExecutor.binaryMessenger, "flylab/editor")
            .setMethodCallHandler(ed)
    }
}

/** Minimal EGL (OpenGL ES 2) - one context + one window surface. */
class LabEgl(recordable: Boolean) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext
    var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attrs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        )
        if (recordable) { attrs += 0x3142; attrs += 1 } // EGL_RECORDABLE_ANDROID
        attrs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, num, 0) &&
            num[0] > 0) { "eglChooseConfig" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
    }

    fun attach(window: Any) {
        surface = EGL14.eglCreateWindowSurface(display, config, window,
            intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
    }

    fun swap(ptsNs: Long? = null) {
        if (ptsNs != null) EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}

/** Draws a template over the photo (needs a current GL context). */
class PhotoRenderer {
    private val programs = HashMap<String, Int>()
    private var tex = 0
    private var imgAspect = 1f
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }

    fun setPhoto(bm: Bitmap) {
        if (tex == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            tex = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0)
        imgAspect = bm.width.toFloat() / bm.height
    }

    private fun program(id: String): Int {
        programs[id]?.let { return it }
        val frag = PhotoTemplates.fragments[id] ?: error("no template $id")
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, PhotoTemplates.VERTEX))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, frag))
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link $id: " + GLES20.glGetProgramInfoLog(p) }
        programs[id] = p
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "compile: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    fun draw(id: String, t: Float, dur: Float, w: Int, h: Int) {
        val p = program(id)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(p)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), t)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uDur"), dur)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uImgAspect"), imgAspect)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uOutAspect"), w.toFloat() / h)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}

class PhotoVideoEngine(
    private val activity: Activity,
    private val textures: TextureRegistry,
) : MethodChannel.MethodCallHandler {

    private val tag = "PhotoVideo"
    private val main = Handler(Looper.getMainLooper())
    private val outW = 720
    private val outH = 1280
    private val fps = 30
    private val previewDur = 8f

    private var photo: Bitmap? = null
    @Volatile private var template = "cinematic"

    // Preview.
    private var pThread: HandlerThread? = null
    private var pHandler: Handler? = null
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var pSurface: Surface? = null
    private var pEgl: LabEgl? = null
    private var pRenderer: PhotoRenderer? = null
    private var pStart = 0L
    @Volatile private var previewing = false

    // Export.
    @Volatile private var progress = 0.0
    @Volatile private var exporting = false

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "load" -> load(call.argument<String>("path") ?: "", result)
            "setTemplate" -> {
                template = call.argument<String>("id") ?: template
                pStart = SystemClock.uptimeMillis()
                result.success(null)
            }
            "export" -> export(call.argument<String>("id") ?: template,
                call.argument<Double>("seconds") ?: 8.0, result)
            "progress" -> result.success(progress)
            "dispose" -> {
                stopPreview()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    // ------------------------------------------------------------- photo
    /** Decodes the photo upright, longest side <= 1600 px. */
    private fun decode(path: String): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: error("Can't read the photo")
        val rot = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Throwable) { 0f }
        if (rot == 0f) return bm
        val m = Matrix().apply { postRotate(rot) }
        val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        if (r != bm) bm.recycle()
        return r
    }

    private fun load(path: String, result: MethodChannel.Result) {
        Thread {
            try {
                val bm = decode(path)
                main.post {
                    photo?.recycle()
                    photo = bm
                    startPreview(result)
                }
            } catch (e: Throwable) {
                Log.e(tag, "load", e)
                main.post { result.error("load_failed", e.toString(), null) }
            }
        }.start()
    }

    // ----------------------------------------------------------- preview
    private fun startPreview(result: MethodChannel.Result) {
        val bm = photo ?: return result.error("no_photo", "No photo", null)
        if (pThread == null) {
            val t = HandlerThread("LabEditorPreview").also { it.start() }
            pThread = t
            pHandler = Handler(t.looper)
            val e = textures.createSurfaceTexture()
            entry = e
            e.surfaceTexture().setDefaultBufferSize(outW, outH)
            pSurface = Surface(e.surfaceTexture())
        }
        val h = pHandler!!
        val s = pSurface!!
        h.post {
            try {
                if (pEgl == null) {
                    pEgl = LabEgl(false).also { it.attach(s) }
                    pRenderer = PhotoRenderer()
                }
                pRenderer!!.setPhoto(bm)
                pStart = SystemClock.uptimeMillis()
                if (!previewing) {
                    previewing = true
                    h.post(frameLoop)
                }
                main.post {
                    result.success(mapOf("textureId" to entry!!.id(),
                        "width" to outW, "height" to outH))
                }
            } catch (e: Throwable) {
                Log.e(tag, "preview", e)
                main.post { result.error("preview_failed", e.toString(), null) }
            }
        }
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!previewing) return
            val h = pHandler ?: return
            val t0 = SystemClock.uptimeMillis()
            try {
                val t = ((t0 - pStart) / 1000f) % previewDur
                pRenderer?.draw(template, t, previewDur, outW, outH)
                pEgl?.swap()
            } catch (e: Throwable) {
                Log.e(tag, "frame", e)
            }
            val spent = SystemClock.uptimeMillis() - t0
            h.postDelayed(this, (33 - spent).coerceAtLeast(1))
        }
    }

    private fun stopPreview() {
        previewing = false
        val h = pHandler
        val t = pThread
        h?.post {
            try { pEgl?.release() } catch (_: Throwable) {}
            pEgl = null
            pRenderer = null
            main.post {
                try { pSurface?.release() } catch (_: Throwable) {}
                pSurface = null
                try { entry?.release() } catch (_: Throwable) {}
                entry = null
            }
            t?.quitSafely()
        }
        pHandler = null
        pThread = null
    }

    // ------------------------------------------------------------ export
    private fun export(id: String, seconds: Double, result: MethodChannel.Result) {
        val bm = photo ?: return result.error("no_photo", "No photo", null)
        if (exporting) return result.error("busy", "Already exporting", null)
        exporting = true
        progress = 0.0
        val copy = bm.copy(Bitmap.Config.ARGB_8888, false)
        Thread {
            val started = SystemClock.uptimeMillis()
            val out = File(activity.cacheDir, "flylab_${System.currentTimeMillis()}.mp4")
            var frames = 0
            try {
                frames = encode(copy, id, seconds.toFloat(), out)
                val uri = saveToGallery(out)
                val ms = SystemClock.uptimeMillis() - started
                val bytes = out.length()
                out.delete()
                main.post {
                    result.success(mapOf("uri" to uri, "ms" to ms,
                        "bytes" to bytes, "frames" to frames))
                }
            } catch (e: Throwable) {
                Log.e(tag, "export", e)
                out.delete()
                main.post { result.error("export_failed", e.toString(), null) }
            } finally {
                copy.recycle()
                exporting = false
            }
        }.start()
    }

    /** Renders every frame of the template into H.264 and muxes an mp4. */
    private fun encode(bm: Bitmap, id: String, dur: Float, out: File): Int {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val egl = LabEgl(true)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val total = (dur * fps).toInt()

        fun drain(end: Boolean) {
            if (end) codec.signalEndOfInputStream()
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return else continue
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                    continue
                }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && muxing && buf != null) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }

        try {
            egl.attach(input)
            val r = PhotoRenderer()
            r.setPhoto(bm)
            for (i in 0 until total) {
                val t = i.toFloat() / fps
                r.draw(id, t, dur, outW, outH)
                egl.swap(i * 1_000_000_000L / fps)
                drain(false)
                progress = (i + 1).toDouble() / total
            }
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Throwable) {}
            muxer.release()
            egl.release()
            input.release()
        }
        return total
    }

    /** Copies the mp4 into the gallery (Movies/FlyLab). */
    private fun saveToGallery(f: File): String {
        val name = "FlyLab_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/FlyLab")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = activity.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Can't create the gallery file")
            cr.openOutputStream(uri)!!.use { os -> f.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            return "Movies/FlyLab/$name"
        }
        val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FlyLab")
        dir.mkdirs()
        val dst = File(dir, name)
        f.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }
}
