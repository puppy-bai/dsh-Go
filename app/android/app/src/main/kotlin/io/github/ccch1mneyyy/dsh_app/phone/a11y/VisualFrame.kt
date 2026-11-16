package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.graphics.Bitmap

/** A small luminance grid used to validate a screenshot without retaining the bitmap. */
internal data class VisualFrame(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val luminance: ByteArray,
) {
    init {
        require(width > 0 && height > 0 && columns > 0 && rows > 0)
        require(luminance.size == columns * rows)
    }

    /**
     * Allows small capture noise or a moving area away from the target, while rejecting a
     * changed target cell even when that change is small compared with the whole screen.
     */
    fun matches(previous: VisualFrame, points: List<Pair<Int, Int>> = emptyList()): Boolean {
        if (width != previous.width || height != previous.height ||
            columns != previous.columns || rows != previous.rows) return false
        val deltas = IntArray(luminance.size) { i ->
            kotlin.math.abs((luminance[i].toInt() and 0xff) - (previous.luminance[i].toInt() and 0xff))
        }
        val globalMean = deltas.average()
        if (globalMean > GLOBAL_MEAN_MAX) return false
        if (points.isEmpty()) return true

        val target = BooleanArray(deltas.size)
        for ((x, y) in points) {
            val cx = (x.coerceIn(0, width - 1) * columns / width).coerceIn(0, columns - 1)
            val cy = (y.coerceIn(0, height - 1) * rows / height).coerceIn(0, rows - 1)
            for (dy in -TARGET_RADIUS..TARGET_RADIUS) for (dx in -TARGET_RADIUS..TARGET_RADIUS) {
                val tx = cx + dx
                val ty = cy + dy
                if (tx in 0 until columns && ty in 0 until rows) target[ty * columns + tx] = true
            }
        }
        val targetDeltas = deltas.filterIndexed { i, _ -> target[i] }
        if (targetDeltas.isEmpty()) return false
        return targetDeltas.average() <= TARGET_MEAN_MAX && targetDeltas.maxOrNull()!! <= TARGET_MAX_DELTA
    }

    companion object {
        const val SAMPLE_SIZE = 128
        private const val GLOBAL_MEAN_MAX = 24.0
        private const val TARGET_MEAN_MAX = 20.0
        private const val TARGET_MAX_DELTA = 72
        private const val TARGET_RADIUS = 2

        fun fromBitmap(bitmap: Bitmap, sampleSize: Int = SAMPLE_SIZE): VisualFrame {
            val columns = sampleSize.coerceAtMost(bitmap.width).coerceAtLeast(1)
            val rows = sampleSize.coerceAtMost(bitmap.height).coerceAtLeast(1)
            val values = ByteArray(columns * rows)
            for (row in 0 until rows) {
                val y = ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
                for (column in 0 until columns) {
                    val x = ((column + 0.5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
                    val color = bitmap.getPixel(x, y)
                    val r = (color shr 16) and 0xff
                    val g = (color shr 8) and 0xff
                    val b = color and 0xff
                    values[row * columns + column] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
                }
            }
            return VisualFrame(bitmap.width, bitmap.height, columns, rows, values)
        }
    }
}
