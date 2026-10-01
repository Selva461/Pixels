package com.pixels.enhancer.data.exif

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

data class Orientation(val rotationDegrees: Int, val mirrored: Boolean) {
    val isNormal: Boolean get() = rotationDegrees == 0 && !mirrored

    companion object {
        val NORMAL = Orientation(0, false)
    }
}

/** Reads EXIF orientation and bakes it into pixels, so analysis always sees the upright image. */
object ExifNormalizer {

    /** Only the orientation tag is read; no other EXIF data (location, device) is touched or logged. */
    fun readOrientation(contentResolver: ContentResolver, uri: Uri): Orientation = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            Orientation(exif.rotationDegrees, exif.isFlipped)
        } ?: Orientation.NORMAL
    } catch (_: IOException) {
        // A missing or unreadable EXIF block is common (PNG, edited files): treat as upright.
        Orientation.NORMAL
    }

    /** Returns [bitmap] upright. Recycles the input when a new bitmap is created. */
    fun apply(bitmap: Bitmap, orientation: Orientation): Bitmap {
        if (orientation.isNormal) return bitmap
        // ExifInterface reports rotation assuming a horizontal flip is applied first.
        val matrix = Matrix().apply {
            if (orientation.mirrored) postScale(-1f, 1f)
            postRotate(orientation.rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }
}
