package de.mm20.launcher2.themes

import android.content.Context
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.themes.colors.ColorsRepository
import de.mm20.launcher2.themes.shapes.ShapesRepository
import de.mm20.launcher2.themes.typography.TypographyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ThemeRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    val colors = ColorsRepository(context, database)
    val shapes = ShapesRepository(context, database)
    val typographies = TypographyRepository(context, database)


}