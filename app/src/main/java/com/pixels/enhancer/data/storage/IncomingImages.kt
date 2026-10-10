package com.pixels.enhancer.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.domain.model.SupportedFormats
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-private homes for photos that don't come from the photo picker:
 * - camera captures (the camera app writes straight into [newCaptureUri]);
 * - photos shared or sent to Pixels by other apps, copied in by [importShared], because the
 *   permission another app grants lasts only until Pixels closes, and projects must reopen later.
 *
 * Both are served through this app's FileProvider as content:// URIs, which is the only kind the
 * decoder accepts. Originals elsewhere are only ever read, never changed.
 */
class IncomingImages(private val context: Context) {

    private val captures = File(context.filesDir, CAPTURES)
    private val imports = File(context.filesDir, IMPORTS)

    /** A new, empty file for the camera app to write into. */
    fun newCaptureUri(): Uri {
        captures.mkdirs()
        return uriFor(File(captures, "IMG_${System.currentTimeMillis()}.jpg"))
    }

    /** Deletes a capture the user cancelled (the camera app may have created an empty file). */
    fun discardCapture(uri: Uri) {
        fileFor(uri, captures)?.delete()
    }

    /**
     * Validates and copies a shared photo into app storage. Rejects anything that is not a
     * content:// image from another app (file:// URIs and this app's own provider are refused,
     * so a hostile sender can't make Pixels open its private files), and anything too large.
     */
    suspend fun importShared(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw EnhancerException(ErrorCode.IMAGE_UNSUPPORTED, "Only content URIs are accepted")
        if (uri.authority == authority()) throw EnhancerException(ErrorCode.IMAGE_UNSUPPORTED, "Refusing this app's own files")
        val type = try {
            resolver.getType(uri)
        } catch (error: SecurityException) {
            throw EnhancerException(ErrorCode.PERMISSION_DENIED, "No permission to read the shared image", error)
        }
        if (!SupportedFormats.isSupported(type)) throw EnhancerException(ErrorCode.IMAGE_UNSUPPORTED, "Unsupported shared type: $type")
        val declaredSize = querySize(resolver, uri)
        if (declaredSize != null && declaredSize > MAX_IMPORT_BYTES) throw EnhancerException(ErrorCode.IMAGE_TOO_LARGE, "Shared image is too large")
        imports.mkdirs()
        val target = File(imports, "${UUID.randomUUID()}.${extensionFor(type)}")
        try {
            val input = resolver.openInputStream(uri) ?: throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "No stream for shared image")
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        // The declared size can be missing or wrong; the copy itself is capped too.
                        if (total > MAX_IMPORT_BYTES) throw EnhancerException(ErrorCode.IMAGE_TOO_LARGE, "Shared image is too large")
                        sink.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: SecurityException) {
            target.delete()
            throw EnhancerException(ErrorCode.PERMISSION_DENIED, "No permission to read the shared image", error)
        } catch (error: IOException) {
            target.delete()
            throw EnhancerException(ErrorCode.IMAGE_NOT_FOUND, "Could not copy the shared image", error)
        } catch (error: EnhancerException) {
            target.delete()
            throw error
        }
        uriFor(target)
    }

    /** Frees space used by imported and captured photos (part of Settings > Delete all edits). */
    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        imports.deleteRecursively()
        captures.deleteRecursively()
    }

    private fun authority() = "${context.packageName}.fileprovider"

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority(), file)

    /** The file behind one of our own provider URIs inside [directory], or null. */
    private fun fileFor(uri: Uri, directory: File): File? {
        if (uri.authority != authority()) return null
        val name = uri.lastPathSegment ?: return null
        val file = File(directory, File(name).name)
        return file.takeIf { it.canonicalPath.startsWith(directory.canonicalPath + File.separator) }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (error: SecurityException) {
        null
    }

    private fun extensionFor(type: String?): String = when (type?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "jpg"
    }

    private companion object {
        const val CAPTURES = "captures"
        const val IMPORTS = "imports"
        const val BUFFER_BYTES = 64 * 1024

        /** Far above any phone photo; stops a hostile or broken sender filling storage. */
        const val MAX_IMPORT_BYTES = 200L * 1024 * 1024
    }
}
