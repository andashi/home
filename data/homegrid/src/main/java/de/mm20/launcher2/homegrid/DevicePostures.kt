package de.mm20.launcher2.homegrid

/**
 * The identifiers a device gives its postures, resolved by name from
 * `cmd device_state print-states`. The numbers differ between devices (the
 * GrapheneOS foldable instance counts from 0 with a rear-display state, the
 * SDK's 7.6" foldable from 1), so tests and scripts must never hard-code
 * them.
 */
data class DevicePostures(val closed: Int, val halfOpened: Int, val opened: Int) {
    companion object {
        private val entry = Regex("""identifier=(\d+),\s*name='([A-Z_]+)'""")

        /**
         * Parses the `print-states` output; null when CLOSED, HALF_OPENED or
         * OPENED is missing, which is how a phone reads.
         */
        fun parse(printStates: String): DevicePostures? {
            val byName = entry.findAll(printStates).associate { it.groupValues[2] to it.groupValues[1].toInt() }
            return DevicePostures(
                closed = byName["CLOSED"] ?: return null,
                halfOpened = byName["HALF_OPENED"] ?: return null,
                opened = byName["OPENED"] ?: return null,
            )
        }
    }
}
