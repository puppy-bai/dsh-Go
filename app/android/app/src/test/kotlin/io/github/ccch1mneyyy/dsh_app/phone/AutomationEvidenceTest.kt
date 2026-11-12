package io.github.ccch1mneyyy.dsh_app.phone

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AutomationEvidenceTest {
    private fun frame(bytes: Int = 4, secure: Boolean = false) = ScreenSnapshot(
        "s1", "", "Lab", "example.lab", secure = secure,
        image = ByteArray(bytes), imageMime = "image/png",
    )

    @Test fun disabledByDefaultAndProtectedFramesNeverPersist() {
        val dir = Files.createTempDirectory("evidence").toFile()
        try {
            val store = AutomationEvidence(dir)
            assertFalse(store.capture("run-a", "snapshot", frame()))
            store.setEnabled(true)
            assertFalse(store.capture("run-a", "snapshot", frame(secure = true)))
            assertFalse(store.capture("../elsewhere", "snapshot", frame()))
            assertTrue(store.list().isEmpty())
            assertTrue(AutomationEvidence(dir).enabled())
        } finally { dir.deleteRecursively() }
    }

    @Test fun perRunRetentionAgeLimitAndClearPreserveOptIn() {
        val dir = Files.createTempDirectory("evidence").toFile()
        var time = 1000L
        try {
            val store = AutomationEvidence(dir) { time++ }
            store.setEnabled(true)
            repeat(12) { assertTrue(store.capture("run-a", "snapshot", frame())) }
            assertEquals(10, store.list().size)
            time += 8L * 24 * 60 * 60 * 1000
            assertTrue(store.list().isEmpty())
            assertTrue(store.capture("run-b", "result", frame()))
            store.clear()
            assertTrue(store.list().isEmpty())
            assertTrue(store.enabled())
        } finally { dir.deleteRecursively() }
    }

    @Test fun oldestFramesArePrunedAtFiftyMegabytes() {
        val dir = Files.createTempDirectory("evidence").toFile()
        var time = 1000L
        try {
            val store = AutomationEvidence(dir) { time++ }
            store.setEnabled(true)
            repeat(6) { assertTrue(store.capture("run-$it", "result", frame(10 * 1024 * 1024))) }
            val kept = store.list()
            assertEquals(5, kept.size)
            assertFalse(kept.any { it["runId"] == "run-0" })
            assertTrue(kept.sumOf { it["bytes"] as Long } <= 50L * 1024 * 1024)
        } finally { dir.deleteRecursively() }
    }
}
