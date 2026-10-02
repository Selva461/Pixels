package com.pixels.enhancer.domain.debug

import com.pixels.enhancer.domain.usecase.EnhancementOutcome
import com.pixels.enhancer.domain.usecase.EnhancementSession
import java.util.Locale

data class DebugSection(val title: String, val lines: List<String>)

/** Builds the human-readable debug report shown on the debug screen and exported as text. */
object DebugReport {
    private const val LABEL_WIDTH = 16

    fun sections(session: EnhancementSession, outcome: EnhancementOutcome?): List<DebugSection> = buildList {
        add(inputSection(session))
        add(analysisSection(session))
        if (outcome != null) {
            add(planSection(outcome))
            add(pipelineSection(outcome))
            add(DebugSection("Stage Timings", outcome.timings.format().lines()))
            add(validationSection(outcome))
        }
    }

    fun format(session: EnhancementSession, outcome: EnhancementOutcome?): String =
        sections(session, outcome).joinToString("\n\n") { section ->
            "[${section.title}]\n\n" + section.lines.joinToString("\n")
        }

    private fun inputSection(session: EnhancementSession): DebugSection {
        val source = session.source
        return DebugSection(
            "Input",
            listOf(
                row("Type", source.mimeType),
                row("Original", "${source.width}x${source.height}"),
                row("Rotation", "${source.rotationDegrees}°" + if (source.mirrored) " (mirrored)" else ""),
                row("Working", "${session.original.width}x${session.original.height}"),
                row("Preview", "${session.preview.width}x${session.preview.height}"),
                row("Preset", session.preset.displayName),
            ),
        )
    }

    private fun analysisSection(session: EnhancementSession): DebugSection {
        val analysis = session.analysis
        return DebugSection(
            "Image Analysis",
            listOf(
                row("Exposure", fmt(analysis.exposureScore)),
                row("Contrast", fmt(analysis.contrastScore)),
                row("Noise", "${fmt(analysis.noiseScore)} (sigma ${fmt(analysis.noiseSigma, 4)})"),
                row("Sharpness", fmt(analysis.sharpnessScore)),
                row("Saturation", fmt(analysis.saturationScore)),
                row("Color cast", fmt(analysis.colorCastScore)),
                row("Highlight clip", fmt(analysis.highlightClipping, 4)),
                row("Shadow clip", fmt(analysis.shadowClipping, 4)),
                row(
                    "Luma p1/50/99",
                    "${fmt(analysis.luminance.p1)} / ${fmt(analysis.luminance.p50)} / ${fmt(analysis.luminance.p99)}",
                ),
            ),
        )
    }

    private fun planSection(outcome: EnhancementOutcome): DebugSection {
        val plan = outcome.plan
        val header = listOf(
            row("Processing ID", outcome.processingId),
            row("Strength", "${(plan.strength * PERCENT).toInt()}%"),
            row("Algorithm", plan.algorithmVersion),
            row("Rendered", "${outcome.request.target} ${outcome.processed.image.width}x${outcome.processed.image.height}"),
            row("Manual", if (outcome.request.manual.isNeutral) "none" else "${outcome.request.manual.values.size} control(s)"),
            "",
        )
        val rows = plan.entries().map { (kind, adjustment) ->
            val amount = if (adjustment.enabled) signed(adjustment.amount) else "  —  "
            row(kind.label, amount) + "  ${adjustment.reason}"
        }
        return DebugSection("Enhancement Plan", header + rows)
    }

    private fun pipelineSection(outcome: EnhancementOutcome): DebugSection {
        val processed = outcome.processed
        val executed = processed.executedStages.map { "[x] $it" }
        val skipped = processed.skippedStages.map { "[ ] ${it.stageId} — ${it.reason}" }
        val until = outcome.request.runUntilStageId?.let { listOf("", "Stopped after: $it") }.orEmpty()
        return DebugSection("Pipeline", executed + skipped + until)
    }

    private fun validationSection(outcome: EnhancementOutcome): DebugSection = DebugSection(
        "Output Validation",
        outcome.validation.checks.map { "${if (it.passed) "PASS" else "FAIL"}  ${it.name.padEnd(LABEL_WIDTH)}${it.detail}" },
    )

    private fun row(label: String, value: String) = label.padEnd(LABEL_WIDTH) + value

    private fun fmt(value: Float, decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun signed(value: Float) = String.format(Locale.ROOT, "%+.3f", value)

    private const val PERCENT = 100
}
