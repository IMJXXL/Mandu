package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSpreadRotateTest {

    @Test
    fun `shouldRotate portrait spread returns true`() {
        assertTrue(PanelSpreadRotate.shouldRotate(2000, 1000, 1080, 1920))
    }

    @Test
    fun `shouldRotate landscape returns false`() {
        assertFalse(PanelSpreadRotate.shouldRotate(2000, 1000, 1920, 1080))
    }

    @Test
    fun `shouldRotate portrait non-spread returns false`() {
        assertFalse(PanelSpreadRotate.shouldRotate(800, 1200, 1080, 1920))
    }

    @Test
    fun `shouldRotate square screen returns false`() {
        assertFalse(PanelSpreadRotate.shouldRotate(2000, 1000, 1000, 1000))
    }

    @Test
    fun `imageAngle clockwise returns 270`() {
        assertEquals(270, PanelSpreadRotate.imageAngle(0, true))
        assertEquals(270, PanelSpreadRotate.imageAngle(0, false))
    }

    @Test
    fun `imageAngle counterclockwise returns 90`() {
        assertEquals(90, PanelSpreadRotate.imageAngle(1, true))
        assertEquals(90, PanelSpreadRotate.imageAngle(1, false))
    }

    @Test
    fun `imageAngle auto rtl returns 270`() {
        assertEquals(270, PanelSpreadRotate.imageAngle(2, true))
    }

    @Test
    fun `imageAngle auto ltr returns 90`() {
        assertEquals(90, PanelSpreadRotate.imageAngle(2, false))
    }
}