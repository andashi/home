package de.mm20.launcher2.config

/**
 * What this build does with a contract key it accepts (issue #47).
 *
 * A key that is spelled correctly gets silence today, and that silence covers
 * two different things: "applied" and "parsed, then dropped on the floor". A
 * provisioning host cannot tell them apart, so it carries hand-written
 * assumptions about what a launcher version can do - comments in another
 * repository, maintained by nobody, checked by nothing. `home.widgets.enabled`
 * stopped working when the two home components were collapsed and was found by
 * a review bot rather than by anything that runs.
 *
 * This is deliberately not a flag beside the key table but a value *in* it, so
 * a new key cannot be added without classifying it - the entry is required to
 * compile. An optional list is one that rots.
 */
sealed class KeyEffect {
    /** The build acts on this key as the contract describes. */
    data object Applied : KeyEffect()

    /**
     * The build accepts this key and does not serve it as specified - it may do
     * nothing, or something other than its name promises.
     *
     * [reason] is required rather than nice to have. Marking a key inert
     * without saying why produces a diagnostic nobody can act on, and the next
     * person has to redo the investigation that produced the classification.
     */
    data class Inert(val reason: String) : KeyEffect()
}
