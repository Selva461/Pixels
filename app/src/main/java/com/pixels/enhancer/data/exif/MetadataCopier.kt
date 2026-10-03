package com.pixels.enhancer.data.exif

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.pixels.enhancer.domain.export.MetadataPolicy

/**
 * Copies selected EXIF tags from the original into the exported file. Orientation is always
 * written as "normal" because rotation is already baked into the pixels.
 */
object MetadataCopier {

    private val CAMERA_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
    )

    private val LOCATION_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    private const val SOFTWARE = "Pixels"

    fun tagsFor(policy: MetadataPolicy): List<String> = when (policy) {
        MetadataPolicy.KEEP_ALL -> CAMERA_TAGS + LOCATION_TAGS
        MetadataPolicy.REMOVE_LOCATION -> CAMERA_TAGS
        MetadataPolicy.REMOVE_ALL -> emptyList()
    }

    /** Throws IOException on failure; the caller decides whether that fails the export. */
    fun copy(resolver: ContentResolver, source: Uri, target: Uri, policy: MetadataPolicy) {
        val tags = tagsFor(policy)
        if (tags.isEmpty()) return
        val original = resolver.openInputStream(source)?.use { ExifInterface(it) } ?: return
        resolver.openFileDescriptor(target, "rw")?.use { descriptor ->
            val exported = ExifInterface(descriptor.fileDescriptor)
            tags.forEach { tag -> original.getAttribute(tag)?.let { exported.setAttribute(tag, it) } }
            exported.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exported.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)
            exported.saveAttributes()
        }
    }
}
