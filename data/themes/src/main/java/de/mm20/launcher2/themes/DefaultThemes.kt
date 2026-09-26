package de.mm20.launcher2.themes

import de.mm20.launcher2.preferences.BuiltInColorSchemes
import java.util.UUID


val DefaultThemeId = UUID(0L, 0L)

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