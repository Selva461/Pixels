package com.pixels.enhancer.domain.regions

import com.pixels.enhancer.domain.analysis.SceneClassifier
import com.pixels.enhancer.domain.image.Argb
import com.pixels.enhancer.domain.image.BoxBlur
import com.pixels.enhancer.domain.image.Luma
import com.pixels.enhancer.domain.image.PixelBuffer
import com.pixels.enhancer.domain.image.PixelResampler
import com.pixels.enhancer.domain.image.smoothstep
import com.pixels.enhancer.domain.processing.ops.SkinToneDetector
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Parts of a photo that Smart edit and region masks can select. */
enum class RegionKind { SUBJECT, SKY, BACKGROUND }

/**
 * Soft maps (0..1 per pixel) of where the sky and the main subject are, at [width]×[height].
 * Background is everything that is neither. [subjectConfidence] is how clearly one subject stands
 * out (0 when the photo has none, e.g. an even landscape); the subject map is empty below
 * [RegionDetector.MIN_SUBJECT_CONFIDENCE].
 */
class RegionMaps(
    val width: Int,
    val height: Int,
    val sky: FloatArray,
    val subject: FloatArray,
    val subjectConfidence: Float,
) {
    val skyFraction: Float = sky.average().toFloat()
    val subjectFraction: Float = subject.average().toFloat()

    fun map(kind: RegionKind): FloatArray = when (kind) {
        RegionKind.SKY -> sky
        RegionKind.SUBJECT -> subject
        RegionKind.BACKGROUND -> FloatArray(sky.size) { i -> (1f - sky[i]) * (1f - subject[i]) }
    }

    /** The map of [kind] resized (bilinear) to [outWidth]×[outHeight]. */
    fun weights(kind: RegionKind, outWidth: Int, outHeight: Int): FloatArray =
        RegionDetector.resize(map(kind), width, height, outWidth, outHeight)

    fun fraction(kind: RegionKind): Float = when (kind) {
        RegionKind.SKY -> skyFraction
        RegionKind.SUBJECT -> subjectFraction
        RegionKind.BACKGROUND -> map(kind).average().toFloat()
    }
}

/**
 * Finds the sky and the main subject with plain image measurements — no trained model, nothing is
 * generated. Masks only decide where existing pixels are adjusted.
 *
 * - **Sky**: sky-like colour (blue, pale overcast or warm sunset cloud), smooth texture, upper part
 *   of the frame, grown from seeds near the top across gentle edges; gaps between branches are
 *   added when they match the sky's colour.
 * - **Subject**: what is in focus (sharp against a soft background), what differs in colour from
 *   the rest of the scene, and skin, weighted towards the centre; the strongest blob(s) are kept.
 *
 * Detection runs at [DETECT_LONG_EDGE]; edges are then refined against the photo's own luma at
 * [REFINE_LONG_EDGE] with a guided filter so masks follow hair, branches and horizons. Both sizes
 * are fixed, so the preview and a full-size export get the same masks.
 */
object RegionDetector {
    const val DETECT_LONG_EDGE = 256
    const val REFINE_LONG_EDGE = 768
    const val MIN_SUBJECT_CONFIDENCE = 0.2f

    private val cacheLock = Any()
    private var cachedKey: Long = 0L
    private var cached: RegionMaps? = null

    /**
     * Maps for [image]; the last result is cached, so repeated renders of one photo detect once.
     * With a [hint] that found a person (same frame as [image]), the subject is the person;
     * otherwise the rules below find it.
     */
    fun detect(image: PixelBuffer, hint: SubjectHint? = null): RegionMaps {
        val key = fingerprint(image) * 31 + (hint?.fingerprint() ?: 0L)
        synchronized(cacheLock) { if (key == cachedKey) cached?.let { return it } }
        val maps = compute(image, hint)
        synchronized(cacheLock) {
            cachedKey = key
            cached = maps
        }
        return maps
    }

