package com.pixels.enhancer.harness

import com.pixels.enhancer.domain.color.IccProfiles
import com.pixels.enhancer.domain.color.RgbColorSpace
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.roundToInt

/**
 * Colour-managed file I/O for the desktop harness, mirroring what the phone does: read the
 * embedded ICC profile and keep the stored values (ImageIO would convert a P3 JPEG to sRGB and
 * clip it); write PNGs with the profile of the space their pixels are in.
 */
object ColorManagedFiles {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private const val JPEG_SOI = 0xFFD8
    private const val JPEG_APP2 = 0xE2
    private const val JPEG_SOS = 0xDA
    private const val ICC_MARKER = "ICC_PROFILE\u0000"
    private const val IHDR_END = 33
    private const val JPEG_APP0 = 0xE0
    private const val MAX_ICC_CHUNK = 65519
    private const val DEFAULT_JPEG_QUALITY = 95
    private const val JPEG_METADATA_FORMAT = "javax_imageio_jpeg_image_1.0"

    /** The file's profile as an RGB space, or null when it has none (or one this engine cannot model). */
    fun colorSpaceOf(file: File): RgbColorSpace? = iccProfileOf(file.readBytes())?.let(IccProfiles::parse)

    /**
     * Stored pixel values of [file]. Used for files with a non-sRGB profile, where ImageIO's own
     * conversion would clip; null when the layout is not one handled here.
     */
    fun readStoredPixels(file: File): PixelBuffer? {
        val input = ImageIO.createImageInputStream(file) ?: return null
        input.use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
            try {
                reader.input = stream
                val raster = reader.readRaster(0, null)
                val bands = raster.numBands
                if (bands != 3 && bands != 4) return null
                val jpeg = reader.formatName.equals("jpeg", ignoreCase = true)
                val width = raster.width
                val height = raster.height
                val sample = IntArray(bands)
                val pixels = IntArray(width * height) { i ->
                    raster.getPixel(i % width, i / width, sample)
                    val alpha = if (bands == 4 && !jpeg) sample[3] else Argb.OPAQUE_ALPHA
                    if (jpeg) ycbcrToRgb(sample[0], sample[1], sample[2]) else Argb.pack(alpha, sample[0], sample[1], sample[2])
                }
                return PixelBuffer(width, height, pixels)
            } finally {
                reader.dispose()
            }
        }
    }

    /** JFIF full-range YCbCr → RGB, as every JPEG decoder does it. */
    private fun ycbcrToRgb(y: Int, cb: Int, cr: Int): Int {
        val blueDiff = cb - 128.0
        val redDiff = cr - 128.0
        return Argb.opaque(
            (y + 1.402 * redDiff).roundToInt(),
            (y - 0.344136 * blueDiff - 0.714136 * redDiff).roundToInt(),
            (y + 1.772 * blueDiff).roundToInt(),
        )
    }

    /** Writes [image] as a PNG tagged with [space]'s ICC profile (the JDK's sRGB profile for sRGB). */
    fun writePng(image: PixelBuffer, space: RgbColorSpace, file: File) {
        val encoded = ByteArrayOutputStream().also { ImageIO.write(ImageIoConversions.toBufferedImage(image), "png", it) }.toByteArray()
        val profile = space.iccProfile ?: if (space.isSrgb) ICC_Profile.getInstance(ColorSpace.CS_sRGB).data else null
        file.absoluteFile.parentFile?.mkdirs()
        file.outputStream().use { out ->
            if (profile == null) {
                out.write(encoded)
            } else {
                out.write(encoded, 0, IHDR_END)
                out.write(iccpChunk(profile))
                out.write(encoded, IHDR_END, encoded.size - IHDR_END)
            }
        }
    }

    /**
     * Writes [image] as a JPEG the way the app's default export does (quality 95), with 4:4:4
     * chroma and [space]'s ICC profile in APP2 segments.
     */
    fun writeJpeg(image: PixelBuffer, space: RgbColorSpace, file: File, quality: Int = DEFAULT_JPEG_QUALITY) {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val encoded = ByteArrayOutputStream()
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(rgb), param)
            val tree = metadata.getAsTree(JPEG_METADATA_FORMAT) as IIOMetadataNode
            val components = tree.getElementsByTagName("componentSpec")
            for (i in 0 until components.length) {
                (components.item(i) as IIOMetadataNode).apply {
                    setAttribute("HsamplingFactor", "1")
                    setAttribute("VsamplingFactor", "1")
                }
            }
            metadata.setFromTree(JPEG_METADATA_FORMAT, tree)
            ImageIO.createImageOutputStream(encoded).use { output ->
                writer.output = output
                writer.write(null, IIOImage(rgb, null, metadata), param)
            }
        } finally {
            writer.dispose()
        }
        val bytes = encoded.toByteArray()
        val profile = space.iccProfile ?: if (space.isSrgb) ICC_Profile.getInstance(ColorSpace.CS_sRGB).data else null
        file.absoluteFile.parentFile?.mkdirs()
        file.outputStream().use { out ->
            // After SOI and the JFIF APP0 segment, where readers look for APP2 ICC data.
            val insertAt = if ((bytes[3].toInt() and 0xFF) == JPEG_APP0) 4 + ((bytes[4].toInt() and 0xFF) shl 8 or (bytes[5].toInt() and 0xFF)) else 2
            out.write(bytes, 0, insertAt)
            profile?.let { out.write(app2Segments(it)) }
            out.write(bytes, insertAt, bytes.size - insertAt)
        }
    }

    /** Writes a JPEG for `.jpg`/`.jpeg` paths and a PNG otherwise. */
    fun write(image: PixelBuffer, space: RgbColorSpace, file: File) =
        if (file.extension.lowercase() in setOf("jpg", "jpeg")) writeJpeg(image, space, file) else writePng(image, space, file)

    private fun app2Segments(profile: ByteArray): ByteArray {
        val chunks = profile.toList().chunked(MAX_ICC_CHUNK)
        val out = ByteArrayOutputStream()
        chunks.forEachIndexed { index, chunk ->
            val length = 2 + ICC_MARKER.length + 2 + chunk.size
            out.write(byteArrayOf(0xFF.toByte(), JPEG_APP2.toByte(), (length ushr 8).toByte(), length.toByte()))
            out.write(ICC_MARKER.toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf((index + 1).toByte(), chunks.size.toByte()))
            out.write(chunk.toByteArray())
        }
        return out.toByteArray()
    }

    private fun iccpChunk(profile: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().also { buffer ->
            DeflaterOutputStream(buffer, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(profile) }
        }.toByteArray()
        val body = "ICC Profile".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + compressed
        val typeAndBody = "iCCP".toByteArray(Charsets.US_ASCII) + body
        val crc = CRC32().apply { update(typeAndBody) }.value.toInt()
        return intBytes(body.size) + typeAndBody + intBytes(crc)
    }

    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun iccProfileOf(bytes: ByteArray): ByteArray? = when {
        bytes.size > 4 && ((bytes[0].toInt() and 0xFF) shl 8 or (bytes[1].toInt() and 0xFF)) == JPEG_SOI -> jpegProfile(bytes)
        bytes.size > PNG_SIGNATURE.size && bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) -> pngProfile(bytes)
        else -> null
    }

    /** Concatenates the APP2 `ICC_PROFILE` chunks in sequence order. */
    private fun jpegProfile(bytes: ByteArray): ByteArray? {
        val chunks = sortedMapOf<Int, ByteArray>()
        var offset = 2
        while (offset + 4 <= bytes.size && (bytes[offset].toInt() and 0xFF) == 0xFF) {
            val marker = bytes[offset + 1].toInt() and 0xFF
            if (marker == JPEG_SOS) break
            val length = (bytes[offset + 2].toInt() and 0xFF) shl 8 or (bytes[offset + 3].toInt() and 0xFF)
            val start = offset + 4
            if (marker == JPEG_APP2 && length > ICC_MARKER.length + 4 && start + ICC_MARKER.length <= bytes.size &&
                String(bytes, start, ICC_MARKER.length, Charsets.ISO_8859_1) == ICC_MARKER
            ) {
                val sequence = bytes[start + ICC_MARKER.length].toInt() and 0xFF
                val dataStart = start + ICC_MARKER.length + 2
                chunks[sequence] = bytes.copyOfRange(dataStart, minOf(offset + 2 + length, bytes.size))
            }
            offset += 2 + length
        }
        return chunks.values.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b }
    }

    private fun pngProfile(bytes: ByteArray): ByteArray? {
        val input = DataInputStream(bytes.inputStream(PNG_SIGNATURE.size, bytes.size - PNG_SIGNATURE.size))
        while (input.available() >= 12) {
            val length = input.readInt()
            val type = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
            if (length < 0 || length > input.available()) return null
            val data = ByteArray(length).also(input::readFully)
            input.readInt()
            when (type) {
                "iCCP" -> {
                    val nameEnd = data.indexOf(0)
                    if (nameEnd < 0) return null
                    return InflaterInputStream(data.inputStream(nameEnd + 2, data.size - nameEnd - 2)).readBytes()
                }
                "IDAT", "IEND" -> return null
            }
        }
        return null
    }
}
