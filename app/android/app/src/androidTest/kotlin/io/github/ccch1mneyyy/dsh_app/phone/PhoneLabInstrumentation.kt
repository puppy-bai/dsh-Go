package io.github.ccch1mneyyy.dsh_app.phone

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

/** Test APK only: operates the public phone core against the explicitly opened local fixture. */
class PhoneLabInstrumentation : Instrumentation() {
    private lateinit var options: Bundle
    private lateinit var control: PhoneControl
    private lateinit var fixtureApp: String
    private class LabFailure(val code: String) : Exception()

    override fun onCreate(arguments: Bundle?) {
        options = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var passed = 0
        var failed = 0
        try {
            fixtureApp = options.getString("app") ?: throw LabFailure("missing-app-argument")
            if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(fixtureApp) || fixtureApp == SafetyRules.OWN_PACKAGE) {
                throw LabFailure("invalid-fixture-app")
            }
            runOnMainSync { control = PhoneControl.get(targetContext) }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (PhoneRegistry.mainDriver == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            if (PhoneRegistry.mainDriver == null) throw LabFailure("accessibility-not-connected")
            if (!control.isEnabled()) throw LabFailure("phone-control-disabled")
            val cases = options.getString("case")?.split(',')?.map(String::trim)
                ?: listOf("dryRun", "assert", "form", "waitMissing")
            if (cases.any { it !in CASES }) throw LabFailure("unknown-case")
            for (name in cases) {
                val started = SystemClock.elapsedRealtime()
                val outcome = try {
                    guardFixture()
                    exercise(name)
                    passed++
                    "PASS"
                } catch (e: Exception) {
                    failed++
                    "FAIL:" + if (e is LabFailure) e.code else e.javaClass.simpleName
                }
                results.putString(name, outcome)
                sendStatus(0, Bundle().apply {
                    putString("case", name)
                    putString("status", outcome)
                    putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
                })
                if (outcome.startsWith("FAIL")) break
            }
        } catch (e: Exception) {
            failed++
            results.putString("setup", if (e is LabFailure) e.code else e.javaClass.simpleName)
        } finally {
            if (::control.isInitialized) control.tasks.finish()
            results.putInt("passed", passed)
            results.putInt("failed", failed)
            finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
        }
    }

    private fun guardFixture() {
        if (PhoneRegistry.mainDriver?.foregroundPackage() != fixtureApp) throw LabFailure("fixture-app-not-in-front")
        val out = control.core.call(PhoneToolCatalog.SCREEN, emptyMap())
        if (out.isError || !out.text.contains(MARKER)) throw LabFailure("fixture-marker-not-visible")
    }

    private fun run(steps: List<Map<String, Any?>>, dryRun: Boolean = false): ToolOutput {
        guardFixture()
        val out = control.core.call(PhoneToolCatalog.RUN, mapOf("app" to fixtureApp, "steps" to steps, "dryRun" to dryRun))
        if (out.isError) {
            val reason = runCatching { (Json.parse(out.text.substringBefore("\n\n")) as? Map<*, *>)?.get("reason") as? String }.getOrNull()
            throw LabFailure("run-failed:" + (reason?.takeIf { Regex("[A-Za-z0-9:_-]{1,60}").matches(it) } ?: "unclassified"))
        }
        return out
    }

    private fun step(op: String, vararg fields: Pair<String, Any?>): Map<String, Any?> = mapOf("op" to op) + fields

    private fun exercise(name: String) {
        when (name) {
            "dryRun" -> {
                val out = run(listOf(step("tap", "target" to "不存在的测试按钮")), dryRun = true)
                val result = Json.parse(out.text) as? Map<*, *>
                if (result?.get("dryRun") != true) throw LabFailure("dry-run-result-invalid")
            }
            "assert" -> run(listOf(step("assert", "text" to MARKER)))
            "form" -> run(listOf(
                step("scroll", "direction" to "up", "until" to "姓名输入框", "max" to 5),
                step("type", "target" to "姓名输入框", "text" to "测试小鲸"),
                step("scroll", "direction" to "down", "until" to "城市输入框", "max" to 5),
                step("type", "target" to "城市输入框", "text" to "上海"),
                step("scroll", "direction" to "down", "until" to "学习目标输入框", "max" to 5),
                step("type", "target" to "学习目标输入框", "text" to "验证手机多步输入"),
                step("scroll", "direction" to "down", "until" to "保存测试表单", "max" to 5),
                step("tap", "target" to "保存测试表单", "expect" to "表单已保存"),
            ))
            "waitMissing" -> {
                val started = SystemClock.elapsedRealtime()
                val out = control.core.call(PhoneToolCatalog.SCREEN, mapOf("until" to "DSH-LAB-NOT-PRESENT-921704"))
                if (!out.isError || control.log.last()?.decision != "untilMissing") throw LabFailure("missing-wait-did-not-fail")
                if (SystemClock.elapsedRealtime() - started < 4500) throw LabFailure("missing-wait-returned-too-early")
            }
            "delay" -> run(listOf(
                step("scroll", "direction" to "up", "until" to "页面等待", "max" to 10),
                step("tap", "target" to "页面等待"),
                step("tap", "target" to "开始三秒等待"),
                step("wait", "text" to "延迟测试按钮", "timeoutMs" to 8000),
                step("tap", "target" to "延迟测试按钮", "expect" to "延迟按钮已点击"),
            ))
            "quiz" -> run(listOf(
                step("scroll", "direction" to "up", "until" to "练习题", "max" to 10),
                step("tap", "target" to "练习题"),
                step("type", "target" to "第一题答案输入框", "text" to "12"),
                step("scroll", "direction" to "down", "until" to "第二题答案输入框", "max" to 5),
                step("type", "target" to "第二题答案输入框", "text" to "3"),
                step("scroll", "direction" to "down", "until" to "第三题答案输入框", "max" to 5),
                step("type", "target" to "第三题答案输入框", "text" to "100"),
                step("scroll", "direction" to "down", "until" to "检查练习答案", "max" to 5),
                step("tap", "target" to "检查练习答案", "expect" to "答对 3/3 题"),
            ))
            "scroll" -> run(listOf(
                step("scroll", "direction" to "up", "until" to "连续滚动", "max" to 10),
                step("tap", "target" to "连续滚动"),
                step("scroll", "direction" to "down", "until" to "目标蓝鲸", "max" to 20),
                step("assert", "text" to "目标蓝鲸"),
            ))
        }
    }

    companion object {
        private const val MARKER = "手机操控测试场"
        private val CASES = setOf("dryRun", "assert", "form", "waitMissing", "delay", "quiz", "scroll")
    }
}