    private fun compute(image: PixelBuffer, hint: SubjectHint?): RegionMaps {
        val small = PixelResampler.downscaleToFit(image, DETECT_LONG_EDGE)
        val planes = Planes.of(small)
        val sky = detectSky(planes)
        val (subject, confidence) = if (hint != null && hint.hasSubject) {
            // A person is never sky, even a dark silhouette against it.
            val person = resize(hint.weights, hint.width, hint.height, small.width, small.height)
            // Keep the main person (and anyone as prominent), not stray patches the model is unsure of.
            val core = FloatArray(person.size) { i -> if (person[i] > SubjectHint.CONFIDENT) 1f else 0f }
            keepStrongestBlobs(core, person, small.width, small.height)
            val near = FloatArray(core.size).also { BoxBlur.blur(core, it, small.width, small.height, MORPH_RADIUS, FloatArray(core.size)) }
            for (i in person.indices) {
                person[i] = if (near[i] > 0f) smoothstep(HINT_LOW, HINT_HIGH, person[i]) else 0f
                sky[i] *= 1f - person[i]
            }
            person to HINT_CONFIDENCE
        } else {
            detectSubject(planes, sky)
        }

        val refine = PixelResampler.downscaleToFit(image, REFINE_LONG_EDGE)
        val guide = FloatArray(refine.pixelCount) { Luma.ofPixel(refine.pixels[it]) }
        val radius = max(2, max(refine.width, refine.height) / GUIDE_RADIUS_DIVISOR)
        val skyFine = refineMask(resize(sky, small.width, small.height, refine.width, refine.height), guide, refine.width, refine.height, radius, SKY_EPSILON)
        val subjectFine = refineMask(resize(subject, small.width, small.height, refine.width, refine.height), guide, refine.width, refine.height, radius, SUBJECT_EPSILON)
        for (i in subjectFine.indices) subjectFine[i] *= 1f - skyFine[i]
        return RegionMaps(refine.width, refine.height, skyFine, subjectFine, confidence)
    }

    // --- Sky ---

    private fun detectSky(p: Planes): FloatArray {
        val w = p.width
        val h = p.height
        val n = w * h
        val median = p.lumaPercentile(0.5f)
        val likelihood = FloatArray(n)
        for (i in 0 until n) {
            val v = (i / w + 0.5f) / h
            likelihood[i] = skyColour(p.luma[i], p.chroma[i], p.hue[i], v, median) * (TEXTURE_FLOOR + (1f - TEXTURE_FLOOR) * (1f - smoothstep(SMOOTH_TEXTURE, ROUGH_TEXTURE, p.texture[i])))
        }

        // Grow components from confident, smooth seeds in the upper part of the frame.
        val label = IntArray(n) { -1 }
        val keep = BooleanArray(n)
        val queue = IntArray(n)
        val seedRows = (h * SEED_REGION).toInt().coerceAtLeast(1)
        var component = 0
        for (seed in 0 until seedRows * w) {
            if (label[seed] >= 0 || likelihood[seed] < SEED_LIKELIHOOD || p.texture[seed] > SMOOTH_TEXTURE * 2) continue
            var head = 0
            var tail = 0
            queue[tail++] = seed
            label[seed] = component
            var sumY = 0L
            var minY = h
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                sumY += y
                minY = min(minY, y)
                fun visit(j: Int) {
                    if (label[j] >= 0 || likelihood[j] < GROW_LIKELIHOOD || abs(p.luma[j] - p.luma[i]) > GROW_STEP) return
                    label[j] = component
                    queue[tail++] = j
                }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            val size = tail
            val centroid = (sumY.toFloat() / size + 0.5f) / h
            if (size >= n * MIN_SKY_COMPONENT && centroid < MAX_SKY_CENTROID && minY < h * SKY_TOUCH_TOP) {
                for (k in 0 until size) keep[queue[k]] = true
            }
            component++
        }

        // Sky seen through branches: pixels like the found sky, above its lower edge.
        var count = 0
        var meanL = 0f
        var meanCb = 0f
        var meanCr = 0f
        var lowest = 0
        for (i in 0 until n) if (keep[i]) {
            count++
            meanL += p.luma[i]; meanCb += p.cb[i]; meanCr += p.cr[i]
            lowest = max(lowest, i / w)
        }
        val mask = FloatArray(n)
        if (count < n * MIN_SKY_FRACTION) return mask
        meanL /= count; meanCb /= count; meanCr /= count
        for (i in 0 until n) {
            if (keep[i]) {
                mask[i] = 1f
            } else if (i / w <= lowest && likelihood[i] > GAP_LIKELIHOOD) {
                val d = sqrt((p.cb[i] - meanCb) * (p.cb[i] - meanCb) + (p.cr[i] - meanCr) * (p.cr[i] - meanCr)) + GAP_LUMA_WEIGHT * abs(p.luma[i] - meanL)
                if (d < GAP_COLOUR_DISTANCE) mask[i] = 1f
            }
        }
        // Clouds of another colour (sunset, grey) that touch the sky, down to just below its lower edge.
        val cloudLimit = max(lowest, (h * CLOUD_MAX_V).toInt())
        var tail = 0
        for (i in 0 until n) if (mask[i] == 1f) queue[tail++] = i
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (mask[j] == 1f || j / w > cloudLimit || !cloudLike(p, j, likelihood[j])) return
                mask[j] = 1f
                queue[tail++] = j
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        return mask
    }

