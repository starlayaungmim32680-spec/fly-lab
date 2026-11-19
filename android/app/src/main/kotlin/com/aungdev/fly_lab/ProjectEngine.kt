package com.aungdev.fly_lab

// Fly Lab PROJECT EDITOR (CapCut-style), E1a - 17 Nov 2026.
// Ko: "like CapCut, but the free way" - everything runs on the phone.
//
// A project = a TIMELINE of clips (photos AND videos, in order) + the
// project's look (effects stacked on every clip, filter + adjust sliders,
// aspect ratio, fit / fill, background). The timeline itself (order,
// durations, template per clip) lives in Dart and is sent here whole with
// "setTimeline" every time it changes.
//
// Every frame (preview AND export) is drawn the same way on the GPU:
//   1. COMPOSE   the clip's photo / video frame into the project frame
//                (fill, or fit with a blurred / colour background)
//   2. TEMPLATE  the clip's template (or "plain"), on the clip's own time
//   3. EFFECTS   the project's effects (max 3), on the project time
// the last pass applies the user's filter / adjust + fade in / out.
//
// Video: preview plays each clip with a muted MediaPlayer into a
// SurfaceTexture (OES); export decodes frame-exact with MediaExtractor +
// MediaCodec into a SurfaceTexture. Sound comes in E2 (Audio).
//
// Channel "flylab/project":
//   addMedia {items: [{path, video}]}  -> [{media, video, dur, aspect, thumb}]
//   setTimeline {clips: [{id, media, dur, template, from, speed}]}  (from = source start, E1b; speed E1c)
//   setLook {adj[8], fx: [{id, start, end, x, y, r, placed}], fit, bgMode, bgColor[3]}
//   thumbs {media} (media -1 = the built-in sample picture)
//   setRatio {ratio: "9:16" | "1:1" | "4:5" | "16:9"} -> {textureId, width, height}
//   preview -> {textureId, width, height}
//   play / pause / seek {t} / position -> {t, playing, total}
//   thumbs {media} -> {templateId: jpeg}
//   export -> {uri, ms, bytes, frames}; progress -> 0..1
//   dispose

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

object ProjectRegistrar {
    fun register(activity: Activity, engine: FlutterEngine) {
        val e = ProjectEngine(activity, engine.renderer)
        MethodChannel(engine.dartExecutor.binaryMessenger, "flylab/project")
            .setMethodCallHandler(e)
    }
}

/** A picked photo or video. poster = the photo, or the video's first frame. */
class Media(
    val id: Int,
    val video: Boolean,
    val path: String,
    val poster: Bitmap,
    val srcDur: Float,
    val aspect: Float,
    val rotation: Int,
)

/** One clip on the timeline. from = where in the source the clip starts (split / trim). */
class TClip(val id: Int, val media: Media, val dur: Float, val template: String,
            val from: Float = 0f, val speed: Float = 1f, val vol: Float = 1f)

/**
 * One effect on the EFFECTS TRACK (18 Nov 2026): on screen from start to end
 * (project seconds). placed = only inside a circle at (x, y) (0..1, y down),
 * radius r in frame heights; otherwise the whole frame.
 */
class FxClip(
    val id: String, val start: Float, val end: Float,
    val x: Float = 0.5f, val y: Float = 0.5f, val r: Float = 0.3f, val placed: Boolean = false,
)

/** The project's look. */
class PLook(
    val adj: FloatArray = floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f),
    val fx: List<FxClip> = emptyList(),
    val fit: Boolean = false,
    val bgMode: Int = 0,
    val bgColor: FloatArray = floatArrayOf(0f, 0f, 0f),
    val texts: List<TItem> = emptyList(),
)

/** Where a frame comes from: a photo texture, or a video (OES) texture. */
class FrameSrc(val tex: Int, val oes: Boolean, val aspect: Float, val st: FloatArray?)

fun projectSize(ratio: String): Pair<Int, Int> = when (ratio) {
    "1:1" -> 720 to 720
    "4:5" -> 768 to 960
    "16:9" -> 1280 to 720
    else -> 720 to 1280
}

