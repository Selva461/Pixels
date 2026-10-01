package com.pixels.enhancer.core.id

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Produces IDs such as `IMG-20261001-8F31` so one user action can be traced through the logs. */
class ProcessingIdGenerator(
    private val random: Random = Random.Default,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    fun next(): String {
        val suffix = random.nextInt(SUFFIX_RANGE).toString(HEX_RADIX).uppercase().padStart(SUFFIX_DIGITS, '0')
        return "IMG-${today().format(DateTimeFormatter.BASIC_ISO_DATE)}-$suffix"
    }

    private companion object {
        const val SUFFIX_DIGITS = 4
        const val SUFFIX_RANGE = 0x10000
        const val HEX_RADIX = 16
    }
}