    /**
     * Cloud next to the sky: any sky-like colour, or a fairly smooth, not dark, non-foliage pixel
     * (grey storm cloud, orange sunset cloud). Reeds, branches and hills are dark, green or rough.
     */
    private fun cloudLike(p: Planes, i: Int, likelihood: Float): Boolean {
        if (likelihood >= CLOUD_LIKELIHOOD) return true
        val foliage = p.hue[i] in FOLIAGE_HUES && p.chroma[i] > FOLIAGE_MIN_CHROMA
        return !foliage && p.luma[i] > CLOUD_MIN_LUMA && p.texture[i] < CLOUD_MAX_TEXTURE
    }

    /** How sky-like a colour is: blue sky, pale overcast/haze, warm sunset cloud, or dusk blue. */
    private fun skyColour(luma: Float, chroma: Float, hue: Float, v: Float, median: Float): Float = when {
        hue in BLUE_HUES && chroma > BLUE_MIN_CHROMA && luma > BLUE_MIN_LUMA -> 1f
        chroma < PALE_MAX_CHROMA && luma > max(PALE_MIN_LUMA, median) -> PALE_SCORE
        (hue < WARM_HUE_HIGH || hue > WARM_HUE_LOW_WRAP) && luma > WARM_MIN_LUMA && v < WARM_MAX_V -> WARM_SCORE
        hue in BLUE_HUES && luma > DUSK_MIN_LUMA -> DUSK_SCORE
        else -> 0f
    } * if (luma < median * DARK_FRACTION_OF_MEDIAN) DARK_PENALTY else 1f

    // --- Subject ---

    private fun detectSubject(p: Planes, sky: FloatArray): Pair<FloatArray, Float> {
        val w = p.width
        val h = p.height
        val n = w * h
        val scratch = FloatArray(n)

        // Focus: local Laplacian energy. Discriminative only when part of the frame is soft.
        val focus = FloatArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            focus[i] = abs(4f * p.luma[i] - p.luma[i - 1] - p.luma[i + 1] - p.luma[i - w] - p.luma[i + w])
        }
        BoxBlur.blur(focus, focus, w, h, FOCUS_RADIUS, scratch)
        val focusMedian = percentile(focus, 0.5f)
        val focusHigh = percentile(focus, 0.9f)
        val focusSpread = ((focusHigh / (focusMedian + EPS) - FOCUS_SPREAD_LOW) / FOCUS_SPREAD_RANGE).coerceIn(0f, 1f)
        normalise(focus, percentile(focus, 0.98f))

