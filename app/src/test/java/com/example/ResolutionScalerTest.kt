package com.example

import com.example.adb.ResolutionPreset
import com.example.adb.ResolutionScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionScalerTest {

    @Test
    fun testResolutionPresetsEnum() {
        val presets = ResolutionPreset.entries
        assertEquals(4, presets.size)
        assertEquals("480p", presets[0].label)
        assertEquals("540p", presets[1].label)
        assertEquals("720p", presets[2].label)
        assertEquals("1080p", presets[3].label)

        assertEquals(ResolutionPreset.P480, ResolutionPreset.fromLabel("480p"))
        assertEquals(ResolutionPreset.P540, ResolutionPreset.fromLabel("540P"))
        assertEquals(ResolutionPreset.P720, ResolutionPreset.fromLabel("720p"))
        assertEquals(ResolutionPreset.P1080, ResolutionPreset.fromLabel("1080p"))
        // Fallback default
        assertEquals(ResolutionPreset.P720, ResolutionPreset.fromLabel("unknown"))
    }

    @Test
    fun testPortraitAspectRatioScaling20by9() {
        // Typical modern 20:9 phone: 1080x2400 @ 432 dpi
        val baseW = 1080
        val baseH = 2400
        val baseD = 432

        val res480 = ResolutionScaler.calculateScaledResolution(ResolutionPreset.P480, baseW, baseH, baseD)
        assertEquals(480, res480.width)
        // 480 * (2400/1080) = 1066.66 -> rounded to even 1068
        assertTrue(res480.height % 2 == 0)
        assertEquals(1068, res480.height)
        assertEquals(192, res480.density)

        val res540 = ResolutionScaler.calculateScaledResolution(ResolutionPreset.P540, baseW, baseH, baseD)
        assertEquals(540, res540.width)
        assertEquals(1200, res540.height)
        assertEquals(216, res540.density)

        val res720 = ResolutionScaler.calculateScaledResolution(ResolutionPreset.P720, baseW, baseH, baseD)
        assertEquals(720, res720.width)
        assertEquals(1600, res720.height)
        assertEquals(288, res720.density)

        val res1080 = ResolutionScaler.calculateScaledResolution(ResolutionPreset.P1080, baseW, baseH, baseD)
        assertEquals(1080, res1080.width)
        assertEquals(2400, res1080.height)
        assertEquals(432, res1080.density)
    }

    @Test
    fun testPortraitAspectRatioScaling16by9() {
        // Standard 16:9 phone: 1080x1920 @ 420 dpi
        val baseW = 1080
        val baseH = 1920
        val baseD = 420

        val res720 = ResolutionScaler.calculateScaledResolution(ResolutionPreset.P720, baseW, baseH, baseD)
        assertEquals(720, res720.width)
        assertEquals(1280, res720.height)
        assertEquals(280, res720.density)
    }

    @Test
    fun testLandscapeOrientationPreservation() {
        // Tablet / gaming handheld in landscape: 2400x1080 @ 432 dpi
        val baseW = 2400
        val baseH = 1080
        val baseD = 432

        val res720 = ResolutionScaler.calculateScaledResolution(ResolutionPreset.P720, baseW, baseH, baseD)
        assertEquals(1600, res720.width)
        assertEquals(720, res720.height)
        assertEquals(288, res720.density)
    }
}
