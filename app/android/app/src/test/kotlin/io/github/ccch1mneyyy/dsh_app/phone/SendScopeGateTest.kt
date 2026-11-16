package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class SendScopeGateTest {
    private fun scope(app: String = "com.tencent.mm", recipient: String = "云星", text: String = "你好") =
        SendScope.parse(Args(mapOf("send" to mapOf("app" to app, "recipient" to recipient, "text" to text))))!!
    private fun target(label: String = "发送", pkg: String = "com.tencent.mm", password: Boolean = false) =
        TargetInfo("s1", 1, "按钮", label, pkg, "Chat", password, false, password)

    private class Fixture {
        val tasks = PhoneTasks().apply { begin() }
        var policy = PhonePolicies(visualPackages = listOf("com.tencent.mm", "other.app"))
        var choice = Decision.ALLOW_ONCE
        var duringConfirm: () -> Unit = {}
        val requests = arrayListOf<ConfirmRequest>()
        val gate = SafetyGate({ policy }, tasks, {
            object : UserGate {
                override fun confirm(request: ConfirmRequest): Decision {
                    requests += request
                    duringConfirm()
                    return choice
                }
                override fun handoff(reason: String, timeoutMs: Long) = HandoffResult.STOP
                override fun status(text: String?) { }
            }
        }, onVisualApproved = { pkg -> policy = policy.copy(visualPackages = policy.visualPackages + pkg); true })
        fun check(send: SendScope, kind: WriteKind = WriteKind.INPUT, typed: String? = send.text, submit: Boolean = false) =
            gate.checkWrite(kind, null, send.app, "Chat", typed = typed, submit = submit, point = 12 to 34, send = send)
        fun resolve(v: SafetyGate.Verdict) = gate.resolve(v, SerialWorker.Scope())
    }

    @Test fun firstSendShowsFullRecipientAndDraftThenReusesOneApproval() {
        val f = Fixture()
        val draft = "请完整核对这条长消息。".repeat(20)
        val send = scope(text = draft)
        val first = f.check(send) as SafetyGate.Verdict.Confirm
        assertEquals("send-scope", first.reason)
        assertTrue(first.request.sendScope)
        assertFalse(first.request.offerTaskWaiver)
        assertTrue(first.request.sentence.contains("云星"))
        assertTrue(first.request.sentence.contains(draft))
        assertTrue(f.resolve(first) is SafetyGate.Result.Go)
        assertSame(SafetyGate.Verdict.Allow, f.check(send))
        assertSame(SafetyGate.Verdict.Allow, f.gate.checkWrite(WriteKind.TAP, target(), send.app, "Chat", send = send))
        assertEquals(1, f.requests.size)
        assertFalse(f.tasks.isApproved(send.app))
        assertFalse(f.tasks.isWaived)
    }

    @Test fun eachChangedScopeRequiresConsentEvenUnderAlwaysMode() {
        val f = Fixture()
        f.policy = f.policy.copy(confirmMode = ConfirmMode.ALWAYS)
        for (send in listOf(scope(), scope(recipient = "另一位"), scope(text = "你好！"), scope(app = "other.app"))) {
            assertEquals("send-scope", (f.check(send) as SafetyGate.Verdict.Confirm).reason)
            assertTrue(f.resolve(f.check(send)) is SafetyGate.Result.Go)
            assertSame(SafetyGate.Verdict.Allow, f.check(send))
        }
        assertEquals(4, f.requests.size)
    }

    @Test fun refusalUnavailableOrStoppedConfirmationNeverCachesConsent() {
        for (choice in listOf(Decision.DENY, Decision.UNAVAILABLE, Decision.STOP)) {
            val f = Fixture()
            f.choice = choice
            val send = scope()
            assertTrue(f.resolve(f.check(send)) is SafetyGate.Result.No)
            assertFalse(f.tasks.isSendApproved(send))
        }
        val f = Fixture()
        val send = scope()
        f.duringConfirm = { f.tasks.endTask(); f.tasks.begin() }
        val result = f.resolve(f.check(send)) as SafetyGate.Result.No
        assertEquals("stopped", result.decision)
        assertFalse(f.tasks.isSendApproved(send))
    }

    @Test fun missingGateNeverCreatesSendApproval() {
        val tasks = PhoneTasks().apply { begin() }
        val send = scope()
        val gate = SafetyGate({ PhonePolicies(visualPackages = listOf(send.app)) }, tasks, { null })
        val verdict = gate.checkWrite(WriteKind.INPUT, null, send.app, "Chat", typed = send.text, send = send)
        assertEquals("refused:no-gate", (gate.resolve(verdict, SerialWorker.Scope()) as SafetyGate.Result.No).decision)
        assertFalse(tasks.isSendApproved(send))
    }

    @Test fun searchCanUseExactRecipientButCannotSubmitItAsTheMessage() {
        val f = Fixture()
        val send = scope()
        assertTrue(f.resolve(f.check(send, WriteKind.TYPE, typed = send.recipient)) is SafetyGate.Result.Go)
        assertSame(SafetyGate.Verdict.Allow, f.check(send, WriteKind.TYPE, typed = send.text, submit = true))
        assertEquals("send-scope-text-mismatch", (f.check(send, WriteKind.TYPE, typed = send.recipient, submit = true) as SafetyGate.Verdict.Refuse).rule)
        assertEquals("send-scope-text-mismatch", (f.check(send, typed = "别的正文") as SafetyGate.Verdict.Refuse).rule)
        assertEquals("send-scope-app-mismatch", (f.gate.checkWrite(WriteKind.INPUT, null, "other.app", "Other", typed = send.text, send = send) as SafetyGate.Verdict.Refuse).rule)
    }

    @Test fun sendApprovalDoesNotWeakenHardRulesOrAuthorizeOtherSensitiveActions() {
        val f = Fixture()
        val send = scope()
        f.resolve(f.check(send))
        for (label in listOf("付款", "删除", "提交", "转账", "授权", "发送红包", "发送转账")) {
            val v = f.gate.checkWrite(WriteKind.TAP, target(label), send.app, "Chat", send = send) as SafetyGate.Verdict.Refuse
            assertEquals(label, "send-scope-action-mismatch", v.rule)
        }
        val mixedId = target("Send").copy(viewId = "btn_send_payment")
        assertEquals("send-scope-action-mismatch", (f.gate.checkWrite(WriteKind.TAP, mixedId, send.app, "Chat", send = send) as SafetyGate.Verdict.Refuse).rule)
        val secret = f.gate.checkWrite(WriteKind.TYPE, target(password = true), send.app, "Chat", typed = send.text, send = send) as SafetyGate.Verdict.Refuse
        assertEquals("secret-field", secret.rule)
        for ((pkg, expected) in listOf("com.icbc" to "deny-list", "com.android.settings" to "system-page", SafetyRules.OWN_PACKAGE to "own-app")) {
            val v = f.gate.checkWrite(WriteKind.TAP, target(pkg = pkg), pkg, "App", send = scope(app = pkg)) as SafetyGate.Verdict.Refuse
            assertEquals(expected, v.rule)
        }
        assertEquals("send-scope-action-mismatch", (f.check(send, WriteKind.GESTURE, typed = null) as SafetyGate.Verdict.Refuse).rule)
    }

    @Test fun visualGrantAndSendCanBeConfirmedTogetherWithoutApprovingUnrelatedWrites() {
        val f = Fixture()
        f.policy = f.policy.copy(visualPackages = emptyList())
        f.choice = Decision.ALLOW_APP_FOR_TASK
        val send = scope()
        val v = f.check(send) as SafetyGate.Verdict.Confirm
        assertEquals("visual-access", v.reason)
        assertTrue(v.request.sendScope)
        assertTrue(v.request.firstInApp)
        assertTrue(f.resolve(v) is SafetyGate.Result.Go)
        assertTrue(send.app in f.policy.visualPackages)
        assertSame(SafetyGate.Verdict.Allow, f.check(send))
        val ordinaryTap = f.gate.checkWrite(WriteKind.TAP, target("搜索"), send.app, "Chat") as SafetyGate.Verdict.Confirm
        assertEquals("every-write", ordinaryTap.reason)
        val ordinaryInput = f.gate.checkWrite(WriteKind.INPUT, null, send.app, "Chat", typed = send.text) as SafetyGate.Verdict.Confirm
        assertEquals("visual-input", ordinaryInput.reason)
    }

    @Test fun allowingTheSendOnceDoesNotPersistVisualPermission() {
        val f = Fixture()
        f.policy = f.policy.copy(visualPackages = emptyList())
        val send = scope()
        assertTrue(f.resolve(f.check(send)) is SafetyGate.Result.Go)
        assertTrue(f.tasks.isSendApproved(send))
        assertFalse(send.app in f.policy.visualPackages)
        assertEquals("visual-access", (f.check(send) as SafetyGate.Verdict.Confirm).reason)
    }
}
