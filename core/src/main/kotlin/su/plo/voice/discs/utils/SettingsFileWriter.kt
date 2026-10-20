package su.plo.voice.discs.utils

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One ordered writer, coalescing repeated saves of each file. Callers supply immutable snapshots. */
class SettingsFileWriter(private val debounceMs: Long = 250) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "discs-settings-writer").apply { isDaemon = true }
    }
    private val pending = mutableMapOf<File, () -> Unit>()
    @Volatile private var closed = false

    @Synchronized fun submit(file: File, encode: () -> String, onError: (Throwable) -> Unit) {
        check(!closed) { "Settings writer is closed" }
        val key = file.absoluteFile
        val scheduled = pending.containsKey(key)
        pending[key] = {
            try {
                val json = encode()
                val target = key.toPath()
                Files.createDirectories(target.parent)
                val temporary = Files.createTempFile(target.parent, key.name, ".tmp")
                try {
                    Files.writeString(temporary, json)
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { Files.deleteIfExists(temporary) }
            } catch (error: Throwable) { onError(error) }
        }
        if (!scheduled) executor.schedule({ take(key)?.invoke() }, debounceMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun take(file: File) = pending.remove(file)
    private fun drain() {
        while (true) {
            val tasks = synchronized(this) {
                pending.values.toList().also { pending.clear() }
            }
            if (tasks.isEmpty()) return
            tasks.forEach { it() }
        }
    }
    fun flush() { executor.submit { drain() }.get() }
    override fun close() {
        val closing = synchronized(this) {
            if (closed) false else { closed = true; true }
        }
        if (closing) try { flush() } finally { executor.shutdownNow() }
    }

    companion object {
        private var instance: SettingsFileWriter? = null
        val shared: SettingsFileWriter
            get() = synchronized(this) {
                instance?.takeUnless { it.closed } ?: SettingsFileWriter().also { instance = it }
            }
    }
}
