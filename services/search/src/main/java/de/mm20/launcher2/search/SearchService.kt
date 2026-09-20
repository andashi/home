package de.mm20.launcher2.search

import android.util.Log
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.utils.withCustomLabels
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.searchactions.SearchActionService
import de.mm20.launcher2.searchactions.actions.SearchAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

interface SearchService {
    fun search(
        query: String,
        filters: SearchFilters,
        initialResults: SearchResults? = null,
    ): Flow<SearchResults>

    fun getAllApps(): Flow<AllAppsResults>
}

internal class SearchServiceImpl(
    private val appRepository: SearchableRepository<Application>,
    private val appShortcutRepository: SearchableRepository<AppShortcut>,
    private val contactRepository: SearchableRepository<Contact>,
    private val searchActionService: SearchActionService,
    private val customAttributesRepository: CustomAttributesRepository,
    private val profileManager: ProfileManager,
) : SearchService {

    override fun search(
        query: String,
        filters: SearchFilters,
        initialResults: SearchResults?,
    ): Flow<SearchResults> = flow {
        supervisorScope {
            val results = MutableStateFlow(
                initialResults?.let {
                    it.copy(
                        apps = if (filters.apps) it.apps else null,
                        shortcuts = if (filters.shortcuts) it.shortcuts else null,
                        contacts = if (filters.contacts) it.contacts else null,
                    )
                }
                    ?: SearchResults())

            val customAttrResults = customAttributesRepository.search(query)
                .map { items ->
                    val apps = mutableListOf<Application>()
                    val shortcuts = mutableListOf<AppShortcut>()
                    val contacts = mutableListOf<Contact>()
                    val searchActions = mutableListOf<SearchAction>()
                    for (it in items) {
                        when (it) {
                            is Application -> if (filters.apps) apps.add(it)
                            is AppShortcut -> if (filters.shortcuts) shortcuts.add(it)
                            is Contact -> if (filters.contacts) contacts.add(it)
                            is SearchAction -> searchActions.add(it)
                        }
                    }
                    SearchResults(
                        apps = apps,
                        shortcuts = shortcuts,
                        contacts = contacts,
                        searchActions = searchActions,
                    )
                }.shareIn(this, SharingStarted.WhileSubscribed(), 1)

            launch {
                searchActionService.search(query)
                    .collectLatest { r ->
                        results.update {
                            it.copy(searchActions = r)
                        }
                    }
            }
            if (filters.apps) {
                launch {
                    appRepository.search(query, filters.allowNetwork)
                        .combine(customAttrResults) { apps, customAttrs ->
                            if (customAttrs.apps != null) apps + customAttrs.apps
                            else apps
                        }
                        .withCustomLabels(customAttributesRepository)
                        .collectLatest { r ->
                            results.update {
                                it.copy(apps = r)
                            }
                        }
                }
            }
            if (filters.shortcuts) {
                launch {
                    appShortcutRepository.search(query, filters.allowNetwork)
                        .combine(customAttrResults) { shortcuts, customAttrs ->
                            if (customAttrs.shortcuts != null) shortcuts + customAttrs.shortcuts
                            else shortcuts
                        }
                        .withCustomLabels(customAttributesRepository)
                        .collectLatest { r ->
                            results.update {
                                it.copy(shortcuts = r)
                            }
                        }
                }
            }
            if (filters.contacts) {
                launch {
                    contactRepository.search(query, filters.allowNetwork)
                        .combine(customAttrResults) { contacts, customAttrs ->
                            if (customAttrs.contacts != null) contacts + customAttrs.contacts
                            else contacts
                        }
                        .withCustomLabels(customAttributesRepository)
                        .collectLatest { r ->
                            results.update {
                                it.copy(contacts = r)
                            }
                        }
                }
            }
            emitAll(results)
        }
    }

    override fun getAllApps(): Flow<AllAppsResults> {
        return profileManager.profiles.flatMapLatest { profiles ->
            val standardProfile = profiles.find { it.type == Profile.Type.Personal }
            val workProfile = profiles.find { it.type == Profile.Type.Work }
            val privateSpace = profiles.find { it.type == Profile.Type.Private }
            appRepository.search("", false)
                .withCustomLabels(customAttributesRepository)
                .map { apps ->
                    val standardProfileApps = mutableListOf<Application>()
                    val workProfileApps = mutableListOf<Application>()
                    val privateSpaceApps = mutableListOf<Application>()
                    for (app in apps) {
                        when {
                            standardProfile != null && app.user == standardProfile.userHandle -> standardProfileApps.add(
                                app
                            )

                            workProfile != null && app.user == workProfile.userHandle -> workProfileApps.add(
                                app
                            )

                            privateSpace != null && app.user == privateSpace.userHandle -> privateSpaceApps.add(
                                app
                            )

                            else -> {
                                Log.w(
                                    "MM20",
                                    "App ${app.label} does not belong to any known profile. Ignoring."
                                )
                            }
                        }
                    }

                    AllAppsResults(
                        standardProfileApps = standardProfileApps.sorted(),
                        workProfileApps = workProfileApps.sorted(),
                        privateSpaceApps = privateSpaceApps.sorted(),
                    )
                }
        }
    }
}

data class SearchResults(
    val apps: List<Application>? = null,
    val shortcuts: List<AppShortcut>? = null,
    val contacts: List<Contact>? = null,
    val searchActions: List<SearchAction>? = null,
)

data class AllAppsResults(
    val standardProfileApps: List<Application>,
    val workProfileApps: List<Application>,
    val privateSpaceApps: List<Application>,
)

fun SearchResults.toList(): List<Searchable> {
    return listOfNotNull(
        apps,
        shortcuts,
        contacts,
        searchActions,
    ).flatten()
}