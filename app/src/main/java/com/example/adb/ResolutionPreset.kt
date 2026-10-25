package com.example.adb

import kotlin.math.roundToInt

/**
 * Supported screen resolution presets (480p, 540p, 720p, 1080p).
 */
enum class ResolutionPreset(val label: String, val targetP: Int) {
    P480("480p", 480),
    P540("540p", 540),
    P720("720p", 720),
    P1080("1080p", 1080);

    companion object {
        fun fromLabel(label: String?): ResolutionPreset =
            entries.find { it.label.equals(label, ignoreCase = true) } ?: P720
    }
}

/**
 * Resulting screen dimensions and density calculated for a given device aspect ratio.
 */
data class ScaledResolution(
    val preset: ResolutionPreset,
    val width: Int,
    val height: Int,
    val density: Int,
    val aspectRatio: Double
) {
    val sizeString: String get() = "${width}x${height}"
}

/**
 * Calculates scaled screen dimensions and density proportionally based on device native aspect ratio.
 */
object ResolutionScaler {

    fun calculateScaledResolution(
        preset: ResolutionPreset,
        baseWidth: Int,
        baseHeight: Int,
        baseDensity: Int
    ): ScaledResolution {
        val isPortrait = baseHeight >= baseWidth
        val nativeShort = if (isPortrait) baseWidth else baseHeight
        val nativeLong = if (isPortrait) baseHeight else baseWidth

        val safeShort = if (nativeShort > 0) nativeShort else 1080
        val safeLong = if (nativeLong > 0) nativeLong else 2400
        val safeDensity = if (baseDensity > 0) baseDensity else 432

        val aspectRatio = safeLong.toDouble() / safeShort.toDouble()
        val targetShort = preset.targetP

        // Compute long dimension and ensure it is an even number
        val rawTargetLong = (targetShort * aspectRatio).roundToInt()
        val targetLong = if (rawTargetLong % 2 != 0) rawTargetLong + 1 else rawTargetLong

        // Proportional density so UI touch targets and icons retain comfortable physical size
        val densityRatio = targetShort.toDouble() / safeShort.toDouble()
        val targetDensity = (safeDensity * densityRatio).roundToInt().coerceAtLeast(120)

        val scaledWidth = if (isPortrait) targetShort else targetLong
        val scaledHeight = if (isPortrait) targetLong else targetShort

        return ScaledResolution(
            preset = preset,
            width = scaledWidth,
            height = scaledHeight,
            density = targetDensity,
            aspectRatio = aspectRatio
        )
    }
}
