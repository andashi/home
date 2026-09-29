package de.mm20.launcher2.searchable

import android.util.Log
import androidx.room.withTransaction
import de.mm20.launcher2.preferences.ui.GestureSettings
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import de.mm20.launcher2.database.entities.CustomAttributeEntity
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.database.entities.SavedSearchableUpdateContentEntity
import de.mm20.launcher2.database.entities.SavedSearchableUpdatePinEntity
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.preferences.WeightFactor
import de.mm20.launcher2.preferences.search.RankingSettings
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.Resolved
import de.mm20.launcher2.search.SearchableDeserializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.qualifier.named

interface SavableSearchableRepository {

    fun insert(
        searchable: SavableSearchable,
    )

    fun upsert(
        searchable: SavableSearchable,
        visibility: VisibilityLevel? = null,
        pinned: Boolean? = null,
        launchCount: Int? = null,
        weight: Double? = null,
    )

    fun update(
        searchable: SavableSearchable,
        visibility: VisibilityLevel? = null,
        pinned: Boolean? = null,
        launchCount: Int? = null,
        weight: Double? = null,
    )

    /**
     * Replace a searchable in the database.
     * The new entry will inherit the visibility, launch count, weight and pin position of the old entry,
     * but it will have a different key and searchable.
     */
    fun replace(
        key: String,
        newSearchable: SavableSearchable,
    )

    /**
     * Touch a searchable to update its weight and launch counter
     **/
    fun touch(
        searchable: SavableSearchable,
    )

    /**
     * @param minVisibility the minimum visibility of the searchables to return. A visible is
     * considered to be "lower" when it makes an item less visible.
     * @param maxVisibility the maximum visibility of the searchables to return. A visible is
     * considered to be "higher" when it makes an item more visible.
     */
    fun get(
        includeTypes: List<String>? = null,
        excludeTypes: List<String>? = null,
        minPinnedLevel: PinnedLevel = PinnedLevel.NotPinned,
        maxPinnedLevel: PinnedLevel = PinnedLevel.ManuallySorted,
        minVisibility: VisibilityLevel = VisibilityLevel.Hidden,
        maxVisibility: VisibilityLevel = VisibilityLevel.Default,
        limit: Int = 9999,
    ): Flow<List<SavableSearchable>>

    fun getKeys(
        includeTypes: List<String>? = null,
        excludeTypes: List<String>? = null,
        minPinnedLevel: PinnedLevel = PinnedLevel.NotPinned,
        maxPinnedLevel: PinnedLevel = PinnedLevel.ManuallySorted,
        minVisibility: VisibilityLevel = VisibilityLevel.Hidden,
        maxVisibility: VisibilityLevel = VisibilityLevel.Default,
        limit: Int = 9999,
    ): Flow<List<String>>


    fun isPinned(searchable: SavableSearchable): Flow<Boolean>
    fun getVisibility(searchable: SavableSearchable): Flow<VisibilityLevel>
    fun updateFavorites(
        manuallySorted: List<SavableSearchable>,
        automaticallySorted: List<SavableSearchable>,
    )

    /**
     * Fork addition (#3 D4): makes [items] the manually sorted favorites of
     * [types], in order. Manually sorted pins of every other type keep their
     * relative order and follow [items]; automatically sorted pins are not
     * touched. Returns once committed, so the new favorites are visible
     * immediately afterwards (the config reload relies on that, ADR 0003).
     *
     * The current pins are read inside the same transaction that writes the
     * new ones, so a pin made while a config reload runs is never replaced
     * by a snapshot taken before it.
     */
    suspend fun replaceManuallySortedAwaited(types: List<String>, items: List<SavableSearchable>)

    /**
     * Fork addition (#3 slice 4): sets each item's visibility in one
     * transaction and returns once it is committed, so a config reload can
     * read back what it wrote. Only the visibility changes; an item without
     * a row gets one, as [upsert] would give it.
     */
    suspend fun setVisibilitiesAwaited(visibilities: Map<SavableSearchable, VisibilityLevel>)

