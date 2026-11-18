package com.aungdev.fly_lab

// Fly Lab PROJECT EDITOR - E2 Audio (19 Nov 2026). Everything on the phone.
//
//   AClip          one music bar on the audio track (project start..end,
//                  source offset "from", volume, fade in / out)
//   AudioPick      "pickAudio": the phone's file picker (audio/*), the file
//                  is copied into the app cache so MediaPlayer / Extractor
//                  can open it; returns {path, name, dur}
//   MusicPreview   preview: one MediaPlayer per music bar, kept in step
//                  with the project time by the render loop
//   AudioMix       export: decodes the clips' own sound (volume, speed) and
//                  the music bars, mixes them at 44.1 kHz stereo, encodes
//                  AAC; ProjectEngine.encode muxes it next to the video.
//
// Speed != 1 on a video clip: its sound is played faster / slower too, so
// the pitch goes up / down (simple resample, no time-stretch yet).

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/** One music bar: on the project from start to end (s), playing the source
 *  from "from" on, at volume vol (0..2), fading in / out over fadeIn / fadeOut s. */
class AClip(
    val uid: Int, val path: String, val start: Float, val end: Float, val from: Float,
    val vol: Float, val fadeIn: Float, val fadeOut: Float,
) {
    /** Gain at project time t (0 outside the bar). */
    fun gain(t: Float): Float {
        if (t < start || t >= end) return 0f
        val lt = t - start
        val len = end - start
        var g = vol
        if (fadeIn > 0.01f) g *= min(1f, lt / fadeIn)
        if (fadeOut > 0.01f) g *= min(1f, (len - lt) / fadeOut)
        return g.coerceAtLeast(0f)
    }

    companion object {
        fun parse(list: List<Map<String, Any>>?): List<AClip> = list?.mapNotNull { mp ->
            fun f(k: String, d: Float) = (mp[k] as? Number)?.toFloat() ?: d
            val path = mp["path"] as? String ?: return@mapNotNull null
            AClip((mp["uid"] as? Number)?.toInt() ?: 0, path, f("start", 0f), f("end", 0f),
                f("from", 0f).coerceAtLeast(0f), f("vol", 1f).coerceIn(0f, 2f),
                f("fadeIn", 0f).coerceAtLeast(0f), f("fadeOut", 0f).coerceAtLeast(0f))
        } ?: emptyList()
    }
}

// ------------------------------------------------------------------ picker
object AudioPick {
    const val REQ = 4207
    private var pending: MethodChannel.Result? = null
    private var act: Activity? = null
    private val main = Handler(Looper.getMainLooper())

    fun pick(activity: Activity, result: MethodChannel.Result) {
        pending?.success(null)
        pending = result
        act = activity
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            activity.startActivityForResult(i, REQ)
        } catch (e: Throwable) {
            pending = null
            result.error("no_picker", e.toString(), null)
        }
    }

    /** Called from MainActivity.onActivityResult. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ) return false
        val r = pending ?: return true
        val a = act ?: return true
        pending = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            r.success(null)
            return true
        }
        Thread {
            try {
                var name = "Music"
                a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                val ext = name.substringAfterLast('.', "m4a").take(5)
                val dir = File(a.cacheDir, "music").also { it.mkdirs() }
                val out = File(dir, "music_${System.currentTimeMillis()}.$ext")
                a.contentResolver.openInputStream(uri)!!.use { inp ->
                    out.outputStream().use { inp.copyTo(it) }
                }
                val mr = MediaMetadataRetriever()
                val ms = try {
                    mr.setDataSource(out.absolutePath)
                    mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    mr.release()
                }
                if (ms <= 0L) error("Can't read this audio file")
                val label = name.substringBeforeLast('.')
                main.post {
                    r.success(mapOf("path" to out.absolutePath, "name" to label,
                        "dur" to ms / 1000.0))
                }
            } catch (e: Throwable) {
                Log.e("FlyAudio", "pick", e)
                main.post { r.error("pick_failed", e.message ?: e.toString(), null) }
            }
        }.start()
        return true
    }
}

// ----------------------------------------------------------------- preview
/** Preview music: call sync() every frame on the GL thread (it has a Looper). */
class MusicPreview {
    private val players = HashMap<Int, Pair<String, MediaPlayer>>()

