package com.pixels.enhancer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.core.logging.NoOpLogger
import com.pixels.enhancer.data.storage.IncomingImages
import com.pixels.enhancer.data.storage.MediaStoreImageSaver
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.repository.SaveRequest
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** "Share to Pixels" imports: only another app's content:// images get in. */
@RunWith(AndroidJUnit4::class)
class IncomingImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val incoming = IncomingImages(context)
    private val saver = MediaStoreImageSaver(context.contentResolver, NoOpLogger)
    private val useCase get() = (context.applicationContext as PixelsApplication).container.enhanceImageUseCase
    private val created = mutableListOf<Uri>()

    @After
    fun cleanUp() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        runBlocking { incoming.deleteAll() }
    }

    private fun failureOf(uri: Uri): ErrorCode? = runBlocking {
        try {
            incoming.importShared(uri)
            null
        } catch (error: EnhancerException) {
            error.code
        }
    }

    @Test
    fun refusesFileUrisThatCouldPointAtPrivateFiles() {
        assertEquals(ErrorCode.IMAGE_UNSUPPORTED, failureOf(Uri.fromFile(File(context.filesDir, "projects/any.json"))))
    }

    @Test
    fun refusesThisAppsOwnProvider() {
        val own = incoming.newCaptureUri()
        assertEquals(ErrorCode.IMAGE_UNSUPPORTED, failureOf(own))
        incoming.discardCapture(own)
    }

    @Test
    fun copiesASharedPhotoIntoAppStorageAndItOpens() = runBlocking {
        val photo = PixelBuffer(64, 48, IntArray(64 * 48) { Argb.opaque(120, 140, 160) })
        val source = Uri.parse(saver.save(photo, SaveRequest("pixels_test_shared.jpg")).id).also { created += it }

        val local = incoming.importShared(source)

        assertEquals("${context.packageName}.fileprovider", local.authority)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(local)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(64 to 48, bounds.outWidth to bounds.outHeight)
        assertTrue(useCase.open(local.toString()) is OperationResult.Success)
    }
}
