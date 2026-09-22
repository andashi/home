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
 * The classification rule, kept apart from the Android call so it can be
 * tested with plain values. A device folds when it has a hinge: Android
 * exposes that as the `android.hardware.sensor.hinge_angle` system feature,
 * which every foldable (and the SDK's foldable emulator profiles) declares
 * and no candybar phone or tablet does. The rule is a device property, not
 * a window measurement, so the answer is the same folded and unfolded.
 */
object FormFactorRule {
    /**
     * [hasHingeAngleSensor] is the system feature above. Invariant: the
     * answer does not depend on the current window.
     */
    fun classify(hasHingeAngleSensor: Boolean, builtInDisplays: Int): FormFactor =
        if (hasHingeAngleSensor) FormFactor.Fold else FormFactor.Phone
}