    /**
     * Fork addition (#3 slice 4): a row for each of [searchables] that has
     * none, written when this returns - [insert] is fire-and-forget. What a
     * customization is anchored to before it is written: the cleanup removes
     * a label whose item has no row. An existing row is left as it is. A
     * gesture that opens an app (#3 slice 2) saves it here too: the launcher
     * looks the item up by the key the setting names.
     */
    suspend fun insertAwaited(searchables: Collection<SavableSearchable>)

    /**
     * Returns the given keys sorted by relevance.
     * The first item in the list is the most relevant.
     * Unknown keys will not be included in the result.
     */
    fun sortByRelevance(keys: List<String>): Flow<List<String>>

    fun sortByWeight(keys: List<String>): Flow<List<String>>

    fun getWeights(keys: List<String>): Flow<Map<String, Double>>

    /**
     * Remove this item from the Searchable database
     */
    fun delete(searchable: SavableSearchable)

    /**
     * Get items with the given keys from the favorites database.
     * Items that don't exist in the database will not be returned.
     */
    fun getByKeys(keys: List<String>): Flow<List<SavableSearchable>>

    /**
     * Remove database entries that are invalid. This includes
     * - entries that cannot be deserialized anymore
     * - entries that are inconsistent (the key column is not equal to the key of the searchable)
     */
    suspend fun cleanupDatabase(): Int

    /**
     * Resolves every stored row of the types whose keys can move
     * ([SearchableDeserializer.storedKeysMove]) and moves the drifted ones to
     * their current keys (#237).
     */
    suspend fun refreshMovedKeys()

    /**
     * The keys under which a search result for a hidden item arrives (#237).
     */
    fun hiddenKeys(): Flow<Set<String>>
}