    fun sync(music: List<AClip>, t: Float, playing: Boolean) {
        val keep = HashSet<Int>()
        for (a in music) {
            keep += a.uid
            val inside = playing && t >= a.start && t < a.end
            var e = players[a.uid]
            if (e != null && e.first != a.path) {
                try { e.second.release() } catch (_: Throwable) {}
                players.remove(a.uid)
                e = null
            }
            if (e == null) {
                if (!inside) continue
                val mp = try {
                    MediaPlayer().apply { setDataSource(a.path); prepare() }
                } catch (x: Throwable) {
                    Log.e("FlyAudio", "music player", x)
                    continue
                }
                e = a.path to mp
                players[a.uid] = e
            }
            val mp = e.second
            try {
                val want = a.from + (t - a.start)
                if (inside && want * 1000f < mp.duration - 50) {
                    if (!mp.isPlaying) {
                        mp.seekTo((want * 1000).toInt())
                        mp.start()
                    } else if (abs(mp.currentPosition / 1000f - want) > 0.3f) {
                        mp.seekTo((want * 1000).toInt())
                    }
                    val g = a.gain(t).coerceAtMost(1f)
                    mp.setVolume(g, g)
                } else if (mp.isPlaying) {
                    mp.pause()
                }
            } catch (x: Throwable) {
                Log.e("FlyAudio", "music sync", x)
            }
        }
        val gone = players.keys.filter { it !in keep }
        for (k in gone) players.remove(k)?.second?.let { try { it.release() } catch (_: Throwable) {} }
    }

    fun pauseAll() {
        for ((_, e) in players) try { if (e.second.isPlaying) e.second.pause() } catch (_: Throwable) {}
    }

    fun release() {
        for ((_, e) in players) try { e.second.release() } catch (_: Throwable) {}
        players.clear()
    }
}

