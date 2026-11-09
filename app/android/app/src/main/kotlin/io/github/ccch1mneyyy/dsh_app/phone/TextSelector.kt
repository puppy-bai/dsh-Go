package io.github.ccch1mneyyy.dsh_app.phone

/**
 * How `tap` with `text` picks one element out of a snapshot: an exact match beats a partial one,
 * a partial match on the visible label beats one inside a row's whole text, an id fragment matches
 * last, an actionable element beats a passive label, and a tie is refused instead of guessed —
 * two hits mean the person has to say which one by id.
 *
 * Pure Kotlin on purpose: the rule is the part that must not drift, so it is tested on the JVM.
 */
object TextSelector {
    /** A snapshot element reduced to what matching needs; the driver builds these from ScreenElements. */
    data class Candidate(
        val id: Int,
        /** As rendered: masked, ≤ 60 chars (a row's own label). */
        val label: String,
        /** Masked, ≤ 300: the row with all its texts. */
        val detail: String = "",
        /** `com.app:id/ok` when the app gave one. */
        val viewId: String? = null,
        /** Clickable, long-clickable or editable: something a finger could land on. */
        val actionable: Boolean = false,
    )

    sealed interface Hit {
        data class One(val id: Int, val label: String) : Hit
        /** The sentence for the model, plus what was found so the next try can be precise. */
        data class Fail(val message: String, val candidates: List<Candidate> = emptyList()) : Hit
    }

    private val space = Regex("\\s+")
    private val invisible = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")

    /** Trimmed, single-spaced, without zero-width marks, lower-cased for Latin ids. */
    fun norm(value: String): String = invisible.replace(value, "").replace(space, " ").trim().lowercase()

    private fun label(c: Candidate) = norm(c.label)
    private fun detail(c: Candidate) = norm(c.detail)
    private fun view(c: Candidate) = norm(c.viewId ?: "")

    private fun brief(c: Candidate): String {
        val text = c.label.ifBlank { c.detail }.ifBlank { c.viewId ?: "" }
        // l10n:keep agent tool result
        return "#${c.id} " + if (text.isBlank()) "（无文字）" else "「${text.take(24)}」"
    }

    private fun list(items: List<Candidate>, limit: Int): String =
        // l10n:keep agent tool result
        items.take(limit).joinToString("、") { brief(it) } + if (items.size > limit) "…" else ""

    private fun actionableOnly(items: List<Candidate>) = items.filter { it.actionable }

    /**
     * The element [query] names, or why it cannot be decided. [candidates] is the snapshot in
     * reading order, so the list a refusal shows is the same order the model saw.
     */
    fun pick(query: String, candidates: List<Candidate>, limit: Int = 6): Hit {
        val q = norm(query)
        // l10n:keep agent tool result
        if (q.isEmpty()) return Hit.Fail("tap 的 text 不能是空白。给一段界面上看得见的文字。")

        val tiers: List<List<Candidate>> = listOf(
            candidates.filter { label(it) == q },
            candidates.filter { detail(it) == q },
            candidates.filter { q.length >= 2 && label(it).contains(q) },
            candidates.filter { q.length >= 2 && detail(it).contains(q) },
            candidates.filter { q.length >= 3 && view(it).isNotEmpty() && view(it).contains(q) },
        )
        val hit = tiers.firstOrNull { it.isNotEmpty() }
        if (hit == null) {
            val near = actionableOnly(candidates).ifEmpty { candidates }
            // l10n:keep agent tool result
            return Hit.Fail(
                if (near.isEmpty()) "屏幕上没有可以点的「$query」，也没有别的元素。先用 screen 看一眼，或者换个界面。"
                // l10n:keep agent tool result
                else "屏幕上没有「$query」。现在能点的有：${list(near, limit)}。换一段界面上看得见的文字，或者用 id。",
                near,
            )
        }
        // A clickable row and the text inside it both match the same words: prefer what a finger would hit.
        val acts = actionableOnly(hit)
        val pool = if (acts.isNotEmpty()) acts else hit
        if (pool.size == 1) return Hit.One(pool.first().id, pool.first().label)
        // l10n:keep agent tool result
        return Hit.Fail("「$query」匹配到 ${pool.size} 个元素，无法确定点哪个。用 id 指定：${list(pool, limit)}。", pool)
    }
}
