package de.mm20.launcher2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic codes and their severities, as the provisioning host reads
 * them. Written out on purpose: a changed severity or a renamed code is a
 * changed line here, and a reviewer checks one table instead of every place
 * a diagnostic is built.
 */
class DiagnosticCodeTest {

    @Test
    fun `every code has exactly this severity`() {
        val expected = mapOf(
            "app-unavailable" to Severity.Warning,
            "apply-failed" to Severity.Error,
            "decode-failed" to Severity.Error,
            "duplicate-app" to Severity.Error,
            "duplicate-app-on-device" to Severity.Warning,
            "duplicate-favorite" to Severity.Error,
            "duplicate-grid-item-id" to Severity.Error,
            "duplicate-search-action" to Severity.Error,
            "favorite-unavailable" to Severity.Warning,
            "gesture-app-unavailable" to Severity.Warning,
            "grid-crosses-fold" to Severity.Warning,
            "grid-item-moved" to Severity.Warning,
            "grid-out-of-bounds" to Severity.Warning,
            "grid-overflow" to Severity.Warning,
            "inert-key" to Severity.Warning,
            "input-too-large" to Severity.Error,
            "invalid-apps" to Severity.Error,
            "invalid-glass" to Severity.Error,
            "invalid-grid-columns" to Severity.Error,
            "invalid-grid-geometry" to Severity.Error,
            "invalid-grid-item-id" to Severity.Error,
            "invalid-grid-widget" to Severity.Error,
            "invalid-icons" to Severity.Error,
            "invalid-package-name" to Severity.Error,
            "invalid-schema-version" to Severity.Error,
            "invalid-search" to Severity.Error,
            "invalid-search-action" to Severity.Error,
            "invalid-wallpaper-image" to Severity.Error,
            "malformed-json" to Severity.Error,
            "missing-schema-version" to Severity.Error,
            "partial-grid-position" to Severity.Warning,
            "permission-missing" to Severity.Warning,
            "profile-unavailable" to Severity.Warning,
            "read-failed" to Severity.Error,
            "read-state-failed" to Severity.Error,
            "search-action-app-not-searchable" to Severity.Warning,
            "search-action-field-ignored" to Severity.Warning,
            "search-action-intent-missing" to Severity.Warning,
            "search-action-read-only" to Severity.Warning,
            "search-action-unavailable" to Severity.Warning,
            "search-reversed-with-top-bar" to Severity.Warning,
            "too-many-favorites" to Severity.Error,
            "too-many-grid-items" to Severity.Error,
            "too-many-search-actions" to Severity.Error,
            "transliterator-unavailable" to Severity.Warning,
            "unknown-key" to Severity.Warning,
            "unknown-layout" to Severity.Warning,
            "unknown-widget-provider" to Severity.Warning,
            "unsupported-schema-version" to Severity.Error,
            "wallpaper-missing" to Severity.Error,
            "wallpaper-pending-foreground" to Severity.Warning,
            "wallpaper-replaced-during-apply" to Severity.Error,
            "widget-too-large" to Severity.Warning,
            "widget-too-small" to Severity.Warning,
            "write-back-skipped:" to Severity.Warning,
        )

        assertEquals(expected, DiagnosticCode.entries.associate { it.code to it.severity })
    }

    @Test
    fun `no two entries share a code`() {
        val codes = DiagnosticCode.entries.map { it.code }

        assertEquals(codes.sorted(), codes.distinct().sorted())
    }

    /** The family's codes are its prefix and a reason; a plain code is built without one. */
    @Test
    fun `only the write-back family takes a reason`() {
        assertEquals(
            "write-back-skipped:colors-custom",
            Diagnostic(DiagnosticCode.WriteBackSkipped, "colors-custom", "", "m").code,
        )
        assertTrue("a plain code with a reason", runCatching { Diagnostic(DiagnosticCode.UnknownKey, "why", "", "m") }.isFailure)
        assertTrue("the family without a reason", runCatching { Diagnostic(DiagnosticCode.WriteBackSkipped, "", "m") }.isFailure)
    }
}