// ------------------------------------------------------------------ export
/** The finished AAC track: its format and the encoded samples, in order. */
class EncodedAudio(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

object AudioMix {
    const val RATE = 44100
    private const val TAG = "FlyAudio"

    /** What happened to the sound in the last export (shown under Export). */
    @Volatile var report = ""
    private val log = StringBuilder()
    private fun note(s: String) {
        Log.i(TAG, s)
        if (log.isNotEmpty()) log.append(" | ")
        log.append(s)
    }

    /** Decoded sound of a file from fromSec for durSec: interleaved stereo shorts. */
    private class Pcm(val data: ShortArray, val frames: Int, val rate: Int)

    private fun decode(path: String, fromSec: Float, durSec: Float): Pcm? {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) { note("no sound track"); return null }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val fromUs = (fromSec * 1_000_000).toLong()
            val endUs = ((fromSec + durSec) * 1_000_000).toLong()
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(fmt, null, null, 0)
            d.start()
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var isFloat = false
            var out = ShortArray(((durSec + 0.5f) * rate).toInt().coerceAtLeast(1) * 2)
            var frames = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = d.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = d.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = d.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = d.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4
                    continue
                }
                if (oi < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                val ob = d.getOutputBuffer(oi)
                if (ob != null && info.size > 0) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    val bytesPer = if (isFloat) 4 else 2
                    val n = info.size / (bytesPer * ch)
                    for (j in 0 until n) {
                        val tUs = info.presentationTimeUs + j * 1_000_000L / rate
                        if (tUs < fromUs) { ob.position(ob.position() + bytesPer * ch); continue }
                        if (tUs >= endUs) { outDone = true; break }
                        var l = 0; var r = 0
                        for (k in 0 until ch) {
                            val v = if (isFloat) (ob.float * 32767f).toInt() else ob.short.toInt()
                            if (k == 0) l = v
                            if (k == 1) r = v
                        }
                        if (ch == 1) r = l
                        if (frames * 2 + 1 >= out.size) out = out.copyOf(out.size * 2)
                        out[frames * 2] = l.coerceIn(-32768, 32767).toShort()
                        out[frames * 2 + 1] = r.coerceIn(-32768, 32767).toShort()
                        frames++
                    }
                }
                d.releaseOutputBuffer(oi, false)
            }
            note("${rate / 1000}kHz ${ch}ch ${if (isFloat) "f" else "s16"} ${"%.1f".format(frames.toFloat() / rate)}s")
            return if (frames > 0) Pcm(out, frames, rate) else null
        } catch (e: Throwable) {
            Log.e(TAG, "decode $path", e)
            note("decode error: ${e.javaClass.simpleName} ${e.message ?: ""}".take(90))
            return null
        } finally {
            try { dec?.stop() } catch (_: Throwable) {}
            try { dec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }

    /** Adds pcm into mix from project time t0 for len s, reading the source
     *  speed times faster, with gain(project t). */
    private fun addInto(mix: FloatArray, pcm: Pcm, t0: Float, len: Float, speed: Float, gain: (Float) -> Float) {
        val i0 = (t0 * RATE).toInt()
        val n = (len * RATE).toInt()
        val step = speed * pcm.rate / RATE
        val edge = (0.012f * RATE).toInt() // 12 ms soft edges: no clicks at cuts
        for (k in 0 until n) {
            val i = i0 + k
            if (i * 2 + 1 >= mix.size) break
            val s = k * step
            val a = s.toInt()
            if (a + 1 >= pcm.frames) break
            val f = s - a
            var g = gain(i.toFloat() / RATE)
            if (k < edge) g *= k.toFloat() / edge
            if (n - k < edge) g *= (n - k).toFloat() / edge
            if (g <= 0f) continue
            val l = pcm.data[a * 2] * (1 - f) + pcm.data[a * 2 + 2] * f
            val r = pcm.data[a * 2 + 1] * (1 - f) + pcm.data[a * 2 + 3] * f
            mix[i * 2] += l * g
            mix[i * 2 + 1] += r * g
        }
    }

    /** The whole project's sound, or null when there is none. */
    fun mix(tl: List<TClip>, music: List<AClip>, original: Boolean, total: Float): FloatArray? {
        log.setLength(0)
        val mix = FloatArray(((total * RATE).toInt() + 1) * 2)
        var any = false
        if (!original) note("original off")
        if (original) {
            var start = 0f
            for (c in tl) {
                if (c.media.video && c.vol > 0.001f) {
                    val pcm = decode(c.media.path, c.from, c.dur * c.speed)
                    if (pcm != null) {
                        val v = c.vol
                        addInto(mix, pcm, start, c.dur, c.speed) { v }
                        any = true
                    }
                }
                start += c.dur
            }
        }
        for (a in music) {
            val s = a.start.coerceAtLeast(0f)
            val e = min(a.end, total)
            if (e - s < 0.05f) continue
            val pcm = decode(a.path, a.from + (s - a.start), e - s) ?: continue
            addInto(mix, pcm, s, e - s, 1f) { a.gain(it) }
            any = true
        }
        if (!any) note("no sound in the project")
        report = log.toString()
        return if (any) mix else null
    }

    /** AAC (LC, 44.1 kHz stereo, 160 kbps) of the mix. */
    fun encode(mix: FloatArray): EncodedAudio {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFmt: MediaFormat? = null
        val frames = mix.size / 2
        var pos = 0
        var inDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inDone) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = enc.getInputBuffer(ii)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val n = min((buf.capacity() / 4), frames - pos)
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, pos * 1_000_000L / RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            for (k in 0 until n) {
                                val i = (pos + k) * 2
                                buf.putShort(softClip(mix[i]))
                                buf.putShort(softClip(mix[i + 1]))
                            }
                            enc.queueInputBuffer(ii, 0, n * 4, pos * 1_000_000L / RATE, 0)
                            pos += n
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFmt = enc.outputFormat; continue }
                if (oi < 0) continue
                val ob = enc.getOutputBuffer(oi)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && ob != null) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    ob.get(bytes)
                    val bi = MediaCodec.BufferInfo()
                    bi.set(0, info.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    out += bytes to bi
                }
                enc.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            try { enc.stop() } catch (_: Throwable) {}
            enc.release()
        }
        report = (report + " | AAC ${out.size} blocks").trimStart(' ', '|')
        return EncodedAudio(outFmt ?: error("no AAC format"), out)
    }

    /** Float sample -> short; loud mixes are rounded off instead of cracking. */
    private fun softClip(v: Float): Short {
        val x = v / 32768f
        val y = if (abs(x) < 0.8f) x else Math.signum(x) * (0.8f + 0.2f * kotlin.math.tanh((abs(x) - 0.8f) / 0.2f))
        return (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Writes the audio samples into an started muxer track. */
    fun writeAll(muxer: android.media.MediaMuxer, track: Int, a: EncodedAudio) {
        for ((bytes, info) in a.samples) muxer.writeSampleData(track, ByteBuffer.wrap(bytes), info)
    }
}
