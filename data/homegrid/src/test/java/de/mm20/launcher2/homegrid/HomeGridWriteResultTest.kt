package de.mm20.launcher2.homegrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The two answers a write-back gives, as values: equal by content, distinct from each other. */
class HomeGridWriteResultTest {

    @Test
    fun `a skip carries its code and reason and never equals a write`() {
        val skipped = HomeGridWriteResult.Skipped("locked", "home.grid.locked is true")

        assertEquals(HomeGridWriteResult.Skipped("locked", "home.grid.locked is true"), skipped)
        assertEquals("locked", skipped.code)
        assertNotEquals(HomeGridWriteResult.Written as HomeGridWriteResult, skipped as HomeGridWriteResult)
        assertEquals(HomeGridWriteResult.Written, HomeGridWriteResult.Written)
    }
}
