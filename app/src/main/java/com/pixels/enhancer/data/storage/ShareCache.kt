package com.pixels.enhancer.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.data.decoder.BitmapConversions
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.repository.SaveRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes a temporary JPEG for the share sheet. Only the latest shared file is kept. */
class ShareCache(private val context: Context) {

    suspend fun write(image: PixelBuffer, displayName: String): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(directory, displayName)
        val bitmap = BitmapConversions.toBitmap(image)
        try {
            val encoded = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, SaveRequest.DEFAULT_JPEG_QUALITY, it) }
            if (!encoded) throw EnhancerException(ErrorCode.OUTPUT_ENCODE_FAILED, "JPEG encoder failed")
        } finally {
            bitmap.recycle()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        const val DIRECTORY = "shared"
    }
}
