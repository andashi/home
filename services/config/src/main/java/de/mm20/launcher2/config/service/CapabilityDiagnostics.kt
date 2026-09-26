package de.mm20.launcher2.config.service

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

    /** Only keys the file sets: what it leaves out is not its request (ADR 0002). */
    fun of(config: LauncherConfig): List<Diagnostic> = buildList {
        if (config.search?.contacts == true && !contactsGranted()) {
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
