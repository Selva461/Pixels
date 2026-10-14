package com.pixels.enhancer.domain.color

import java.nio.ByteBuffer

/**
 * Reads matrix/TRC RGB ICC profiles (sRGB, Display P3, Adobe RGB, ProPhoto…) — what cameras,
 * phones and editors embed. LUT-based profiles return null: the caller falls back to the
 * platform's own conversion to sRGB.
 */
object IccProfiles {
    private const val HEADER_SIZE = 128
    private const val TAG_ENTRY_SIZE = 12
    private const val FIXED_ONE = 65536.0
    private const val CURVE_UNIT = 65535.0
    private const val GAMMA_UNIT = 256.0
    private const val MAX_TAGS = 100
    private val PARAMETER_COUNTS = intArrayOf(1, 3, 4, 5, 7)

    fun parse(profile: ByteArray): RgbColorSpace? = runCatching { parseOrThrow(profile) }.getOrNull()

    private fun parseOrThrow(profile: ByteArray): RgbColorSpace? {
        val data = ByteBuffer.wrap(profile)
        if (profile.size < HEADER_SIZE + 4 || String(profile, 16, 4, Charsets.US_ASCII) != "RGB ") return null
        val count = data.getInt(HEADER_SIZE)
        if (count !in 1..MAX_TAGS) return null
        val tags = (0 until count).associate { i ->
            val entry = HEADER_SIZE + 4 + TAG_ENTRY_SIZE * i
            val signature = String(profile, entry, 4, Charsets.US_ASCII)
            signature to (data.getInt(entry + 4) to data.getInt(entry + 8))
        }
        fun tag(name: String): ByteBuffer? = tags[name]?.let { (offset, size) ->
            if (offset < 0 || size < 8 || offset.toLong() + size > profile.size) null else ByteBuffer.wrap(profile, offset, size).slice()
        }
        val colorants = listOf("rXYZ", "gXYZ", "bXYZ").map { tag(it) ?: return null }
        val curves = listOf("rTRC", "gTRC", "bTRC").map { name -> tag(name)?.let(::parseCurve) ?: return null }
        // One curve per channel is what every real-world RGB profile has; differing curves are not supported.
        if (curves.distinct().size != 1) return null
        val matrix = DoubleArray(9)
        colorants.forEachIndexed { column, xyz ->
            for (row in 0 until 3) matrix[row * 3 + column] = xyz.getInt(8 + 4 * row) / FIXED_ONE
        }
        return RgbColorSpace(describe(tag("desc")) ?: "ICC profile", matrix, curves.first(), profile.copyOf())
    }

    private fun parseCurve(tag: ByteBuffer): TransferFunction? = when (signature(tag)) {
        "curv" -> {
            val entries = tag.getInt(8)
            when (entries) {
                0 -> TransferFunction.Parametric(g = 1.0)
                1 -> TransferFunction.Parametric(g = (tag.getShort(12).toInt() and 0xFFFF) / GAMMA_UNIT)
                else -> TransferFunction.Table(DoubleArray(entries) { (tag.getShort(12 + 2 * it).toInt() and 0xFFFF) / CURVE_UNIT })
            }
        }
        "para" -> {
            val type = tag.getShort(8).toInt()
            if (type !in PARAMETER_COUNTS.indices) {
                null
            } else {
                val p = DoubleArray(PARAMETER_COUNTS[type]) { tag.getInt(12 + 4 * it) / FIXED_ONE }
                when (type) {
                    0 -> TransferFunction.Parametric(g = p[0])
                    // type 1: (ax+b)^g for x ≥ -b/a, else 0
                    1 -> TransferFunction.Parametric(g = p[0], a = p[1], b = p[2], d = -p[2] / p[1])
                    // type 2: (ax+b)^g + c for x ≥ -b/a, else c
                    2 -> TransferFunction.Parametric(g = p[0], a = p[1], b = p[2], d = -p[2] / p[1], e = p[3], f = p[3])
                    3 -> TransferFunction.Parametric(g = p[0], a = p[1], b = p[2], c = p[3], d = p[4])
                    else -> TransferFunction.Parametric(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
                }
            }
        }
        else -> null
    }

    private fun describe(tag: ByteBuffer?): String? = tag?.let {
        when (signature(it)) {
            "mluc" -> if (it.getInt(8) > 0) {
                val length = it.getInt(20)
                val offset = it.getInt(24)
                ByteArray(length).also { bytes -> it.position(offset); it.get(bytes) }.toString(Charsets.UTF_16BE)
            } else {
                null
            }
            "desc" -> {
                val length = it.getInt(8)
                ByteArray(length).also { bytes -> it.position(12); it.get(bytes) }.toString(Charsets.ISO_8859_1).substringBefore('\u0000')
            }
            else -> null
        }
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun signature(tag: ByteBuffer): String = String(ByteArray(4) { tag.get(it) }, Charsets.US_ASCII)
}
