import pathlib, re

def rw(p, f):
    s = pathlib.Path(p).read_text()
    n = f(s)
    pathlib.Path(p).write_text(n)

def drop_block(s, start_marker, end_marker):
    a = s.index(start_marker)
    b = s.index(end_marker, a)
    return s[:a] + s[b:]

# --- WidgetPickerSheet -----------------------------------------------------
p = 'app/ui/src/main/java/de/mm20/launcher2/ui/launcher/sheets/WidgetPickerSheet.kt'
s = pathlib.Path(p).read_text()
for imp in ['import de.mm20.launcher2.widgets.AppWidget\n', 'import de.mm20.launcher2.widgets.AppWidgetConfig\n',
            'import de.mm20.launcher2.widgets.AppsWidget\n', 'import de.mm20.launcher2.widgets.Widget\n', 'import java.util.UUID\n']:
    s = s.replace(imp, '')
s = s.replace('import de.mm20.launcher2.services.widgets.AppWidgetHostIds\n',
              'import de.mm20.launcher2.services.widgets.AppWidgetHostIds\nimport de.mm20.launcher2.services.widgets.BuiltInWidgets\nimport de.mm20.launcher2.services.widgets.PickedWidget\n')
old = '''private class BindAndConfigureAppWidgetContract(
    private val density: Density,
) : ActivityResultContract<AppWidgetProviderInfo, Widget?>() {'''
new = '''private class BindAndConfigureAppWidgetContract : ActivityResultContract<AppWidgetProviderInfo, PickedWidget?>() {'''
assert old in s; s = s.replace(old, new)
old = '''    override fun parseResult(resultCode: Int, intent: Intent?): Widget? {'''
new = '''    override fun parseResult(resultCode: Int, intent: Intent?): PickedWidget? {'''
assert old in s; s = s.replace(old, new)
old = '''            if (widgetId != null && widgetProviderInfo != null) {
                return AppWidget(
                    id = UUID.randomUUID(),
                    config = AppWidgetConfig(
                        height = with(density) { widgetProviderInfo.minHeight.toDp() }.value.toInt(),
                        width = with(density) { widgetProviderInfo.minWidth.toDp() }.value.toInt(),
                        widgetId = widgetId,
                    ),
                )
            } else {'''
new = '''            if (widgetId != null && widgetProviderInfo != null) {
                return PickedWidget.App(appWidgetId = widgetId, provider = widgetProviderInfo)
            } else {'''
assert old in s; s = s.replace(old, new)
s = s.replace('    onWidgetSelected: (Widget) -> Unit,', '    onWidgetSelected: (PickedWidget) -> Unit,')
s = s.replace('rememberLauncherForActivityResult(BindAndConfigureAppWidgetContract(density))', 'rememberLauncherForActivityResult(BindAndConfigureAppWidgetContract())')
old = '''                                val id = UUID.randomUUID()
                                val widget = when (it.type) {
                                    AppsWidget.Type -> AppsWidget(id)
                                    else -> return@clickable
                                }
                                onWidgetSelected(widget)'''
new = '''                                val widget = when (it.type) {
                                    BuiltInWidgets.Favorites -> PickedWidget.Favorites
                                    else -> return@clickable
                                }
                                onWidgetSelected(widget)'''
assert old in s; s = s.replace(old, new)
s = s.replace('                                        AppsWidget.Type -> R.drawable.apps_24px', '                                        BuiltInWidgets.Favorites -> R.drawable.apps_24px')
pathlib.Path(p).write_text(s)

p = 'app/ui/src/main/java/de/mm20/launcher2/ui/launcher/sheets/WidgetPickerSheetVM.kt'
s = pathlib.Path(p).read_text()
s = s.replace('''    private val enabledWidgets = widgetsService.getWidgets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(100), emptyList())

''', '')
pathlib.Path(p).write_text(s)

# --- grid: GridCell, HomeGrid ------------------------------------------------
for p in ['app/ui/src/main/java/de/mm20/launcher2/ui/launcher/grid/GridCell.kt', 'app/ui/src/main/java/de/mm20/launcher2/ui/launcher/grid/HomeGrid.kt']:
    s = pathlib.Path(p).read_text()
    s = s.replace('import de.mm20.launcher2.widgets.AppWidget\n', 'import de.mm20.launcher2.services.widgets.PickedWidget\n')
    s = s.replace('if (picked is AppWidget) {', 'if (picked is PickedWidget.App) {')
    s = s.replace('getAppWidgetInfo(picked.config.widgetId)', 'getAppWidgetInfo(picked.appWidgetId)')
    s = s.replace('picked.config.widgetId', 'picked.appWidgetId')
    pathlib.Path(p).write_text(s)
