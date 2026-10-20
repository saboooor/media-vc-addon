package su.plo.voice.discs.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SettingsFileWriterTest {
    @TempDir lateinit var directory: Path

    @Test fun `repeated saves serialize only the latest snapshot and flush on close`() {
        val file = directory.resolve("settings.json").toFile()
        val encoded = AtomicInteger()
        val errors = CopyOnWriteArrayList<Throwable>()
        SettingsFileWriter(60_000).use { writer ->
            repeat(100) { version ->
                writer.submit(file, { encoded.incrementAndGet(); "{\"version\":$version}" }, errors::add)
            }
        }
        assertEquals("{\"version\":99}", file.readText())
        assertEquals(1, encoded.get())
        assertTrue(errors.isEmpty())
    }

    @Test fun `flush writes every pending file and newer snapshots win subsequent saves`() {
        val first = directory.resolve("first.json").toFile()
        val second = directory.resolve("second.json").toFile()
        SettingsFileWriter(60_000).use { writer ->
            writer.submit(first, { "old" }) { fail(it) }
            writer.submit(second, { "other" }) { fail(it) }
            writer.flush()
            assertEquals("old", first.readText())
            assertEquals("other", second.readText())
            writer.submit(first, { "new" }) { fail(it) }
            writer.flush()
            assertEquals("new", first.readText())
        }
    }

    @Test fun `serialization failures preserve existing settings and do not block other saves`() {
        val first = directory.resolve("first.json").toFile().apply { writeText("original") }
        val second = directory.resolve("second.json").toFile()
        val errors = CopyOnWriteArrayList<Throwable>()
        SettingsFileWriter(60_000).use { writer ->
            writer.submit(first, { error("bad snapshot") }, errors::add)
            writer.submit(second, { "good" }, errors::add)
        }
        assertEquals("original", first.readText())
        assertEquals("good", second.readText())
        assertEquals(1, errors.size)
        assertEquals(2, directory.toFile().listFiles()!!.size)
    }
}
