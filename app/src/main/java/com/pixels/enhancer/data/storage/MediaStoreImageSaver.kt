package com.pixels.enhancer.data.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.data.decoder.BitmapConversions
import com.pixels.enhancer.data.exif.MetadataCopier
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.repository.ImageSaver
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.repository.SavedImage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Saves a new image under Pictures/Pixels following the export state flow:
 * write (hidden, IS_PENDING) → verify decode → copy metadata → verify again → publish → verify
 * the published entry. Anything failing — including cancellation — deletes the partial file, so a
 * broken export can never appear in the gallery or be reported as a success. The original is
 * never opened for writing; MediaStore de-duplicates clashing names.
 */
class MediaStoreImageSaver(
    private val contentResolver: ContentResolver,
    private val logger: EnhancerLogger,
) : ImageSaver {

    override suspend fun save(image: PixelBuffer, request: SaveRequest): SavedImage = withContext(Dispatchers.IO) {
        val uri = try {
            contentResolver.insert(collection(), pendingEntry(request))
        } catch (error: SecurityException) {
            throw EnhancerException(ErrorCode.PERMISSION_DENIED, "Not allowed to create a gallery entry", error)
        } ?: throw EnhancerException(ErrorCode.SAVE_FAILED, "MediaStore refused the new entry")
        try {
            encode(uri, image, request)
            verifyDecodable(uri, image)
            copyMetadata(uri, request)
            verifyDecodable(uri, image)
            publish(uri)
            verifyPublished(uri, image)
            SavedImage(uri.toString(), request.displayName)
        } catch (error: Throwable) {
            // NonCancellable: a cancelled export must still clean up its partial file.
            withContext(NonCancellable) { runCatching { contentResolver.delete(uri, null, null) } }
            throw classify(error)
        }
    }

    private fun collection(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun pendingEntry(request: SaveRequest) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, request.displayName)
        put(MediaStore.Images.Media.MIME_TYPE, request.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    private fun encode(uri: Uri, image: PixelBuffer, request: SaveRequest) {
        val format = compressFormatFor(request.mimeType)
        val bitmap = BitmapConversions.toBitmap(image)
        try {
            val stream = contentResolver.openOutputStream(uri) ?: throw EnhancerException(ErrorCode.SAVE_FAILED, "Cannot open output")
            val encoded = stream.use { bitmap.compress(format, request.quality, it) }
            if (!encoded) throw EnhancerException(ErrorCode.OUTPUT_ENCODE_FAILED, "Encoder failed")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Metadata is best-effort: a photo without EXIF is still a valid export, but the failure is
     * logged. ExifInterface reports unsupported containers and malformed source EXIF with runtime
     * exceptions as well as IOException, so those are contained too; the decode check that follows
     * still rejects a file the copy might have damaged.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun copyMetadata(uri: Uri, request: SaveRequest) {
        val source = request.metadataSourceId ?: return
        try {
            MetadataCopier.copy(contentResolver, Uri.parse(source), uri, request.metadata)
        } catch (error: IOException) {
            logger.error("METADATA_COPY_FAILED", mapOf("policy" to request.metadata), error)
        } catch (error: SecurityException) {
            logger.error("METADATA_COPY_FAILED", mapOf("policy" to request.metadata), error)
        } catch (error: RuntimeException) {
            logger.error("METADATA_COPY_FAILED", mapOf("policy" to request.metadata), error)
        }
    }

    private fun publish(uri: Uri) {
        val updated = contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        if (updated != 1) throw EnhancerException(ErrorCode.SAVE_FAILED, "Could not publish the saved file")
    }

    /** The file must exist and decode back to the expected size. */
    private fun verifyDecodable(uri: Uri, image: PixelBuffer) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream always returns null in bounds-only mode, so check the stream, not the result.
        val stream = contentResolver.openInputStream(uri)
            ?: throw EnhancerException(ErrorCode.SAVE_FAILED, "Saved file cannot be opened")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth != image.width || bounds.outHeight != image.height) {
            throw EnhancerException(ErrorCode.OUTPUT_ENCODE_FAILED, "Saved file decodes to ${bounds.outWidth}x${bounds.outHeight}")
        }
    }

    private fun verifyPublished(uri: Uri, image: PixelBuffer) {
        val projection = arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE)
        val published = contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getLong(1) > 0
        } ?: false
        if (!published) throw EnhancerException(ErrorCode.SAVE_FAILED, "Saved file is not visible in the gallery")
        verifyDecodable(uri, image)
    }

    private fun classify(error: Throwable): Throwable = when {
        error is EnhancerException || error is kotlinx.coroutines.CancellationException -> error
        error is SecurityException -> EnhancerException(ErrorCode.PERMISSION_DENIED, "Permission denied while saving", error)
        error is IOException && error.message.orEmpty().let { "ENOSPC" in it || "No space" in it } ->
            EnhancerException(ErrorCode.INSUFFICIENT_STORAGE, "Not enough storage space", error)
        error is IOException -> EnhancerException(ErrorCode.SAVE_FAILED, error.message ?: "Write failed", error)
        else -> error
    }

    private companion object {
        const val ALBUM = "Pixels"
        const val MIME_PNG = "image/png"
        const val MIME_WEBP = "image/webp"

        fun compressFormatFor(mimeType: String): Bitmap.CompressFormat = when (mimeType) {
            MIME_PNG -> Bitmap.CompressFormat.PNG
            MIME_WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                // Android 10 only has the combined format: lossy below quality 100.
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.JPEG
        }
    }
}
