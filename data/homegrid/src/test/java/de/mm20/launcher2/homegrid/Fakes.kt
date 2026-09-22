package de.mm20.launcher2.homegrid

import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/** The widget column as a map from parent to ordered widgets. */
class FakeWidgetRepository(
    initial: Map<UUID?, List<Widget>> = emptyMap(),
) : WidgetRepository {
    val widgets = MutableStateFlow(initial)

    override fun get(parent: UUID?, limit: Int, offset: Int): Flow<List<Widget>> =
        widgets.map { it[parent].orEmpty().drop(offset).take(limit) }

    override fun update(widget: Widget) = Unit
    override fun create(widget: Widget, position: Int, parentId: UUID?) = Unit
    override fun delete(widget: Widget) = Unit
    override fun set(widgets: List<Widget>, parentId: UUID?) = Unit
    override suspend fun setAwaited(widgets: List<Widget>, parentId: UUID?) = Unit
    override fun exists(type: String): Flow<Boolean> = flowOf(false)
    override fun count(type: String): Flow<Int> = flowOf(0)
}

class FakeSeedFlag(var seeded: Boolean = false) : HomeGridSeedFlag {
    var marks = 0
    override suspend fun isSeeded(): Boolean = seeded
    override suspend fun markSeeded() {
        seeded = true
        marks++
    }
}

/**
 * A host that hands out ids in sequence. [bindable] lists the widgets it
 * agrees to bind; [gone] the ids whose provider has disappeared.
 */
class FakeAppWidgetHostPort(
    bound: Collection<Int> = emptyList(),
    private val bindable: Set<String> = emptySet(),
    private val gone: Set<Int> = emptySet(),
    private var nextId: Int = 100,
) : AppWidgetHostPort {
    val bound = bound.toMutableSet()
    val released = mutableListOf<Int>()
    val bindCalls = mutableListOf<Triple<Int, String, String?>>()

    override fun boundIds(): List<Int> = bound.toList()

    override fun allocate(): Int = nextId++.also { bound += it }

    override fun release(id: Int) {
        bound -= id
        released += id
    }

    override fun isProviderAvailable(id: Int): Boolean = id !in gone

    override fun bind(id: Int, widget: String, profile: String?): Boolean {
        bindCalls += Triple(id, widget, profile)
        return widget in bindable
    }
}
