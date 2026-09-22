package de.mm20.launcher2.ui.launcher.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.FormFactorDetector
import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.homegrid.HomeGridSeedFlag
import de.mm20.launcher2.homegrid.HomeGridWidgets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory grid table: enough for the view model and layout tests. */
class FakeHomeGridRepository(
    initial: Map<String, List<HomeGridItem>> = emptyMap(),
) : HomeGridRepository {
    val layouts = MutableStateFlow(initial)

    override fun observe(layout: String): Flow<List<HomeGridItem>> =
        layouts.map { it[layout].orEmpty().sortedBy { item -> item.position } }

    override suspend fun replace(layout: String, items: List<HomeGridItem>) {
        layouts.value = layouts.value + (layout to items.map { it.copy(layout = layout) })
    }

    override suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int) {
        layouts.value = layouts.value + (layout to layouts.value[layout].orEmpty().map {
            if (it.id == id) it.copy(x = x, y = y, w = w, h = h) else it
        })
    }

    override suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?) {
        layouts.value = layouts.value + (layout to layouts.value[layout].orEmpty().map {
            if (it.id == id) it.copy(appWidgetId = appWidgetId) else it
        })
    }

    override suspend fun delete(layout: String, id: String) {
        layouts.value = layouts.value + (layout to layouts.value[layout].orEmpty().filter { it.id != id })
    }
}

class FakeFormFactorDetector(private val formFactor: FormFactor) : FormFactorDetector {
    override fun detect(): FormFactor = formFactor
}

class FakeSeedFlag(private var seeded: Boolean = false) : HomeGridSeedFlag {
    override suspend fun isSeeded(): Boolean = seeded
    override suspend fun markSeeded() {
        seeded = true
    }
}

fun gridItem(
    id: String,
    x: Int,
    y: Int,
    w: Int = 1,
    h: Int = 1,
    layout: String = HomeGridLayouts.Phone,
    widget: String = "com.example/.Widget",
    appWidgetId: Int? = null,
    position: Int = 0,
) = HomeGridItem(layout, id, widget, x = x, y = y, w = w, h = h, appWidgetId = appWidgetId, position = position)

fun dockItem(x: Int, y: Int, w: Int, h: Int, layout: String = HomeGridLayouts.Phone, position: Int = 9) =
    HomeGridItem(layout, "dock", HomeGridWidgets.Favorites, x = x, y = y, w = w, h = h, position = position)

/**
 * What a golden shows in a cell: a tinted card with the item's id, or a row
 * of dots for the favorites strip. Roborazzi cannot host real AppWidgets, so
 * the goldens pin the geometry and the card, not a provider's pixels.
 */
@Composable
fun PlaceholderCell(id: String, span: Span) {
    GridCard {
        if (id == "dock") {
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(span.w) {
                    Box(Modifier.size(36.dp).background(Color(0xFF3D7DD6), CircleShape))
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0x333D7DD6)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$id ${span.w}x${span.h}")
            }
        }
    }
}
