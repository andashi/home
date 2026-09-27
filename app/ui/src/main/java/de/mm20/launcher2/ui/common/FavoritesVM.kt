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

    /**
     * The pins and the frequently used apps, fetched for the widest row there
     * can be and cut to a row's width only where it is drawn
     * ([FavoritesRow.forColumns]). Search's row is laid out in the home grid's
     * columns (#91), not in upstream's grid setting, and those change with
     * the window - a fold opening doubles them. Fetching for a width meant a
     * row drew the list fetched for the width before, if only for a frame;
     * fetching for every width cuts it in the same frame the width is known.
     */
    val row: Flow<FavoritesRow> = selectedTag.flatMapLatest { tag ->
        if (tag == null) {
            settings
                .transformLatest {
                    val includeFrequentlyUsed = it.frequentlyUsed && showsFrequentlyUsed()
                    val frequentlyUsedRows = it.frequentlyUsedRows

                    val pinned = favoritesService.getFavorites(
                        excludeTypes = listOf("tag"),
                        minPinnedLevel = PinnedLevel.AutomaticallySorted,
                        limit = 10 * FavoritesRow.MaxColumns,
                    ).withCustomLabels(customAttributesRepository)
                    if (includeFrequentlyUsed) {
                        emitAll(pinned.flatMapLatest { pinned ->
                            favoritesService.getFavorites(
                                excludeTypes = listOf("tag"),
                                maxPinnedLevel = PinnedLevel.FrequentlyUsed,
                                minPinnedLevel = PinnedLevel.FrequentlyUsed,
                                limit = frequentlyUsedRows * FavoritesRow.MaxColumns,
                            )
                                .withCustomLabels(customAttributesRepository)
                                .map { FavoritesRow(pinned, it, frequentlyUsedRows) }
                        })
                    } else {
                        emitAll(pinned.map { FavoritesRow(it, emptyList(), frequentlyUsedRows) })
                    }
                }
        } else {
            customAttributesRepository
                .getItemsForTag(tag)
                .withCustomLabels(customAttributesRepository)
                .map { FavoritesRow(it.sortedBy { it }, emptyList(), 0) }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * The row cut to upstream's grid column count: what every row that is
     * not search's gets, as before - the dock (pins only), and anything else
     * that shows favorites.
     */
    open val favorites: Flow<List<SavableSearchable>> =
        row.combine(settings) { row, settings -> row.forColumns(settings.columns) }

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

/**
 * A favorites row before it knows its width: the pins, in order, and the
 * frequently used apps that may follow them, fetched for the widest row.
 */
data class FavoritesRow(
    val pinned: List<SavableSearchable>,
    val frequentlyUsed: List<SavableSearchable>,
    val frequentlyUsedRows: Int,
) {
    /**
     * The row [columns] wide: the pins, then as many frequently used apps as
     * fill [frequentlyUsedRows] rows after them - upstream's count, applied
     * where the width is known.
     */
    fun forColumns(columns: Int): List<SavableSearchable> {
        if (frequentlyUsed.isEmpty() || columns <= 0) return pinned
        val room = frequentlyUsedRows * columns - pinned.size % columns
        return pinned + frequentlyUsed.take(room.coerceAtLeast(0))
    }

    companion object {
        /**
         * The widest a row can be: a fold's inner display, twice the most
         * home grid columns there are (ConfigValidator.MaxGridColumns).
         */
        const val MaxColumns = 16

        val Empty = FavoritesRow(emptyList(), emptyList(), 0)
    }
}