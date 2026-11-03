package com.example.drawenginelab.notefile

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartLearningColorTest {
    @Test
    fun rgbaHexRoundTrip() {
        // smartlearning default stroke color "#d0d0d0D7" = RGB d0d0d0, alpha D7
        val argb = SmartLearningJson.parseRgbaHex("#d0d0d0D7")
        assertEquals(0xD7D0D0D0.toInt(), argb)
        assertEquals("#D0D0D0D7", SmartLearningJson.toRgbaHex(argb))
        assertEquals("#FF0000FF", SmartLearningJson.toRgbaHex(0xFFFF0000.toInt()))
        assertEquals(0xFF123456.toInt(), SmartLearningJson.parseRgbaHex("#123456"))
    }
}
