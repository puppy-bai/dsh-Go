package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class AutomationUiHandoffTest {
    private fun paused() = mapOf("runId" to "run", "state" to "paused", "generation" to 1L,
        "dispatchEpoch" to 2L, "apps" to listOf("example.app"))

    @Test fun resumesOnlyAfterRevealingTheExistingAllowedApp() {
        var now = 0L
        var hidden = 0
        var resumed = 0
        var pkg = "dsh.app"
        val gate = AutomationUiHandoff(::paused, { hidden++; true }, { pkg }, { resumed++; mapOf("ok" to true) },
            { now }, { now += it; pkg = "example.app" })
        assertEquals(true, gate.run("run")["ok"])
        assertEquals(1, hidden)
        assertEquals(1, resumed)
        assertEquals(50L, now)
    }

    @Test fun aStaleRunNeverHidesTheSettingsOrResumesAnything() {
        var hidden = false
        val gate = AutomationUiHandoff(::paused, { hidden = true; true }, { "example.app" }, { error("must not resume") })
        assertEquals("stale-run", gate.run("old")["reason"])
        assertFalse(hidden)
    }

    @Test fun timeoutOrAChangedPauseEpochKeepsTheTaskPaused() {
        for (replaceEpoch in listOf(false, true)) {
            var now = 0L
            var current: Map<*, *> = paused()
            val gate = AutomationUiHandoff({ current }, { true }, { "outside.app" }, { error("must not resume") },
                { now }, { now += it; if (replaceEpoch) current = paused() + ("dispatchEpoch" to 3L) })
            assertEquals(if (replaceEpoch) "stale-run" else "automation-page-unavailable", gate.run("run")["reason"])
            assertTrue(now <= 1500)
        }
    }

    @Test fun startAndResumeDispatchOnlyAfterTheSettingsHandoff() {
        for (resume in listOf(false, true)) {
            val order = mutableListOf<String>()
            val gate = AutomationUiHandoff(
                { if (resume) paused() else emptyMap<String, Any?>() },
                { order += "hide"; true },
                { order += "foreground"; "example.app" },
                { order += "dispatch"; mapOf("ok" to true) },
            )
            assertEquals(true, (if (resume) gate.run("run") else gate.start())["ok"])
            assertEquals(listOf("hide", "foreground", "dispatch"), order)
        }
    }

    @Test fun startWaitsForSettingsToLeaveAndDoesNotRequireOpeningTheTargetAgain() {
        var now = 0L
        var pkg: String? = SafetyRules.OWN_PACKAGE
        val gate = AutomationUiHandoff({ emptyMap<String, Any?>() }, { true }, { pkg }, { mapOf("ok" to true) },
            { now }, { now += it; pkg = "existing.app" })
        assertEquals(true, gate.start()["ok"])
        assertEquals(50L, now)
    }

    @Test fun missingActivityKeepsAStartUnexecuted() {
        val gate = AutomationUiHandoff({ emptyMap<String, Any?>() }, { false }, { "example.app" }, { error("must not start") })
        assertEquals("automation-page-unavailable", gate.start()["reason"])
    }
}
