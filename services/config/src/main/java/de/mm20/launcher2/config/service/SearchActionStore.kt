package de.mm20.launcher2.config.service

import android.content.Context
import android.content.Intent
import de.mm20.launcher2.config.Diagnostic
import de.mm20.launcher2.config.SearchActionConfig
import de.mm20.launcher2.config.SearchActionTypes
import de.mm20.launcher2.config.Severity
import de.mm20.launcher2.searchactions.SearchActionRepository
import de.mm20.launcher2.searchactions.searchActivityOf
import de.mm20.launcher2.searchactions.builders.AppSearchActionBuilder
import de.mm20.launcher2.searchactions.builders.CustomIntentActionBuilder
import de.mm20.launcher2.searchactions.builders.CustomWebsearchActionBuilder
import de.mm20.launcher2.searchactions.builders.SearchActionBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The device's search actions in the contract's form (#106): what the config
 * store reads back and what `search.actions` replaces.
 */
interface SearchActionStore {
    /** The actions in effect, in order. */
    suspend fun read(): List<SearchActionConfig>

    /**
     * Replaces the device's actions with [actions], in order. An entry the
     * device cannot apply is left out and reported at `[basePath][index]`.
     */
    suspend fun replace(actions: List<SearchActionConfig>, basePath: String): List<Diagnostic>

    /** Emits when the actions change, for write-back to follow (#3 slice 4). */
    fun changes(): Flow<Unit> = emptyFlow()
}

/**
 * The store over the launcher's search-action repository: built-ins by the
 * key they are stored under, `url` as a custom web search (its app pin in
 * the entry's options), `app` resolved to the package's search activity.
 */
internal class AndroidSearchActionStore(
    private val context: Context,
    private val repository: SearchActionRepository,
) : SearchActionStore {

    override suspend fun read(): List<SearchActionConfig> =
        repository.getSearchActionBuilders().first().map { it.toConfig() }

    override fun changes(): Flow<Unit> = repository.getSearchActionBuilders().map { }

    override suspend fun replace(actions: List<SearchActionConfig>, basePath: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val builtIns = repository.getBuiltinSearchActionBuilders().associateBy { it.key }
        // A user's own intent actions, kept where a pulled file names them (#116 review).
        val intents = repository.getSearchActionBuilders().first()
            .filterIsInstance<CustomIntentActionBuilder>()
            .groupBy { it.label }
            .mapValues { it.value.toMutableList() }
        val builders = actions.mapIndexedNotNull { index, action ->
            val path = "$basePath[$index]"
            when (action.type) {
                SearchActionTypes.Url -> CustomWebsearchActionBuilder(
                    label = action.label.orEmpty(),
                    urlTemplate = action.url.orEmpty(),
                    encoding = action.encoding.toQueryEncoding(),
                    packageName = action.packageName,
                )
                SearchActionTypes.App -> {
                    val packageName = action.packageName.orEmpty()
                    val activity = searchActivityOf(context, packageName)
                    if (activity == null) {
                        diagnostics += Diagnostic(
                            Severity.Warning,
                            "search-action-app-not-searchable",
                            path,
                            "'$packageName' has no search this profile can open; the action was left out",
                        )
                        null
                    } else {
                        AppSearchActionBuilder(
                            label = action.label.orEmpty(),
                            baseIntent = Intent().setComponent(activity),
                        )
                    }
                }
                SearchActionTypes.Intent -> intents[action.label]?.removeFirstOrNull() ?: run {
                    diagnostics += Diagnostic(
                        Severity.Warning,
                        "search-action-intent-missing",
                        path,
                        "no intent action '${action.label}' on this device; a file cannot create one, it was left out",
                    )
                    null
                }
                // Validated before: a built-in by its stored key.
                else -> builtIns[action.type] ?: run {
                    diagnostics += Diagnostic(
                        Severity.Warning,
                        "search-action-unavailable",
                        path,
                        "'${action.type}' is not available on this device; the action was left out",
                    )
                    null
                }
            }
        }
        repository.replaceSearchActionBuilders(builders)
        return diagnostics
    }

    private fun SearchActionBuilder.toConfig(): SearchActionConfig = when (this) {
        is CustomWebsearchActionBuilder -> SearchActionConfig(
            type = SearchActionTypes.Url,
            label = label,
            url = urlTemplate,
            packageName = packageName,
            encoding = encoding.toConfig(),
        )
        is AppSearchActionBuilder -> SearchActionConfig(
            type = SearchActionTypes.App,
            label = label,
            packageName = baseIntent.component?.packageName ?: baseIntent.`package`,
        )
        // A user's own intent action: read back as what it is; a file cannot write it.
        is CustomIntentActionBuilder -> SearchActionConfig(type = SearchActionTypes.Intent, label = label)
        else -> SearchActionConfig(type = key)
    }

    private fun String?.toQueryEncoding(): CustomWebsearchActionBuilder.QueryEncoding = when (this) {
        "form" -> CustomWebsearchActionBuilder.QueryEncoding.FormData
        "none" -> CustomWebsearchActionBuilder.QueryEncoding.None
        else -> CustomWebsearchActionBuilder.QueryEncoding.UrlEncode
    }

    private fun CustomWebsearchActionBuilder.QueryEncoding.toConfig(): String = when (this) {
        CustomWebsearchActionBuilder.QueryEncoding.UrlEncode -> "url"
        CustomWebsearchActionBuilder.QueryEncoding.FormData -> "form"
        CustomWebsearchActionBuilder.QueryEncoding.None -> "none"
    }
}