// ====================================================================
/** Draws project frames (needs a current GL context; one per thread). */
class ProjectRenderer {
    private val programs = HashMap<String, Int>()
    private val photoTex = HashMap<Int, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
    private val fb = IntArray(2)
    private val tx = IntArray(2)
    private var fw = 0
    private var fh = 0
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private fun program(key: String, frag: String): Int {
        programs[key]?.let { return it }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, PhotoTemplates.VERTEX))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, frag))
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link $key: " + GLES20.glGetProgramInfoLog(p) }
        programs[key] = p
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

    /** The photo (or the video's poster) as a texture, uploaded once. */
    fun photoTexture(m: Media): Int {
        photoTex[m.id]?.let { return it }
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, m.poster, 0)
        photoTex[m.id] = t[0]
        return t[0]
    }

    fun forget(mediaId: Int) {
        photoTex.remove(mediaId)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
    }

    private fun ensure(w: Int, h: Int) {
        if (fb[0] != 0 && fw == w && fh == h) return
        if (fb[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fb, 0)
            GLES20.glDeleteTextures(2, tx, 0)
        }
        GLES20.glGenFramebuffers(2, fb, 0)
        GLES20.glGenTextures(2, tx, 0)
        for (k in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[k])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[k])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tx[k], 0)
        }
        fw = w
        fh = h
    }

    private fun drawQuad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * One project frame into the framebuffer bound now.
     * lt / clipDur: the clip's own time (template); pt / total: project time
     * (effects). fadeIn / fadeOut: is this the first / last clip.
     */
    fun drawFrame(
        src: FrameSrc, template: String, lt: Float, clipDur: Float, pt: Float, total: Float,
        look: PLook, fadeIn: Boolean, fadeOut: Boolean, w: Int, h: Int,
    ) {
        val target = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target, 0)
        ensure(w, h)
        // 1. compose into buffer 0.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
        GLES20.glViewport(0, 0, w, h)
        val cp = if (src.oes) program("#compose_oes", PhotoTemplates.COMPOSE_OES)
                 else program("#compose", PhotoTemplates.COMPOSE)
        GLES20.glUseProgram(cp)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (src.oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, src.tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cp, "uSrc"), 0)
        if (src.oes) {
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cp, "uSt"), 1, false, src.st ?: identity, 0)
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cp, "uSrcAspect"), src.aspect)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cp, "uOutAspect"), w.toFloat() / h)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cp, "uFit"), if (look.fit) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cp, "uBgMode"), look.bgMode.toFloat())
        GLES20.glUniform3f(GLES20.glGetUniformLocation(cp, "uBgColor"),
            look.bgColor[0], look.bgColor[1], look.bgColor[2])
        drawQuad()
        if (src.oes) GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        // 2 + 3. template, then effects.
        val tpl = if (PhotoTemplates.has(template)) template else "plain"
        val active = look.fx.filter { PhotoTemplates.has(it.id) && pt >= it.start && pt < it.end }.take(3)
        val chain = listOf(tpl) + active.map { it.id }
        var cur = 0
        for ((i, id) in chain.withIndex()) {
            val last = i == chain.size - 1
            val out = if (last) target[0] else fb[1 - cur]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out)
            GLES20.glViewport(0, 0, w, h)
            val p = program(PhotoTemplates.baseOf(id), PhotoTemplates.fragments[PhotoTemplates.baseOf(id)]!!)
            GLES20.glUseProgram(p)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[cur])
            val isTpl = i == 0
            val fxc = if (isTpl) null else active[i - 1]
            val t = if (fxc == null) lt else pt - fxc.start
            val d = if (fxc == null) clipDur else maxOf(0.1f, minOf(fxc.end, total) - fxc.start)
            val fx = if (isTpl) (if (fadeIn) 1f else 0f) else 1f
            val fy = if (isTpl) (if (fadeOut) 1f else 0f) else 1f
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), t)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uDur"), d)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uImgAspect"), w.toFloat() / h)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uOutAspect"), w.toFloat() / h)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uRes"), w.toFloat(), h.toFloat())
            val a = look.adj
            GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uAdj"), a[0], a[1], a[2], a[3])
            GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uAdj2"), a[4], a[5], a[6], a[7])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uFinal"), if (last) 1f else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uFlipIn"), 1f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uFade"), fx, fy)
            val v = PhotoTemplates.varOf(id)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uVar"), v[0], v[1], v[2], v[3])
            if (fxc != null && fxc.placed) {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uArea"), fxc.x, 1f - fxc.y, fxc.r, 1f)
            } else {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uArea"), 0f, 0f, 0f, 0f)
            }
            drawQuad()
            cur = 1 - cur
        }
        // 4. texts (E3) on top of everything.
        textLayer.draw(look.texts, pt, w, h)
    }

    private val textLayer = TextLayer()

    fun release() {
        textLayer.release()
        for (t in photoTex.values) GLES20.glDeleteTextures(1, intArrayOf(t), 0)
        photoTex.clear()
        if (fb[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fb, 0)
            GLES20.glDeleteTextures(2, tx, 0)
            fb[0] = 0
        }
    }
}