p = 'app/ui/src/main/java/de/mm20/launcher2/ui/launcher/grid/HomeGrid.kt'
s = pathlib.Path(p).read_text()
s = s.replace('                is GridEditEvent.SeedLeftovers -> context.getString(R.string.grid_seed_leftovers, event.count)\n', '')
pathlib.Path(p).write_text(s)

# --- HomeGridVM ---------------------------------------------------------------
p = 'app/ui/src/main/java/de/mm20/launcher2/ui/launcher/grid/HomeGridVM.kt'
s = pathlib.Path(p).read_text()
s = s.replace('import de.mm20.launcher2.homegrid.HomeGridSeeder\nimport de.mm20.launcher2.homegrid.HomeGridSeeding\n', 'import de.mm20.launcher2.homegrid.HomeGridDefaults\nimport de.mm20.launcher2.homegrid.HomeGridInitFlag\n')
s = s.replace('import de.mm20.launcher2.widgets.WidgetRepository\n', '')
s = s.replace('''    /** Widgets of the old column that found no room when the grid was seeded. */
    data class SeedLeftovers(val count: Int) : GridEditEvent()

''', '')
s = s.replace(''' * Derives the grid's geometry from the measured window (D1), arranges what
 * the repository holds for it, and runs the one-time seeding of the old
 * widget column. Constructor-injected so tests build it with fakes; the
 * composable obtains it through [factory].''', ''' * Derives the grid's geometry from the measured window (D1), arranges what
 * the repository holds for it, and gives a never-configured launcher its one
 * default, the favorites row (PR 5b). Constructor-injected so tests build it
 * with fakes; the composable obtains it through [factory].''')
s = s.replace('''    private val seeder: HomeGridSeeding,
    private val widgetRepository: WidgetRepository,
''', '''    private val initFlag: HomeGridInitFlag,
''')
s = s.replace('''    private var seedLeftovers = 0
    private var leftoversAnnounced = false

''', '')
s = s.replace('''        if (seedLeftovers > 0 && !leftoversAnnounced) {
            leftoversAnnounced = true
            _events.tryEmit(GridEditEvent.SeedLeftovers(seedLeftovers))
        }
''', '')
old = '''    init {
        viewModelScope.launch {
            val result = seeder.seedIfNeeded(geometry.filterNotNull().first())
            seedLeftovers = result.leftovers.size
        }
    }'''
new = '''    init {
        viewModelScope.launch {
            val first = geometry.filterNotNull().first()
            HomeGridDefaults.ensureFavoritesRow(
                repository, initFlag, first.layout, columns = first.spec.columns, rows = first.spec.rows,
            )
        }
    }'''
assert old in s; s = s.replace(old, new)
s = s.replace('return HomeGridReconciler(repository, widgetRepository, port).reconcile()', 'return HomeGridReconciler(repository, port).reconcile()')
s = s.replace('''                    seeder = koin.get<HomeGridSeeder>(),
                    widgetRepository = koin.get(),
''', '''                    initFlag = koin.get(),
''')
pathlib.Path(p).write_text(s)

# --- ProvideSettings, FavoritesVM ------------------------------------------
p = 'app/ui/src/main/java/de/mm20/launcher2/ui/base/ProvideSettings.kt'
s = pathlib.Path(p).read_text()
s = s.replace('import de.mm20.launcher2.widgets.AppsWidget\nimport de.mm20.launcher2.widgets.WidgetRepository\n', '')
s = s.replace('    val widgetRepository: WidgetRepository = koinInject()\n', '')
old = '''    val favoritesEnabled by remember {
        combine(
            widgetRepository.exists(AppsWidget.Type),
            settings.favoritesEnabled,
        ) { a, b -> a || b }.distinctUntilChanged()
    }.collectAsState(true)'''
new = '''    val favoritesEnabled by remember {
        settings.favoritesEnabled.distinctUntilChanged()
    }.collectAsState(true)'''
