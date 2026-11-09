package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [GesturePlan]: the planner is pure Kotlin, so the argument handling and the
 * platform limits can be checked without a device. Injection itself is verified on a phone.
 */
class GesturePlanTest {

    private fun ok(steps: List<Any?>, gap: Long? = null): GesturePlan.Outcome.Ok {
        val out = GesturePlan.plan(steps, gap)
        assertTrue("expected Ok, got $out", out is GesturePlan.Outcome.Ok)
        return out as GesturePlan.Outcome.Ok
    }

    private fun bad(steps: List<Any?>, gap: Long? = null): String {
        val out = GesturePlan.plan(steps, gap)
        assertTrue("expected Bad, got $out", out is GesturePlan.Outcome.Bad)
        return (out as GesturePlan.Outcome.Bad).message
    }

    private fun step(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(*pairs)

    @Test
    fun tapIsOneShortStroke() {
        val out = ok(listOf(step("kind" to "tap", "x" to 10, "y" to 20)))
        assertEquals(1, out.strokes.size)
        val stroke = out.strokes.single()
        assertEquals(listOf(10 to 20), stroke.points)
        assertEquals(0L, stroke.startMs)
        assertEquals(GesturePlan.DEFAULT_TAP_MS, stroke.durationMs)
    }

    @Test
    fun longPressTakesAnyDuration() {
        // The whole point of the tool: the press is caller-timed, not the system's long-press timeout.
        assertEquals(GesturePlan.DEFAULT_LONG_MS, ok(listOf(step("kind" to "long", "x" to 1, "y" to 2))).strokes.single().durationMs)
        val held = ok(listOf(step("kind" to "long", "x" to 1, "y" to 2, "duration" to 9_000))).strokes.single()
        assertEquals(9_000L, held.durationMs)
    }

    @Test
    fun doubleTapIsTwoStrokesOnOnePoint() {
        val out = ok(listOf(step("kind" to "double", "x" to 5, "y" to 6)))
        assertEquals(2, out.strokes.size)
        assertEquals(listOf(5 to 6), out.strokes[0].points)
        assertEquals(listOf(5 to 6), out.strokes[1].points)
        assertEquals(0L, out.strokes[0].startMs)
        assertEquals(GesturePlan.DEFAULT_TAP_MS + GesturePlan.DEFAULT_DOUBLE_GAP_MS, out.strokes[1].startMs)
    }

    @Test
    fun swipeRunsFromOnePointToTheOther() {
        val stroke = ok(listOf(step("kind" to "swipe", "x" to 1, "y" to 2, "x2" to 30, "y2" to 40, "duration" to 700)))
            .strokes.single()
        assertEquals(listOf(1 to 2, 30 to 40), stroke.points)
        assertEquals(700L, stroke.durationMs)
    }

    @Test
    fun pathKeepsEveryPointAndDropsRepeats() {
        val stroke = ok(
            listOf(step("kind" to "path", "points" to listOf(listOf(1, 1), listOf(1, 1), listOf(2, 5), listOf(9, 9)))),
        ).strokes.single()
        assertEquals(listOf(1 to 1, 2 to 5, 9 to 9), stroke.points)

        assertTrue(bad(listOf(step("kind" to "path", "points" to listOf(listOf(1, 1), listOf(1, 1)))))
            .contains("只剩一个点"))
    }

    @Test
    fun pinchOpensTwoFingersThatNeverCross() {
        val zoomIn = ok(listOf(step("kind" to "pinch", "x" to 500, "y" to 900, "spread" to 600))).strokes
        assertEquals(2, zoomIn.size)
        // Fingers start apart, then move away from each other: the top goes up, the bottom goes down.
        assertTrue(zoomIn[0].points[0].second > zoomIn[0].points[1].second)
        assertTrue(zoomIn[1].points[1].second > zoomIn[1].points[0].second)

        val zoomOut = ok(listOf(step("kind" to "pinch", "x" to 500, "y" to 900, "spread" to -600))).strokes
        assertTrue(zoomOut[0].points[1].second > zoomOut[0].points[0].second)
        assertTrue(zoomOut[1].points[0].second > zoomOut[1].points[1].second)
    }

    @Test
    fun stepsRunInOrderWithAGap() {
        val out = ok(
            listOf(
                step("kind" to "tap", "x" to 1, "y" to 1),
                step("kind" to "tap", "x" to 2, "y" to 2),
            ),
            gap = 200,
        )
        assertEquals(2, out.strokes.size)
        assertEquals(0L, out.strokes[0].startMs)
        assertEquals(GesturePlan.DEFAULT_TAP_MS + 200, out.strokes[1].startMs)
        assertEquals("tap (1,1) → tap (2,2)", out.summary)
    }

    @Test
    fun rejectsWhatOneGestureDescriptionCannotCarry() {
        assertTrue(bad(emptyList()).contains("至少一个步骤"))
        assertTrue(bad(List(11) { step("kind" to "tap", "x" to 1, "y" to 1) }).contains("最多 10 个步骤"))
        // 11 double taps are 22 strokes, over the 10 a single GestureDescription allows.
        assertTrue(bad(List(6) { step("kind" to "double", "x" to 1, "y" to 1) }).contains("最多 10 条手指轨迹"))
        assertTrue(bad(listOf(step("kind" to "long", "x" to 1, "y" to 1, "duration" to 61_000))).contains("毫秒之间"))
        assertTrue(bad(listOf(step("kind" to "double", "x" to 1, "y" to 1, "gap" to 900))).contains("双击要求"))
        // Android's window is getDoubleTapMinTime()..getDoubleTapTimeout(), i.e. 40..300 ms.
        assertTrue(bad(listOf(step("kind" to "double", "x" to 1, "y" to 1, "gap" to 30))).contains("双击要求"))
        assertTrue(bad(listOf(step("kind" to "double", "x" to 1, "y" to 1, "gap" to 301))).contains("双击要求"))
        // Past the long-press timeout the first press stops being a tap, so a double needs short presses.
        assertTrue(bad(listOf(step("kind" to "double", "x" to 1, "y" to 1, "duration" to 900))).contains("就不是双击"))
        // A pinch or a swipe that moves nothing is not a gesture.
        assertTrue(bad(listOf(step("kind" to "pinch", "x" to 500, "y" to 700, "spread" to 10))).contains("几乎不动"))
        assertTrue(bad(listOf(step("kind" to "swipe", "x" to 5, "y" to 5, "x2" to 5, "y2" to 5))).contains("手指不会动"))
    }

    @Test
    fun rejectsArgumentsThatAreNotCoordinates() {
        assertTrue(bad(listOf("not an object")).contains("要是一个对象"))
        assertTrue(bad(listOf(step("kind" to "tap", "x" to -1, "y" to 5))).contains("不能是负数"))
        assertTrue(bad(listOf(step("kind" to "tap", "x" to 1))).contains("缺少整数 y"))
        assertTrue(bad(listOf(step("kind" to "swipe", "x" to 1, "y" to 1, "x2" to 2))).contains("缺少整数 y2"))
        assertTrue(bad(listOf(step("kind" to "zoom", "x" to 1, "y" to 1))).contains("kind 是「zoom」"))
        assertTrue(bad(listOf(step("kind" to "pinch", "x" to 1, "y" to 1, "spread" to 0))).contains("spread 是 0"))
    }

    @Test
    fun acceptsDoublesAndNumericStringsForCoordinates() {
        val stroke = ok(listOf(step("kind" to "tap", "x" to 12.0, "y" to "34"))).strokes.single()
        assertEquals(listOf(12 to 34), stroke.points)
    }
}