fun newOesTexture(): Int {
    val t = IntArray(1)
    GLES20.glGenTextures(1, t, 0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return t[0]
}

// ====================================================================
/** Preview: a muted MediaPlayer playing one video into an OES texture. */
class VPlayer(val m: Media, handler: Handler) {
    val tex = newOesTexture()
    private val st = SurfaceTexture(tex)
    private val surface = Surface(st)
    val mp = MediaPlayer()
    @Volatile private var fresh = false
    var hasFrame = false
    var lastSeek = -1f
    var speed = 1f
    val mat = FloatArray(16)

    init {
        st.setOnFrameAvailableListener({ fresh = true }, handler)
        mp.setDataSource(m.path)
        mp.setSurface(surface)
        mp.setVolume(0f, 0f)
        mp.prepare()
    }

    fun seek(s: Float) {
        mp.seekTo((s * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        lastSeek = s
    }

    /** Start playing at speed v (E1c). Setting the params also starts it. */
    fun startAt(v: Float) {
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(v)
            speed = v
        } catch (_: Throwable) {}
        if (!mp.isPlaying) mp.start()
    }

    /** Call on the GL thread: takes the newest frame, if there is one. */
    fun update() {
        if (!fresh) return
        fresh = false
        st.updateTexImage()
        st.getTransformMatrix(mat)
        hasFrame = true
    }

    fun release() {
        try { mp.release() } catch (_: Throwable) {}
        surface.release()
        st.release()
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

/** Export: frame-exact video decoding into an OES texture. */
class VDecoder(val m: Media, cb: Handler) {
    val tex = newOesTexture()
    private val st = SurfaceTexture(tex)
    private val surface = Surface(st)
    private val lock = Object()
    private var avail = false
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    var hasFrame = false
    private var curPts = -1L
    val mat = FloatArray(16)

    init {
        st.setOnFrameAvailableListener({
            synchronized(lock) { avail = true; lock.notifyAll() }
        }, cb)
        ex.setDataSource(m.path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) { track = i; break }
        }
        check(track >= 0) { "no video track" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        if (!fmt.containsKey(MediaFormat.KEY_ROTATION) && m.rotation != 0) {
            fmt.setInteger(MediaFormat.KEY_ROTATION, m.rotation)
        }
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, surface, null, 0)
        codec.start()
    }

    private fun awaitFrame() {
        synchronized(lock) {
            val until = SystemClock.uptimeMillis() + 1000
            while (!avail) {
                val left = until - SystemClock.uptimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
            avail = false
        }
    }

    /** Makes the texture show the frame at s seconds of the source. */
    fun frameAt(s: Float) {
        val target = (s * 1_000_000).toLong()
        if (hasFrame && curPts + 17_000 >= target) return
        var guard = 0
        while (!outputDone && guard < 2000) {
            guard++
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(5_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 5_000)
            if (oi >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts + 17_000 >= target) {
                    codec.releaseOutputBuffer(oi, true)
                    awaitFrame()
                    st.updateTexImage()
                    st.getTransformMatrix(mat)
                    curPts = pts
                    hasFrame = true
                    return
                }
                codec.releaseOutputBuffer(oi, false)
            }
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { ex.release() } catch (_: Throwable) {}
        surface.release()
        st.release()
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

// ====================================================================
/** Media id of the built-in sample picture (effect cards). */
private const val SAMPLE = -1

class ProjectEngine(
    private val activity: Activity,
    private val textures: TextureRegistry,
) : MethodChannel.MethodCallHandler {

    private val tag = "FlyProject"

    init { TextArt.init(activity) }
    private val main = Handler(Looper.getMainLooper())
    private val fps = 30

    private val media = HashMap<Int, Media>()
    private var nextMedia = 1
    @Volatile private var timeline: List<TClip> = emptyList()
    @Volatile private var look = PLook()
    @Volatile private var ratio = "9:16"
    @Volatile private var outW = 720
    @Volatile private var outH = 1280

    // Preview (GL thread).
    private var glThread: HandlerThread? = null
    private var gl: Handler? = null
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var pSurface: Surface? = null
    private var egl: LabEgl? = null
    private var renderer: ProjectRenderer? = null
    private val players = LinkedHashMap<Int, VPlayer>()
    @Volatile private var running = false
    @Volatile private var playing = false
    @Volatile private var curT = 0f
    private var playWall = 0L
    private var playT0 = 0f

    // Audio (E2): music bars + the clips' own sound on / off.
    @Volatile private var music: List<AClip> = emptyList()
    @Volatile private var original = true
    private val musicPreview = MusicPreview()

    // Export.
    @Volatile private var progress = 0.0
    @Volatile private var exporting = false

    private fun total(tl: List<TClip> = timeline): Float = tl.fold(0f) { s, c -> s + c.dur }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "addMedia" -> addMedia(call, result)
            "setTimeline" -> {
                val list = call.argument<List<Map<String, Any>>>("clips") ?: emptyList()
                timeline = list.mapNotNull { mp ->
                    val m = media[(mp["media"] as Number).toInt()] ?: return@mapNotNull null
                    TClip((mp["id"] as Number).toInt(), m,
                        (mp["dur"] as Number).toFloat().coerceAtLeast(0.2f),
                        (mp["template"] as? String) ?: "plain",
                        ((mp["from"] as? Number)?.toFloat() ?: 0f).coerceAtLeast(0f),
                        ((mp["speed"] as? Number)?.toFloat() ?: 1f).coerceIn(0.25f, 4f),
                        ((mp["vol"] as? Number)?.toFloat() ?: 1f).coerceIn(0f, 2f))
                }
                val tot = total()
                if (curT > tot) curT = maxOf(0f, tot - 0.01f)
                result.success(null)
            }
            "setLook" -> {
                val a = call.argument<List<Double>>("adj")
                val bg = call.argument<List<Double>>("bgColor")
                look = PLook(
                    adj = if (a != null && a.size >= 8) FloatArray(8) { a[it].toFloat() } else look.adj,
                    fx = parseFx(call) ?: look.fx,
                    fit = call.argument<Boolean>("fit") ?: look.fit,
                    bgMode = call.argument<Int>("bgMode") ?: look.bgMode,
                    bgColor = if (bg != null && bg.size >= 3) FloatArray(3) { bg[it].toFloat() } else look.bgColor,
                    texts = look.texts,
                )
                result.success(null)
            }
            "setRatio" -> {
                ratio = call.argument<String>("ratio") ?: "9:16"
                val (w, h) = projectSize(ratio)
                outW = w
                outH = h
                entry?.surfaceTexture()?.setDefaultBufferSize(w, h)
                result.success(mapOf("textureId" to (entry?.id() ?: -1L), "width" to w, "height" to h))
            }
            "preview" -> startPreview(result)
            "play" -> {
                gl?.post {
                    val tot = total()
                    if (curT >= tot - 0.05f) curT = 0f
                    playT0 = curT
                    playWall = SystemClock.uptimeMillis()
                    playing = tot > 0f
                }
                result.success(null)
            }
            "setAudio" -> {
                music = AClip.parse(call.argument<List<Map<String, Any>>>("music"))
                original = call.argument<Boolean>("original") ?: original
                result.success(null)
            }
            "pickAudio" -> AudioPick.pick(activity, result)
            "setTexts" -> {
                val l = look
                look = PLook(l.adj, l.fx, l.fit, l.bgMode, l.bgColor,
                    TItem.parseAll(call.argument<List<Map<String, Any>>>("texts")))
                result.success(null)
            }
            "textSize" -> {
                val mp = call.argument<Map<String, Any>>("text")
                Thread {
                    val t = mp?.let { TItem.parse(it) }
                    val sz = try { t?.let { TextArt.size(it) } } catch (e: Throwable) { null }
                    main.post { result.success(sz?.let { listOf(it.first, it.second) }) }
                }.start()
            }
            "stickerPreviews" -> Thread {
                val out = try { TextArt.stickerPreviews() } catch (e: Throwable) { emptyList() }
                main.post { result.success(out) }
            }.start()
            "fontPreviews" -> Thread {
                val out = try { TextArt.previews() } catch (e: Throwable) { emptyList() }
                main.post { result.success(out) }
            }.start()
            "pause" -> {
                gl?.post { playing = false }
                result.success(null)
            }
            "seek" -> {
                val t = (call.argument<Double>("t") ?: 0.0).toFloat()
                gl?.post {
                    playing = false
                    curT = t.coerceIn(0f, maxOf(0f, total() - 0.01f))
                }
                result.success(null)
            }
            "position" -> result.success(mapOf(
                "t" to curT.toDouble(), "playing" to playing, "total" to total().toDouble()))
            "thumbs" -> thumbs((call.argument<Number>("media") ?: 0).toInt(), result)
            "export" -> export(result)
            "progress" -> result.success(progress)
            "dispose" -> {
                stopPreview()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    // ------------------------------------------------------------ media
    private fun addMedia(call: MethodCall, result: MethodChannel.Result) {
        val items = call.argument<List<Map<String, Any>>>("items") ?: emptyList()
        Thread {
            val out = ArrayList<Map<String, Any>>()
            val added = ArrayList<Media>()
            for (it in items) {
                val path = it["path"] as? String ?: continue
                val video = it["video"] as? Boolean ?: false
                try {
                    val m = if (video) probeVideo(path) else probePhoto(path)
                    added += m
                    out += mapOf(
                        "media" to m.id, "video" to m.video,
                        "dur" to (if (m.video) m.srcDur else 3f).toDouble(),
                        "aspect" to m.aspect.toDouble(),
                        "thumb" to jpeg(m.poster, 200),
                    )
                } catch (e: Throwable) {
                    Log.e(tag, "addMedia $path", e)
                }
            }
            main.post {
                for (m in added) media[m.id] = m
                result.success(out)
            }
        }.start()
    }

    @Synchronized private fun newId(): Int = nextMedia++

    private fun probePhoto(path: String): Media {
        val bm = LabImages.decodeUpright(path, 1280)
        return Media(newId(), false, path, bm, 0f, bm.width.toFloat() / bm.height, 0)
    }

    private fun probeVideo(path: String): Media {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Can't read the video")
            val poster = LabImages.fitLongSide(frame, 1280)
            return Media(newId(), true, path, poster, ms / 1000f,
                poster.width.toFloat() / poster.height, rot)
        } finally {
            r.release()
        }
    }

    private fun jpeg(bm: Bitmap, longSide: Int): ByteArray {
        val small = LabImages.fitLongSide(bm, longSide, recycleSource = false)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (small !== bm) small.recycle()
        return bos.toByteArray()
    }

    // ---------------------------------------------------------- preview
    private fun startPreview(result: MethodChannel.Result) {
        if (glThread == null) {
            val t = HandlerThread("LabProject").also { it.start() }
            glThread = t
            gl = Handler(t.looper)
            val e = textures.createSurfaceTexture()
            entry = e
            e.surfaceTexture().setDefaultBufferSize(outW, outH)
            pSurface = Surface(e.surfaceTexture())
        }
        val h = gl!!
        val s = pSurface!!
        h.post {
            try {
                if (egl == null) {
                    egl = LabEgl(false).also { it.attach(s) }
                    renderer = ProjectRenderer()
                }
                if (!running) {
                    running = true
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

    private fun player(m: Media): VPlayer {
        players[m.id]?.let { return it }
        // Keep at most 3 players (each holds a hardware decoder).
        while (players.size >= 3) {
            val k = players.keys.first()
            players.remove(k)?.release()
        }
        val p = VPlayer(m, gl!!)
        players[m.id] = p
        return p
    }

    /** The clip at project time t, its start, and its index. */
    private fun clipAt(tl: List<TClip>, t: Float): Triple<TClip, Float, Int>? {
        var start = 0f
        for ((i, c) in tl.withIndex()) {
            if (t < start + c.dur || i == tl.size - 1) return Triple(c, start, i)
            start += c.dur
        }
        return null
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val h = gl ?: return
            val t0 = SystemClock.uptimeMillis()
            try {
                val tl = timeline
                val tot = total(tl)
                if (playing) {
                    curT = playT0 + (t0 - playWall) / 1000f
                    if (curT >= tot) {
                        curT = maxOf(0f, tot - 0.01f)
                        playing = false
                    }
                }
                val r = renderer
                val e = egl
                if (r != null && e != null) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    val at = clipAt(tl, curT)
                    if (at == null) {
                        GLES20.glViewport(0, 0, outW, outH)
                        GLES20.glClearColor(0.07f, 0.07f, 0.08f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    } else {
                        val (c, start, k) = at
                        val lt = (curT - start).coerceIn(0f, c.dur)
                        val src = previewSource(c, lt)
                        r.drawFrame(src, c.template, lt, c.dur, curT, tot, look,
                            k == 0, k == tl.size - 1, outW, outH)
                    }
                    e.swap()
                }
                musicPreview.sync(music, curT, playing)
            } catch (ex: Throwable) {
                Log.e(tag, "frame", ex)
            }
            val spent = SystemClock.uptimeMillis() - t0
            h.postDelayed(this, (33 - spent).coerceAtLeast(1))
        }
    }

    private fun previewSource(c: TClip, lt: Float): FrameSrc {
        val m = c.media
        val r = renderer!!
        // Pause the players of other clips.
        for ((id, p) in players) if (id != m.id && p.mp.isPlaying) p.mp.pause()
        if (!m.video) return FrameSrc(r.photoTexture(m), false, m.aspect, null)
        val p = try { player(m) } catch (e: Throwable) {
            Log.e(tag, "player", e)
            return FrameSrc(r.photoTexture(m), false, m.aspect, null)
        }
        val s = (c.from + lt * c.speed).coerceIn(0f, maxOf(0f, m.srcDur - 0.05f))
        val v = if (original) c.vol.coerceAtMost(1f) else 0f
        try { p.mp.setVolume(v, v) } catch (_: Throwable) {}
        if (playing) {
            if (!p.mp.isPlaying) {
                p.seek(s)
                p.startAt(c.speed)
            } else {
                if (p.speed != c.speed) p.startAt(c.speed)
                if (abs(p.mp.currentPosition / 1000f - s) > 0.35f * maxOf(1f, c.speed)) p.seek(s)
            }
        } else {
            if (p.mp.isPlaying) p.mp.pause()
            if (abs(p.lastSeek - s) > 0.03f) p.seek(s)
        }
        p.update()
        return if (p.hasFrame) FrameSrc(p.tex, true, m.aspect, p.mat)
               else FrameSrc(r.photoTexture(m), false, m.aspect, null)
    }

    private fun stopPreview() {
        running = false
        playing = false
        val h = gl
        val t = glThread
        h?.post {
            for (p in players.values) p.release()
            players.clear()
            musicPreview.release()
            try { renderer?.release() } catch (_: Throwable) {}
            try { egl?.release() } catch (_: Throwable) {}
            egl = null
            renderer = null
            main.post {
                try { pSurface?.release() } catch (_: Throwable) {}
                pSurface = null
                try { entry?.release() } catch (_: Throwable) {}
                entry = null
                for (m in media.values) m.poster.recycle()
                media.clear()
            }
            t?.quitSafely()
        }
        gl = null
        glThread = null
    }

    // ----------------------------------------------------------- thumbs
    /** Every template AND effect on this media's picture, as small JPEG
     *  cards (made once per media; shaders stay compiled afterwards). */
    /** setLook "fx": [{id, start, end, x, y, r, placed}] (or the old "effects": [id]). */
    private fun parseFx(call: MethodCall): List<FxClip>? {
        val list = call.argument<List<Map<String, Any>>>("fx")
        if (list != null) {
            return list.mapNotNull { mp ->
                val id = mp["id"] as? String ?: return@mapNotNull null
                fun f(k: String, d: Float) = (mp[k] as? Number)?.toFloat() ?: d
                FxClip(id, f("start", 0f), f("end", 1e9f), f("x", 0.5f), f("y", 0.5f),
                    f("r", 0.3f).coerceIn(0.05f, 1.2f), mp["placed"] as? Boolean ?: false)
            }
        }
        val ids = call.argument<List<String>>("effects") ?: return null
        return ids.map { FxClip(it, 0f, 1e9f) }
    }

    /** The picture behind the "Sample" effect cards (media id -1): drawn here,
     *  no photo of a real person is shipped in the app. */
    private val sample: Media by lazy { Media(SAMPLE, false, "", sampleBitmap(), 0f, 720f / 1280f, 0) }

    private fun sampleBitmap(): Bitmap {
        val w = 720
        val h = 1280
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bm)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF2B1B4A.toInt(), 0xFF8E4B8F.toInt(), 0xFFFF8A80.toInt(), 0xFFFFC98B.toInt()),
            floatArrayOf(0f, 0.38f, 0.66f, 0.8f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = android.graphics.RadialGradient(w * 0.5f, h * 0.7f, 260f,
            intArrayOf(0xFFFFF3C4.toInt(), 0x00FFE0A0), null, android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(w * 0.5f, h * 0.7f, 260f, p)
        p.shader = null
        p.color = 0xFFFFF1D0.toInt()
        cv.drawCircle(w * 0.5f, h * 0.7f, 95f, p)
        val rnd = java.util.Random(7)
        for (i in 0 until 14) {
            p.color = android.graphics.Color.argb(40 + rnd.nextInt(50), 255, 220 + rnd.nextInt(35), 230)
            cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.6f, 20f + rnd.nextFloat() * 60f, p)
        }
        p.color = 0xFF3A2350.toInt()
        val far = android.graphics.Path()
        far.moveTo(0f, h * 0.76f)
        far.cubicTo(w * 0.25f, h * 0.70f, w * 0.45f, h * 0.80f, w * 0.7f, h * 0.73f)
        far.cubicTo(w * 0.85f, h * 0.69f, w * 0.95f, h * 0.74f, w.toFloat(), h * 0.72f)
        far.lineTo(w.toFloat(), h.toFloat()); far.lineTo(0f, h.toFloat()); far.close()
        cv.drawPath(far, p)
        p.color = 0xFF1E1230.toInt()
        val near = android.graphics.Path()
        near.moveTo(0f, h * 0.86f)
        near.cubicTo(w * 0.3f, h * 0.80f, w * 0.6f, h * 0.90f, w.toFloat(), h * 0.83f)
        near.lineTo(w.toFloat(), h.toFloat()); near.lineTo(0f, h.toFloat()); near.close()
        cv.drawPath(near, p)
        return bm
    }

    private fun thumbs(mediaId: Int, result: MethodChannel.Result) {
        val h = gl ?: return result.error("no_preview", "No preview", null)
        val m = (if (mediaId == SAMPLE) sample else media[mediaId])
            ?: return result.error("no_media", "No media", null)
        h.post {
            val fbo = IntArray(1)
            val tex = IntArray(1)
            try {
                val r = renderer ?: error("no renderer")
                val tw = 160
                val th = (160f * outH / outW).toInt().coerceIn(90, 300)
                GLES20.glGenFramebuffers(1, fbo, 0)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex[0], 0)
                val buf = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
                val px = IntArray(tw * th)
                val src = FrameSrc(r.photoTexture(m), false, m.aspect, null)
                val plain = PLook(fit = look.fit, bgMode = look.bgMode, bgColor = look.bgColor)
                val out = HashMap<String, ByteArray>()
                for (id in PhotoTemplates.allIds) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                    r.drawFrame(src, id, PhotoTemplates.thumbTime(id), 8f, 2.6f, 8f, plain,
                        false, false, tw, th)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                    buf.rewind()
                    GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    for (y in 0 until th) {
                        val row = (th - 1 - y) * tw * 4
                        for (x in 0 until tw) {
                            val i = row + x * 4
                            px[y * tw + x] = (0xFF shl 24) or
                                ((buf.get(i).toInt() and 0xFF) shl 16) or
                                ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                                (buf.get(i + 2).toInt() and 0xFF)
                        }
                    }
                    val bm = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
                    val bos = ByteArrayOutputStream()
                    bm.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    bm.recycle()
                    out[id] = bos.toByteArray()
                }
                main.post { result.success(out) }
            } catch (e: Throwable) {
                Log.e(tag, "thumbs", e)
                main.post { result.error("thumbs_failed", e.toString(), null) }
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (fbo[0] != 0) GLES20.glDeleteFramebuffers(1, fbo, 0)
                if (tex[0] != 0) GLES20.glDeleteTextures(1, tex, 0)
            }
        }
    }

    // ----------------------------------------------------------- export
    private fun export(result: MethodChannel.Result) {
        val tl = timeline
        if (tl.isEmpty()) return result.error("empty", "Add photos or videos first", null)
        if (exporting) return result.error("busy", "Already exporting", null)
        exporting = true
        progress = 0.0
        gl?.post { playing = false }
        val lk = look
        val w = outW
        val h = outH
        val mus = music
        val orig = original
        Thread {
            val started = SystemClock.uptimeMillis()
            val out = File(activity.cacheDir, "flyproject_${System.currentTimeMillis()}.mp4")
            try {
                // Sound first (it is small), then the video frames.
                AudioMix.report = ""
                val audio = try {
                    val mix = AudioMix.mix(tl, mus, orig, total(tl))
                    progress = 0.03
                    mix?.let { AudioMix.encode(it) }
                } catch (e: Throwable) {
                    Log.e(tag, "audio", e)
                    AudioMix.report += " | audio error: ${e.javaClass.simpleName} ${e.message ?: ""}".take(100)
                    null
                }
                val frames = encode(tl, lk, w, h, out, audio)
                val uri = LabImages.saveVideoToGallery(activity, out)
                val ms = SystemClock.uptimeMillis() - started
                val bytes = out.length()
                out.delete()
                main.post {
                    result.success(mapOf("uri" to uri, "ms" to ms, "bytes" to bytes, "frames" to frames,
                        "audio" to (if (audio != null) "Sound: " else "NO sound: ") + AudioMix.report))
                }
            } catch (e: Throwable) {
                Log.e(tag, "export", e)
                out.delete()
                main.post { result.error("export_failed", e.toString(), null) }
            } finally {
                exporting = false
            }
        }.start()
    }

    private fun encode(tl: List<TClip>, lk: PLook, w: Int, h: Int, out: File, audio: EncodedAudio?): Int {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
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
        val cbThread = HandlerThread("LabProjectDecode").also { it.start() }
        val cb = Handler(cbThread.looper)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val tot = total(tl)
        val count = (tot * fps).toInt().coerceAtLeast(1)

        fun drain(end: Boolean) {
            if (end) codec.signalEndOfInputStream()
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return else continue
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    val aTrack = if (audio != null) muxer.addTrack(audio.format) else -1
                    muxer.start()
                    muxing = true
                    if (audio != null) {
                        AudioMix.writeAll(muxer, aTrack, audio)
                        AudioMix.report += " | muxed"
                    }
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

        var dec: VDecoder? = null
        var decClip = -1
        var r: ProjectRenderer? = null
        try {
            egl.attach(input)
            r = ProjectRenderer()
            for (i in 0 until count) {
                val t = i.toFloat() / fps
                val (c, start, k) = clipAt(tl, t) ?: break
                val lt = (t - start).coerceIn(0f, c.dur)
                val m = c.media
                val src = if (m.video) {
                    if (decClip != c.id) {
                        dec?.release()
                        dec = VDecoder(m, cb)
                        decClip = c.id
                    }
                    val d = dec!!
                    d.frameAt((c.from + lt * c.speed).coerceAtMost(maxOf(0f, m.srcDur - 0.02f)))
                    if (d.hasFrame) FrameSrc(d.tex, true, m.aspect, d.mat)
                    else FrameSrc(r.photoTexture(m), false, m.aspect, null)
                } else FrameSrc(r.photoTexture(m), false, m.aspect, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                r.drawFrame(src, c.template, lt, c.dur, t, tot, lk, k == 0, k == tl.size - 1, w, h)
                egl.swap(i * 1_000_000_000L / fps)
                drain(false)
                progress = (i + 1).toDouble() / count
            }
            drain(true)
        } finally {
            try { dec?.release() } catch (_: Throwable) {}
            try { r?.release() } catch (_: Throwable) {}
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Throwable) {}
            muxer.release()
            egl.release()
            input.release()
            cbThread.quitSafely()
        }
        return count
    }
}
