package de.mm20.launcher2.data.customattrs

import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.Tag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

interface CustomAttributesRepository {

    fun search(query: String): Flow<ImmutableList<SavableSearchable>>

    fun getCustomIcon(searchable: SavableSearchable): Flow<CustomIcon?>
    fun setCustomIcon(searchable: SavableSearchable, icon: CustomIcon?)

    fun getCustomLabels(items: List<SavableSearchable>): Flow<List<CustomLabel>>
    fun setCustomLabel(searchable: SavableSearchable, label: String)
    fun clearCustomLabel(searchable: SavableSearchable)

    /**
     * Fork addition (#3 slice 4): the labels of [items] become [labels] (by
     * key) in one transaction, returning once committed, so a config reload
     * can read back what it wrote. An item of [items] missing from [labels]
     * loses its label; items not in [items] are left alone.
     */
    suspend fun replaceCustomLabelsAwaited(items: List<SavableSearchable>, labels: Map<String, String>)

    /** Fork addition (#3 slice 4): every app's label, by key. */
    fun getAppLabels(): Flow<Map<String, String>>

    /**
     * Fork addition (#3 slice 4): the icons of [items] become [icons] (by
     * key) in one transaction, returning once committed. An item of [items]
     * missing from [icons] loses its icon; items not in [items] are left
     * alone. No item row is needed: the cleanup never removes an app's icon.
     */
    suspend fun replaceCustomIconsAwaited(items: List<SavableSearchable>, icons: Map<String, CustomIcon>)

    /** Fork addition (#3 slice 4): every app's custom icon, by key, decoded as the picker's are. */
    fun getAppIcons(): Flow<Map<String, CustomIcon>>

    /**
     * Fork addition (#3 slice 4): the tags of [items] become [tags] (by key)
     * in one transaction, returning once committed. An item of [items]
     * missing from [tags] loses its tags; items not in [items] are left
     * alone. A tagged item is anchored first, as a labelled one is: the
     * cleanup removes a tag whose item has no row.
     */
    suspend fun replaceAppTagsAwaited(items: List<SavableSearchable>, tags: Map<String, Set<String>>)

    /** Fork addition (#3 slice 4): every app's tags, by key. */
    fun getAppTags(): Flow<Map<String, Set<String>>>

    /**
     * Fork addition (#3 slice 4): the icons of the tags named [tags] become
     * [icons] (by tag name), returning once committed. A tag of [tags]
     * missing from [icons] loses its icon; other tags keep theirs.
     */
    suspend fun replaceTagIconsAwaited(tags: Collection<String>, icons: Map<String, CustomIcon>)

    /** Fork addition (#3 slice 4): every tag's custom icon, by tag name. */
    fun getTagIcons(): Flow<Map<String, CustomIcon>>

    fun setTags(searchable: SavableSearchable, tags: List<String>)
    fun getTags(searchable: SavableSearchable): Flow<List<String>>

    fun getAllTags(startsWith: String? = null): Flow<List<String>>
    fun getItemsForTag(tag: String): Flow<List<SavableSearchable>>
    fun setItemsForTag(tag: String, items: List<SavableSearchable>): Job
    fun addTag(item: SavableSearchable, tag: String)

    fun renameTag(oldName: String, newName: String): Job
    fun deleteTag(tag: String): Job
    suspend fun cleanupDatabase(): Int
}

