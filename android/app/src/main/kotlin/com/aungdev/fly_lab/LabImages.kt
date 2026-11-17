package com.aungdev.fly_lab

// Fly Lab - small image / gallery helpers for the Project editor
// (17 Nov 2026, E1a). Same rules as PhotoVideoEngine: photos upright
// (EXIF), long side limited; videos go to Movies/FlyLab.

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object LabImages {
    /** Decodes a photo upright (EXIF), long side <= maxSide. */
    fun decodeUpright(path: String, maxSide: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
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
        val up = if (rot == 0f) bm else {
            val m = Matrix().apply { postRotate(rot) }
            val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            if (r != bm) bm.recycle()
            r
        }
        return fitLongSide(up, maxSide)
    }

    /** Scales down so the long side is <= maxSide (never up). */
    fun fitLongSide(bm: Bitmap, maxSide: Int, recycleSource: Boolean = true): Bitmap {
        val long = maxOf(bm.width, bm.height)
        if (long <= maxSide) return bm
        val s = maxSide.toFloat() / long
        val out = Bitmap.createScaledBitmap(bm,
            maxOf(1, (bm.width * s).toInt()), maxOf(1, (bm.height * s).toInt()), true)
        if (recycleSource && out !== bm) bm.recycle()
        return out
    }

    /** Copies an mp4 into the gallery (Movies/FlyLab). */
    fun saveVideoToGallery(activity: Activity, f: File): String {
        val name = "FlyLab_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FlyLab")
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