        // Colour distinctness from the (non-sky) scene average.
        val l = FloatArray(n).also { BoxBlur.blur(p.luma, it, w, h, COLOUR_RADIUS, scratch) }
        val cb = FloatArray(n).also { BoxBlur.blur(p.cb, it, w, h, COLOUR_RADIUS, scratch) }
        val cr = FloatArray(n).also { BoxBlur.blur(p.cr, it, w, h, COLOUR_RADIUS, scratch) }
        var weight = 0f
        var mL = 0f
        var mCb = 0f
        var mCr = 0f
        for (i in 0 until n) {
            val g = 1f - sky[i]
            weight += g; mL += l[i] * g; mCb += cb[i] * g; mCr += cr[i] * g
        }
        if (weight < 1f) return FloatArray(n) to 0f
        mL /= weight; mCb /= weight; mCr /= weight
        val colour = FloatArray(n) { i ->
            sqrt(LUMA_SALIENCY_WEIGHT * (l[i] - mL) * (l[i] - mL) + (cb[i] - mCb) * (cb[i] - mCb) + (cr[i] - mCr) * (cr[i] - mCr))
        }
        normalise(colour, percentile(colour, 0.98f))

        val skin = FloatArray(n) { i -> if (SkinToneDetector.isLikelySkin(p.pixels[i])) 1f else 0f }
        BoxBlur.blur(skin, skin, w, h, COLOUR_RADIUS, scratch)

        val focusWeight = FOCUS_WEIGHT_BASE + FOCUS_WEIGHT_RANGE * focusSpread
        val saliency = FloatArray(n)
        for (i in 0 until n) {
            val u = (i % w + 0.5f) / w - CENTRE_X
            val v = (i / w + 0.5f) / h - CENTRE_Y
            val prior = exp(-(u * u / (2 * CENTRE_SIGMA_X * CENTRE_SIGMA_X) + v * v / (2 * CENTRE_SIGMA_Y * CENTRE_SIGMA_Y)))
            // Flat areas (fog, blurred bokeh, sky) are not a subject however their colour stands out.
            val sharp = smoothstep(FOCUS_GATE_LOW, FOCUS_GATE_HIGH, focus[i])
            val gate = 1f - focusSpread * (1f - sharp) * FOCUS_GATE_STRENGTH
            val cue = (focusWeight * focus[i] + (1f - focusWeight) * colour[i] + SKIN_WEIGHT * skin[i]) * gate
            saliency[i] = cue * (PRIOR_FLOOR + (1f - PRIOR_FLOOR) * prior) * (1f - sky[i])
        }
        normalise(saliency, saliency.max())

        val threshold = max(otsu(saliency, sky), MIN_THRESHOLD)
        var mask = FloatArray(n) { i -> if (saliency[i] > threshold) 1f else 0f }
        // Close small gaps, then drop specks.
        mask = blurThreshold(mask, w, h, MORPH_RADIUS, CLOSE_LEVEL, scratch)
        mask = blurThreshold(mask, w, h, MORPH_RADIUS, OPEN_LEVEL, scratch)
        keepStrongestBlobs(mask, saliency, w, h)
        fillHoles(mask, w, h)

