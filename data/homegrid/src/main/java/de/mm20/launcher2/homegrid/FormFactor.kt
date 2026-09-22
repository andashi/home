package de.mm20.launcher2.homegrid

/**
 * Which of the two layouts a device uses (ADR 0001, D7): a candybar phone
 * draws the `phone` layout; a foldable draws the `fold` layout, whole on the
 * inner display and clipped to its left half on the cover.
 */
enum class FormFactor(val layout: String) {
    Phone(HomeGridLayouts.Phone),
    Fold(HomeGridLayouts.Fold),
}

/** Answers what this device is; the UI never asks the display directly. */
interface FormFactorDetector {
    fun detect(): FormFactor
}

/**
 * The classification rule, kept apart from the Android calls so it can be
 * tested with plain values. A device folds when either of two device
 * properties says so, and both are needed:
 *
 * - the `android.hardware.sensor.hinge_angle` system feature, which every
 *   real foldable declares (the Pixel Fold does) but which the foldable
 *   GrapheneOS emulator instance does not (`pm has-feature` answers false);
 * - at least two built-in displays, which the emulator does expose (the
 *   inner panel and the cover, the cover being off while closed), while a
 *   device with a hinge but a single logical display would only ever show
 *   the first.
 *
 * Both are device properties, not window measurements, so the answer is the
 * same folded and unfolded.
 */
object FormFactorRule {
    /**
     * [hasHingeAngleSensor] is the system feature above; [builtInDisplays]
     * the number of displays that are not presentation displays.
     * Invariant: the answer does not depend on the current window.
     */
    fun classify(hasHingeAngleSensor: Boolean, builtInDisplays: Int): FormFactor =
        if (hasHingeAngleSensor || builtInDisplays >= 2) FormFactor.Fold else FormFactor.Phone
}
