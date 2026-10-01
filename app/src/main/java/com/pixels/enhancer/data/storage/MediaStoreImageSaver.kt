package com.pixels.enhancer.data.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.data.decoder.BitmapConversions
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.repository.ImageSaver
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.repository.SavedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inserts a new MediaStore entry under Pictures/Pixels. The original is never opened for
 * writing; MediaStore de-duplicates the display name if `_enhanced` already exists.
 */
class MediaStoreImageSaver(private val contentResolver: ContentResolver) : ImageSaver {

    override suspend fun save(image: PixelBuffer, request: SaveRequest): SavedImage = withContext(Dispatchers.IO) {
        val bitmap = BitmapConversions.toBitmap(image)
        try {
            val uri = contentResolver.insert(collection(), pendingEntry(request))
                ?: throw EnhancerException(ErrorCode.SAVE_FAILED, "MediaStore refused the new entry")
            try {
                write(uri, bitmap, request)
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                verifyDecodable(uri, image)
                SavedImage(uri.toString(), request.displayName)
            } catch (error: Throwable) {
                // Never leave a half-written file behind in the gallery.
                contentResolver.delete(uri, null, null)
                throw error
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun collection(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun pendingEntry(request: SaveRequest) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, request.displayName)
        put(MediaStore.Images.Media.MIME_TYPE, request.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    private fun write(uri: Uri, bitmap: Bitmap, request: SaveRequest) {
        val encoded = contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, request.quality, stream)
        } ?: false
        if (!encoded) throw EnhancerException(ErrorCode.OUTPUT_ENCODE_FAILED, "JPEG encoder failed")
    }

    /** Output validation: the saved file must exist and decode back to the expected size. */
    private fun verifyDecodable(uri: Uri, image: PixelBuffer) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw EnhancerException(ErrorCode.SAVE_FAILED, "Saved file cannot be opened")
        if (bounds.outWidth != image.width || bounds.outHeight != image.height) {
            throw EnhancerException(ErrorCode.OUTPUT_ENCODE_FAILED, "Saved file decodes to ${bounds.outWidth}x${bounds.outHeight}")
        }
    }

    private companion object {
        const val ALBUM = "Pixels"
    }
}