assert old in s; s = s.replace(old, new)
if 'combine(' not in s:
    s = s.replace('import kotlinx.coroutines.flow.combine\n', '')
pathlib.Path(p).write_text(s)

p = 'app/ui/src/main/java/de/mm20/launcher2/ui/common/FavoritesVM.kt'
s = pathlib.Path(p).read_text()
s = s.replace('import de.mm20.launcher2.widgets.WidgetRepository\n', '')
s = s.replace('    internal val widgetRepository: WidgetRepository by inject()\n', '')
pathlib.Path(p).write_text(s)

# --- gestures ------------------------------------------------------------------
p = 'app/ui/src/main/java/de/mm20/launcher2/ui/settings/gestures/GestureSettingsScreenVM.kt'
s = pathlib.Path(p).read_text()
for imp in ['import de.mm20.launcher2.preferences.WidgetScreenTarget\n', 'import de.mm20.launcher2.widgets.Widget\n', 'import de.mm20.launcher2.widgets.WidgetRepository\n']:
    s = s.replace(imp, '')
s = s.replace('    private val widgetRepository: WidgetRepository by inject()\n', '')
s = drop_block(s, '    val widgetOptions: Flow<List<WidgetPageOption>> =', '    fun requestPermission(')
s = re.sub(r'\ninternal data class WidgetPageOption\(\n    val id: WidgetScreenTarget,\n    val widgets: List<Widget>,\n\)\s*$', '\n', s)
pathlib.Path(p).write_text(s)

p = 'app/ui/src/main/java/de/mm20/launcher2/ui/settings/gestures/GestureSettingsScreen.kt'
s = pathlib.Path(p).read_text()
s = s.replace('import de.mm20.launcher2.preferences.WidgetScreenTarget\n', '')
s = s.replace('        add(GestureAction.Widgets::class)\n', '')
s = s.replace('    val widgetOptions by viewModel.widgetOptions.collectAsStateWithLifecycle(emptyList())\n', '')
s = s.replace('                        widgetOptions = widgetOptions,\n', '')
pathlib.Path(p).write_text(s)

p = 'app/ui/src/main/java/de/mm20/launcher2/ui/settings/gestures/GesturePreference.kt'
s = pathlib.Path(p).read_text()
s = s.replace('import de.mm20.launcher2.preferences.WidgetScreenTarget\n', '')
s = s.replace('    widgetOptions: List<WidgetPageOption>,\n', '')
old = '''    val value =
        if (value is GestureAction.Widgets && widgetOptions.none { it.id == value.target }) {
            GestureAction.NoAction
        } else {
            value
        }
'''
assert old in s; s = s.replace(old, '')
s = drop_block(s, '                            if (options.contains(GestureAction.Widgets::class)) {', '                            if (options.contains(GestureAction.Launch::class)) {')
s = drop_block(s, '        is GestureAction.Widgets -> {', '        else -> resources.getString(R.string.gesture_action_none)')
pathlib.Path(p).write_text(s)

# --- SharedLauncherActivity ---------------------------------------------------
p = 'app/ui/src/main/java/de/mm20/launcher2/ui/launcher/SharedLauncherActivity.kt'
s = pathlib.Path(p).read_text()
s = s.replace('import de.mm20.launcher2.preferences.WidgetScreenTarget\n', '')
s = s.replace('import de.mm20.launcher2.ui.launcher.scaffold.components.WidgetsComponent\n', '')
s = drop_block(s, '                                            is GestureAction.Widgets ->', '                                            is GestureAction.Notifications ->')
pathlib.Path(p).write_text(s)

# strings
p = 'core/i18n/src/main/res/values/strings.xml'
s = pathlib.Path(p).read_text()
s = re.sub(r'    <string name="grid_seed_leftovers">[^\n]*\n', '', s)
s = re.sub(r'    <string name="preference_clockwidget_favorites_part(_summary)?">[^\n]*\n', '', s)
s = re.sub(r'    <string name="preference_clockwidget_dock_rows">[^\n]*\n', '', s)
s = re.sub(r'    <string name="gesture_action_widgets(_empty|_indexed)?">[^\n]*\n', '', s)
pathlib.Path(p).write_text(s)
print("batch F done")
