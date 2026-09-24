package de.mm20.launcher2.ui.launcher.grid

import android.appwidget.AppWidgetManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.grid.Span
import de.mm20.launcher2.homegrid.AndroidGridItemLimits
import de.mm20.launcher2.homegrid.GridGeometry
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.base.LocalAppWidgetHost
import de.mm20.launcher2.ui.launcher.sheets.EditFavoritesSheet
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.ui.locals.LocalSnackbarHostState
import de.mm20.launcher2.services.widgets.PickedWidget
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Semantics of the grid root: true while the cells wiggle in edit mode (tests read it). */
val GridWiggling = SemanticsPropertyKey<Boolean>("GridWiggling")

/** True inside the grid while edit mode is on; the favorites cell shows its overflow count then. */
val LocalGridEditing = compositionLocalOf { false }

/**
 * The single-page widget grid (ADR 0001). One measurement at the top decides
 * the geometry; everything below is placed at fixed cell rectangles.
 *
 * Edit mode (plan section D) lives here too: a long press on the grid
 * enters it, cells get a gesture overlay for drag, select, resize and
 * remove, an edit bar offers Add and Done, and Done writes the layout back
 * through the view model. [onEditFavorites] opens the favorites editor
 * when the favorites cell is tapped in edit mode; the default is the
 * existing sheet, tests pass a probe. [reducedMotion] turns the wiggle off.
 */
@Composable
fun HomeGrid(
    modifier: Modifier = Modifier,
    viewModel: HomeGridVM = viewModel(factory = HomeGridVM.factory()),
    reducedMotion: Boolean = rememberReducedMotion(),
    onEditFavorites: (() -> Unit)? = null,
    favoritesContent: @Composable (columns: Int, rows: Int) -> Unit = { columns, rows ->
        FavoritesGridWidget(columns = columns, rows = rows, modifier = Modifier.fillMaxSize())
    },
) {
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    val visual = remember { GridEditVisual() }
    val wiggling = editing && !reducedMotion

    var favoritesSheet by rememberSaveable { mutableStateOf(false) }
    var pickerSheet by rememberSaveable { mutableStateOf(false) }
    val editFavorites = onEditFavorites ?: { favoritesSheet = true }

    // Events become snackbars; the strings are the user's, the codes the log's.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is GridEditEvent.WriteBackSkipped -> context.getString(R.string.grid_write_back_skipped, event.reason)
                GridEditEvent.NoRoom -> context.getString(R.string.grid_no_room)
                is GridEditEvent.WriteBackFailed -> context.getString(R.string.grid_write_back_failed, event.reason)
            }
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    if (wiggling) WiggleDriver(visual) else SideEffect { visual.wiggle = null }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("home-grid")
            .semantics { this[GridWiggling] = wiggling }
            // Sits behind the cells: a long press on free cells enters edit
            // mode, and because the down is consumed here the scaffold's
            // configured long-press gesture on the parent never fires. A tap
            // on free cells clears the selection while editing.
            .pointerInput(editing) {
                detectTapGestures(
                    onLongPress = {
                        if (!editing) {
                            scope.launch {
                                if (viewModel.enterEdit()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    onTap = { if (editing) viewModel.select(null) },
                )
            },
    ) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        LaunchedEffect(widthDp, heightDp) {
            viewModel.onWindowMeasured(widthDp, heightDp)
        }

        val state by viewModel.state.collectAsStateWithLifecycle()
        val uiState = state ?: return@BoxWithConstraints

        val host = LocalAppWidgetHost.current
        val profileManager: ProfileManager = koinInject()
        // Re-run whenever an item appears, disappears or changes its host id;
        // a pass that finds nothing to do costs one query.
        val bindingKey = uiState.cells.map { it.item.id to it.item.appWidgetId }
        LaunchedEffect(bindingKey, editing) {
            if (editing) return@LaunchedEffect
            val port = AndroidAppWidgetHostPort(context, host) { type ->
                profileManager.getProfile(type)?.userHandle
            }
            viewModel.reconcile(port)
        }

        val cellsById = remember(uiState.cells) { uiState.cells.associateBy { it.item.id } }
        CompositionLocalProvider(LocalGridEditing provides editing) {
            HomeGridLayout(
                geometry = uiState.geometry,
                cells = uiState.cells.map { it.item.id to it.span },
                modifier = Modifier.fillMaxSize(),
                visual = visual,
                overlay = if (editing) {
                    { id ->
                        val cell = cellsById[id] ?: return@HomeGridLayout
                        GridCellEditOverlay(
                            cell = cell,
                            geometry = uiState.geometry,
                            visual = visual,
                            viewModel = viewModel,
                            selected = selectedId == id,
                            onEditFavorites = editFavorites,
                            onRemoved = { removed ->
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = context.getString(R.string.widget_removed),
                                        actionLabel = context.getString(R.string.action_undo),
                                        duration = SnackbarDuration.Short,
                                    )
                                    // Undo puts the item back; otherwise nothing more happens
                                    // here. The host id stays allocated until Done has written
                                    // the layout: the reconciler then releases every id no item
                                    // references. Releasing it now would strand the stored item
                                    // if the process died before Done (review on #70).
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restore(removed)
                                    }
                                }
                            },
                        )
                    }
                } else {
                    null
                },
            ) { id ->
                val cell = cellsById[id] ?: return@HomeGridLayout
                GridCell(cell = cell, viewModel = viewModel, favoritesContent = favoritesContent, showLabel = labels)
            }
        }

        if (editing) {
            GridEditBar(
                modifier = Modifier.align(Alignment.TopEnd),
                onAdd = { pickerSheet = true },
                onDone = { scope.launch { viewModel.exitEdit() } },
            )
        }

        if (favoritesSheet) {
            EditFavoritesSheet(expanded = true, onDismiss = { favoritesSheet = false })
        }
        if (pickerSheet) {
            val density = LocalDensity.current
            WidgetPickerSheet(
                expanded = true,
                includeBuiltinWidgets = false,
                onDismiss = { pickerSheet = false },
                onWidgetSelected = { picked ->
                    pickerSheet = false
                    if (picked is PickedWidget.App) {
                        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(picked.appWidgetId)
                        val provider = info?.provider?.flattenToString()
                        if (provider != null) {
                            val geometry = uiState.geometry
                            val added = viewModel.addWidget(
                                widget = provider,
                                profile = null,
                                appWidgetId = picked.appWidgetId,
                                default = AndroidGridItemLimits.defaultSpanFor(info, geometry, density.density),
                                limits = AndroidGridItemLimits.limitsFor(info, geometry, density.density),
                            )
                            if (!added) host.deleteAppWidgetId(picked.appWidgetId)
                        }
                    }
                },
            )
        }
    }
}