// Fork edit (Phase 2): `settings` is nullable so headless unit tests can construct
// the repository without a RankingSettings instance (its constructor is internal to
// :core:preferences). touch() falls back to the medium weight factor when it is null.
internal class SavableSearchableRepositoryImpl(
    private val database: AppDatabase,
    private val settings: RankingSettings?,
    /** Gestures name items by key and follow a moved one (#237); null in tests that do not look. */
    private val gestures: GestureSettings? = null,
) : SavableSearchableRepository, KoinComponent {

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    override fun insert(searchable: SavableSearchable) {
        scope.launch { insertAwaited(listOf(searchable)) }
    }


    override fun upsert(
        searchable: SavableSearchable,
        visibility: VisibilityLevel?,
        pinned: Boolean?,
        launchCount: Int?,
        weight: Double?
    ) {
        val dao = database.searchableDao()
        scope.launch {
            val entity = dao.getByKey(searchable.key).firstOrNull()
            dao.upsert(
                SavedSearchableEntity(
                    key = searchable.key,
                    type = searchable.domain,
                    visibility = visibility?.value ?: entity?.visibility ?: 0,
                    pinPosition = pinned?.let { if (it) 1 else 0 } ?: entity?.pinPosition ?: 0,
                    launchCount = launchCount ?: entity?.launchCount ?: 0,
                    weight = weight ?: entity?.weight ?: 0.0,
                    serializedSearchable = searchable.serialize() ?: return@launch,
                )
            )
        }
    }

    override fun update(
        searchable: SavableSearchable,
        visibility: VisibilityLevel?,
        pinned: Boolean?,
        launchCount: Int?,
        weight: Double?
    ) {
        val dao = database.searchableDao()
        scope.launch {
            val entity = dao.getByKey(searchable.key).firstOrNull()
            dao.upsert(
                SavedSearchableEntity(
                    key = searchable.key,
                    type = searchable.domain,
                    visibility = visibility?.value ?: entity?.visibility ?: 0,
                    pinPosition = pinned?.let { if (it) 1 else 0 } ?: entity?.pinPosition ?: 0,
                    launchCount = launchCount ?: entity?.launchCount ?: 0,
                    weight = weight ?: entity?.weight ?: 0.0,
                    serializedSearchable = searchable.serialize() ?: return@launch,
                )
            )
        }
    }

    override fun touch(searchable: SavableSearchable) {
        scope.launch {
            val weightFactor =
                when (settings?.weightFactor?.firstOrNull()) {
                    WeightFactor.Low -> WEIGHT_FACTOR_LOW
                    WeightFactor.High -> WEIGHT_FACTOR_HIGH
                    else -> WEIGHT_FACTOR_MEDIUM
                }
            val item =
                SavedSearchable(searchable.key, searchable, 0, 0, VisibilityLevel.Default, 0.0)
            item.toDatabaseEntity()?.let {
                database.searchableDao()
                    .touch(it, weightFactor)
            }
        }
    }

    override fun get(
        includeTypes: List<String>?,
        excludeTypes: List<String>?,
        minPinnedLevel: PinnedLevel,
        maxPinnedLevel: PinnedLevel,
        minVisibility: VisibilityLevel,
        maxVisibility: VisibilityLevel,
        limit: Int
    ): Flow<List<SavableSearchable>> {
        val dao = database.searchableDao()
        val query = { fetch: Int ->
            when {
                includeTypes == null && excludeTypes == null -> dao.get(
                    manuallySorted = PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel,
                    automaticallySorted = PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel,
                    frequentlyUsed = PinnedLevel.FrequentlyUsed in minPinnedLevel..maxPinnedLevel,
                    unused = PinnedLevel.NotPinned in minPinnedLevel..maxPinnedLevel,
                    minVisibility = minVisibility.value,
                    maxVisibility = maxVisibility.value,
                    limit = fetch
                )

                includeTypes == null -> dao.getExcludeTypes(
                    excludeTypes = excludeTypes,
                    manuallySorted = PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel,
                    automaticallySorted = PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel,
                    frequentlyUsed = PinnedLevel.FrequentlyUsed in minPinnedLevel..maxPinnedLevel,
                    unused = PinnedLevel.NotPinned in minPinnedLevel..maxPinnedLevel,
                    minVisibility = minVisibility.value,
                    maxVisibility = maxVisibility.value,
                    limit = fetch
                )

                excludeTypes == null -> dao.getIncludeTypes(
                    includeTypes = includeTypes,
                    manuallySorted = PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel,
                    automaticallySorted = PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel,
                    frequentlyUsed = PinnedLevel.FrequentlyUsed in minPinnedLevel..maxPinnedLevel,
                    unused = PinnedLevel.NotPinned in minPinnedLevel..maxPinnedLevel,
                    minVisibility = minVisibility.value,
                    maxVisibility = maxVisibility.value,
                    limit = fetch
                )

                else -> throw IllegalArgumentException("Cannot specify both includeTypes and excludeTypes")
            }
        }
        // A list without hidden items leaves out an item that has a hidden row
        // anywhere: while two contacts are merged, one's hidden row makes the
        // merged contact hidden, and the other's pin must not show it. The
        // stricter visibility wins (#237). Left out before the limit is
        // counted, so the list still fills.
        val hidden = if (minVisibility.value >= VisibilityLevel.Hidden.value) null else hiddenKeys()
        return resolvedUpTo(limit, query, hidden)
    }

    override fun getKeys(
        includeTypes: List<String>?,
        excludeTypes: List<String>?,
        minPinnedLevel: PinnedLevel,
        maxPinnedLevel: PinnedLevel,
        minVisibility: VisibilityLevel,
        maxVisibility: VisibilityLevel,
        limit: Int
    ): Flow<List<String>> {
        val dao = database.searchableDao()
        return when {
            includeTypes == null && excludeTypes == null -> dao.getKeys(
                manuallySorted = PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel,
                automaticallySorted = PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel,
                frequentlyUsed = PinnedLevel.FrequentlyUsed in minPinnedLevel..maxPinnedLevel,
                unused = PinnedLevel.NotPinned in minPinnedLevel..maxPinnedLevel,
                minVisibility = minVisibility.value,
                maxVisibility = maxVisibility.value,
                limit = limit
            )

            includeTypes == null -> dao.getKeysExcludeTypes(
                excludeTypes = excludeTypes,
                manuallySorted = PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel,
                automaticallySorted = PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel,
                frequentlyUsed = PinnedLevel.FrequentlyUsed in minPinnedLevel..maxPinnedLevel,
                unused = PinnedLevel.NotPinned in minPinnedLevel..maxPinnedLevel,
                minVisibility = minVisibility.value,
                maxVisibility = maxVisibility.value,
                limit = limit
            )

            excludeTypes == null -> dao.getKeysIncludeTypes(
                includeTypes = includeTypes,
                manuallySorted = PinnedLevel.ManuallySorted in minPinnedLevel..maxPinnedLevel,
                automaticallySorted = PinnedLevel.AutomaticallySorted in minPinnedLevel..maxPinnedLevel,
                frequentlyUsed = PinnedLevel.FrequentlyUsed in minPinnedLevel..maxPinnedLevel,
                unused = PinnedLevel.NotPinned in minPinnedLevel..maxPinnedLevel,
                minVisibility = minVisibility.value,
                maxVisibility = maxVisibility.value,
                limit = limit
            )

            else -> throw IllegalArgumentException("Cannot specify both includeTypes and excludeTypes")
        }
    }

    override fun isPinned(searchable: SavableSearchable): Flow<Boolean> {
        return database.searchableDao().isPinned(searchable.key)
    }

    override fun getVisibility(searchable: SavableSearchable): Flow<VisibilityLevel> {
        return database.searchableDao().getVisibility(searchable.key).map {
            VisibilityLevel.fromInt(it)
        }
    }

    override fun delete(searchable: SavableSearchable) {
        scope.launch {
            database.searchableDao().delete(searchable.key)
        }
    }

    override fun replace(key: String, newSearchable: SavableSearchable) {
        scope.launch {
            database.searchableDao().replace(
                key,
                SavedSearchableUpdateContentEntity(
                    key = newSearchable.key,
                    type = newSearchable.domain,
                    serializedSearchable = newSearchable.serialize() ?: return@launch
                )
            )
        }
    }

    // Fork addition (Phase 2 config reload): one ordered writer for favorite
    // replacements. Both enqueue at call time, so a fire-and-forget
    // updateFavorites(A) issued before replaceManuallySortedAwaited(B) can never
    // land after B and undo it; the awaited one returns once its entry committed.
    private val favoriteWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in favoriteWrites) write()
        }
    }

    override fun updateFavorites(
        manuallySorted: List<SavableSearchable>,
        automaticallySorted: List<SavableSearchable>
    ) {
        favoriteWrites.trySend { updateFavoritesInternal(manuallySorted, automaticallySorted) }
    }

    // Fork addition (#3 D4), see interface. The other types' pins are not
    // touched at all: the items go above the highest of them, so their order
    // and positions stay as they are, and a pin whose item no longer
    // deserializes (an uninstalled app's shortcut) is kept, not dropped.
    override suspend fun replaceManuallySortedAwaited(types: List<String>, items: List<SavableSearchable>) {
        val done = CompletableDeferred<Unit>()
        favoriteWrites.trySend {
            try {
                val dao = database.searchableDao()
                database.withTransaction {
                    dao.unpinManuallySorted(types)
                    val othersTop = dao.getHighestManualPinPositionExcept(types) ?: 1
                    dao.upsert(pinEntities(items, top = othersTop + items.size))
                }
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        done.await()
    }

    // Fork addition (#3 slice 4), see interface.
    override suspend fun setVisibilitiesAwaited(visibilities: Map<SavableSearchable, VisibilityLevel>) {
        if (visibilities.isEmpty()) return
        val dao = database.searchableDao()
        database.withTransaction {
            for ((searchable, visibility) in visibilities) {
                val serialized = searchable.serialize() ?: continue
                val entity = dao.getByKey(searchable.key).firstOrNull()
                dao.upsert(
                    SavedSearchableEntity(
                        key = searchable.key,
                        type = searchable.domain,
                        visibility = visibility.value,
                        pinPosition = entity?.pinPosition ?: 0,
                        launchCount = entity?.launchCount ?: 0,
                        weight = entity?.weight ?: 0.0,
                        serializedSearchable = serialized,
                    )
                )
            }
        }
    }

    // Fork addition (#3 slice 4), see interface.
    override suspend fun insertAwaited(searchables: Collection<SavableSearchable>) {
        if (searchables.isEmpty()) return
        val dao = database.searchableDao()
        database.withTransaction {
            for (searchable in searchables) {
                dao.insert(
                    SavedSearchableEntity(
                        key = searchable.key,
                        type = searchable.domain,
                        serializedSearchable = searchable.serialize() ?: continue,
                        visibility = VisibilityLevel.Default.value,
                        launchCount = 0,
                        weight = 0.0,
                        pinPosition = 0,
                    )
                )
            }
        }
    }

    /**
     * Manual pins for [items] in order, the first at [top] and each next one
     * below it; positions above 1 are manually sorted, 1 is automatic.
     */
    private fun pinEntities(items: List<SavableSearchable>, top: Int) =
        items.mapIndexedNotNull { index, searchable ->
            SavedSearchableUpdatePinEntity(
                key = searchable.key,
                type = searchable.domain,
                pinPosition = top - index,
                serializedSearchable = searchable.serialize() ?: return@mapIndexedNotNull null,
            )
        }

    private suspend fun updateFavoritesInternal(
        manuallySorted: List<SavableSearchable>,
        automaticallySorted: List<SavableSearchable>
    ) {
        val dao = database.searchableDao()
        database.withTransaction {
            dao.unpinAll()
            dao.upsert(pinEntities(manuallySorted, top = manuallySorted.size + 1))
            dao.upsert(
                automaticallySorted.mapNotNull { savableSearchable ->
                    SavedSearchableUpdatePinEntity(
                        key = savableSearchable.key,
                        type = savableSearchable.domain,
                        pinPosition = 1,
                        serializedSearchable = savableSearchable.serialize()
                            ?: return@mapNotNull null,
                    )
                }
            )
        }
    }

    override fun sortByRelevance(keys: List<String>): Flow<List<String>> {
        if (keys.size > 999) return flowOf(emptyList())
        return database.searchableDao().sortByRelevance(keys)
    }

    override fun sortByWeight(keys: List<String>): Flow<List<String>> {
        if (keys.size > 999) return flowOf(emptyList())
        return database.searchableDao().sortByWeight(keys)
    }

    override fun getWeights(keys: List<String>): Flow<Map<String, Double>> {
        if (keys.size > 999) return flowOf(emptyMap())
        return database.searchableDao().getWeights(keys)
    }

    private suspend fun resolve(entity: SavedSearchableEntity): Resolved {
        val deserializer: SearchableDeserializer = try {
            get(named(entity.type))
        } catch (e: NoDefinitionFoundException) {
            CrashReporter.logException(e)
            return Resolved.Gone
        } catch (e: InstanceCreationException) {
            // A deserializer that could not be created says nothing about the item.
            CrashReporter.logException(e)
            return Resolved.Unknown
        }
        return deserializer.resolve(entity.serializedSearchable)
    }

    /**
     * Resolves [entities], and resolves them again whenever the deserializer
     * of one of their types says its answer may have changed: a contact
     * that was Unknown without its permission appears once it is granted,
     * without waiting for the database to change (#237).
     */
    private fun resolving(entities: List<SavedSearchableEntity>): Flow<List<SavableSearchable>> {
        val again = entities.map { it.type }.distinct().mapNotNull { type ->
            // Logged by resolve() when it fails; a missing trigger only
            // means that type is not resolved again.
            runCatching { get<SearchableDeserializer>(named(type)).resolveAgain }.getOrNull()
        }
        return merge(flowOf(Unit), *again.toTypedArray()).map {
            // Two rows can resolve to one item - merged contacts keep their
            // rows under their own keys (#237) - and a list keyed by item
            // throws on a key used twice. The first row in the query's order
            // is kept: an arbitrary tiebreak, which the stored rows do not
            // settle either way.
            entities.mapNotNull { fromDatabaseEntity(it).searchable }.distinctBy { it.key }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<List<SavedSearchableEntity>>.resolved(): Flow<List<SavableSearchable>> =
        flatMapLatest { resolving(it) }

    /**
     * Up to [limit] resolved items. The limit is applied in SQL, before rows
     * that do not resolve are dropped, and such rows are kept (#237): a read
     * whose rows fall short while the query returned all it was asked for
     * asks again for twice as many, until the limit is filled or the rows run
     * out. With every row resolving it costs nothing extra.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun resolvedUpTo(
        limit: Int,
        query: (Int) -> Flow<List<SavedSearchableEntity>>,
        /** Keys to leave out, counted before the limit; null leaves out nothing. */
        excluded: Flow<Set<String>>?,
        fetch: Int = limit,
    ): Flow<List<SavableSearchable>> = query(fetch).flatMapLatest { entities ->
        val resolved = resolving(entities)
        val kept = if (excluded == null) resolved else combine(resolved, excluded) { found, out -> found.filterNot { it.key in out } }
        kept.flatMapLatest { found ->
            if (found.size >= limit || entities.size < fetch) flowOf(found.take(limit))
            else resolvedUpTo(limit, query, excluded, (fetch.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private suspend fun fromDatabaseEntity(entity: SavedSearchableEntity): SavedSearchable {
        val resolved = resolve(entity)
        // Only Gone deletes. An app that is not installed is knowably gone; a
        // contact never is (permission, Contact Scopes), so it answers Unknown
        // and keeps its pin, tags and label. Do not re-unify the two (#237).
        if (resolved == Resolved.Gone) removeInvalidItem(entity.key)
        if (resolved is Resolved.Moved) moveItem(entity.key, resolved.searchable)
        return SavedSearchable(
            key = entity.key,
            searchable = when (resolved) {
                is Resolved.Found -> resolved.searchable
                is Resolved.Moved -> resolved.searchable
                else -> null
            },
            launchCount = entity.launchCount,
            pinPosition = entity.pinPosition,
            visibility = VisibilityLevel.fromInt(entity.visibility),
            weight = entity.weight
        )
    }

    private fun moveItem(oldKey: String, item: SavableSearchable) {
        scope.launch { rekey(oldKey, item) }
    }

    /**
     * Moves the row stored under [oldKey], and every customization stored
     * under it, to [item]'s current key, in one transaction (#237) - unless
     * the new key is taken, by a row or by customizations alone. Then nothing
     * moves: a row is never merged into one that belongs to someone else.
     * Merging cannot be undone; on the emulator a merge, a resume and a split
     * sent a merged row - Bob's hidden flag with Alice's tag - to Alice and
     * left Bob visible. Two rows that resolve to one item are collapsed where
     * they are read instead (see [resolving]).
     */
    internal suspend fun rekey(oldKey: String, item: SavableSearchable) = RekeyLock.withLock {
        val newKey = item.key
        if (newKey == oldKey) return@withLock
        val serializer = item.getSerializer()
        val serialized = serializer.serialize(item) ?: return@withLock
        val moved = database.withTransaction {
            val dao = database.searchableDao()
            val attrs = database.customAttrsDao()
            if (dao.getOnce(newKey) != null || attrs.getAllFor(newKey).isNotEmpty()) return@withTransaction false
            val old = dao.getOnce(oldKey)
            if (old != null) {
                dao.delete(oldKey)
                dao.upsert(old.copy(key = newKey, type = serializer.typePrefix, serializedSearchable = serialized))
            }
            val customizations = attrs.getAllFor(oldKey)
            if (customizations.isNotEmpty()) {
                attrs.deleteAllFor(oldKey)
                attrs.insertCustomAttributes(customizations.map { it.copy(key = newKey, id = null) })
            }
            true
        }
        // Outside the transaction: the gestures live in the DataStore, not in
        // Room. A gesture briefly on the old key launches nothing; the next
        // resolve of that key moves it again.
        if (moved) gestures?.replaceLaunchKey(oldKey, newKey)
    }

    private fun removeInvalidItem(key: String) {
        scope.launch {
            database.searchableDao().delete(key)
        }
    }

    override fun getByKeys(keys: List<String>): Flow<List<SavableSearchable>> {
        val dao = database.searchableDao()
        if (keys.size > 999) {
            return combine(keys.chunked(999).map {
                dao.getByKeys(it).resolved()
            }) { results ->
                results.flatMap { it }
            }
        }
        return dao.getByKeys(keys).resolved()
    }

    /**
     * The stored key of every hidden row, and the current key of every
     * hidden item that resolves. A contact's key moves on a rename, a merge
     * or a split, and the refresh on resume only catches up afterwards; a
     * search result in between carries the new key. For labels and tags that
     * window costs a moment without them. For hiding it would put a contact
     * somebody hid back on the screen, so hiding resolves instead of waiting
     * (#237). Hidden items are few; resolving them is cheap.
     */
    override fun hiddenKeys(): Flow<Set<String>> = combine(
        getKeys(maxVisibility = VisibilityLevel.Hidden),
        get(maxVisibility = VisibilityLevel.Hidden),
    ) { stored, resolved -> stored.toSet() + resolved.map { it.key } }

    override suspend fun refreshMovedKeys() {
        val dao = database.searchableDao()
        for (type in dao.getTypes()) {
            val deserializer = runCatching { get<SearchableDeserializer>(named(type)) }.getOrNull() ?: continue
            if (!deserializer.storedKeysMove) continue
            for (row in dao.getAllOfType(type)) {
                val resolved = deserializer.resolve(row.serializedSearchable)
                if (resolved is Resolved.Moved) rekey(row.key, resolved.searchable)
            }
        }
    }

    override suspend fun cleanupDatabase(): Int {
        var removed = 0
        val job = scope.launch {
            val dao = database.backupDao()
            var page = 0
            do {
                val favorites = dao.exportFavorites(limit = 100, offset = page * 100)
                for (fav in favorites) {
                    val resolved = resolve(fav)
                    if (resolved is Resolved.Moved) rekey(fav.key, resolved.searchable)
                    if (resolved == Resolved.Gone || (resolved is Resolved.Found && resolved.searchable.key != fav.key)) {
                        removeInvalidItem(fav.key)
                        removed++
                        // The kind of item, never its key: a key names the
                        // app or the contact (#15).
                        Log.i(
                            "MM20",
                            "SearchableDatabase cleanup: removed an invalid ${fav.type} item"
                        )
                    }
                }
                page++
            } while (favorites.size == 100)
        }
        job.join()
        return removed
    }

    companion object {
        private const val WEIGHT_FACTOR_LOW = 0.01
        private const val WEIGHT_FACTOR_MEDIUM = 0.03
        private const val WEIGHT_FACTOR_HIGH = 0.1
    }
}

/**
 * Serializes [SavableSearchableRepositoryImpl.rekey] across the process. The
 * repository is bound as a Koin factory, so every injection is a new instance:
 * a lock held by the instance would serialize nothing (#237).
 */
private val RekeyLock = Mutex()
