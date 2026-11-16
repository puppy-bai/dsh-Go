package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class SendScopeTest {
    private fun scope(app: String = "com.tencent.mm", recipient: String = "云星", text: String = "你好") =
        SendScope.parse(Args(mapOf("send" to mapOf("app" to app, "recipient" to recipient, "text" to text))))!!

    @Test fun scopeRequiresExactFieldsAndBoundedStrings() {
        assertNull(SendScope.parse(Args(emptyMap())))
        val valid = mapOf("app" to "com.tencent.mm", "recipient" to "云星", "text" to "你好")
        val invalid = listOf(null, "send", valid - "recipient", valid + ("id" to "same-task"),
            valid + ("app" to "WeChat"), valid + ("recipient" to " "), valid + ("recipient" to "x".repeat(201)),
            valid + ("text" to ""), valid + ("text" to 123), valid + ("text" to "x".repeat(2001)))
        for (value in invalid) {
            try {
                SendScope.parse(Args(mapOf("send" to value)))
                fail("Accepted invalid send scope")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(" 你好\n", scope(text = " 你好\n").text)
    }

    @Test fun approvalsMatchTheWholeTupleAndNeverTrustASharedName() {
        val tasks = PhoneTasks()
        val owner = (tasks.begin() as PhoneTasks.Begin.Go).owner
        val approved = scope()
        assertTrue(tasks.confirmIfCurrent(owner, send = approved))
        assertTrue(tasks.isSendApproved(scope()))
        for (changed in listOf(scope(app = "other.app"), scope(recipient = "另一位"), scope(text = "你好！"), scope(text = "你好 "))) {
            assertFalse(tasks.isSendApproved(changed))
        }
        val replacement = scope(text = "再见")
        assertTrue(tasks.confirmIfCurrent(owner, send = replacement))
        assertTrue(tasks.isSendApproved(replacement))
        assertFalse(tasks.isSendApproved(approved))
        assertFalse(tasks.isApproved("com.tencent.mm"))
        assertFalse(tasks.isWaived)
    }

    @Test fun lifecycleChangesInvalidateConsentAndRejectTheOldOwner() {
        val transitions: List<(PhoneTasks) -> Unit> = listOf(
            { it.finish() }, { it.endTask() }, { it.stop("test") }, { it.invalidateOwners() }, { it.clearStop() },
        )
        for (transition in transitions) {
            val tasks = PhoneTasks()
            val owner = (tasks.begin() as PhoneTasks.Begin.Go).owner
            val send = scope()
            assertTrue(tasks.confirmIfCurrent(owner, send = send))
            transition(tasks)
            assertFalse(tasks.isSendApproved(send))
            assertFalse(tasks.confirmIfCurrent(owner, send = send))
        }
    }

    @Test fun idleExpiryStartsANewTaskWithoutThePreviousConsent() {
        var now = 1000L
        val tasks = PhoneTasks(clock = { now }, idleMs = 100)
        val old = (tasks.begin() as PhoneTasks.Begin.Go).owner
        val send = scope()
        assertTrue(tasks.confirmIfCurrent(old, send = send))
        now += 101
        assertFalse(tasks.isSendApproved(send))
        val next = (tasks.begin() as PhoneTasks.Begin.Go).owner
        assertNotEquals(old.taskId, next.taskId)
        assertFalse(tasks.confirmIfCurrent(old, send = send))
        assertFalse(tasks.isSendApproved(send))
    }
}
