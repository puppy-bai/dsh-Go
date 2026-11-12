package io.github.ccch1mneyyy.dsh_app.phone

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Opt-in local key frames. No pixels or user labels enter the task history. */
internal class AutomationEvidence(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val preference = File(directory, "enabled")
    private var optedIn = preference.isFile && runCatching { preference.readText() == "true" }.getOrDefault(false)

    @Synchronized fun enabled(): Boolean = optedIn

    @Synchronized fun setEnabled(value: Boolean) {
        directory.mkdirs()
        val temporary = File(directory, "enabled.tmp")
        temporary.writeText(value.toString())
        Files.move(temporary.toPath(), preference.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        optedIn = value
    }

    @Synchronized fun capture(runId: String, kind: String, screen: ScreenSnapshot?): Boolean {
        if (!optedIn || screen == null || screen.secure || screen.image == null) return false
        if (!Regex("[A-Za-z0-9-]{1,80}").matches(runId) || kind !in setOf("snapshot", "failure", "result", "ai")) return false
        val bytes = screen.image
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val extension = when (screen.imageMime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> return false
        }
        return runCatching {
            directory.mkdirs()
            prune()
            val existing = frames().filter { it.runId == runId }.sortedBy { it.created }
            existing.take((existing.size - 9).coerceAtLeast(0)).forEach { it.file.delete() }
            val file = File(directory, "${clock()}__${runId}__${kind}__${UUID.randomUUID()}.$extension")
            file.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            prune()
            file.isFile
        }.getOrDefault(false)
    }

    @Synchronized fun list(): List<Map<String, Any?>> {
        prune()
        return frames().sortedByDescending { it.created }.map {
            mapOf("runId" to it.runId, "name" to it.kind, "path" to it.file.absolutePath,
                "createdAt" to it.created, "bytes" to it.file.length())
        }
    }

    @Synchronized fun clear() { frames().forEach { it.file.delete() } }

    private data class Frame(val file: File, val created: Long, val runId: String, val kind: String)
    private fun frames(): List<Frame> = directory.listFiles().orEmpty().mapNotNull { file ->
        val pieces = file.nameWithoutExtension.split("__")
        if (!file.isFile || file.extension !in setOf("png", "jpg") || pieces.size != 4) return@mapNotNull null
        val created = pieces[0].toLongOrNull() ?: return@mapNotNull null
        Frame(file, created, pieces[1], pieces[2])
    }

    private fun prune() {
        val cutoff = clock() - 7L * 24 * 60 * 60 * 1000
        val ordered = frames().sortedBy { it.created }
        ordered.filter { it.created < cutoff }.forEach { it.file.delete() }
        var size = ordered.filter { it.file.exists() }.sumOf { it.file.length() }
        for (frame in ordered) {
            if (size <= MAX_BYTES) break
            if (frame.file.exists()) {
                val removed = frame.file.length()
                if (frame.file.delete()) size -= removed
            }
        }
    }

    companion object { private const val MAX_BYTES = 50L * 1024 * 1024 }
}
