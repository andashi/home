package de.mm20.launcher2.searchactions

import android.content.Context
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.searchactions.builders.CallActionBuilder
import de.mm20.launcher2.searchactions.builders.CreateContactActionBuilder
import de.mm20.launcher2.searchactions.builders.PrivateSpaceLockActionBuilder
import de.mm20.launcher2.searchactions.builders.EmailActionBuilder
import de.mm20.launcher2.searchactions.builders.MessageActionBuilder
import de.mm20.launcher2.searchactions.builders.OpenUrlActionBuilder
import de.mm20.launcher2.searchactions.builders.ScheduleEventActionBuilder
import de.mm20.launcher2.searchactions.builders.SearchActionBuilder
import de.mm20.launcher2.searchactions.builders.SetAlarmActionBuilder
import de.mm20.launcher2.searchactions.builders.ShareActionBuilder
import de.mm20.launcher2.searchactions.builders.TimerActionBuilder
import de.mm20.launcher2.searchactions.builders.WebsearchActionBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

interface SearchActionRepository {
    fun getSearchActionBuilders(): Flow<List<SearchActionBuilder>>
    fun getBuiltinSearchActionBuilders(): List<SearchActionBuilder>

    fun saveSearchActionBuilders(builders: List<SearchActionBuilder>)

    /** Replaces the stored actions and returns once they are written (#106: the config's read-back). */
    suspend fun replaceSearchActionBuilders(builders: List<SearchActionBuilder>)
}

internal class SearchActionRepositoryImpl(
    private val context: Context,
    private val database: AppDatabase,
    writeContext: CoroutineContext = Dispatchers.Default,
) : SearchActionRepository {

    private val scope = CoroutineScope(writeContext + SupervisorJob())
    override fun getSearchActionBuilders(): Flow<List<SearchActionBuilder>> {
        val dao = database.searchActionDao()
        return dao.getSearchActions()
            .map { it.mapNotNull { SearchActionBuilder.from(context, it) } }
    }

    override fun getBuiltinSearchActionBuilders(): List<SearchActionBuilder> {
        val allActions = listOf(
            CallActionBuilder(context),
            MessageActionBuilder(context),
            CreateContactActionBuilder(context),
            EmailActionBuilder(context),
            ScheduleEventActionBuilder(context),
            SetAlarmActionBuilder(context),
            TimerActionBuilder(context),
            OpenUrlActionBuilder(context),
            WebsearchActionBuilder(context),
            ShareActionBuilder(context),
            PrivateSpaceLockActionBuilder(context),
        )

        return allActions
    }

    override suspend fun replaceSearchActionBuilders(builders: List<SearchActionBuilder>) {
        database.searchActionDao().replaceAll(
            builders.mapIndexed { i, it -> SearchActionBuilder.toDatabaseEntity(it, i) }
        )
    }

    override fun saveSearchActionBuilders(builders: List<SearchActionBuilder>) {
        scope.launch {
            val dao = database.searchActionDao()
            dao.replaceAll(
                builders.mapIndexed { i, it -> SearchActionBuilder.toDatabaseEntity(it, i) }
            )
        }
    }

}