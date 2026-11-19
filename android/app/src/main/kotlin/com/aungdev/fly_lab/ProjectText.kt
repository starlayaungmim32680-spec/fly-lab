package com.aungdev.fly_lab

// Fly Lab PROJECT EDITOR - E3 Text (19 Nov 2026). Everything on the phone.
//
//   TItem      one text on the text track: words, start..end, where it sits
//              (x, y 0..1 from the top-left), size, turn, font, style,
//              colours and animations (in / loop / out)
//   TextArt    draws a TItem into a Bitmap with Android's own text layout,
//              so Myanmar (Unicode) is shaped right; fonts in
//              assets/fonts (SIL OFL, see assets/fonts/OFL.txt) - letters a
//              font does not have come from the phone's system fonts
//   TextLayer  puts the texts on the frame with the GPU (blended quads),
//              moving them with their animations; used by preview AND export
//
// Size: scale 1 = the bitmap's pixels on a 1280-high frame (text 96 px).

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class TItem(
    val uid: Int, val text: String, val start: Float, val end: Float,
    val x: Float, val y: Float, val scale: Float, val rot: Float,
    val font: Int, val style: Int, val color: Int, val color2: Int,
    val animIn: Int, val animLoop: Int, val animOut: Int,
    /** E4: 0 text, 1 emoji, 2 drawn sticker (text = its name), 3 photo (path). */
    val kind: Int = 0, val path: String = "",
) {
    /** Everything that changes the picture (not where it is). */
    val key: String get() = "$kind|$font|$style|$color|$color2|$path|$text"

    companion object {
        fun parse(mp: Map<String, Any>): TItem? {
            fun f(k: String, d: Float) = (mp[k] as? Number)?.toFloat() ?: d
            fun i(k: String, d: Int) = (mp[k] as? Number)?.toLong()?.toInt() ?: d
            val text = (mp["text"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return TItem(i("uid", 0), text, f("start", 0f), f("end", 3f),
                f("x", 0.5f), f("y", 0.5f), f("scale", 1f).coerceIn(0.1f, 6f), f("rot", 0f),
                i("font", 0), i("style", 0), i("color", -1), i("color2", 0xFF000000.toInt()),
                i("animIn", 0), i("animLoop", 0), i("animOut", 0),
                i("kind", 0), mp["path"] as? String ?: "")
        }

        fun parseAll(list: List<Map<String, Any>>?): List<TItem> = list?.mapNotNull { parse(it) } ?: emptyList()
    }
}

object TextArt {
    const val SIZE = 96f
    /** Font files in assets/fonts; index 0 = the phone's own font (bold). */
    val FONTS = listOf(
        "", "padauk.ttf", "padauk_bold.ttf", "noto_sans_mm.ttf", "noto_sans_mm_black.ttf",
        "noto_serif_mm.ttf", "pacifico.ttf", "bebas_neue.ttf", "lobster.ttf",
        "permanent_marker.ttf", "dancing_script.ttf", "bangers.ttf",
    )
    private var ctx: Context? = null
    private val faces = HashMap<Int, Typeface>()

    fun init(c: Context) { ctx = c.applicationContext }

    @Synchronized fun typeface(i: Int): Typeface {
        faces[i]?.let { return it }
        val file = FONTS.getOrNull(i) ?: ""
        val t = if (file.isEmpty()) Typeface.DEFAULT_BOLD else try {
            Typeface.createFromAsset(ctx!!.assets, "fonts/$file")
        } catch (e: Throwable) {
            Log.e("FlyText", "font $file", e)
            Typeface.DEFAULT_BOLD
        }
        faces[i] = t
        return t
    }

    private fun layout(text: String, paint: TextPaint): StaticLayout {
        var w = 1
        for (line in text.split('\n')) w = max(w, ceil(Layout.getDesiredWidth(line, paint)).toInt())
        w = min(w, 1400)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(true)
            .build()
    }

    /** The picture of an item: words, emoji, a drawn sticker or a photo. */
    fun render(t: TItem): Bitmap = when (t.kind) {
        2 -> Stickers.draw(t.text, t.color, t.color2)
        3 -> photo(t)
        else -> words(t)
    }

    /** E4 overlay photo with a frame: 0 none, 1 border, 2 round corners, 3 circle. */
    private fun photo(t: TItem): Bitmap {
        val src = LabImages.decodeUpright(t.path, 1024)
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w + 40, h + 40, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = RectF(20f, 20f, 20f + w, 20f + h)
        when (t.style) {
            1 -> {
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.color2 }
                c.drawRect(RectF(4f, 4f, w + 36f, h + 36f), bp)
                c.drawBitmap(src, null, r, p)
            }
            2, 3 -> {
                val rad = if (t.style == 3) min(w, h) / 2f else min(w, h) * 0.12f
                val rr = if (t.style == 3) {
                    val d = min(w, h).toFloat()
                    RectF(20f + (w - d) / 2f, 20f + (h - d) / 2f, 20f + (w + d) / 2f, 20f + (h + d) / 2f)
                } else r
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.color2 }
                c.drawRoundRect(RectF(rr.left - 10f, rr.top - 10f, rr.right + 10f, rr.bottom + 10f),
                    rad + 10f, rad + 10f, bp)
                p.shader = android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(android.graphics.Matrix().apply { setTranslate(20f, 20f) }) }
                c.drawRoundRect(rr, rad, rad, p)
            }
            else -> c.drawBitmap(src, null, r, p)
        }
        src.recycle()
        return out
    }

    /** The words drawn in their style; transparent around them. */
    private fun words(t: TItem): Bitmap {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = typeface(t.font)
        p.textSize = SIZE
        p.color = t.color
        val lay = layout(t.text, p)
        val pad = 40
        val bw = lay.width + pad * 2
        val bh = lay.height + pad * 2
        val bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        fun draw(dx: Float = 0f, dy: Float = 0f) {
            c.save()
            c.translate(pad + dx, pad + dy)
            lay.draw(c)
            c.restore()
        }
        when (t.style) {
            1 -> { // outline
                p.style = Paint.Style.STROKE
                p.strokeWidth = 12f
                p.strokeJoin = Paint.Join.ROUND
                p.color = t.color2
                draw()
                p.style = Paint.Style.FILL
                p.color = t.color
                draw()
            }
            2 -> { // box behind
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.color2 }
                c.drawRoundRect(RectF(pad - 22f, pad - 8f, pad + lay.width + 22f, pad + lay.height + 8f),
                    26f, 26f, bp)
                draw()
            }
            3 -> { // neon glow
                p.color = t.color
                p.setShadowLayer(26f, 0f, 0f, t.color)
                draw()
                p.setShadowLayer(10f, 0f, 0f, t.color)
                p.color = blend(t.color, Color.WHITE, 0.6f)
                draw()
                p.clearShadowLayer()
            }
            4 -> { // 3D shadow
                p.color = t.color2
                for (k in 7 downTo 1) draw(k * 1.2f, k * 1.2f)
                p.color = t.color
                draw()
            }
            5 -> { // gradient
                p.style = Paint.Style.STROKE
                p.strokeWidth = 6f
                p.color = 0x99000000.toInt()
                draw()
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, 0f, 0f, lay.height.toFloat(), t.color, t.color2, Shader.TileMode.CLAMP)
                draw()
                p.shader = null
            }
            else -> { // plain, soft shadow
                p.setShadowLayer(8f, 0f, 3f, 0x99000000.toInt())
                draw()
                p.clearShadowLayer()
            }
        }
        return bm
    }

    private fun blend(a: Int, b: Int, k: Float): Int = Color.argb(
        Color.alpha(a),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())

    /** Bitmap size of a text (for the box the app draws around it). */
    fun size(t: TItem): Pair<Int, Int> {
        val bm = render(t)
        val s = bm.width to bm.height
        bm.recycle()
        return s
    }

    /** Small "မြန်မာ Aa" cards, one per font, for the font picker. */
    /** Small cards of the drawn stickers, for the sticker picker. */
    fun stickerPreviews(): List<ByteArray> = Stickers.NAMES.map { n ->
        val bm = Stickers.draw(n, Stickers.defaultColor(n), Color.WHITE)
        val bos = ByteArrayOutputStream()
        Bitmap.createScaledBitmap(bm, bm.width / 2, bm.height / 2, true)
            .compress(Bitmap.CompressFormat.PNG, 100, bos)
        bm.recycle()
        bos.toByteArray()
    }

    fun previews(): List<ByteArray> = FONTS.indices.map { i ->
        val bm = render(TItem(0, "မြန်မာ Aa", 0f, 1f, 0.5f, 0.5f, 1f, 0f, i, 0,
            Color.WHITE, Color.BLACK, 0, 0, 0))
        val bos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bm.recycle()
        bos.toByteArray()
    }
}

