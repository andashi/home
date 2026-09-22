package de.mm20.launcher2.ui.launcher.grid

import androidx.activity.ComponentActivity

/**
 * The host of the grid's device tests. Declared in the androidTest manifest
 * with every size-related `configChanges`, so folding, unfolding and
 * rotating the device re-measure the composition instead of recreating the
 * activity under the test's feet.
 */
class GridTestActivity : ComponentActivity()
