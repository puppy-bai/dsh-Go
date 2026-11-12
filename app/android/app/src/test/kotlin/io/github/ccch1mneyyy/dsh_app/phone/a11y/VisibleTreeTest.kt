package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.*
import org.junit.Assert.*
import org.junit.Test

class VisibleTreeTest {
    private val frame = Box(0, 0, 400, 800)
    private fun windows(root: UiNode) = listOf(UiWindow(1, WindowKind.APP, "example.app", null, frame, root))
    private fun formatted(root: UiNode) = ScreenFormat.format(ScreenInput("s1", "Example", "example.app", 400, 800,
        false, windows(root)))

    @Test fun clippedListDescendantsCannotLeakIntoTheirVisibleParentLabelOrIndex() {
        val visible = UiNode(text = "记录 28", bounds = Box(0, 250, 300, 290))
        val hidden = UiNode(text = "记录 31", bounds = Box(0, 400, 300, 440))
        val list = UiNode(bounds = Box(0, 100, 350, 300), scrollable = true, clickable = true,
            children = listOf(visible, hidden))
        val wrapper = UiNode(bounds = frame, clickable = true, children = listOf(list))
        val out = formatted(wrapper)
        assertTrue(out.text.contains("记录 28"))
        assertFalse(out.text.contains("记录 31"))
        assertFalse(ScreenFormat.index(windows(wrapper)).any { it.fullText.contains("记录 31") })
    }

    @Test fun nestedListIntersectionsAreAppliedBeforeParentTextAggregation() {
        val inner = UiNode(bounds = Box(0, 100, 350, 700), scrollable = true, children = listOf(
            UiNode(text = "inside", bounds = Box(0, 200, 300, 250)),
            UiNode(text = "outside outer", bounds = Box(0, 400, 300, 450))))
        val outer = UiNode(bounds = Box(0, 100, 350, 300), scrollable = true, children = listOf(inner))
        val out = formatted(UiNode(bounds = frame, children = listOf(outer)))
        assertTrue(out.text.contains("inside"))
        assertFalse(out.text.contains("outside outer"))
    }

    @Test fun partialTargetsUseTheirEffectiveRectangleAndZeroSizeLayoutWrappersDoNotHideChildren() {
        val root = UiNode(bounds = Box(0, 0, 0, 0), children = listOf(
            UiNode(text = "part", bounds = Box(-20, 100, 80, 160), clickable = true),
            UiNode(text = "offscreen", bounds = Box(500, 100, 600, 160))))
        val out = formatted(root)
        assertEquals(Box(0, 100, 80, 160), out.elements.single().bounds)
        assertFalse(out.text.contains("offscreen"))
    }

    @Test fun keyboardViewportClipsTargetsWithoutChangingPhysicalWindowGeometry() {
        val root = UiNode(bounds = frame, children = listOf(
            UiNode(text = "above", bounds = Box(0, 100, 200, 150)),
            UiNode(text = "under keyboard", bounds = Box(0, 650, 200, 700))))
        val clipped = VisibleTree.windows(windows(root), Box(0, 0, 400, 500))
        assertEquals(frame, clipped.single().bounds)
        assertTrue(ScreenFormat.index(clipped).any { it.fullText == "above" })
        assertFalse(ScreenFormat.index(clipped).any { it.fullText.contains("under keyboard") })
    }

    @Test fun aKnownFrontWindowHidesFullyCoveredTargetsFromTheUnderlyingWindow() {
        val dialogBounds = Box(20, 100, 380, 400)
        val dialog = UiWindow(2, WindowKind.APP, "example.app", "Dialog", dialogBounds,
            UiNode(bounds = dialogBounds, children = listOf(UiNode(text = "Dialog action", bounds = Box(40, 150, 200, 200)))))
        val behind = windows(UiNode(bounds = frame, children = listOf(
            UiNode(text = "Covered action", bounds = Box(40, 200, 200, 250)),
            UiNode(text = "Outside dialog", bounds = Box(40, 500, 200, 550)))))
        val index = ScreenFormat.index(listOf(dialog) + behind)
        assertFalse(index.any { it.fullText.contains("Covered action") })
        assertTrue(index.any { it.fullText == "Outside dialog" })
    }

    @Test fun targetAssertionsDoNotMatchHeaderOrDiagnosticWords() {
        val snapshot = ScreenSnapshot("s1", "屏幕 s1 · 找到了记录 31\n[1] 记录 28", "Example", "example.app",
            visibleTexts = listOf("记录 28"))
        val driver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(true)
            override fun observe(detail: Boolean, image: Boolean) = snapshot
            override fun target(screenId: String, elementId: Int): TargetInfo? = null
            override fun perform(action: PhoneAction) = ActionResult(false, "unused")
            override fun open(packageName: String) = ActionResult(false, "unused")
            override fun foregroundPackage() = "example.app"
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult = error("unused")
        }
        assertFalse(driver.hasVisibleText(snapshot, "记录 31"))
        assertTrue(driver.hasVisibleText(snapshot, "记录 28", "exact"))
        assertFalse(driver.hasVisibleText(snapshot, "记录", "exact"))
        assertTrue(driver.hasVisibleText(snapshot, "  记录   28 ", "normalized"))
    }
}