/** Draws texts onto the frame that is bound (GL thread of the renderer). */
class TextLayer {
    private class Tex(val id: Int, val w: Int, val h: Int, var used: Long)

    private val cache = HashMap<String, Tex>()
    private var prog = 0
    private var frameNo = 0L
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }

    private val vs = """
attribute vec2 aPos;
uniform vec2 uC;
uniform vec2 uHalf;
uniform float uRot;
uniform vec2 uFrame;
varying vec2 vT;
void main() {
    vec2 p = aPos * uHalf;
    float c = cos(uRot);
    float s = sin(uRot);
    p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
    gl_Position = vec4((uC + p) / uFrame * 2.0 - 1.0, 0.0, 1.0);
    vT = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
}
"""
    private val fs = """
precision mediump float;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uReveal;
varying vec2 vT;
void main() {
    vec4 c = texture2D(uTex, vT);
    float r = 1.0 - smoothstep(uReveal, uReveal + 0.03, vT.x);
    gl_FragColor = c * uAlpha * r;
}
"""

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "text shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun program(): Int {
        if (prog != 0) return prog
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glLinkProgram(p)
        prog = p
        return p
    }

    private fun texture(t: TItem): Tex {
        cache[t.key]?.let { it.used = frameNo; return it }
        val bm = TextArt.render(t)
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0)
        val tex = Tex(id[0], bm.width, bm.height, frameNo)
        bm.recycle()
        cache[t.key] = tex
        // Forget pictures nobody has drawn for a while.
        if (cache.size > 30) {
            val old = cache.entries.filter { frameNo - it.value.used > 60 }
            for (e in old) {
                GLES20.glDeleteTextures(1, intArrayOf(e.value.id), 0)
                cache.remove(e.key)
            }
        }
        return tex
    }

    private fun backOut(k: Float): Float { val s = 1.7f; val x = k - 1f; return 1f + (s + 1f) * x * x * x + s * x * x }
    private fun easeOut(k: Float): Float = 1f - (1f - k) * (1f - k)
    private fun bounceOut(k: Float): Float {
        val n = 7.5625f; val d = 2.75f
        return when {
            k < 1f / d -> n * k * k
            k < 2f / d -> { val x = k - 1.5f / d; n * x * x + 0.75f }
            k < 2.5f / d -> { val x = k - 2.25f / d; n * x * x + 0.9375f }
            else -> { val x = k - 2.625f / d; n * x * x + 0.984375f }
        }
    }

    fun draw(items: List<TItem>, pt: Float, w: Int, h: Int) {
        if (items.isEmpty()) return
        frameNo++
        val p = program()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(p)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uFrame"), w.toFloat(), h.toFloat())
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        for (t in items) {
            if (pt < t.start || pt >= t.end) continue
            val lt = pt - t.start
            val len = t.end - t.start
            val inD = min(0.5f, len * 0.4f)
            val outD = min(0.4f, len * 0.3f)
            val ki = (lt / inD).coerceIn(0f, 1f)
            val ko = ((len - lt) / outD).coerceIn(0f, 1f)
            var a = 1f; var sc = 1f; var dy = 0f; var rot = t.rot; var reveal = 1.05f
            when (t.animIn) {
                1 -> a *= ki
                2 -> { sc *= 0.3f + 0.7f * backOut(ki); a *= min(1f, ki * 3f) }
                3 -> { dy += (1f - easeOut(ki)) * 0.08f; a *= ki }
                4 -> reveal = (lt / min(1.6f, len * 0.6f)).coerceIn(0f, 1.05f)
                5 -> { dy -= (1f - bounceOut(ki)) * 0.22f; a *= min(1f, ki * 4f) }
                6 -> { sc *= 1f + (1f - easeOut(ki)) * 0.8f; a *= ki }
            }
            when (t.animLoop) {
                1 -> sc *= 1f + 0.06f * sin(lt * 5f)
                2 -> rot += 0.07f * sin(lt * 7f)
                3 -> dy += 0.012f * sin(lt * 2.5f)
                4 -> a *= 0.65f + 0.35f * (0.5f + 0.5f * cos(lt * 6f))
            }
            when (t.animOut) {
                1 -> a *= ko
                2 -> { sc *= easeOut(ko); a *= ko }
                3 -> { dy += (1f - ko) * 0.08f; a *= ko }
            }
            if (a <= 0.003f || sc <= 0.01f) continue
            val tex = texture(t)
            val px = t.scale * sc * h / 1280f
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uC"),
                t.x * w, (1f - (t.y + dy)) * h)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uHalf"), tex.w * px / 2f, tex.h * px / 2f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRot"), -rot * PI.toFloat() / 180f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uAlpha"), a.coerceIn(0f, 1f))
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uReveal"), reveal)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        for (t in cache.values) GLES20.glDeleteTextures(1, intArrayOf(t.id), 0)
        cache.clear()
        if (prog != 0) GLES20.glDeleteProgram(prog)
        prog = 0
    }
}

