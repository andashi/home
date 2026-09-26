package de.mm20.launcher2.themes

import de.mm20.launcher2.preferences.BuiltInColorSchemes
import java.util.UUID


// The system colour scheme's id, and the default id of every other theme
// part (shapes, typography): one value, defined once in core/preferences.
val DefaultThemeId: UUID = BuiltInColorSchemes.System

// The built-in colour schemes' ids live in core/preferences, where
// appearance.theme.colors maps its slugs onto them (#3 slice 3).
val HighContrastThemeId: UUID = BuiltInColorSchemes.HighContrast
val BlackAndWhiteThemeId: UUID = BuiltInColorSchemes.BlackAndWhite

val ExtraRoundShapesId = UUID(0L, 1L)
val CutShapesId = UUID(0L, 2L)
val RectShapesId = UUID(0L, 3L)


val SystemFontId = UUID(0L, 1L)
val MonospaceId = UUID(0L, 2L)
val SerifId = UUID(0L, 3L)
val RoundedTypographyId = UUID(0L, 4L)