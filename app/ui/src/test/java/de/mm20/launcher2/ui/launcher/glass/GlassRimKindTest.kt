package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import de.mm20.launcher2.ui.component.SquircleShape
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a glass surface draws its rim (#122). Modifier.border draws a rounded
 * rectangle directly, but a generic shape - the icon chip's squircle, a
 * polygon of a few hundred segments - it builds with Path.op and rasterizes
 * into a mask on the CPU, once per size. On unfold every dock icon changes
 * size, and that was the largest single cost in the launcher's first frame
 * there (simpleperf on the fold emulator, #122). The squircle's rim is a
 * stroke of its outline, built once per size.
 */
class GlassRimKindTest {

    @Test
    fun `the icon chip's squircle rim is a stroke`() {
        assertEquals(GlassRimKind.Stroke, glassRimKind(SquircleShape, openEdges = emptySet()))
    }

    /** Control: a closed card keeps Modifier.border's direct path and its pixels (#82). */
    @Test
    fun `a closed card's rim stays the border`() {
        val card = glassOutline(radiusDp = 28f, pill = false, shape = null, openEdges = emptySet())
        assertEquals(GlassRimKind.Border, glassRimKind(card, openEdges = emptySet()))
    }

    /** Control: a pill (the search bar) keeps the border too. */
    @Test
    fun `a pill's rim stays the border`() {
        assertEquals(GlassRimKind.Border, glassRimKind(RoundedCornerShape(percent = 50), openEdges = emptySet()))
    }

    /** Control: a segment already strokes around its seams (#91). */
    @Test
    fun `a segment's rim is a stroke`() {
        val open = setOf(GlassEdge.Bottom)
        val card = glassOutline(radiusDp = 28f, pill = false, shape = null, openEdges = open)
        assertEquals(GlassRimKind.Stroke, glassRimKind(card, openEdges = open))
    }
}