/** E4 drawn stickers: shapes made here (no image files, no licences). */
object Stickers {
    val NAMES = listOf(
        "heart", "star", "sparkle", "arrow", "ring", "bubble", "crown", "bolt",
        "cloud", "badge_new", "badge_love", "badge_wow", "badge_omg", "badge_sale",
        "check", "pin",
    )

    fun defaultColor(n: String): Int = when (n) {
        "heart", "badge_love", "pin" -> 0xFFFF3B5C.toInt()
        "star", "crown", "bolt", "badge_wow" -> 0xFFFFC93C.toInt()
        "sparkle" -> 0xFFFFF3A0.toInt()
        "arrow", "ring" -> 0xFFFF9F43.toInt()
        "bubble", "cloud" -> 0xFFFFFFFF.toInt()
        "badge_new", "check" -> 0xFF2ED573.toInt()
        "badge_omg" -> 0xFFA55EEA.toInt()
        "badge_sale" -> 0xFFFF3B5C.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    private fun star(cx: Float, cy: Float, ro: Float, ri: Float, n: Int): Path {
        val p = Path()
        for (k in 0 until n * 2) {
            val r = if (k % 2 == 0) ro else ri
            val a = -Math.PI / 2 + k * Math.PI / n
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    /** One sticker: filled with col, a white rim and a soft shadow. */
    fun draw(name: String, col: Int, rim: Int): Bitmap {
        val badge = name.startsWith("badge_")
        val w = if (badge) 460 else 320
        val h = if (badge) 220 else 320
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val cx = w / 2f
        val cy = h / 2f
        val path = Path()
        var stroke = false
        when (name) {
            "heart" -> {
                path.moveTo(cx, 268f)
                path.cubicTo(20f, 160f, 40f, 40f, cx - 70f, 52f)
                path.cubicTo(cx - 30f, 56f, cx, 90f, cx, 110f)
                path.cubicTo(cx, 90f, cx + 30f, 56f, cx + 70f, 52f)
                path.cubicTo(w - 40f, 40f, w - 20f, 160f, cx, 268f)
                path.close()
            }
            "star" -> path.addPath(star(cx, cy + 10f, 140f, 60f, 5))
            "sparkle" -> path.addPath(star(cx, cy, 140f, 34f, 4))
            "arrow" -> {
                path.moveTo(30f, 130f); path.lineTo(180f, 130f); path.lineTo(180f, 60f)
                path.lineTo(295f, 160f); path.lineTo(180f, 260f); path.lineTo(180f, 190f)
                path.lineTo(30f, 190f); path.close()
            }
            "ring" -> { path.addCircle(cx, cy, 115f, Path.Direction.CW); stroke = true }
            "bubble" -> {
                path.addRoundRect(RectF(24f, 40f, 296f, 220f), 60f, 60f, Path.Direction.CW)
                val tail = Path()
                tail.moveTo(80f, 200f); tail.lineTo(60f, 290f); tail.lineTo(150f, 210f); tail.close()
                path.op(tail, Path.Op.UNION)
            }
            "crown" -> {
                path.moveTo(40f, 250f); path.lineTo(40f, 90f); path.lineTo(105f, 160f)
                path.lineTo(cx, 60f); path.lineTo(215f, 160f); path.lineTo(280f, 90f)
                path.lineTo(280f, 250f); path.close()
            }
            "bolt" -> {
                path.moveTo(190f, 20f); path.lineTo(70f, 180f); path.lineTo(150f, 180f)
                path.lineTo(120f, 300f); path.lineTo(250f, 130f); path.lineTo(170f, 130f); path.close()
            }
            "cloud" -> {
                path.addCircle(110f, 180f, 70f, Path.Direction.CW)
                path.op(Path().apply { addCircle(180f, 140f, 85f, Path.Direction.CW) }, Path.Op.UNION)
                path.op(Path().apply { addCircle(240f, 190f, 60f, Path.Direction.CW) }, Path.Op.UNION)
                path.op(Path().apply { addRoundRect(RectF(60f, 180f, 280f, 250f), 35f, 35f, Path.Direction.CW) }, Path.Op.UNION)
            }
            "check" -> {
                path.moveTo(50f, 170f); path.lineTo(120f, 240f); path.lineTo(275f, 80f)
                stroke = true
            }
            "pin" -> {
                path.addCircle(cx, 130f, 95f, Path.Direction.CW)
                val tip = Path()
                tip.moveTo(cx - 70f, 190f); tip.lineTo(cx, 300f); tip.lineTo(cx + 70f, 190f); tip.close()
                path.op(tip, Path.Op.UNION)
                path.op(Path().apply { addCircle(cx, 130f, 38f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            }
            else -> path.addRoundRect(RectF(20f, 30f, w - 20f, h - 30f), 70f, 70f, Path.Direction.CW)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        if (stroke) {
            p.style = Paint.Style.STROKE
            p.color = rim
            p.strokeWidth = 56f
            p.setShadowLayer(12f, 0f, 5f, 0x66000000)
            c.drawPath(path, p)
            p.clearShadowLayer()
            p.strokeWidth = 34f
            p.color = col
            c.drawPath(path, p)
        } else {
            p.style = Paint.Style.STROKE
            p.color = rim
            p.strokeWidth = 18f
            p.setShadowLayer(12f, 0f, 5f, 0x66000000)
            c.drawPath(path, p)
            p.clearShadowLayer()
            p.style = Paint.Style.FILL
            p.color = col
            c.drawPath(path, p)
            // a little shine on top
            p.color = 0x40FFFFFF
            c.save()
            c.clipPath(path)
            c.drawOval(RectF(cx - w * 0.3f, h * 0.08f, cx + w * 0.15f, h * 0.36f), p)
            c.restore()
        }
        if (badge) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.typeface = TextArt.typeface(11)
            tp.textSize = 120f
            tp.color = if (col == 0xFFFFFFFF.toInt()) Color.BLACK else Color.WHITE
            tp.textAlign = Paint.Align.CENTER
            val word = name.removePrefix("badge_").uppercase()
            val fm = tp.fontMetrics
            c.drawText(word, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
        }
        return bm
    }
}
