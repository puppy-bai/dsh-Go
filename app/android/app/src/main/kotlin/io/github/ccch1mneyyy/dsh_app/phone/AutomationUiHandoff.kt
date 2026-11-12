package io.github.ccch1mneyyy.dsh_app.phone

/** Reveal the existing app before a user-requested start/resume, without launching it again. */
internal class AutomationUiHandoff(
    private val status: () -> Map<*, *>,
    private val hideSettings: () -> Boolean,
    private val foreground: () -> String?,
    private val dispatch: () -> Map<String, Any?>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val ownPackage: String = SafetyRules.OWN_PACKAGE,
) {
    fun start(): Map<String, Any?> {
        val original = status()
        if (original["isActive"] == true) return failure("phone-busy")
        fun current(): Boolean {
            val now = status()
            return now["isActive"] != true && now["runId"] == original["runId"] && now["generation"] == original["generation"]
        }
        return reveal(::current) { it != null && it != ownPackage }
    }

    fun run(runId: String?): Map<String, Any?> {
        val original = status()
        if (runId == null || original["runId"] != runId || original["state"] != "paused")
            return failure("stale-run")
        val apps = (original["apps"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        fun current(): Boolean {
            val now = status()
            return now["runId"] == runId && now["state"] == "paused" &&
                now["generation"] == original["generation"] && now["dispatchEpoch"] == original["dispatchEpoch"]
        }
        return reveal(::current) { it in apps }
    }

    private fun reveal(current: () -> Boolean, ready: (String?) -> Boolean): Map<String, Any?> {
        if (!hideSettings()) return failure("automation-page-unavailable")
        val deadline = clock() + 1500
        while (current()) {
            if (ready(foreground())) return if (current()) dispatch() else failure("stale-run")
            if (clock() >= deadline) return failure("automation-page-unavailable")
            sleep(50)
        }
        return failure("stale-run")
    }

    private fun failure(reason: String) = mapOf("ok" to false, "reason" to reason)
}
