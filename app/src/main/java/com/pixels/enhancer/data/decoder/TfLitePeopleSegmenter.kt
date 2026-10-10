package com.pixels.enhancer.data.decoder

import android.content.res.AssetManager
import com.pixels.enhancer.core.logging.EnhancerLogger
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.regions.SubjectHint
import com.pixels.enhancer.domain.regions.SubjectSegmenter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

/**
 * Finds people with DeepLab v3 (PASCAL VOC, 2.7 MB, Apache 2.0), bundled in the app and run on the
 * phone's CPU with TensorFlow Lite: no download, no network, the photo never leaves the device.
 * It only tells Smart edit where the subject is; it never changes or generates pixels. If the
 * model can't load, Smart edit falls back to finding the subject by rules.
 */
class TfLitePeopleSegmenter(private val assets: AssetManager, private val logger: EnhancerLogger) : SubjectSegmenter {
    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var unavailable = false

    override suspend fun segment(image: PixelBuffer): SubjectHint? = withContext(Dispatchers.Default) {
        val size = SubjectHint.PASCAL_INPUT_SIZE
        val input = ByteBuffer.allocateDirect(size * size * 3 * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(SubjectHint.pascalInput(image))
        val output = ByteBuffer.allocateDirect(size * size * SubjectHint.PASCAL_CLASSES * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        synchronized(lock) {
            val model = loaded() ?: return@withContext null
            model.run(input, output)
        }
        output.rewind()
        val logits = FloatArray(size * size * SubjectHint.PASCAL_CLASSES)
        output.asFloatBuffer().get(logits)
        SubjectHint.fromPascalLogits(logits, size, size)
    }

    /** The interpreter, created on first use; null (and not retried) if the model or runtime is missing. */
    private fun loaded(): Interpreter? {
        if (unavailable) return null
        interpreter?.let { return it }
        return try {
            val bytes = assets.open(MODEL_PATH).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buffer.rewind()
            Interpreter(buffer, Interpreter.Options().setNumThreads(THREADS)).also { interpreter = it }
        } catch (e: IOException) {
            unavailable(e)
        } catch (e: IllegalArgumentException) {
            unavailable(e)
        } catch (e: UnsatisfiedLinkError) {
            unavailable(e)
        }
    }

    private fun unavailable(cause: Throwable): Interpreter? {
        unavailable = true
        logger.error("PEOPLE_MODEL_UNAVAILABLE", throwable = cause)
        return null
    }

    private companion object {
        const val MODEL_PATH = "models/deeplab_v3.tflite"
        const val FLOAT_BYTES = 4
        const val THREADS = 2
    }
}
