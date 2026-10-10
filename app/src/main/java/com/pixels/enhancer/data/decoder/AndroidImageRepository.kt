package com.pixels.enhancer.data.decoder

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.data.exif.ExifNormalizer
import com.pixels.enhancer.data.exif.Orientation
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.model.ImageSource
import com.pixels.enhancer.domain.repository.ImageRepository
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes picked images. Never loads full resolution when it is not needed: the decoder
 * subsamples by a power of two first, then one exact downscale reaches the working size.
 */
class AndroidImageRepository(private val contentResolver: ContentResolver) : ImageRepository {

    override suspend fun readSource(sourceId: String): ImageSource = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceId)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val mimeType = contentResolver.getType(uri) ?: bounds.outMimeType
            ?: throw EnhancerException(ErrorCode.IMAGE_UNSUPPORTED, "Unknown image type")
        val orientation = ExifNormalizer.readOrientation(contentResolver, uri)
        ImageSource(
            id = sourceId,
            displayName = queryDisplayName(uri),
            mimeType = mimeType,
            width = bounds.outWidth,
            height = bounds.outHeight,
            rotationDegrees = orientation.rotationDegrees,
            mirrored = orientation.mirrored,
        )
    }

    override suspend fun loadWorkingImage(source: ImageSource, maxLongEdge: Int): PixelBuffer = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source.id)
        val options = BitmapFactory.Options().apply {
            inSampleSize = PixelResampler.powerOfTwoSubsample(source.width, source.height, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw EnhancerException(ErrorCode.IMAGE_DECODE_FAILED, "Decoder returned no bitmap")
        val scaled = scaleToFit(decoded, maxLongEdge)
        val upright = ExifNormalizer.apply(scaled, Orientation(source.rotationDegrees, source.mirrored))
        try {
            BitmapConversions.toPixelBuffer(upright)
        } finally {
            upright.recycle()
        }
    }

    private fun scaleToFit(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val (width, height) = PixelResampler.fitWithin(maxLongEdge, bitmap.width, bitmap.height)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Only content:// URIs are read. A file:// URI handed in by another app could point at this
     * app's private files; the photo picker, camera capture and shared imports all use content URIs.
     */
    private fun open(uri: Uri): InputStream = try {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw EnhancerException(ErrorCode.IMAGE_UNSUPPORTED, "Only content URIs can be opened")
        contentResolver.openInputStream(uri) ?: throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "No stream for image")
    } catch (error: FileNotFoundException) {
        throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "Image not found", error)
    } catch (error: SecurityException) {
        throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "No permission to read image", error)
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
