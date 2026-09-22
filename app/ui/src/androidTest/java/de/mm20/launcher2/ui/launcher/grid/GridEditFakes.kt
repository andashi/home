package de.mm20.launcher2.ui.launcher.grid

import de.mm20.launcher2.homegrid.FormFactor
import de.mm20.launcher2.homegrid.FormFactorDetector
import de.mm20.launcher2.homegrid.GridGeometry
import de.mm20.launcher2.homegrid.HomeGridItem
import de.mm20.launcher2.homegrid.HomeGridLayouts
import de.mm20.launcher2.homegrid.HomeGridRepository
import de.mm20.launcher2.homegrid.HomeGridSeeder
import de.mm20.launcher2.homegrid.HomeGridSeeding
import de.mm20.launcher2.homegrid.HomeGridWidgets
import de.mm20.launcher2.homegrid.HomeGridWriteBack
import de.mm20.launcher2.homegrid.HomeGridWriteResult
import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * The fakes of the JVM tests, again: androidTest cannot see src/test, and a
 * shared fixtures source set is more Gradle than these forty lines are worth.
 */

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

/** Writes into the fake repository, like the real write-back writes the database first. */
class FakeWriteBack(
    private val repository: FakeHomeGridRepository,
    var result: HomeGridWriteResult = HomeGridWriteResult.Written,
) : HomeGridWriteBack {
    val writes = mutableListOf<Pair<String, List<HomeGridItem>>>()
    override suspend fun write(layout: String, items: List<HomeGridItem>): HomeGridWriteResult {
        writes += layout to items
        repository.replace(layout, items)
        return result
    }
}

class FakeSeeding : HomeGridSeeding {
    override suspend fun seedIfNeeded(geometry: GridGeometry): HomeGridSeeder.SeedResult = HomeGridSeeder.SeedResult()
}

class FakeFormFactorDetector(private val formFactor: FormFactor) : FormFactorDetector {
    override fun detect(): FormFactor = formFactor
}

val emptyColumn = object : WidgetRepository {
    override fun get(parent: UUID?, limit: Int, offset: Int): Flow<List<Widget>> = flowOf(emptyList())
    override fun update(widget: Widget) = Unit
    override fun create(widget: Widget, position: Int, parentId: UUID?) = Unit
    override fun delete(widget: Widget) = Unit
    override fun set(widgets: List<Widget>, parentId: UUID?) = Unit
    override suspend fun setAwaited(widgets: List<Widget>, parentId: UUID?) = Unit
    override fun exists(type: String): Flow<Boolean> = flowOf(false)
    override fun count(type: String): Flow<Int> = flowOf(0)
}

fun gridItem(
    id: String,
    x: Int,
    y: Int,
    w: Int = 1,
    h: Int = 1,
    layout: String = HomeGridLayouts.Phone,
    widget: String = "com.example/.Widget",
    position: Int = 0,
) = HomeGridItem(layout, id, widget, x = x, y = y, w = w, h = h, position = position)

fun dockItem(x: Int, y: Int, w: Int, h: Int, layout: String = HomeGridLayouts.Phone, position: Int = 9) =
    HomeGridItem(layout, "dock", HomeGridWidgets.Favorites, x = x, y = y, w = w, h = h, position = position)
