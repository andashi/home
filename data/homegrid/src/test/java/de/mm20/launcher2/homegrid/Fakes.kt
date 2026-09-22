package de.mm20.launcher2.homegrid


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