/**
 * One infinite transition for the whole grid; the cells' layers read its
 * value through [GridEditVisual.wiggle], the composition never does.
 */
@Composable
private fun WiggleDriver(visual: GridEditVisual) {
    val transition = rememberInfiniteTransition(label = "gridWiggle")
    val angle = transition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 140, easing = LinearEasing), RepeatMode.Reverse),
        label = "gridWiggleAngle",
    )
    DisposableEffect(angle) {
        visual.wiggle = angle
        onDispose { visual.wiggle = null }
    }
}

/**
 * Places every cell at its rectangle in one layout pass (performance rules of
 * the plan): no lazy grid, no per-cell measurement of constraints, and the
 * geometry in pixels is remembered on (geometry, cells) so a recomposition
 * for any other reason does not recompute it.
 *
 * Each child carries `contentDescription = "grid-item:<id>"`, which is how
 * the L4 scenario finds cells through `uiautomator dump`.
 *
 * Edit mode's visuals go through the cells' graphics layers, never through
 * relayout: the dragged cell is translated to [GridEditVisual]'s ghost
 * position, a cell whose rectangle changed (push-down) slides from its old
 * rectangle to the new one, and the wiggle rotates each layer. [overlay]
 * is drawn on top of a cell's content, for edit mode's gestures.
 *
 * [onRecomposed] exists for the recomposition-count test only.
 */