        var area = 0f
        var inside = 0f
        var outside = 0f
        var outsideCount = 0f
        for (i in 0 until n) {
            if (mask[i] > 0f) {
                area++
                inside += saliency[i]
            } else if (sky[i] < 1f) {
                outside += saliency[i]
                outsideCount++
            }
        }
        if (area == 0f) return mask to 0f
        val ratio = (inside / area) / (outside / outsideCount.coerceAtLeast(1f) + EPS)
        val fraction = area / n
        val confidence = smoothstep(RATIO_LOW, RATIO_HIGH, ratio) *
            smoothstep(MIN_SUBJECT_AREA, MIN_SUBJECT_AREA * 4, fraction) * (1f - smoothstep(LARGE_SUBJECT_AREA, MAX_SUBJECT_AREA, fraction))
        if (confidence < MIN_SUBJECT_CONFIDENCE) return FloatArray(n) to confidence
        return mask to confidence
    }

    private fun keepStrongestBlobs(mask: FloatArray, saliency: FloatArray, w: Int, h: Int) {
        val n = w * h
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        val scores = ArrayList<Float>()
        for (start in 0 until n) {
            if (mask[start] == 0f || label[start] >= 0) continue
            val id = scores.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = id
            var score = 0f
            while (head < tail) {
                val i = queue[head++]
                score += saliency[i]
                val x = i % w
                val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (mask[j] == 0f || label[j] >= 0) continue
                    label[j] = id
                    queue[tail++] = j
                }
            }
            scores += score
        }
        val best = scores.maxOrNull() ?: return
        for (i in 0 until n) if (label[i] >= 0 && scores[label[i]] < best * KEEP_BLOB_FRACTION) mask[i] = 0f
    }

    /** Fills background pixels that are fully enclosed by the subject (eyes, a shirt pattern). */
    private fun fillHoles(mask: FloatArray, w: Int, h: Int) {
        val n = w * h
        val outside = BooleanArray(n)
        val queue = IntArray(n)
        var tail = 0
        for (i in 0 until n) {
            val x = i % w
            val y = i / w
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && mask[i] == 0f) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (outside[j] || mask[j] > 0f) return
                outside[j] = true
                queue[tail++] = j
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        for (i in 0 until n) if (!outside[i]) mask[i] = 1f
    }

    // --- Shared maths ---

    /** Edge-aware smoothing of [mask] guided by [guide] (He et al.), so mask edges snap to photo edges. */
    internal fun refineMask(mask: FloatArray, guide: FloatArray, w: Int, h: Int, radius: Int, epsilon: Float): FloatArray {
        val n = w * h
        val scratch = FloatArray(n)
        val meanI = FloatArray(n).also { BoxBlur.blur(guide, it, w, h, radius, scratch) }
        val meanP = FloatArray(n).also { BoxBlur.blur(mask, it, w, h, radius, scratch) }
        val corrIp = FloatArray(n) { guide[it] * mask[it] }.also { BoxBlur.blur(it, it, w, h, radius, scratch) }
        val varI = FloatArray(n) { guide[it] * guide[it] }.also { BoxBlur.blur(it, it, w, h, radius, scratch) }
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = (varI[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            a[i] = (corrIp[i] - meanI[i] * meanP[i]) / (variance + epsilon)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        BoxBlur.blur(a, a, w, h, radius, scratch)
        BoxBlur.blur(b, b, w, h, radius, scratch)
        return FloatArray(n) { i -> (a[i] * guide[i] + b[i]).coerceIn(0f, 1f) }
    }

    /** Bilinear resize of a plane. */
    fun resize(source: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return source.copyOf()
        val out = FloatArray(dw * dh)
        val sx = sw.toFloat() / dw
        val sy = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val top = source[y0 * sw + x0] + (source[y0 * sw + x1] - source[y0 * sw + x0]) * tx
                val bottom = source[y1 * sw + x0] + (source[y1 * sw + x1] - source[y1 * sw + x0]) * tx
                out[y * dw + x] = top + (bottom - top) * ty
            }
        }
        return out
    }

    private fun blurThreshold(mask: FloatArray, w: Int, h: Int, radius: Int, level: Float, scratch: FloatArray): FloatArray {
        val blurred = FloatArray(mask.size)
        BoxBlur.blur(mask, blurred, w, h, radius, scratch)
        for (i in blurred.indices) blurred[i] = if (blurred[i] >= level) 1f else 0f
        return blurred
    }

    /** Otsu threshold of [values] (0..1) over non-sky pixels. */
    private fun otsu(values: FloatArray, sky: FloatArray): Float {
        val bins = IntArray(OTSU_BINS)
        var total = 0
        for (i in values.indices) if (sky[i] < 0.5f) {
            bins[(values[i] * (OTSU_BINS - 1)).toInt().coerceIn(0, OTSU_BINS - 1)]++
            total++
        }
        if (total == 0) return 1f
        var sumAll = 0.0
        for (b in 0 until OTSU_BINS) sumAll += b.toDouble() * bins[b]
        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0.0
        var bestBin = OTSU_BINS / 2
        for (b in 0 until OTSU_BINS) {
            weightBackground += bins[b]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += b.toDouble() * bins[b]
            val meanB = sumBackground / weightBackground
            val meanF = (sumAll - sumBackground) / weightForeground
            val between = weightBackground.toDouble() * weightForeground * (meanB - meanF) * (meanB - meanF)
            if (between > best) {
                best = between
                bestBin = b
            }
        }
        return (bestBin + 0.5f) / OTSU_BINS
    }

    private fun normalise(plane: FloatArray, top: Float) {
        if (top <= EPS) {
            plane.fill(0f)
            return
        }
        for (i in plane.indices) plane[i] = (plane[i] / top).coerceIn(0f, 1f)
    }

    private fun percentile(plane: FloatArray, q: Float): Float {
        val sorted = plane.copyOf().also { it.sort() }
        return sorted[((sorted.size - 1) * q).toInt()]
    }

    private fun fingerprint(image: PixelBuffer): Long {
        var hash = image.width.toLong() * 31 + image.height
        val step = max(1, image.pixelCount / FINGERPRINT_SAMPLES)
        var i = 0
        while (i < image.pixelCount) {
            hash = hash * 1_000_003L + image.pixels[i]
            i += step
        }
        return hash
    }

    /** Per-pixel planes of the detection image. */
    private class Planes(val width: Int, val height: Int, val pixels: IntArray, val luma: FloatArray, val cb: FloatArray, val cr: FloatArray, val chroma: FloatArray, val hue: FloatArray, val texture: FloatArray) {
        fun lumaPercentile(q: Float) = percentile(luma, q)

        companion object {
            fun of(image: PixelBuffer): Planes {
                val n = image.pixelCount
                val w = image.width
                val h = image.height
                val luma = FloatArray(n)
                val cb = FloatArray(n)
                val cr = FloatArray(n)
                val chroma = FloatArray(n)
                val hue = FloatArray(n)
                for (i in 0 until n) {
                    val c = image.pixels[i]
                    val r = Argb.red(c) / 255f
                    val g = Argb.green(c) / 255f
                    val b = Argb.blue(c) / 255f
                    val y = Luma.of(r, g, b)
                    luma[i] = y
                    cb[i] = (b - y) * CB_SCALE
                    cr[i] = (r - y) * CR_SCALE
                    chroma[i] = max(r, max(g, b)) - min(r, min(g, b))
                    hue[i] = SceneClassifier.hueOf(c)
                }
                val gradient = FloatArray(n)
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val gx = luma[y * w + min(x + 1, w - 1)] - luma[y * w + max(x - 1, 0)]
                    val gy = luma[min(y + 1, h - 1) * w + x] - luma[max(y - 1, 0) * w + x]
                    gradient[i] = (abs(gx) + abs(gy)) * 0.5f
                }
                BoxBlur.blur(gradient, gradient, w, h, TEXTURE_RADIUS, FloatArray(n))
                return Planes(w, h, image.pixels, luma, cb, cr, chroma, hue, gradient)
            }
        }
    }

    private const val EPS = 1e-4f
    private const val HINT_LOW = 0.25f
    private const val HINT_HIGH = 0.6f
    private const val HINT_CONFIDENCE = 0.9f
    private const val CB_SCALE = 0.5389f
    private const val CR_SCALE = 0.6350f
    private const val TEXTURE_RADIUS = 2
    private const val GUIDE_RADIUS_DIVISOR = 96
    private const val SKY_EPSILON = 0.004f
    private const val SUBJECT_EPSILON = 0.01f
    private const val FINGERPRINT_SAMPLES = 4096

    // Sky colour and texture rules.
    private val BLUE_HUES = 170f..260f
    private const val BLUE_MIN_CHROMA = 0.05f
    private const val BLUE_MIN_LUMA = 0.3f
    private const val PALE_MAX_CHROMA = 0.14f
    private const val PALE_MIN_LUMA = 0.5f
    private const val PALE_SCORE = 0.85f
    private const val WARM_HUE_HIGH = 55f
    private const val WARM_HUE_LOW_WRAP = 320f
    private const val WARM_MIN_LUMA = 0.45f
    private const val WARM_MAX_V = 0.6f
    private const val WARM_SCORE = 0.65f
    private const val DUSK_MIN_LUMA = 0.15f
    private const val DUSK_SCORE = 0.5f
    private const val DARK_FRACTION_OF_MEDIAN = 0.6f
    private const val DARK_PENALTY = 0.3f
    private const val SMOOTH_TEXTURE = 0.025f
    private const val ROUGH_TEXTURE = 0.1f
    private const val TEXTURE_FLOOR = 0.3f
    private const val SEED_REGION = 0.3f
    private const val SEED_LIKELIHOOD = 0.6f
    private const val GROW_LIKELIHOOD = 0.3f
    private const val GROW_STEP = 0.08f
    private const val MIN_SKY_COMPONENT = 0.002f
    private const val MAX_SKY_CENTROID = 0.55f
    private const val SKY_TOUCH_TOP = 0.35f
    private const val MIN_SKY_FRACTION = 0.01f
    private const val GAP_LIKELIHOOD = 0.5f
    private const val GAP_LUMA_WEIGHT = 0.5f
    private const val GAP_COLOUR_DISTANCE = 0.07f
    private const val CLOUD_LIKELIHOOD = 0.4f
    private const val CLOUD_MAX_V = 0.85f
    private const val CLOUD_MIN_LUMA = 0.22f
    private const val CLOUD_MAX_TEXTURE = 0.07f
    private val FOLIAGE_HUES = 65f..170f
    private const val FOLIAGE_MIN_CHROMA = 0.08f

    // Subject cues.
    private const val FOCUS_RADIUS = 3
    private const val FOCUS_SPREAD_LOW = 2f
    private const val FOCUS_SPREAD_RANGE = 4f
    private const val FOCUS_WEIGHT_BASE = 0.3f
    private const val FOCUS_WEIGHT_RANGE = 0.55f
    private const val FOCUS_GATE_LOW = 0.08f
    private const val FOCUS_GATE_HIGH = 0.35f
    private const val FOCUS_GATE_STRENGTH = 0.85f
    private const val COLOUR_RADIUS = 2
    private const val LUMA_SALIENCY_WEIGHT = 0.5f
    private const val SKIN_WEIGHT = 0.5f
    private const val CENTRE_X = 0.5f
    private const val CENTRE_Y = 0.55f
    private const val CENTRE_SIGMA_X = 0.28f
    private const val CENTRE_SIGMA_Y = 0.32f
    private const val PRIOR_FLOOR = 0.3f
    private const val MIN_THRESHOLD = 0.25f
    private const val OTSU_BINS = 64
    private const val MORPH_RADIUS = 2
    private const val CLOSE_LEVEL = 0.35f
    private const val OPEN_LEVEL = 0.6f
    private const val KEEP_BLOB_FRACTION = 0.35f
    private const val RATIO_LOW = 1.6f
    private const val RATIO_HIGH = 3.2f
    private const val MIN_SUBJECT_AREA = 0.006f
    private const val LARGE_SUBJECT_AREA = 0.45f
    private const val MAX_SUBJECT_AREA = 0.7f
}
