package io.github.ccch1mneyyy.dsh_app.phone

/**
 * Turns the `gesture` tool's `steps` array into the strokes a driver injects, and holds the
 * limits Android puts on one gesture. Pure Kotlin, no Android types: it is unit-tested on
 * the JVM. Every string here is read by the model, never shown to the person, so none of it
 * is translated.
 */
// l10n:keep-begin agent tool result
object GesturePlan {
    /** One GestureDescription carries at most this many strokes, for at most this long. */
    const val MAX_STROKES = 10
    const val MAX_MS = 60_000L

    /** Steps in one call. A longer sequence is two calls. */
    const val MAX_STEPS = 10

    /** Points in one path. */
    const val MAX_POINTS = 32

    /**
     * Android's double-tap window. GestureDetector measures it from the first tap's
     * up to the second tap's down: getDoubleTapMinTime() … getDoubleTapTimeout().
     */
    const val MIN_DOUBLE_GAP_MS = 40L
    const val MAX_DOUBLE_GAP_MS = 300L

    /**
     * The longest single press of a double tap. Past the system's long-press timeout the first press
     * arrives as a long press and the double is lost, so this stays well under it.
     */
    const val MAX_DOUBLE_TAP_MS = 300L

    const val MIN_MS = 1L
    const val DEFAULT_GAP_MS = 120L
    const val DEFAULT_TAP_MS = 60L
    const val DEFAULT_LONG_MS = 800L
    const val DEFAULT_SWIPE_MS = 300L
    const val DEFAULT_DOUBLE_GAP_MS = 120L
    const val DEFAULT_PINCH_MS = 300L

    /** How far each finger of a pinch starts from its centre. */
    const val PINCH_RADIUS = 220

    /** Below this the fingers barely move; a screenshot is thousands of pixels across. */
    const val MIN_PINCH_SPREAD = 40

    /** The kinds a step may use, in the order the tool description lists them. */
    val KINDS = listOf("tap", "long", "double", "swipe", "path", "pinch")

    sealed interface Outcome {
        /** [strokes] is ready to inject; [summary] is one line for the log and the confirm card. */
        data class Ok(val strokes: List<GestureStroke>, val summary: String) : Outcome

        /** [message] says what is wrong with the arguments, for the model. */
        data class Bad(val message: String) : Outcome
    }

    /** Thrown while reading the arguments, caught in [plan]. */
    private class Bad(message: String) : Exception(message)

    /**
     * Plans one gesture. [steps] is the raw `steps` array of the tool call: each item is an
     * object with a `kind` and the coordinates that kind needs. [gapMs] is the pause between
     * two steps.
     */
    fun plan(steps: List<*>, gapMs: Long?): Outcome = try {
        val gap = (gapMs ?: DEFAULT_GAP_MS).coerceIn(0L, 5_000L)
        if (steps.isEmpty()) throw Bad("gesture 需要至少一个步骤，现在 steps 是空的。")
        if (steps.size > MAX_STEPS) throw Bad("一次 gesture 最多 $MAX_STEPS 个步骤，这次有 ${steps.size} 个。分成两次调用。")
        val placed = ArrayList<GestureStroke>()
        val words = ArrayList<String>()
        var at = 0L
        for ((index, raw) in steps.withIndex()) {
            val step = step(fields(raw, index), index)
            for (stroke in step.strokes) placed += stroke.copy(startMs = stroke.startMs + at)
            words += step.word
            at += step.spanMs + gap
        }
        if (placed.size > MAX_STROKES) throw Bad(
            "一次 gesture 最多 $MAX_STROKES 条手指轨迹（点击 1 条、双击 2 条、双指缩放 2 条），这次是 ${placed.size} 条。分成两次调用。"
        )
        val span = placed.maxOf { it.startMs + it.durationMs }
        if (span > MAX_MS) throw Bad(
            "一次 gesture 最长 ${MAX_MS / 1000} 秒，这次是 ${(span + 999) / 1000} 秒。把长按或滑动的时间缩短，或分成两次调用。"
        )
        Outcome.Ok(placed, words.joinToString(" → "))
    } catch (e: Bad) {
        Outcome.Bad(e.message ?: "gesture 的参数不对。")
    }

    /** One planned step, with the strokes' times relative to the start of the step. */
    private class Step(val strokes: List<GestureStroke>, val spanMs: Long, val word: String)