@Composable
fun HomeGridLayout(
    geometry: GridGeometry,
    cells: List<Pair<String, Span>>,
    modifier: Modifier = Modifier,
    visual: GridEditVisual? = null,
    onRecomposed: (() -> Unit)? = null,
    overlay: (@Composable (id: String) -> Unit)? = null,
    content: @Composable (id: String) -> Unit,
) {
    if (onRecomposed != null) {
        SideEffect { onRecomposed() }
    }
    val density = LocalDensity.current
    val cellPx = with(density) { geometry.cellDp.dp.toPx() }
    val gapPx = with(density) { geometry.gapDp.dp.toPx() }

    // Push-down slides: one animatable per cell, snapped to the old
    // rectangle's offset and animated to zero when the rectangle changes.
    // The map is snapshot state so a layer that read a missing entry is
    // re-run once the entry exists.
    val slides = remember { mutableStateMapOf<String, Animatable<Offset, *>>() }
    val previousRects = remember { mutableMapOf<String, IntOffset>() }
    LaunchedEffect(cells, cellPx, gapPx) {
        for ((id, span) in cells) {
            val rect = cellTopLeft(span, cellPx, gapPx, geometry.firstVisibleColumn)
            val previous = previousRects[id]
            previousRects[id] = rect
            if (previous == null || previous == rect || visual?.draggedId == id) continue
            val slide = slides.getOrPut(id) { Animatable(Offset.Zero, Offset.VectorConverter) }
            launch {
                slide.snapTo(Offset((previous.x - rect.x).toFloat(), (previous.y - rect.y).toFloat()))
                slide.animateTo(Offset.Zero, tween(SlideMillis))
            }
        }
        previousRects.keys.retainAll(cells.map { it.first }.toSet())
    }

    val measurePolicy = remember(geometry, cells, density, visual) {
        gridMeasurePolicy(geometry, cells, cellPx = cellPx, gapPx = gapPx, visual = visual, slides = slides)
    }
    Layout(
        content = {
            for ((id, _) in cells) {
                key(id) {
                    Box(
                        modifier = Modifier
                            .layoutId(id)
                            .semantics { contentDescription = "grid-item:$id" },
                    ) {
                        content(id)
                        overlay?.invoke(id)
                    }
                }
            }
        },
        modifier = modifier,
        measurePolicy = measurePolicy,
    )
}

private const val SlideMillis = 180

/**
 * Top-left of a span's rectangle in px, rounded from the accumulated float
 * position. Spans are in layout coordinates; [firstColumn] is the first one
 * the window draws (the cover's, #93).
 */
internal fun cellTopLeft(span: Span, cellPx: Float, gapPx: Float, firstColumn: Int = 0): IntOffset {
    val pitch = cellPx + gapPx
    return IntOffset(((span.x - firstColumn) * pitch).roundToInt(), (span.y * pitch).roundToInt())
}

/**
 * Measures each child exactly to its cell rectangle and places it there
 * with a layer. Rectangle edges are rounded from the accumulated float
 * position, so no gap or cell drifts by a pixel across the row. The layer
 * block reads [visual] and [slides]; those reads are observed by the layer
 * alone, which is what keeps a drag or wiggle frame out of layout.
 */
internal fun gridMeasurePolicy(
    geometry: GridGeometry,
    cells: List<Pair<String, Span>>,
    cellPx: Float,
    gapPx: Float,
    visual: GridEditVisual? = null,
    slides: Map<String, Animatable<Offset, *>> = emptyMap(),
): MeasurePolicy {
    val pitch = cellPx + gapPx
    val spansById = cells.toMap()
    // Spans are layout columns; the cover draws from its first one (#93).
    val first = geometry.firstVisibleColumn
    return MeasurePolicy { measurables, constraints ->
        val placed = measurables.map { measurable ->
            val id = measurable.layoutId as String
            val span = spansById.getValue(id)
            val left = ((span.x - first) * pitch).roundToInt()
            val top = (span.y * pitch).roundToInt()
            val right = ((span.right - first) * pitch - gapPx).roundToInt()
            val bottom = (span.bottom * pitch - gapPx).roundToInt()
            val placeable = measurable.measure(
                Constraints.fixed(
                    width = (right - left).coerceAtLeast(0),
                    height = (bottom - top).coerceAtLeast(0),
                ),
            )
            PlacedCell(id, placeable, left, top)
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placed.maxOfOrNull { it.left + it.placeable.width } ?: 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placed.maxOfOrNull { it.top + it.placeable.height } ?: 0
        layout(width, height) {
            for (cell in placed) {
                val dragged = visual?.draggedId == cell.id
                cell.placeable.placeRelativeWithLayer(
                    x = cell.left,
                    y = cell.top,
                    zIndex = if (dragged) 1f else 0f,
                ) {
                    if (visual != null && visual.draggedId == cell.id) {
                        translationX = visual.ghostLeftPx - cell.left
                        translationY = visual.ghostTopPx - cell.top
                    } else {
                        val slide = slides[cell.id]?.value ?: Offset.Zero
                        translationX = slide.x
                        translationY = slide.y
                    }
                    rotationZ = visual?.wiggleFor(cell.id) ?: 0f
                }
            }
        }
    }
}

private class PlacedCell(val id: String, val placeable: Placeable, val left: Int, val top: Int)

/** Whether the platform asks for no animation (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