internal class CustomAttributesRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val searchableRepository: SavableSearchableRepository
) : CustomAttributesRepository {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    override fun getCustomIcon(searchable: SavableSearchable): Flow<CustomIcon?> {
        val dao = appDatabase.customAttrsDao()
        return dao.getCustomAttribute(searchable.key, CustomAttributeType.Icon.value)
            .map {
                CustomAttribute.fromDatabaseEntity(it) as? CustomIcon
            }
    }

    override fun setCustomIcon(searchable: SavableSearchable, icon: CustomIcon?) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.clearCustomAttribute(searchable.key, CustomAttributeType.Icon.value)
            if (icon != null) {
                dao.setCustomAttribute(icon.toDatabaseEntity(searchable.key))
            }
        }
    }

    override fun getCustomLabels(items: List<SavableSearchable>): Flow<List<CustomLabel>> {
        if (items.size <= 999) {
            val dao = appDatabase.customAttrsDao()
            return dao.getCustomAttributes(items.map { it.key }, CustomAttributeType.Label.value)
                .map { list ->
                    list.mapNotNull { CustomAttribute.fromDatabaseEntity(it) as? CustomLabel }
                }
        } else {
            val dao = appDatabase.customAttrsDao()
            return combine(items.chunked(999).map { chunk ->
                dao.getCustomAttributes(chunk.map { it.key }, CustomAttributeType.Label.value)
            }) { results ->
                results.flatMap { list ->
                    list.mapNotNull { CustomAttribute.fromDatabaseEntity(it) as? CustomLabel }
                }
            }
        }
    }

    override fun setCustomLabel(searchable: SavableSearchable, label: String) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            searchableRepository.insert(searchable)
            appDatabase.runInTransaction {
                dao.clearCustomAttribute(searchable.key, CustomAttributeType.Label.value)
                dao.setCustomAttribute(
                    CustomLabel(
                        key = searchable.key,
                        label = label,
                    ).toDatabaseEntity(searchable.key)
                )
            }
        }
    }

    override suspend fun replaceCustomLabelsAwaited(items: List<SavableSearchable>, labels: Map<String, String>) {
        // A labelled item keeps a row, as setCustomLabel gives it: the
        // cleanup removes a label whose item has none. Awaited, so the row
        // exists before the label does (review on #207).
        val byKey = items.associateBy { it.key }
        searchableRepository.insertAwaited(labels.keys.mapNotNull(byKey::get))
        appDatabase.customAttrsDao().replaceLabels(
            keys = items.map { it.key },
            labels = labels.map { (key, label) -> CustomLabel(key = key, label = label).toDatabaseEntity(key) },
        )
    }

    override fun getAppLabels(): Flow<Map<String, String>> =
        appDatabase.customAttrsDao().getAppLabels().map { rows -> rows.associate { it.key to it.value } }

    override suspend fun replaceCustomIconsAwaited(items: List<SavableSearchable>, icons: Map<String, CustomIcon>) {
        appDatabase.customAttrsDao().replaceAttributes(
            CustomAttributeType.Icon.value,
            keys = items.map { it.key },
            entities = icons.map { (key, icon) -> icon.toDatabaseEntity(key) },
        )
    }

    override fun getAppIcons(): Flow<Map<String, CustomIcon>> =
        appDatabase.customAttrsDao().getAppAttributes(CustomAttributeType.Icon.value).map { rows ->
            rows.mapNotNull { row -> (CustomAttribute.fromDatabaseEntity(row) as? CustomIcon)?.let { row.key to it } }.toMap()
        }

    override suspend fun replaceAppTagsAwaited(items: List<SavableSearchable>, tags: Map<String, Set<String>>) {
        val byKey = items.associateBy { it.key }
        searchableRepository.insertAwaited(tags.filterValues { it.isNotEmpty() }.keys.mapNotNull(byKey::get))
        appDatabase.customAttrsDao().replaceAttributes(
            CustomAttributeType.Tag.value,
            keys = items.map { it.key },
            entities = tags.flatMap { (key, names) -> names.map { CustomTag(it).toDatabaseEntity(key) } },
        )
    }

    override fun getAppTags(): Flow<Map<String, Set<String>>> =
        appDatabase.customAttrsDao().getAppAttributes(CustomAttributeType.Tag.value).map { rows ->
            rows.groupBy({ it.key }, { it.value }).mapValues { it.value.toSet() }
        }

    override suspend fun replaceTagIconsAwaited(tags: Collection<String>, icons: Map<String, CustomIcon>) {
        appDatabase.customAttrsDao().replaceAttributes(
            CustomAttributeType.Icon.value,
            keys = tags.map { Tag(it).key },
            entities = icons.map { (tag, icon) -> icon.toDatabaseEntity(Tag(tag).key) },
        )
    }

    override fun getTagIcons(): Flow<Map<String, CustomIcon>> =
        appDatabase.customAttrsDao().getTagAttributes(CustomAttributeType.Icon.value).map { rows ->
            rows.mapNotNull { row ->
                (CustomAttribute.fromDatabaseEntity(row) as? CustomIcon)?.let { row.key.removePrefix("${Tag.Domain}://") to it }
            }.toMap()
        }

    override fun clearCustomLabel(searchable: SavableSearchable) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.clearCustomAttribute(searchable.key, CustomAttributeType.Label.value)
        }
    }

    override fun setTags(searchable: SavableSearchable, tags: List<String>) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            searchableRepository.insert(searchable)
            dao.setTags(searchable.key, tags.map {
                CustomTag(it).toDatabaseEntity(searchable.key)
            })
        }
    }

    override fun getTags(searchable: SavableSearchable): Flow<List<String>> {
        val dao = appDatabase.customAttrsDao()
        return dao.getCustomAttributes(listOf(searchable.key), CustomAttributeType.Tag.value).map {
            it.map { it.value }
        }
    }

    override fun getAllTags(startsWith: String?): Flow<List<String>> {
        val dao = appDatabase.customAttrsDao()
        return if (startsWith != null) {
            dao.getAllTagsLike("$startsWith%")
        } else {
            dao.getAllTags()
        }
    }

    override fun getItemsForTag(tag: String): Flow<List<SavableSearchable>> {
        val dao = appDatabase.customAttrsDao()
        return dao.getItemsWithTag(tag).flatMapLatest {
            searchableRepository.getByKeys(it)
        }
    }

    override fun setItemsForTag(tag: String, items: List<SavableSearchable>): Job {
        val dao = appDatabase.customAttrsDao()
        return scope.launch {
            dao.setItemsWithTag(tag, items.map { it.key })
            for (item in items) {
                searchableRepository.insert(item)
            }
        }
    }


    override fun addTag(item: SavableSearchable, tag: String) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.addTag(item.key, tag)
        }
    }

    override fun renameTag(oldName: String, newName: String): Job {
        val dao = appDatabase.customAttrsDao()
        return scope.launch {
            dao.renameTag(oldName, newName)
        }
    }

    override fun deleteTag(tag: String): Job {
        val dao = appDatabase.customAttrsDao()
        return scope.launch {
            dao.deleteTag(tag)
        }
    }

    override fun search(query: String): Flow<ImmutableList<SavableSearchable>> {
        if (query.isBlank()) {
            return flow {
                emit(persistentListOf())
            }
        }
        val dao = appDatabase.customAttrsDao()
        return dao.search("%$query%").flatMapLatest {
            searchableRepository.getByKeys(it).map {
                it.toImmutableList()
            }
        }
    }

    override suspend fun cleanupDatabase(): Int {
        val dao = appDatabase.backupDao()
        var removed = 0
        val job = scope.launch {
            removed = dao.cleanUp()
        }
        job.join()
        return removed
    }
}