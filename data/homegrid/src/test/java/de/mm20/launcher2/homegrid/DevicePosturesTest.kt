package de.mm20.launcher2.homegrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Posture ids by name, for both id tables seen in practice. */
class DevicePosturesTest {

    private val grapheneFold = """
        DeviceStateManager (dumpsys device_state)
          mSupportedStates=[DeviceState{identifier=0, name='CLOSED', app_accessible=true, cancel_when_requester_not_on_top=false}, DeviceState{identifier=1, name='HALF_OPENED', app_accessible=true, cancel_when_requester_not_on_top=false}, DeviceState{identifier=2, name='OPENED', app_accessible=true, cancel_when_requester_not_on_top=false}, DeviceState{identifier=3, name='REAR_DISPLAY_MODE', app_accessible=true, cancel_when_requester_not_on_top=true}]
    """.trimIndent()

    private val sdkFoldable = """
        Supported states: [
          DeviceState{identifier=1, name='CLOSED', app_accessible=true, cancel_when_requester_not_on_top=false},
          DeviceState{identifier=2, name='HALF_OPENED', app_accessible=true, cancel_when_requester_not_on_top=false},
          DeviceState{identifier=3, name='OPENED', app_accessible=true, cancel_when_requester_not_on_top=false},
        ]
    """.trimIndent()

    @Test
    fun `the GrapheneOS fold instance counts from zero`() {
        assertEquals(DevicePostures(closed = 0, halfOpened = 1, opened = 2), DevicePostures.parse(grapheneFold))
    }

    @Test
    fun `the SDK foldable counts from one`() {
        assertEquals(DevicePostures(closed = 1, halfOpened = 2, opened = 3), DevicePostures.parse(sdkFoldable))
    }

    @Test
    fun `a phone has no postures`() {
        assertNull(DevicePostures.parse("Supported states: [DeviceState{identifier=0, name='DEFAULT', app_accessible=true}]"))
        assertNull(DevicePostures.parse(""))
    }
}
