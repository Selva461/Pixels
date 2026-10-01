package com.pixels.enhancer.core.logging

/**
 * Structured event logger. Fields must only contain metadata (sizes, scores, timings, codes) —
 * never pixel data, file contents or sensitive EXIF values.
 */
interface EnhancerLogger {
    fun event(name: String, fields: Map<String, Any?> = emptyMap())
    fun error(name: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null)
}

object NoOpLogger : EnhancerLogger {
    override fun event(name: String, fields: Map<String, Any?>) = Unit
    override fun error(name: String, fields: Map<String, Any?>, throwable: Throwable?) = Unit
}

object LogFormat {

    /** Renders an event as `NAME key=value key=value`, the single format used by every logger. */
    fun format(name: String, fields: Map<String, Any?>): String = buildString {
        append(name)
        fields.forEach { (key, value) ->
            append(' ').append(key).append('=').append(formatValue(value))
        }
    }

    private fun formatValue(value: Any?): String = when (value) {
        is Float -> "%.3f".format(value)
        is Double -> "%.3f".format(value)
        null -> "null"
        else -> value.toString()
    }
}
