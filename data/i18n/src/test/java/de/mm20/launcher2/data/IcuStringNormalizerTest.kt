package de.mm20.launcher2.data

import android.content.Context
import android.icu.text.Transliterator
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The search matching's transliteration (#3 slice 1). Upstream code, so its
 * behaviour is pinned first: a transliterator the device has is used, one it
 * does not have falls back to stripping accents.
 */
@RunWith(RobolectricTestRunner::class)
class IcuStringNormalizerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The id is resolved on a background scope; wait for it rather than for time. */
    private fun normalizer(setting: String?, factory: (String) -> Transliterator = Transliterator::getInstance) =
        IcuStringNormalizer(context, flowOf(setting), factory).also { n ->
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!n.id.startsWith(setting ?: "Latin-ASCII")) {
                check(System.nanoTime() < deadline) { "the transliterator setting never arrived (id ${n.id})" }
                Thread.sleep(5)
            }
        }

    @Test
    fun `a transliterator the device has is used`() {
        assertEquals("privet", normalizer("Any-Latin").normalize("Привет"))
    }

    @Test
    fun `a transliterator the device does not have falls back to stripping accents`() {
        assertEquals("apfel", normalizer("No-Such-Transliterator").normalize("Äpfel"))
    }

    /**
     * It is looked up once. Before, every normalize - every item, every
     * keystroke - asked ICU again, which threw, and logged the exception again.
     */
    @Test
    fun `a transliterator the device does not have is looked up once`() {
        var lookups = 0
        val n = normalizer("No-Such-Transliterator") { id ->
            lookups++
            Transliterator.getInstance(id)
        }

        repeat(5) { assertEquals("apfel", n.normalize("Äpfel")) }

        assertEquals(1, lookups)
    }

    /**
     * Also when the first calls come at once: search normalizes on several
     * coroutines, and a check-then-record cache let each of them look the id
     * up before any had recorded the failure (#190 review).
     */
    @Test
    fun `a transliterator the device does not have is looked up once under concurrent first use`() {
        val lookups = java.util.concurrent.atomic.AtomicInteger()
        val n = normalizer("No-Such-Transliterator") { id ->
            lookups.incrementAndGet()
            Thread.sleep(50) // a slow lookup widens the window the race needs
            Transliterator.getInstance(id)
        }
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = List(8) {
            Thread {
                start.await()
                assertEquals("apfel", n.normalize("Äpfel"))
            }.apply { start() }
        }

        start.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(1, lookups.get())
    }
}
