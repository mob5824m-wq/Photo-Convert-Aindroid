package com.pixelnas.photosaver.converter

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class TargetDimensions(
    val width: Int,
    val height: Int,
    val wasResized: Boolean,
    val sampleSize: Int
)

object PhotoDimensionCalculator {

    /**
     * Computes both:
     * 1. OOM-safe power-of-2 `inSampleSize` so 50MP/108MP/200MP photos decode safely on 4GB RAM Pixel 1–3
     *    without ever dropping below the target resolution before fine bicubic scaling.
     * 2. Exact Google Photos Storage Saver target dimensions (`width * height <= maxMegapixels * 1_000_000`).
     */
    fun calculate(
        origWidth: Int,
        origHeight: Int,
        maxMegapixels: Double = 16.0
    ): TargetDimensions {
        if (origWidth <= 0 || origHeight <= 0) {
            return TargetDimensions(max(1, origWidth), max(1, origHeight), false, 1)
        }
        val maxPixels = (maxMegapixels * 1_000_000.0).roundToLong()
        val totalPixels = origWidth.toLong() * origHeight.toLong()
        if (totalPixels <= maxPixels) {
            return TargetDimensions(origWidth, origHeight, false, 1)
        }

        val scale = sqrt(maxPixels.toDouble() / totalPixels.toDouble())
        var newW = max(1, floor(origWidth * scale).toInt())
        var newH = max(1, floor(origHeight * scale).toInt())

        while (newW.toLong() * newH.toLong() > maxPixels) {
            if (newW >= newH && newW > 1) {
                newW -= 1
            } else if (newH > 1) {
                newH -= 1
            } else {
                break
            }
        }

        // Compute largest power-of-two sampleSize where sampled dimensions remain >= target dimensions
        var sample = 1
        while ((origWidth / (sample * 2)) >= newW && (origHeight / (sample * 2)) >= newH) {
            sample *= 2
        }

        return TargetDimensions(
            width = newW,
            height = newH,
            wasResized = true,
            sampleSize = sample
        )
    }
}
