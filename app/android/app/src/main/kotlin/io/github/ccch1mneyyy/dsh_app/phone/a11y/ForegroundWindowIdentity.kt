package io.github.ccch1mneyyy.dsh_app.phone.a11y

/** A package reported by Android for the same active window, never a guessed last app. */
internal class ForegroundWindowIdentity {
    private var id = -1
    private var pkg: String? = null
    private var at = 0L

    @Synchronized fun record(windowId: Int, packageName: String?, now: Long) {
        if (windowId < 0 || packageName.isNullOrBlank()) return
        id = windowId
        pkg = packageName
        at = now
    }

    @Synchronized fun packageFor(windowId: Int, active: Boolean, now: Long): String? =
        pkg?.takeIf { active && windowId == id && now - at in 0..MAX_AGE_MS }

    companion object { const val MAX_AGE_MS = 120_000L }
}
