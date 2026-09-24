package de.mm20.launcher2.searchactions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.searchactions.actions.SearchAction
import de.mm20.launcher2.searchactions.actions.SearchActionIcon
import de.mm20.launcher2.searchactions.builders.SearchActionBuilder
import de.mm20.launcher2.searchactions.builders.CustomWebsearchActionBuilder
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.UUID

interface SearchActionService {
    suspend fun search(query: String): Flow<ImmutableList<SearchAction>>

    fun getSearchActionBuilders(): Flow<List<SearchActionBuilder>>
    fun getDisabledActionBuilders(): Flow<List<SearchActionBuilder>>

    fun saveSearchActionBuilders(builders: List<SearchActionBuilder>)

    suspend fun getSearchActivities(): List<ComponentName>

    suspend fun createIcon(uri: Uri, size: Int): String?
}

internal class SearchActionServiceImpl(
    private val context: Context,
    private val repository: SearchActionRepository,
    private val textClassifier: TextClassifier,
) : SearchActionService {
    override suspend fun search(
        query: String
    ): Flow<ImmutableList<SearchAction>> {

        if (query.isBlank()) {
            return flowOf(persistentListOf())
        }

        val classificationResult = textClassifier.classify(context, query)

        val builders = repository.getSearchActionBuilders()

        return builders.map {
            it.mapNotNull { it.build(context, classificationResult) }.toImmutableList()
        }
    }

    override fun getSearchActionBuilders(): Flow<List<SearchActionBuilder>> {
        return repository.getSearchActionBuilders()
    }

    override fun getDisabledActionBuilders(): Flow<List<SearchActionBuilder>> {
        val allActions = repository.getBuiltinSearchActionBuilders()

        return getSearchActionBuilders().map { enabled ->
            allActions.filter { action -> !enabled.any { it.key == action.key } }
        }
    }

    override fun saveSearchActionBuilders(builders: List<SearchActionBuilder>) {
        repository.saveSearchActionBuilders(builders)
    }

    override suspend fun createIcon(uri: Uri, size: Int): String? = withContext(
        Dispatchers.IO
    ) {
        val file = File(context.filesDir, UUID.randomUUID().toString())
        val imageRequest = ImageRequest.Builder(context)
            .data(uri)
            .size(size)
            .scale(Scale.FIT)
            .build()
        val drawable =
            context.imageLoader.execute(imageRequest).drawable ?: return@withContext null
        val scaledIcon = drawable.toBitmap()
        val out = FileOutputStream(file)
        scaledIcon.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.close()
        return@withContext file.absolutePath
    }

    override suspend fun getSearchActivities(): List<ComponentName> {
        return withContext(Dispatchers.Default) {
            val resolveInfos = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_SEARCH).addCategory(Intent.CATEGORY_DEFAULT),
                PackageManager.GET_META_DATA,
            )
            resolveInfos.mapNotNull { it.startableSearchActivity(context) }
        }
    }
}

/**
 * The search activity in [packageName] the launcher can start, or null: the
 * same rule the settings use to list searchable apps (exported, enabled, no
 * permission the launcher lacks), shared with the config store (#116 review).
 */
fun searchActivityOf(context: Context, packageName: String): ComponentName? =
    context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_SEARCH).addCategory(Intent.CATEGORY_DEFAULT).setPackage(packageName),
        PackageManager.GET_META_DATA,
    ).firstNotNullOfOrNull { it.startableSearchActivity(context) }

private fun ResolveInfo.startableSearchActivity(context: Context): ComponentName? {
    if (!activityInfo.exported || !activityInfo.enabled) return null
    val permission = activityInfo.permission
    if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null
    return ComponentName(activityInfo.packageName, activityInfo.name)
}
