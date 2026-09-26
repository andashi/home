package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.LauncherConfig
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
class CapabilityDiagnostics(private val contactsGranted: () -> Boolean) {

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
                    Severity.Warning,
                    "permission-missing",
                    "search.contacts",
                    "search.contacts is true, but this profile does not hold READ_CONTACTS; " +
                        "contact search finds nothing until it is granted",
                )
            )
        }
    }

    companion object {
        /** No checks: for a reloader that is not the device's own. */
        val None = CapabilityDiagnostics { true }
    }
}
