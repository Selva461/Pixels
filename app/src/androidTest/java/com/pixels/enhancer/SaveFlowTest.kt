package com.pixels.enhancer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.exifinterface.media.ExifInterface
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.core.logging.NoOpLogger
import com.pixels.enhancer.domain.export.ExportFormat
import com.pixels.enhancer.domain.export.ExportOptions
import com.pixels.enhancer.domain.export.ExportSize
import com.pixels.enhancer.domain.export.MetadataPolicy
import com.pixels.enhancer.data.storage.MediaStoreImageSaver
import com.pixels.enhancer.domain.geometry.CropRect
import com.pixels.enhancer.domain.geometry.Geometry
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.planning.Look
import com.pixels.enhancer.domain.repository.SaveRequest
import com.pixels.enhancer.domain.usecase.EnhanceRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real MediaStore save path on a device or emulator. */
@RunWith(AndroidJUnit4::class)
class SaveFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver = context.contentResolver
    private val saver = MediaStoreImageSaver(resolver, NoOpLogger)
    private val useCase get() = (context.applicationContext as PixelsApplication).container.enhanceImageUseCase
    private val created = mutableListOf<Uri>()

    @After
    fun cleanUp() {
        created.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun saverWritesADecodableJpegIntoPicturesPixels() = runBlocking {
        val saved = saver.save(gradient(320, 240), SaveRequest("pixels_test_enhanced.jpg"))
        val uri = Uri.parse(saved.id).also { created += it }

        assertEquals(320 to 240, decodedSize(uri))
        resolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null).use { cursor ->
            assertTrue(cursor!!.moveToFirst())
            assertTrue(cursor.getString(0).contains("Pixels"))
        }
    }

    @Test
    fun fullFlowSavesAnEnhancedCopyAndKeepsTheOriginal() = runBlocking {
        val source = Uri.parse(saver.save(gradient(1600, 1200), SaveRequest("pixels_test_source.jpg")).id).also { created += it }

        val session = (useCase.open(source.toString()) as OperationResult.Success).value
        val result = useCase.save(session, EnhanceRequest(strength = 0.45f, manual = Look.byId("film").adjustments))

        assertTrue("save failed: $result", result is OperationResult.Success)
        val saved = Uri.parse((result as OperationResult.Success).value.id).also { created += it }
        assertTrue(saved != source)
        assertEquals(1600 to 1200, decodedSize(saved))
        assertEquals(1600 to 1200, decodedSize(source))
    }

    @Test
    fun rotatedAndCroppedEditIsSavedWithTheNewShape() = runBlocking {
        val source = Uri.parse(saver.save(gradient(1600, 1200), SaveRequest("pixels_test_geometry.jpg")).id).also { created += it }
        val session = (useCase.open(source.toString()) as OperationResult.Success).value

        // Turn to portrait (1200x1600), then keep the left half.
        val geometry = Geometry.NONE.rotatedClockwise().copy(crop = CropRect.of(0f, 0f, 0.5f, 1f))
        val result = useCase.save(session, EnhanceRequest(strength = 0.45f, geometry = geometry))

        assertTrue("save failed: $result", result is OperationResult.Success)
        val saved = Uri.parse((result as OperationResult.Success).value.id).also { created += it }
        assertEquals(600 to 1600, decodedSize(saved))
    }

    private fun decodedSize(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }

    private fun gradient(width: Int, height: Int) = PixelBuffer(
        width,
        height,
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Argb.opaque(x * 255 / width, y * 255 / height, 90)
        },
    )

    @Test
    fun fullResolutionExportKeepsSourceSizeAboveTheWorkingSize() = runBlocking {
        val source = Uri.parse(saver.save(gradient(3000, 2000), SaveRequest("pixels_test_big.jpg")).id).also { created += it }
        val session = (useCase.open(source.toString()) as OperationResult.Success).value
        assertEquals(2560, session.original.width)

        val result = useCase.export(session, EnhanceRequest(0.45f), ExportOptions(size = ExportSize.FULL))

        assertTrue("export failed: $result", result is OperationResult.Success)
        val saved = Uri.parse((result as OperationResult.Success).value.saved.id).also { created += it }
        assertEquals(3000 to 2000, decodedSize(saved))
    }

    @Test
    fun pngExportAtRequestedSize() = runBlocking {
        val source = Uri.parse(saver.save(gradient(1600, 1200), SaveRequest("pixels_test_png_source.jpg")).id).also { created += it }
        val session = (useCase.open(source.toString()) as OperationResult.Success).value

        val result = useCase.export(session, EnhanceRequest(0.45f), ExportOptions(format = ExportFormat.PNG, size = ExportSize.SMALL))

        val saved = Uri.parse((result as OperationResult.Success).value.saved.id).also { created += it }
        assertEquals("image/png", resolver.getType(saved))
        assertEquals(1080 to 810, decodedSize(saved))
    }

    @Test
    fun removeLocationKeepsCameraInfoButDropsGps() = runBlocking {
        val source = Uri.parse(saver.save(gradient(800, 600), SaveRequest("pixels_test_exif.jpg")).id).also { created += it }
        resolver.openFileDescriptor(source, "rw")!!.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_MAKE, "TestCam")
                setLatLong(48.8584, 2.2945)
                saveAttributes()
            }
        }
        val session = (useCase.open(source.toString()) as OperationResult.Success).value

        val result = useCase.export(session, EnhanceRequest(0.45f), ExportOptions(metadata = MetadataPolicy.REMOVE_LOCATION))

        val saved = Uri.parse((result as OperationResult.Success).value.saved.id).also { created += it }
        val exif = resolver.openInputStream(saved)!!.use { ExifInterface(it) }
        assertEquals("TestCam", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(null, exif.latLong)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }
}
