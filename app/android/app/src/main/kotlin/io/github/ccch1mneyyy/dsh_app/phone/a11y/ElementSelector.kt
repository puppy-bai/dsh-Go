package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.TargetLookup
import io.github.ccch1mneyyy.dsh_app.phone.TextSelector

/** Structured targets are resolved against one clipped observation, never a global text blob. */
internal object ElementSelector {
    fun pick(selector: Map<String, Any?>, elements: List<ScreenElement>): TargetLookup {
        val fields = listOf("text", "viewId", "description", "role", "parentText")
        val allowed = fields + listOf("match", "enabled", "visible", "unique")
        val mode = selector["match"] as? String ?: "contains"
        if (selector.keys.any { it !in allowed } || mode !in listOf("exact", "contains", "normalized") ||
            fields.none { (selector[it] as? String)?.isNotBlank() == true } ||
            fields.any { selector.containsKey(it) && (selector[it] !is String || (selector[it] as String).isBlank()) } ||
            listOf("enabled", "visible", "unique").any { selector.containsKey(it) && selector[it] !is Boolean } ||
            selector["visible"] == false) return TargetLookup(null, "选择器参数无效；目标必须在可见区域。", "invalid-selector")
        fun matches(value: String?, query: String?): Boolean {
            if (query == null) return true
            if (value == null) return false
            return when (mode) {
                "exact" -> value == query
                "normalized" -> TextSelector.norm(value).contains(TextSelector.norm(query))
                else -> value.contains(query)
            }
        }
        fun parentMatches(el: ScreenElement, query: String): Boolean {
            var parent = el.parentId ?: el.listId
            val seen = HashSet<Int>()
            while (parent != null && seen.add(parent)) {
                val node = elements.firstOrNull { it.id == parent } ?: return false
                if (matches(node.label, query) || matches(node.fullText, query)) return true
                parent = node.parentId ?: node.listId
            }
            return false
        }
        val hits = elements.filter { el ->
            !el.bounds.isEmpty &&
                ((selector["text"] as? String)?.let { matches(el.label, it) || matches(el.fullText, it) } ?: true) &&
                matches(el.viewId, selector["viewId"] as? String) &&
                matches(el.description, selector["description"] as? String) &&
                matches(el.role, selector["role"] as? String) &&
                ((selector["parentText"] as? String)?.let { parentMatches(el, it) } ?: true)
        }
        if (hits.isEmpty()) return TargetLookup(null, "当前可见区域没有符合选择器的目标。", "target-missing")
        val enabled = selector["enabled"] as? Boolean ?: true
        val available = hits.filter { it.enabled == enabled }
        if (available.isEmpty()) return TargetLookup(null, "目标当前不可用。", "target-disabled")
        val actionable = available.filter { it.clickable || it.longClickable || it.editable || it.checkable }
        val pool = actionable.ifEmpty { available }
        if (pool.size != 1) return TargetLookup(null, "选择器匹配到 ${pool.size} 个可见目标，请补充作用域或资源编号。", "target-ambiguous")
        return TargetLookup(pool.single().id)
    }
}
