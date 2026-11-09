package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectorTest {
    private fun c(id: Int, label: String, detail: String = "", viewId: String? = null, actionable: Boolean = true) =
        TextSelector.Candidate(id = id, label = label, detail = detail, viewId = viewId, actionable = actionable)

    @Test fun exactLabelBeatsEverything() {
        val pool = listOf(c(1, "取消", "取消"), c(2, "确定", "确定"))
        val hit = TextSelector.pick("确定", pool)
        assertEquals(2, (hit as TextSelector.Hit.One).id)
    }

    @Test fun containmentWorksForARowText() {
        val pool = listOf(
            c(3, "常用地址", actionable = false),
            c(4, "公司 科技园 3 号楼 · 500m", detail = "公司 科技园 3 号楼 · 500m"),
        )
        val hit = TextSelector.pick("科技园", pool)
        assertEquals(4, (hit as TextSelector.Hit.One).id)
    }

    @Test fun actionableCopyOfTheSameWordsWinsOverThePassiveLabel() {
        val pool = listOf(
            c(5, "发送", actionable = false),
            c(6, "发送", actionable = true),
        )
        val hit = TextSelector.pick("发送", pool)
        assertEquals(6, (hit as TextSelector.Hit.One).id)
    }

    @Test fun twoRealCandidatesAreRefusedWithTheirIds() {
        val pool = listOf(c(7, "删除"), c(8, "删除"), c(9, "保存"))
        val hit = TextSelector.pick("删除", pool)
        assertTrue(hit is TextSelector.Hit.Fail)
        val fail = hit as TextSelector.Hit.Fail
        assertTrue(fail.message.contains("2 个元素"))
        assertTrue(fail.message.contains("#7"))
        assertTrue(fail.message.contains("#8"))
    }

    @Test fun nothingFoundListsWhatCanBeTapped() {
        val pool = listOf(c(10, "微信"), c(11, "支付宝"), c(12, "设置"))
        val hit = TextSelector.pick("淘宝", pool)
        assertTrue(hit is TextSelector.Hit.Fail)
        val fail = hit as TextSelector.Hit.Fail
        assertTrue(fail.message.contains("淘宝"))
        assertTrue(fail.message.contains("微信"))
    }

    @Test fun blankQueryIsRefused() {
        val hit = TextSelector.pick("   ", listOf(c(1, "确定")))
        assertTrue(hit is TextSelector.Hit.Fail)
    }

    @Test fun viewIdFragmentMatchesLast() {
        val pool = listOf(c(13, "确定", viewId = "com.example:id/ok"))
        val hit = TextSelector.pick("com.example:id/ok", pool)
        assertEquals(13, (hit as TextSelector.Hit.One).id)
    }

    @Test fun normalisationIgnoresSpacesAndZeroWidthMarks() {
        assertEquals("确定 发送", TextSelector.norm("  确定​  发送 "))
        val pool = listOf(c(14, "确定 发送"))
        assertEquals(14, (TextSelector.pick("确定 发送", pool) as TextSelector.Hit.One).id)
    }
}
