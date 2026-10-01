package com.pixels.enhancer.core.timing

/** Injectable monotonic clock so timing-dependent code is testable. */
fun interface MonotonicClock {
    fun nowNanos(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

const val NANOS_PER_MILLI = 1_000_000L

inline fun <T> MonotonicClock.measure(block: () -> T): Timed<T> {
    val start = nowNanos()
    val value = block()
    return Timed(value, (nowNanos() - start) / NANOS_PER_MILLI)
}

data class Timed<out T>(val value: T, val durationMs: Long)

data class StageTiming(val name: String, val durationMs: Long)

data class TimingReport(val entries: List<StageTiming>) {

    val totalMs: Long get() = entries.sumOf { it.durationMs }

    operator fun plus(other: TimingReport) = TimingReport(entries + other.entries)

    /** Aligned table matching the debug output format, e.g. `Denoise   84 ms`. */
    fun format(): String {
        val nameWidth = (entries.map { it.name.length } + TOTAL_LABEL.length).max() + COLUMN_GAP
        return buildString {
            entries.forEach { appendLine(row(it.name, it.durationMs, nameWidth)) }
            appendLine()
            append(row(TOTAL_LABEL, totalMs, nameWidth))
        }
    }

    private fun row(name: String, ms: Long, nameWidth: Int) =
        name.padEnd(nameWidth) + ms.toString().padStart(MS_WIDTH) + " ms"

    companion object {
        val EMPTY = TimingReport(emptyList())
        private const val TOTAL_LABEL = "TOTAL"
        private const val COLUMN_GAP = 4
        private const val MS_WIDTH = 6
    }
}
