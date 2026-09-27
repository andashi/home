package de.mm20.launcher2.themes

import de.mm20.launcher2.preferences.BuiltInColorSchemes
import de.mm20.launcher2.preferences.BuiltInShapes
import de.mm20.launcher2.preferences.BuiltInTypography
import java.util.UUID


// The system colour scheme's id, and the default id of every other theme
// part (shapes, typography): one value, defined once in core/preferences.
val DefaultThemeId: UUID = BuiltInColorSchemes.System

// The built-in colour schemes' ids live in core/preferences, where
// appearance.theme.colors maps its slugs onto them (#3 slice 3).
val HighContrastThemeId: UUID = BuiltInColorSchemes.HighContrast
val BlackAndWhiteThemeId: UUID = BuiltInColorSchemes.BlackAndWhite

// The built-in shape sets and typographies too, where appearance.theme.shapes
// and appearance.theme.typography map their slugs onto them.
val ExtraRoundShapesId: UUID = BuiltInShapes.ExtraRound
val CutShapesId: UUID = BuiltInShapes.Cut
val RectShapesId: UUID = BuiltInShapes.Rect


val SystemFontId: UUID = BuiltInTypography.System
val MonospaceId: UUID = BuiltInTypography.Monospace
val SerifId: UUID = BuiltInTypography.Serif
val RoundedTypographyId: UUID = BuiltInTypography.GoogleSansRounded