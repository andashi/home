package de.mm20.launcher2.preferences.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.toLauncherConfig
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import de.mm20.launcher2.preferences.seedSettingsFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * "A phone is a file" (#225): every setting a person can change on the phone
 * is either something `launcher.json` can express, or excluded for a reason,
 * or a counted gap. Something the file expresses is paired by the settings
 * bridge, or - when the stored form carries a device value only the store can
 * map, a user serial - by the store. The mapping from settings fields to contract keys is not
 * derivable - `homeGridColumns` is `home.grid.columns`, `localeTransliterator`
 * is `search.transliterator` - so it is declared here, and these tests guard
 * the declaration:
 *
 * - **Complete:** every field of [LauncherSettingsData], found by reflection,
 *   is in [Contract] in exactly one state; a new field in none fails, which
 *   fires on ordinary work rather than waiting for a regression.
 * - **Real keys:** a declared key is one this build applies.
 * - **Gaps only shrink:** a gap names the issue that tracks it, and no field
 *   may become a gap that was not one when the list was taken (#229).
 * - **The pairing, through the real bridge:** each field is mutated - the
 *   mutation derived from its type and its default, not from a second table -
 *   and read back through [LauncherConfigSettingsImpl] as the config sees it.
 *   A mapped field must change **exactly one** key, its own: a wrong key, two
 *   fields on one key, or a field that moves two keys all fail. An excluded
 *   or gap field must change **none**, or the table hides a mapping. So must
 *   a store key: the bridge does not pair it, and its store test does.
 *
 * What the round trip establishes is that the table and the bridge agree: a
 * declared pairing the bridge does not make fails, whatever the table says.
 * What it cannot establish is that the bridge is right - a field the bridge
 * itself feeds into the wrong key, declared here as that same key, passes.
 * That judgement belongs to the bridge's own tests (LauncherConfigSettingsTest),
 * not to this table.
 *
 * The gap list here, [GapsAt229], is the authority: #229 describes the ten and
 * points here. Closing a gap is an edit to this file; the issue follows it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsContractTest {

    private sealed interface State {
        /** The dotted contract key the field is read back as. */
        data class Key(val path: String) : State

        /**
         * The dotted contract key, paired by the store rather than the bridge
         * (#229): the stored form carries a user serial. The bridge must not
         * read it back; the store's own test pairs it.
         */
        data class StoreKey(val path: String) : State

        /** Deliberately not in the file; the reason is the design decision. */
        data class Excluded(val reason: String) : State

        /** A setting the file cannot express yet, tracked by an issue. */
        data class Gap(val issue: Int) : State
    }

    private companion object {
        const val GapsIssue = 229

        /**
         * The gaps when #229 was written: **the** list, which #229 describes and
         * points to. It may only lose members; a closed gap is removed here, and
         * the issue is updated to match.
         */
        val GapsAt229 = setOf(
            "gridColumnCount", "searchBarStyle", "searchBarColors", "iconsShape",
        )

        val Contract: Map<String, State> = mapOf(
            "schemaVersion" to State.Excluded("the settings store's own format version; the file carries its own schemaVersion"),
            "uiColorScheme" to State.Key("appearance.theme.mode"),
            "uiColorsId" to State.Key("appearance.theme.colors"),
            "uiShapesId" to State.Key("appearance.theme.shapes"),
            "glassBlur" to State.Key("appearance.glass.blur"),
            "glassTint" to State.Key("appearance.glass.tint"),
            "glassRadius" to State.Key("appearance.glass.radius"),
            "glassContrast" to State.Key("appearance.glass.contrast"),
            "glassWallpaperBlur" to State.Key("appearance.glass.wallpaperBlur"),
            "glassSearchWallpaperBlur" to State.Key("appearance.glass.searchWallpaperBlur"),
            "uiTypographyId" to State.Key("appearance.theme.typography"),
            "uiCompatModeColors" to State.Key("appearance.theme.colorSource"),
            "uiOrientation" to State.Key("home.lockRotation"),
            "wallpaperDim" to State.Key("appearance.dimWallpaper"),
            "homeScreenWidgets" to State.Key("home.widgets.enabled"),
            "homeGridColumns" to State.Key("home.grid.columns"),
            "homeGridLocked" to State.Key("home.grid.locked"),
            "homeGridLabels" to State.Key("home.grid.labels"),
            "homeGridInitialized" to State.Excluded(
                "a one-shot flag the grid sets when it has seeded its default rows; device state, not a choice",
            ),
            "favoritesEnabled" to State.Key("search.favorites"),
            "favoritesFrequentlyUsed" to State.Key("search.frequentlyUsed"),
            "favoritesFrequentlyUsedRows" to State.Key("search.frequentlyUsedRows"),
            "favoritesEditButton" to State.Key("search.favoritesEditButton"),
            "favoritesCompactTags" to State.Key("search.compactTags"),
            "searchAllApps" to State.Key("search.allApps"),
            "appsShowDetails" to State.Key("search.appDetails"),
            "contactSearchProviders" to State.Key("search.contacts"),
            "contactSearchCallOnTap" to State.Key("search.contactsCallOnTap"),
            "shortcutSearchEnabled" to State.Key("search.shortcuts"),
            "shortcutSearchBlocklist" to State.StoreKey("search.shortcutsExcluded"),
            "badgesNotifications" to State.Key("icons.badges.notifications"),
            "badgesSuspendedApps" to State.Key("icons.badges.suspendedApps"),
            "badgesShortcuts" to State.Key("icons.badges.shortcuts"),
            "gridColumnCount" to State.Gap(GapsIssue),
            "gridIconSize" to State.Key("icons.size"),
            "gridLabels" to State.Key("search.labels"),
            "gridList" to State.Key("search.layout"),
            "gridListIcons" to State.Key("search.listIcons"),
            "searchBarStyle" to State.Gap(GapsIssue),
            "searchBarColors" to State.Gap(GapsIssue),
            "searchBarKeyboard" to State.Key("search.openKeyboard"),
            "searchLaunchOnEnter" to State.Key("search.launchOnEnter"),
            "searchBarBottom" to State.Key("home.searchBar.position"),
            "searchBarBottomInSearch" to State.Key("search.barPosition"),
            "searchBarFixed" to State.Key("home.searchBar.fixed"),
            "searchResultsReversed" to State.Key("search.reversed"),
            "rankingWeightFactor" to State.Excluded(
                "a weight on launch history, which the file deliberately never carries: launch counts are " +
                    "device-local and stay out of the contract for privacy (#229)",
            ),
            "hiddenItemsShowButton" to State.Key("search.hiddenItemsButton"),
            "iconsShape" to State.Gap(GapsIssue),
            "iconsAdaptify" to State.Key("icons.adaptify"),
            "iconsThemed" to State.Key("icons.themed"),
            "iconsForceThemed" to State.Key("icons.enforceThemed"),
            "iconsPack" to State.Key("icons.pack"),
            "systemBarsHideStatus" to State.Key("appearance.systemBars.statusBar.hidden"),
            "systemBarsHideNav" to State.Key("appearance.systemBars.navigationBar.hidden"),
            "systemBarsStatusColors" to State.Key("appearance.systemBars.statusBar.icons"),
            "systemBarsNavColors" to State.Key("appearance.systemBars.navigationBar.icons"),
            "gesturesSwipeDown" to State.Key("gestures.swipeDown"),
            "gesturesSwipeLeft" to State.Key("gestures.swipeLeft"),
            "gesturesSwipeRight" to State.Key("gestures.swipeRight"),
            "gesturesSwipeUp" to State.Key("gestures.swipeUp"),
            "gesturesDoubleTap" to State.Key("gestures.doubleTap"),
            "gesturesLongPress" to State.Key("gestures.longPress"),
            "gesturesHomeButton" to State.Key("gestures.homeButton"),
            "stateTagsMultiline" to State.Excluded(
                "whether the favorites' tag row is expanded: the last state of a toggle, which the person flips while using it",
            ),
            "searchFilter" to State.Key("search.defaultFilter"),
            "searchFilterBar" to State.Key("search.filterBar"),
            "searchFilterBarItems" to State.Key("search.filterBarItems"),
            "localeTransliterator" to State.Key("search.transliterator"),
            "feedProviderPackage" to State.Excluded(
                "the feed sits behind FeatureFlags.feed, and a configuration file must never be a route around a feature flag (#3)",
            ),
        )

        /** The data class's own properties, in declaration order - what its constructor and copy take. */
        fun propertiesOf(type: Class<*>): List<Field> =
            type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

        /** Why [fields] and [contract] disagree; empty when every field is in exactly one state. */
        fun coverageProblems(fields: List<String>, contract: Map<String, State>): List<String> =
            fields.filter { it !in contract }.map { "$it is in no state" } +
                contract.keys.filter { it !in fields }.map { "$it is declared but is no field" }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---- the declaration ----

    @Test
    fun `every settings field is in exactly one state, and every entry is a field`() {
        assertEquals(emptyList<String>(), coverageProblems(propertiesOf(LauncherSettingsData::class.java).map { it.name }, Contract))
    }

    /** What makes the guard fire on ordinary work: a new setting in no state fails. */
    @Test
    fun `a setting added without a state fails`() {
        @Suppress("unused")
        class WithANewSetting(val iconsThemed: Boolean, val aBrandNewSetting: Boolean)

        val problems = coverageProblems(propertiesOf(WithANewSetting::class.java).map { it.name }, Contract.filterKeys { it == "iconsThemed" })

        assertEquals(listOf("aBrandNewSetting is in no state"), problems)
    }

    @Test
    fun `every declared key is one this build applies, and no two fields share one`() {
        val keys = Contract.values.filterIsInstance<State.Key>().map { it.path } +
            Contract.values.filterIsInstance<State.StoreKey>().map { it.path }
        assertEquals(emptyList<String>(), keys.filterNot { ConfigParser.isAppliedKey(it) })
        assertEquals(emptyList<String>(), keys.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }

    @Test
    fun `gaps only shrink, and every exclusion gives its reason`() {
        val gaps = Contract.filterValues { it is State.Gap }
        assertEquals(
            "a new setting arrives as a key or a reasoned exclusion, never as a new gap",
            emptySet<String>(), gaps.keys - GapsAt229,
        )
        assertTrue(gaps.values.all { (it as State.Gap).issue == GapsIssue })
        assertTrue(Contract.values.filterIsInstance<State.Excluded>().all { it.reason.isNotBlank() })
    }

    // ---- the pairing, through the real bridge ----

    /** The config as the device reads it back, flattened to dotted leaf paths. */
    private fun flatten(element: JsonElement, prefix: String = "", out: MutableMap<String, JsonElement> = mutableMapOf()): Map<String, JsonElement> {
        if (element is JsonObject) {
            for ((key, value) in element) flatten(value, if (prefix.isEmpty()) key else "$prefix.$key", out)
        } else {
            out[prefix] = element
        }
        return out
    }

    /**
     * Other values for a field, from its type and its current and default
     * values alone: a boolean flipped, the other enum constants, a number
     * stepped, the built-in ids and a random one, empty and default
     * collections. Null when the type has no generic mutation.
     */
    private fun alternatives(field: Field, current: Any?, default: Any?): List<Any?>? {
        val type = field.type
        val candidates: List<Any?> = when {
            type == java.lang.Boolean.TYPE -> listOf(!(current as Boolean))
            type == java.lang.Boolean::class.java -> listOf(true, false, null)
            type == Integer.TYPE -> listOf((current as Int) + 1, current - 1)
            type == java.lang.Float.TYPE -> listOf((current as Float) + 0.5f)
            type.isEnum -> type.enumConstants.toList()
            type == UUID::class.java -> (0L..4L).map { UUID(0L, it) } + UUID.randomUUID()
            type == String::class.java -> listOf(null, "", default, "org.example.other")
            Set::class.java.isAssignableFrom(type) -> listOf(emptySet<Any>(), default, setOf("org.example.other"))
            List::class.java.isAssignableFrom(type) -> listOf(emptyList<Any>(), default)
            type == GestureAction::class.java ->
                // Its objects are nested in it; permittedSubclasses is not emitted for this target.
                type.declaredClasses.filter { type.isAssignableFrom(it) }
                    .mapNotNull { sub -> runCatching { sub.getField("INSTANCE").get(null) }.getOrNull() }
            // A data class of switches (SearchFilters): each switch flipped on a clone, by name.
            type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.let { switches ->
                switches.isNotEmpty() && switches.all { it.type == java.lang.Boolean.TYPE }
            } -> {
                val copy = type.declaredMethods.single { it.name == "copy" && it.parameterCount == type.declaredFields.count { f -> !Modifier.isStatic(f.modifiers) } }
                val components = (1..copy.parameterCount).map { type.getMethod("component$it") }
                type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }.map { switch ->
                    val clone = copy.invoke(current, *components.map { it.invoke(current) }.toTypedArray())
                    switch.set(clone, !(switch.get(clone) as Boolean))
                    clone
                }
            }
            else -> return null
        }
        return candidates.filter { it != current }.distinct()
    }

    @Test
    fun `each field changes exactly its own key, and excluded and gap fields change none`() = runBlocking {
        val base = LauncherSettingsData()
        seedSettingsFile(context, base)
        val store = LauncherDataStore(context)
        val bridge = LauncherConfigSettingsImpl(store)
        val fields = propertiesOf(LauncherSettingsData::class.java).onEach { it.isAccessible = true }
        // Internal, like the constructor, so its JVM name is mangled (copy$<module>).
        val copy = LauncherSettingsData::class.java.declaredMethods.single {
            (it.name == "copy" || it.name.startsWith("copy$")) && !Modifier.isStatic(it.modifiers) && it.parameterCount == fields.size
        }.apply { isAccessible = true }
        // A mutated instance without assuming any order (review on #230):
        // declaredFields has no ordering contract, so it is never used as the
        // copy's argument order. componentK is the K-th constructor property,
        // which is copy's K-th parameter - Kotlin guarantees that - so this
        // clones the base, and the one field is then set on the clone by name.
        val components = (1..fields.size).map { LauncherSettingsData::class.java.getMethod("component$it") }
        fun mutated(field: Field, value: Any?): LauncherSettingsData {
            val clone = copy.invoke(base, *components.map { it.invoke(base) }.toTypedArray()) as LauncherSettingsData
            field.set(clone, value)
            check(field.get(clone) == value) { "${field.name} was not set on the clone" }
            return clone
        }

        suspend fun readBack(data: LauncherSettingsData): Map<String, JsonElement> {
            store.updateAndAwait { data }
            val config = bridge.readState().toLauncherConfig()
            return flatten(ConfigParser.json.encodeToJsonElement(LauncherConfig.serializer(), config))
        }
        val before = readBack(base)

        val problems = mutableListOf<String>()
        val unmutated = mutableListOf<String>()
        for (field in fields) {
            val state = Contract.getValue(field.name)
            val current = field.get(base)
            val others = alternatives(field, current, current)
            if (others.isNullOrEmpty()) {
                unmutated += field.name
                continue
            }
            var movedOwn = false
            for (other in others) {
                val after = readBack(mutated(field, other))
                val changed = (before.keys + after.keys).filter { before[it] != after[it] }
                if (changed.isEmpty()) continue
                when (state) {
                    is State.Key -> {
                        // Exactly the declared key: every declared key is a leaf, and the
                        // mutations make no gesture an object, so no descendant is its own.
                        val foreign = changed.filterNot { it == state.path }
                        if (foreign.isNotEmpty()) problems += "${field.name} = $other also changed $foreign"
                        else movedOwn = true
                    }
                    else -> problems += "${field.name} is ${state::class.simpleName} but changed $changed"
                }
            }
            if (state is State.Key && !movedOwn) problems += "${field.name} changed nothing, not even ${state.path}"
        }
        store.updateAndAwait { base }

        assertEquals(emptyList<String>(), problems)
        // A field with no generic mutation is not checked by the pairing test:
        // named here, so a new one is noticed rather than skipped in silence.
        assertEquals(emptyList<String>(), unmutated)
    }
}