    private fun step(m: Map<String, Any?>, index: Int): Step {
        val at = "steps[$index]"
        return when (val kind = text(m, "kind")) {
            null -> throw Bad("$at 缺少 kind，只能是 ${KINDS.joinToString(" / ")}。")
            "tap", "long" -> {
                val ms = millis(m, "duration", index, if (kind == "long") DEFAULT_LONG_MS else DEFAULT_TAP_MS)
                val point = point(m, index)
                Step(
                    listOf(GestureStroke(listOf(point), 0, ms)),
                    ms,
                    if (kind == "long") "hold ${show(point)} ${ms}ms" else "tap ${show(point)}",
                )
            }
            "double" -> {
                val ms = millis(m, "duration", index, DEFAULT_TAP_MS)
                // A press long enough for the system's long-press timeout is no longer a tap, so the
                // first half of the double would become a long press and the second a plain tap.
                if (ms > MAX_DOUBLE_TAP_MS) throw Bad("$at 的 duration 是 ${ms}ms，双击的每次点击最长 ${MAX_DOUBLE_TAP_MS}ms；再长系统会当成一次长按，就不是双击了。长时间按住请用 long 步骤。")
                val between = millis(m, "gap", index, DEFAULT_DOUBLE_GAP_MS)
                if (between !in MIN_DOUBLE_GAP_MS..MAX_DOUBLE_GAP_MS) throw Bad("$at 的两次点击隔了 ${between}ms，双击要求 ${MIN_DOUBLE_GAP_MS}–${MAX_DOUBLE_GAP_MS}ms。想分开点就用两个 tap 步骤。")
                val point = point(m, index)
                Step(
                    listOf(GestureStroke(listOf(point), 0, ms), GestureStroke(listOf(point), ms + between, ms)),
                    ms + between + ms,
                    "double ${show(point)}",
                )
            }
            "swipe" -> {
                val from = point(m, index)
                val to = pair(m, "x2", "y2", index)
                if (from == to) throw Bad("$at 的 swipe 起点和终点相同，手指不会动。")
                val ms = millis(m, "duration", index, DEFAULT_SWIPE_MS)
                Step(listOf(GestureStroke(listOf(from, to), 0, ms)), ms, "swipe ${show(from)}→${show(to)} ${ms}ms")
            }
            "path" -> {
                val points = path(m, index)
                val ms = millis(m, "duration", index, DEFAULT_SWIPE_MS)
                Step(listOf(GestureStroke(points, 0, ms)), ms, "path ${points.size} points ${ms}ms")
            }
            "pinch" -> {
                val centre = point(m, index)
                val spread = num(m, "spread") ?: throw Bad("$at 的 pinch 需要 spread：像素数，正数放大、负数缩小。")
                if (kotlin.math.abs(spread) < MIN_PINCH_SPREAD) throw Bad("$at 的 spread 是 ${spread}，两根手指几乎不动；至少要 ${MIN_PINCH_SPREAD} 像素（正数放大、负数缩小）。")
                val ms = millis(m, "duration", index, DEFAULT_PINCH_MS)
                val half = spread / 2
                // Fingers must not cross; the further they travel, the further apart they start.
                val r = maxOf(PINCH_RADIUS, kotlin.math.abs(half) + 40)
                Step(
                    listOf(
                        GestureStroke(listOf(centre.first to centre.second - r, centre.first to centre.second - r - half), 0, ms),
                        GestureStroke(listOf(centre.first to centre.second + r, centre.first to centre.second + r + half), 0, ms),
                    ),
                    ms,
                    (if (spread > 0) "pinch out" else "pinch in") + " ${show(centre)}",
                )
            }
            else -> throw Bad("$at 的 kind 是「$kind」，只能是 ${KINDS.joinToString(" / ")}。")
        }
    }

    private fun fields(raw: Any?, index: Int): Map<String, Any?> =
        (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: throw Bad("steps[$index] 要是一个对象，例如 {\"kind\":\"tap\",\"x\":540,\"y\":1200}。")

    private fun text(m: Map<String, Any?>, key: String): String? =
        (m[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun num(m: Map<String, Any?>, key: String): Int? = when (val v = m[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun point(m: Map<String, Any?>, index: Int): Pair<Int, Int> = pair(m, "x", "y", index)

    private fun pair(m: Map<String, Any?>, kx: String, ky: String, index: Int): Pair<Int, Int> {
        val x = num(m, kx) ?: throw Bad("steps[$index] 缺少整数 $kx。")
        val y = num(m, ky) ?: throw Bad("steps[$index] 缺少整数 $ky。")
        if (x < 0 || y < 0) throw Bad("steps[$index] 的坐标不能是负数（$x, $y）。")
        return x to y
    }

    private fun millis(m: Map<String, Any?>, key: String, index: Int, fallback: Long): Long {
        val raw = m[key] ?: return fallback
        val ms = (raw as? Number)?.toLong() ?: (raw as? String)?.trim()?.toLongOrNull()
            ?: throw Bad("steps[$index] 的 $key 要是毫秒整数。")
        if (ms < MIN_MS || ms > MAX_MS) throw Bad("steps[$index] 的 $key 要在 $MIN_MS..$MAX_MS 毫秒之间，这次是 $ms。")
        return ms
    }

    private fun path(m: Map<String, Any?>, index: Int): List<Pair<Int, Int>> {
        val raw = m["points"] as? List<*> ?: throw Bad("steps[$index] 的 path 需要 points：[[x, y], …]。")
        if (raw.size < 2) throw Bad("steps[$index] 的 path 至少要两个点。")
        if (raw.size > MAX_POINTS) throw Bad("steps[$index] 的 path 最多 $MAX_POINTS 个点，这次是 ${raw.size} 个。")
        val points = ArrayList<Pair<Int, Int>>(raw.size)
        for ((i, item) in raw.withIndex()) {
            val xy = item as? List<*> ?: throw Bad("steps[$index].points[$i] 要是 [x, y]。")
            if (xy.size != 2) throw Bad("steps[$index].points[$i] 要是 [x, y]。")
            val x = (xy[0] as? Number)?.toInt() ?: throw Bad("steps[$index].points[$i] 的 x 要是整数。")
            val y = (xy[1] as? Number)?.toInt() ?: throw Bad("steps[$index].points[$i] 的 y 要是整数。")
            if (x < 0 || y < 0) throw Bad("steps[$index].points[$i] 的坐标不能是负数。")
            // The same point twice makes a zero-length segment; the finger would not move.
            if (points.isNotEmpty() && points.last() == (x to y)) continue
            points += x to y
        }
        if (points.size < 2) throw Bad("steps[$index] 的 path 去重后只剩一个点，改用 tap 或 long。")
        return points
    }

    private fun show(p: Pair<Int, Int>) = "(${p.first},${p.second})"
}
// l10n:keep-end
