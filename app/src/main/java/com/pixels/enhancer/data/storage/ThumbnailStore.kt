package com.pixels.enhancer.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pixels.enhancer.data.decoder.BitmapConversions
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small JPEG previews for the Recent list, in app-private storage (never the gallery). */
class ThumbnailStore(private val directory: File) {

    suspend fun save(projectId: String, image: PixelBuffer) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val small = PixelResampler.downscaleToFit(image, LONG_EDGE)
        val bitmap = BitmapConversions.toBitmap(small)
        val temp = File(directory, "$projectId.tmp")
        try {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            temp.renameTo(fileFor(projectId))
        } finally {
            bitmap.recycle()
            temp.delete()
        }
    }

    suspend fun load(projectId: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(projectId)
        if (!file.exists()) null else BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }

    suspend fun delete(projectId: String) {
        withContext(Dispatchers.IO) { fileFor(projectId).delete() }
    }

    /** Copies a project's preview to a new project id (duplicated edits). */
    suspend fun copy(fromProjectId: String, toProjectId: String) {
        withContext(Dispatchers.IO) {
            val source = fileFor(fromProjectId)
            if (source.exists()) source.copyTo(fileFor(toProjectId), overwrite = true)
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) { directory.deleteRecursively() }
    }

    private fun fileFor(projectId: String) = File(directory, "${projectId.filter { it.isLetterOrDigit() || it == '-' }}.jpg")

    private companion object {
        const val LONG_EDGE = 320
        const val QUALITY = 85
    }
}
