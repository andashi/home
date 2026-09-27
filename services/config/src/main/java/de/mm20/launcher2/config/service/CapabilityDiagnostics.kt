package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.DiagnosticCode
import de.mm20.launcher2.config.GestureActionName
import de.mm20.launcher2.config.GestureConfig
import de.mm20.launcher2.config.fileName
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.SearchDefaults
import de.mm20.launcher2.config.Severity

/**
 * What the file asks for that this profile cannot do (#140). The key stays as
 * written, is applied and is served back: the read-back is a configuration
 * report, and it feeds write-back and provisioning's `--pull`, so a revocable
 * runtime condition such as a missing permission must not become a written
 * choice. The report says why the effect differs, which is the same rule as
 * for a size the grid shrinks: the file keeps what it asked, the report says
 * what is in effect and why.
 */
class CapabilityDiagnostics(
    private val contactsGranted: () -> Boolean,
    private val callGranted: () -> Boolean,
    /** Whether this device's ICU has a transliterator id; ICU versions differ between devices. */
    private val transliteratorAvailable: (id: String) -> Boolean,
    /** Whether the launcher's accessibility service is on; only the person can turn it on. */
    private val accessibilityOn: () -> Boolean,
) {

    /**
     * Only keys the file sets, since what it leaves out is not its request
     * (ADR 0002), and only a key that is on after the reload: as the file says
     * when it applied, as it was [before] when a failure covered it
     * ([failedAt], asked with the key's own path, so `search.actions` failing
     * does not cover `search.contacts`). A failure that left contact search
     * off has no permission to be missing for it; one that left it on still
     * does (#172 review).
     */
    fun of(config: LauncherConfig, before: ConfigState, failedAt: (keyPath: String) -> Boolean): List<Diagnostic> = buildList {
        val contactsOn = if (failedAt("search.contacts")) before.search.contacts else config.search?.contacts == true
        if (config.search?.contacts == true && contactsOn && !contactsGranted()) {
            add(
                Diagnostic(
                    DiagnosticCode.PermissionMissing,
                    "search.contacts",
                    "search.contacts is true, but this profile does not hold READ_CONTACTS; " +
                        "contact search finds nothing until it is granted",
                )
            )
        }
        // #3 slice 1: without CALL_PHONE the tap dials instead (callOrDial).
        val callOnTapOn = if (failedAt("search.contactsCallOnTap")) {
            before.search.contactsCallOnTap
        } else {
            config.search?.contactsCallOnTap == true
        }
        if (config.search?.contactsCallOnTap == true && callOnTapOn && !callGranted()) {
            add(
                Diagnostic(
                    DiagnosticCode.PermissionMissing,
                    "search.contactsCallOnTap",
                    "search.contactsCallOnTap is true, but this profile does not hold CALL_PHONE; " +
                        "a tap on a number opens the dialer instead of calling",
                )
            )
        }
        // #3 slice 1: one file serves devices with different ICU versions, so an
        // id this one lacks is not a parse error; search falls back instead
        // (IcuStringNormalizer, which looks it up once).
        val transliterator = if (failedAt("search.transliterator")) before.search.transliterator else config.search?.transliterator
        if (config.search?.transliterator != null && transliterator != null &&
            transliterator != SearchDefaults.TransliteratorAuto && transliterator != SearchDefaults.TransliteratorOff &&
            !transliteratorAvailable(transliterator)
        ) {
            add(
                Diagnostic(
                    DiagnosticCode.TransliteratorUnavailable,
                    "search.transliterator",
                    "search.transliterator is \"$transliterator\", which this device's ICU does not have; " +
                        "search matching falls back to stripping accents",
                )
            )
        }
        // #3 slice 2: these three go through the accessibility service, which a
        // file must not turn on; the gesture asks for it when used.
        val needsService = config.gestures?.byGesture().orEmpty().mapNotNull { (gesture, asked) ->
            val path = gesture.path
            val effect = if (failedAt(path)) before.gestures[gesture] else asked
            val action = (effect as? GestureConfig.Action)?.action?.takeIf { it in ServiceActions }
            action?.let { path to it }
        }
        if (needsService.isNotEmpty() && !accessibilityOn()) {
            for ((path, action) in needsService) {
                add(
                    Diagnostic(
                        DiagnosticCode.PermissionMissing,
                        path,
                        "$path is ${action.fileName}, which needs the launcher's accessibility service; " +
                            "it is off, so the gesture asks for it when used",
                    )
                )
            }
        }
    }

    companion object {
        /** No checks: for a reloader that is not the device's own. */
        val None = CapabilityDiagnostics(
            contactsGranted = { true }, callGranted = { true }, transliteratorAvailable = { true }, accessibilityOn = { true },
        )

        /** What the launcher does through its accessibility service (ScreenOff-, PowerMenu-, RecentsComponent). */
        private val ServiceActions = setOf(GestureActionName.ScreenLock, GestureActionName.PowerMenu, GestureActionName.Recents)
    }
}
