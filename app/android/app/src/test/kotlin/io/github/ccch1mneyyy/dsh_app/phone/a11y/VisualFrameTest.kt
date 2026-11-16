package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFrameTest {
    private fun frame(value: Int = 20): VisualFrame =
        VisualFrame(160, 160, 16, 16, ByteArray(16 * 16) { value.toByte() })

    private fun changed(base: VisualFrame, cells: List<Pair<Int, Int>>, value: Int): VisualFrame {
        val copy = base.luminance.copyOf()
        for ((x, y) in cells) copy[y * base.columns + x] = value.toByte()
        return base.copy(luminance = copy)
    }

    @Test fun smallBrightnessNoiseIsAcceptedForAStableTarget() {
        val before = frame()
        val after = frame(30)
        assertTrue(after.matches(before, listOf(80 to 80)))
    }

    @Test fun unrelatedDynamicPatchDoesNotInvalidateTheTarget() {
        val before = frame()
        val after = changed(before, listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), 180)
        assertTrue(after.matches(before, listOf(80 to 80)))
    }

    @Test fun targetPatchIsRejectedEvenWhenWholeFrameMeanIsSmall() {
        val before = frame()
        val after = changed(before, (7..9).flatMap { y -> (7..9).map { x -> x to y } }, 220)
        assertFalse(after.matches(before, listOf(80 to 80)))
    }

    @Test fun dimensionsMustStayBoundToTheOriginalScreenshot() {
        val before = frame()
        val after = VisualFrame(161, 160, 16, 16, ByteArray(16 * 16) { 20 })
        assertFalse(after.matches(before))
    }
}
