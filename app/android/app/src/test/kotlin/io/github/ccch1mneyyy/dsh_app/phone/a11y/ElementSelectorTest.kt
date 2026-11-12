package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.*
import org.junit.Test

class ElementSelectorTest {
    private fun elements(vararg nodes: UiNode): List<ScreenElement> {
        val box = Box(0, 0, 400, 800)
        return ScreenFormat.format(ScreenInput("s1", "Example", "example.app", 400, 800, false,
            listOf(UiWindow(1, WindowKind.APP, "example.app", null, box, UiNode(bounds = box, children = nodes.toList()))))).elements
    }
    private fun button(y: Int, id: String, desc: String? = null, enabled: Boolean = true) =
        UiNode(text = "继续", desc = desc, viewId = id, clickable = true, enabled = enabled, bounds = Box(0, y, 300, y + 50))

    @Test fun sameLabelsRequireMoreScopeAndViewIdDisambiguates() {
        val nodes = elements(button(100, "example:id/first"), button(200, "example:id/second"))
        assertEquals("target-ambiguous", ElementSelector.pick(mapOf("text" to "继续", "match" to "exact"), nodes).reasonCode)
        assertEquals(2, ElementSelector.pick(mapOf("text" to "继续", "viewId" to "example:id/second", "match" to "exact"), nodes).elementId)
    }

    @Test fun descriptionDisabledAndInvalidSelectorsHaveStableReasons() {
        val nodes = elements(button(100, "example:id/first", "下一页", enabled = false))
        assertEquals("target-disabled", ElementSelector.pick(mapOf("description" to "下一页"), nodes).reasonCode)
        assertEquals("target-missing", ElementSelector.pick(mapOf("description" to "上一页"), nodes).reasonCode)
        assertEquals("invalid-selector", ElementSelector.pick(mapOf("text" to "继续", "visible" to false), nodes).reasonCode)
        assertEquals("invalid-selector", ElementSelector.pick(mapOf("text" to "继续", "bogus" to 1), nodes).reasonCode)
    }

    @Test fun normalizationDoesNotMergeDifferentNumbersOrRecipients() {
        val nodes = elements(UiNode(text = " Price   12 ", clickable = true, bounds = Box(0, 100, 300, 150)))
        assertEquals(1, ElementSelector.pick(mapOf("text" to "price 12", "match" to "normalized"), nodes).elementId)
        assertEquals("target-missing", ElementSelector.pick(mapOf("text" to "price 13", "match" to "normalized"), nodes).reasonCode)
    }
}
