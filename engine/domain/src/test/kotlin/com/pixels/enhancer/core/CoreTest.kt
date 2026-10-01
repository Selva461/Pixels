package com.pixels.enhancer.core

import com.pixels.enhancer.core.error.EnhancerException
import com.pixels.enhancer.core.error.ErrorCode
import com.pixels.enhancer.core.error.ErrorMapper
import com.pixels.enhancer.core.error.OperationResult
import com.pixels.enhancer.core.error.runControlled
import com.pixels.enhancer.core.id.ProcessingIdGenerator
import com.pixels.enhancer.core.logging.LogFormat
import com.pixels.enhancer.core.timing.StageTiming
import com.pixels.enhancer.core.timing.TimingReport
import com.pixels.enhancer.domain.model.OutputNaming
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorMapperTest {

    @Test
    fun `enhancer exception keeps its code`() {
        val failure = ErrorMapper.toFailure(EnhancerException(ErrorCode.IMAGE_UNSUPPORTED, "gif"), ErrorCode.UNKNOWN)
        assertEquals(ErrorCode.IMAGE_UNSUPPORTED, failure.code)
    }

    @Test
    fun `out of memory maps to OUT_OF_MEMORY regardless of phase`() {
        assertEquals(ErrorCode.OUT_OF_MEMORY, ErrorMapper.toFailure(OutOfMemoryError(), ErrorCode.PROCESSING_FAILED).code)
    }

    @Test
    fun `unknown exceptions map to the phase fallback`() {
        assertEquals(ErrorCode.ANALYSIS_FAILED, ErrorMapper.toFailure(IllegalStateException("x"), ErrorCode.ANALYSIS_FAILED).code)
    }

    @Test
    fun `runControlled wraps success and failure`() {
        assertIs<OperationResult.Success<Int>>(runControlled(ErrorCode.UNKNOWN) { 1 })
        val failure = runControlled(ErrorCode.SAVE_FAILED) { error("disk full") }
        assertIs<OperationResult.Failure>(failure)
        assertEquals(ErrorCode.SAVE_FAILED, failure.code)
    }

    @Test
    fun `runControlled rethrows cancellation`() {
        assertFailsWith<CancellationException> { runControlled(ErrorCode.UNKNOWN) { throw CancellationException("cancel") } }
    }
}

class ProcessingIdGeneratorTest {
    @Test
    fun `id has date and four hex digits`() {
        val id = ProcessingIdGenerator(Random(1)) { LocalDate.of(2026, 10, 1) }.next()
        assertTrue(Regex("IMG-20261001-[0-9A-F]{4}").matches(id), id)
    }
}

class OutputNamingTest {
    @Test
    fun `enhanced name keeps the original stem`() {
        assertEquals("IMG_1234_enhanced.jpg", OutputNaming.enhancedName("IMG_1234.jpg"))
        assertEquals("photo.final_enhanced.jpg", OutputNaming.enhancedName("photo.final.png"))
        assertEquals("IMG_enhanced.jpg", OutputNaming.enhancedName(null))
    }
}

class FormattingTest {
    @Test
    fun `log events render as key value pairs`() {
        assertEquals("STAGE_COMPLETE stage=Denoise durationMs=84", LogFormat.format("STAGE_COMPLETE", mapOf("stage" to "Denoise", "durationMs" to 84)))
    }

    @Test
    fun `timing report totals and aligns`() {
        val report = TimingReport(listOf(StageTiming("Decode", 42), StageTiming("Denoise", 84)))
        assertEquals(126, report.totalMs)
        assertTrue(report.format().lines().last().startsWith("TOTAL"))
        assertTrue(report.format().contains("84 ms"))
    }
}

class PixelResamplerTest {
    @Test
    fun `subsample factor never drops below the working size`() {
        assertEquals(1, com.pixels.enhancer.domain.image.PixelResampler.powerOfTwoSubsample(2000, 1500, 2560))
        assertEquals(1, com.pixels.enhancer.domain.image.PixelResampler.powerOfTwoSubsample(4032, 3024, 2560))
        assertEquals(2, com.pixels.enhancer.domain.image.PixelResampler.powerOfTwoSubsample(6000, 4000, 2560))
        assertEquals(4, com.pixels.enhancer.domain.image.PixelResampler.powerOfTwoSubsample(12000, 9000, 2560))
    }

    @Test
    fun `fit keeps aspect ratio`() {
        assertEquals(2560 to 1920, com.pixels.enhancer.domain.image.PixelResampler.fitWithin(2560, 4032, 3024))
        assertEquals(100 to 50, com.pixels.enhancer.domain.image.PixelResampler.fitWithin(2560, 100, 50))
    }
}
