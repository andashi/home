package de.mm20.launcher2.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.utils.withCustomLabels
import de.mm20.launcher2.preferences.search.FavoritesSettings
import de.mm20.launcher2.preferences.search.FavoritesSettingsData
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.Tag
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.services.favorites.FavoritesService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

abstract class FavoritesVM : ViewModel(), KoinComponent {

    private val favoritesService: FavoritesService by inject()
    private val customAttributesRepository: CustomAttributesRepository by inject()
    internal val settings: FavoritesSettings by inject()

    val selectedTag = MutableStateFlow<String?>(null)

    /**
     * The columns the row is drawn in, once its composable says so: search's
     * row is laid out in the home grid's columns (#91), not in upstream's grid
     * setting, and fetching for the setting asked for the wrong number of
     * frequently-used apps (a 5-column count into a 4-column row). Declared
     * before [favorites], which is built in the constructor and reads it.
     * Until it is set - the dock, and the moment before search is composed -
     * the setting decides, as it always has.
     */
    private val rowColumns = MutableStateFlow<Int?>(null)

    val showEditButton =
        settings.showEditButton.stateIn(viewModelScope, SharingStarted.Lazily, false)
    abstract val tagsExpanded: Flow<Boolean>
    abstract val compactTags: Flow<Boolean>

    val pinnedTags = favoritesService.getFavorites(
        includeTypes = listOf("tag"),
        minPinnedLevel = PinnedLevel.AutomaticallySorted,
    ).map {
        it.filterIsInstance<Tag>()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    open val favorites: Flow<List<SavableSearchable>> = selectedTag.flatMapLatest { tag ->
        if (tag == null) {
            settings
                .combine(rowColumns) { settings, row -> settings to (row ?: settings.columns) }
                .transformLatest { (row, columns) ->
                    val includeFrequentlyUsed = row.frequentlyUsed && showsFrequentlyUsed()
                    val frequentlyUsedRows = row.frequentlyUsedRows

                    val pinned = favoritesService.getFavorites(
                        excludeTypes = listOf("tag"),
                        minPinnedLevel = PinnedLevel.AutomaticallySorted,
                        limit = 10 * columns,
                    )
                    if (includeFrequentlyUsed) {
                        emitAll(pinned.flatMapLatest { pinned ->
                            favoritesService.getFavorites(
                                excludeTypes = listOf("tag"),
                                maxPinnedLevel = PinnedLevel.FrequentlyUsed,
                                minPinnedLevel = PinnedLevel.FrequentlyUsed,
                                limit = frequentlyUsedRows * columns - pinned.size % columns,
                            ).map {
                                pinned + it
                            }
                                .withCustomLabels(customAttributesRepository)
                        })
                    } else {
                        emitAll(
                            pinned.withCustomLabels(customAttributesRepository)
                        )
                    }
                }
        } else {
            customAttributesRepository
                .getItemsForTag(tag)
                .withCustomLabels(customAttributesRepository)
                .map { it.sortedBy { it } }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)


    fun setRowColumns(columns: Int) {
        rowColumns.value = columns
    }

    fun selectTag(tag: String?) {
        selectedTag.value = tag
    }

    abstract fun setTagsExpanded(expanded: Boolean)

    /**
     * Whether the frequently-used apps follow the pins while that setting is
     * on. A function, not a property: [favorites] is built in this class's
     * constructor, before a subclass has initialised its own fields.
     */
    protected open fun showsFrequentlyUsed(): Boolean = true
}