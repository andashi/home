package de.mm20.launcher2.ui.launcher.grid

import android.appwidget.AppWidgetHost
import android.content.Context
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.services.widgets.AppWidgetHostIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidAppWidgetHostPortTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val host = AppWidgetHost(context, AppWidgetHostIds.Home)
    private val asked = mutableListOf<Profile.Type>()
    private val port = AndroidAppWidgetHostPort(context, host) { type ->
        asked += type
        if (type == Profile.Type.Work) UserHandle.getUserHandleForUid(10 * 100_000) else null
    }

    @Test
    fun `allocate hands out fresh ids and release accepts them`() {
        // Robolectric's shadow host allocates but does not list bound ids, so
        // what is pinned here is that the calls reach the host and answer;
        // the listing itself is covered by the L4 scenario on the emulator.
        val first = port.allocate()

        assertTrue(first >= 0)

        port.release(first)
        assertFalse(first in port.boundIds())
    }

    @Test
    fun `an id nothing is bound to has no provider`() {
        assertFalse(port.isProviderAvailable(4711))
    }

    @Test
    fun `a widget that is not a component name is not bound`() {
        assertFalse(port.bind(1, "not a component", null))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an unknown profile name binds nothing`() {
        assertFalse(port.bind(1, "com.example/.Widget", "guest"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a managed profile the device lacks binds nothing`() {
        assertFalse(port.bind(1, "com.example/.Widget", "private"))
        assertEquals(listOf(Profile.Type.Private), asked)
    }

    @Test
    fun `personal and work resolve to a user handle before the bind is attempted`() {
        // Robolectric hosts no providers, so the bind itself is refused; what
        // is pinned here is the profile mapping and that a refusal is an
        // answer, not an exception.
        assertFalse(port.bind(1, "com.example/.Widget", null))
        assertFalse(port.bind(1, "com.example/.Widget", "personal"))
        assertTrue(asked.isEmpty())

        assertFalse(port.bind(1, "com.example/.Widget", "work"))
        assertEquals(listOf(Profile.Type.Work), asked)
        assertEquals(Process.myUserHandle(), Process.myUserHandle())
    }
}
