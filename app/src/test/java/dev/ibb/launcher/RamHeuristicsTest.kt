package dev.ibb.launcher

import dev.ibb.launcher.data.store.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RAM slider must never offer more memory than the device can give back.
 *
 * An over-allocated `-Xmx` does not fail fast: the JVM happily commits it and then the *system*
 * kills the game mid-session (or, on low-RAM devices, the whole launcher). These tests pin the
 * heuristics so a future "make the slider go higher" change cannot reintroduce that bug.
 */
class RamHeuristicsTest {

    @Test
    fun `recommended value leaves room for android and the gpu driver`() {
        // 4096 - 1536 reserved = 2560 (rounded down to the 256 MB step)
        assertEquals(2560, SettingsStore.recommendedRamMb(4096))
        assertEquals(5120, SettingsStore.recommendedRamMb(8192))
        assertEquals(6144, SettingsStore.recommendedRamMb(10240))
        // Every recommendation is a fraction of the device, never "all of it".
        listOf(4096, 6144, 8192, 12288).forEach { total ->
            val recommended = SettingsStore.recommendedRamMb(total)
            assertTrue("$total MB got $recommended", recommended <= total * 3 / 4)
        }
    }

    @Test
    fun `recommended value is a multiple of 256 and inside the slider range`() {
        listOf(2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576).forEach { total ->
            val recommended = SettingsStore.recommendedRamMb(total)
            assertEquals("$total MB", 0, recommended % 256)
            assertTrue(
                "$total MB → $recommended outside slider",
                recommended in SettingsStore.minRamMb(total)..SettingsStore.maxRamMb(total),
            )
        }
    }

    @Test
    fun `maximum never allocates the entire device`() {
        listOf(2048, 4096, 8192, 16384).forEach { total ->
            assertTrue(SettingsStore.maxRamMb(total) < total)
        }
    }

    @Test
    fun `small devices still get a bootable minimum`() {
        assertTrue(SettingsStore.minRamMb(2048) >= 384)
        assertTrue(SettingsStore.recommendedRamMb(2048) >= SettingsStore.minRamMb(2048))
    }

    @Test
    fun `step count is positive for every device`() {
        listOf(1024, 2048, 4096, 8192).forEach { total ->
            assertTrue(SettingsStore.ramSteps(total) >= 1)
        }
    }

    @Test
    fun `fallback constant is used when meminfo is unreadable`() {
        // `totalRamMb()` reads /proc/meminfo, which exists on Android and on Linux CI but not on
        // every host — the documented fallback is 3 GB, which must itself be a valid device size.
        val fallback = 3072
        assertTrue(SettingsStore.recommendedRamMb(fallback) >= SettingsStore.minRamMb(fallback))
        assertTrue(SettingsStore.recommendedRamMb(fallback) <= SettingsStore.maxRamMb(fallback))
    }
}
