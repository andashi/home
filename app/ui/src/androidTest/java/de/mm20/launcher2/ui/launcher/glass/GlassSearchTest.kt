package de.mm20.launcher2.ui.launcher.glass

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.glass.BackdropGeometry
import de.mm20.launcher2.glass.BackdropImage
import de.mm20.launcher2.glass.Contrast
import de.mm20.launcher2.glass.GlassBackdropSource
import de.mm20.launcher2.glass.GlassInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Search on the glass look (#91, L2): the background behind it and the menus over it. */
@RunWith(AndroidJUnit4::class)
class GlassSearchTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val source = object : GlassBackdropSource {
        override val image = MutableStateFlow<BackdropImage?>(BackdropImage("/w/zone.jpg", "sha1"))
        override suspend fun refresh() = Unit
    }

    /** The backdrop: blue on the left half, green on the right. */
    private fun controller(home: Boolean, search: Boolean) = GlassBackdropController(
        source,
        MutableStateFlow(GlassInputs(24f, 0.12f, 28f, Contrast.Medium)),
        CoroutineScope(Dispatchers.Main.immediate),
        wallpaperBlur = MutableStateFlow(home),
        searchWallpaperBlur = MutableStateFlow(search),
    ) { _, key ->
        val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until w) for (y in 0 until h) {
                setPixel(x, y, if (x < w / 2) android.graphics.Color.BLUE else android.graphics.Color.GREEN)
            }
        }.asImageBitmap()
    }

    private val progress = mutableFloatStateOf(0f)

    /** Blue at the left centre of the window: the backdrop is drawn; red: the "sharp wallpaper" shows. */
    private fun background(home: Boolean, search: Boolean, at: Float): Color {
        progress.floatValue = at
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage().toPixelMap()
        return image[image.width / 4, image.height / 2]
    }

    private fun showBackground(home: Boolean, search: Boolean) {
        val controller = controller(home, search)
        composeRule.setContent {
            MaterialTheme {
                // Red stands in for the sharp system wallpaper under the launcher.
                Box(Modifier.fillMaxSize().background(Color.Red)) {
                    ProvideGlassBackdrop(controller) {
                        GlassWallpaper(searchProgress = { progress.floatValue })
                    }
                }
            }
        }
    }

    @Test
    fun aSharpHomeFadesTheBlurInAsSearchOpens() {
        showBackground(home = false, search = true)
        val closed = background(home = false, search = true, at = 0f)
        val open = background(home = false, search = true, at = 1f)
        assertTrue("home: the wallpaper, $closed", closed.red > 0.9f && closed.blue < 0.1f)
        assertTrue("search: the backdrop, $open", open.blue > 0.9f && open.red < 0.1f)
    }

    /** Control: the reverse combination fades the other way. */
    @Test
    fun aBlurredHomeWithASharpSearchFadesTheBlurOut() {
        showBackground(home = true, search = false)
        val closed = background(home = true, search = false, at = 0f)
        val open = background(home = true, search = false, at = 1f)
        assertTrue("home: the backdrop, $closed", closed.blue > 0.9f)
        assertTrue("search: the wallpaper, $open", open.red > 0.9f)
    }

    /**
     * A menu is a popup window of its own. Its glass must show the part of
     * the backdrop that lies under it on screen. The backdrop here encodes
     * y in its color (red at the bottom, blue at the top) and the tint is 0,
     * so the color at a surface's centre says which row of the backdrop it
     * drew; that row must be where the surface really is on screen, in the
     * window the backdrop was made for.
     */
    /**
     * #113: moving a popup's window changes where it is on screen but not
     * where it is inside its window, so a region computed only on layout
     * stayed at the old place - the same offset every time the first layout
     * ran before the window had its final position. Moved on purpose here.
     */
    @Test
    fun aGlassSurfaceInAMovedPopupFollowsTheWindow() {
        val controller = GlassBackdropController(
            source,
            MutableStateFlow(GlassInputs(24f, 0f, 28f, Contrast.Medium)),
            CoroutineScope(Dispatchers.Main.immediate),
        ) { _, key ->
            val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until h) {
                    val red = (255f * y / (h - 1)).toInt()
                    for (x in 0 until w) setPixel(x, y, android.graphics.Color.rgb(red, 0, 255 - red))
                }
            }.asImageBitmap()
        }
        var windowHeight = 0
        var hostTop = 0f
        var popupY by mutableIntStateOf(0)
        composeRule.setContent {
            val view = LocalView.current
            windowHeight = LocalWindowInfo.current.containerSize.height
            val density = LocalDensity.current
            if (popupY == 0) popupY = with(density) { 200.dp.roundToPx() }
            MaterialTheme {
                ProvideGlassBackdrop(controller) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                val origin = IntArray(2)
                                view.rootView.getLocationOnScreen(origin)
                                hostTop = origin[1].toFloat()
                            }
                    ) {
                        val offset = IntOffset(with(density) { 120.dp.roundToPx() }, popupY)
                        Popup(offset = offset) {
                            GlassMenuGroup(Modifier.size(120.dp).testTag("moved")) {}
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithTag("moved", useUnmergedTree = true).fetchSemanticsNode().positionOnScreen.y
        // Move the window: the content inside it stays where it is.
        val move = (windowHeight * 0.4f).toInt()
        composeRule.runOnIdle { popupY += move }
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.waitForIdle()

        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val semantics = composeRule.onNodeWithTag("moved", useUnmergedTree = true).fetchSemanticsNode()
        // Without this a popup that never moved passes too: both sides of the
        // check below follow the node's own position.
        assertEquals("the popup moved on screen", move.toFloat(), semantics.positionOnScreen.y - before, 2f)
        val centreX = semantics.positionOnScreen.x + semantics.size.width / 2f
        val centreY = semantics.positionOnScreen.y + semantics.size.height / 2f
        val expected = (centreY - hostTop) / windowHeight
        val pixel = screen.getPixel(centreX.toInt(), centreY.toInt())
        val red = android.graphics.Color.red(pixel).toFloat()
        val blue = android.graphics.Color.blue(pixel).toFloat()
        assertEquals("moved popup: backdrop row drawn vs where it is on screen", expected, red / (red + blue), 0.03f)
    }

    /**
     * #113: the launcher window moves after a popup is placed. The popup's
     * glass maps its position on screen into the launcher window through the
     * launcher window's origin, so when that origin changes the drawn row
     * must follow it - also when nothing inside the popup's own window moved.
     */
    @Test
    fun aGlassSurfaceInAPopupFollowsAMovedHostWindow() {
        val controller = GlassBackdropController(
            source,
            MutableStateFlow(GlassInputs(24f, 0f, 28f, Contrast.Medium)),
            CoroutineScope(Dispatchers.Main.immediate),
        ) { _, key ->
            val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until h) {
                    val red = (255f * y / (h - 1)).toInt()
                    for (x in 0 until w) setPixel(x, y, android.graphics.Color.rgb(red, 0, 255 - red))
                }
            }.asImageBitmap()
        }
        var windowHeight = 0
        lateinit var host: android.view.View
        lateinit var compose: android.view.View
        composeRule.setContent {
            compose = LocalView.current
            host = compose.rootView
            windowHeight = LocalWindowInfo.current.containerSize.height
            MaterialTheme {
                ProvideGlassBackdrop(controller) {
                    Box(Modifier.fillMaxSize()) {
                        val offset = with(LocalDensity.current) { IntOffset(200.dp.roundToPx(), 420.dp.roundToPx()) }
                        Popup(offset = offset) {
                            GlassMenuGroup(Modifier.size(120.dp).testTag("popup")) {}
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        fun hostTop() = IntArray(2).also(host::getLocationOnScreen)[1]
        val topBefore = hostTop()
        fun popupNode() = composeRule.onNodeWithTag("popup", useUnmergedTree = true).fetchSemanticsNode()
        val before = popupNode()
        val popupBefore = before.positionOnScreen
        val regionBefore = before.config.getOrNull(GlassBackdropRegion)

        // Move the launcher window down, same size: its content, the popup's
        // anchor included, stays where it is inside it.
        val shift = (windowHeight * 0.15f).toInt()
        composeRule.runOnIdle {
            val window = generateSequence(compose.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().first().window
            val initialY = window.attributes.y
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            window.attributes = window.attributes.apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                width = host.width
                height = host.height
                y = initialY + shift
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.waitForIdle()

        val topAfter = hostTop()
        val after = popupNode()
        val popupAfter = after.positionOnScreen
        val moves = "asked $shift px; host $topBefore -> $topAfter, popup $popupBefore -> $popupAfter, " +
            "region $regionBefore -> ${after.config.getOrNull(GlassBackdropRegion)}"
        android.util.Log.i("GlassSearchTest", "host move: $moves")
        // Where the window lands is the window manager's call, not the test's:
        // on some CI emulators it lands 128 px past the requested offset (#113),
        // so the move is measured, never predicted. Without these two checks a
        // host that never moved, or a popup left behind, would pass too.
        val moved = topAfter - topBefore
        assertTrue("the launcher window moved on screen ($moves)", moved >= shift / 2)
        assertEquals("the popup moved with the launcher window ($moves)", moved.toFloat(), popupAfter.y - popupBefore.y, 2f)

        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val semantics = composeRule.onNodeWithTag("popup", useUnmergedTree = true).fetchSemanticsNode()
        val centreX = semantics.positionOnScreen.x + semantics.size.width / 2f
        val centreY = semantics.positionOnScreen.y + semantics.size.height / 2f
        val expected = (centreY - topAfter) / windowHeight
        val pixel = screen.getPixel(centreX.toInt(), centreY.toInt())
        val red = android.graphics.Color.red(pixel).toFloat()
        val blue = android.graphics.Color.blue(pixel).toFloat()
        assertEquals(
            "popup after the host moved: backdrop row drawn vs where it is on screen " +
                "(host top $topBefore -> $topAfter, popup on screen ${semantics.positionOnScreen}, " +
                "region ${semantics.config.getOrNull(GlassBackdropRegion)})",
            expected, red / (red + blue), 0.03f,
        )
    }

    @Test
    fun aGlassSurfaceInAPopupDrawsTheBackdropUnderItOnScreen() {
        // #113: what the node saw, in order, so a failure says which input was off.
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun event(what: String) = events.add("${android.os.SystemClock.uptimeMillis()} $what")
        fun eventLog() = synchronized(events) { events.toList() }.joinToString("\n")
        val controller = GlassBackdropController(
            source,
            MutableStateFlow(GlassInputs(24f, 0f, 28f, Contrast.Medium)),
            CoroutineScope(Dispatchers.Main.immediate),
        ) { _, key ->
            event("backdrop for window ${key.windowWidthPx}x${key.windowHeightPx}")
            val (w, h) = BackdropGeometry.backdropSize(key.windowWidthPx, key.windowHeightPx)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until h) {
                    val red = (255f * y / (h - 1)).toInt()
                    for (x in 0 until w) setPixel(x, y, android.graphics.Color.rgb(red, 0, 255 - red))
                }
            }.asImageBitmap()
        }
        var windowHeight = 0
        var hostTop = 0f
        var popupRoot: android.view.View? = null
        composeRule.setContent {
            val view = LocalView.current
            windowHeight = LocalWindowInfo.current.containerSize.height
            MaterialTheme {
                ProvideGlassBackdrop(controller) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // Pure green: neither backdrop (no green) nor rim or
                            // specular (bright and neutral) - #113's discriminator.
                            .background(Color(0xFF00FF00))
                            .onGloballyPositioned {
                                val origin = IntArray(2)
                                view.rootView.getLocationOnScreen(origin)
                                hostTop = origin[1].toFloat()
                                event("host origin ${origin.toList()} window height $windowHeight")
                            }
                    ) {
                        GlassSurface(Modifier.offset(16.dp, 200.dp).size(120.dp).testTag("window")) {}
                        // The popup window itself is placed; an offset inside
                        // it would move the content out of its window.
                        val offset = with(LocalDensity.current) { IntOffset(200.dp.roundToPx(), 420.dp.roundToPx()) }
                        Popup(offset = offset) {
                            val popupView = LocalView.current
                            popupRoot = popupView.rootView
                            GlassMenuGroup(
                                Modifier
                                    .size(120.dp)
                                    .testTag("popup")
                                    .onGloballyPositioned {
                                        val host = IntArray(2).also(view.rootView::getLocationOnScreen)
                                        val popup = IntArray(2).also(popupView.rootView::getLocationOnScreen)
                                        event("popup laid out: on screen ${it.positionOnScreen()}, popup window ${popup.toList()}, host ${host.toList()}")
                                    }
                            ) {}
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // The screen as the user sees it: a popup is a window of its own,
        // and capturing its node alone comes back blank.
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val screen = uiAutomation.takeScreenshot()
        event("screenshot")

        fun drawnAt(image: Bitmap, x: Float, y: Float): Float {
            val pixel = image.getPixel(x.toInt(), y.toInt())
            val red = android.graphics.Color.red(pixel).toFloat()
            val blue = android.graphics.Color.blue(pixel).toFloat()
            return red / (red + blue)
        }

        fun check(tag: String) {
            val semantics = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val centreX = semantics.positionOnScreen.x + semantics.size.width / 2f
            val centreY = semantics.positionOnScreen.y + semantics.size.height / 2f
            val expected = (centreY - hostTop) / windowHeight
            fun matches(row: Float) = kotlin.math.abs(expected - row) <= 0.03f
            val drawn = drawnAt(screen, centreX, centreY)
            android.util.Log.i("GlassSearchTest", "$tag: expected $expected, drawn $drawn")
            if (matches(drawn)) return
            // First frame or for good? Look again 100 ms later (#113).
            Thread.sleep(100)
            // The popup's own buffer, taken between the two screenshots, so a
            // signature below is one observation, not two frames apart.
            val popup = popupRoot.takeIf { tag == "popup" }
            val capture = capturePopupWindow(popup)
            val laterScreen = uiAutomation.takeScreenshot()
            val later = drawnAt(laterScreen, centreX, centreY)
            val region = semantics.config.getOrNull(GlassBackdropRegion)
            // Where the glass really is in the screenshot: the backdrop has no
            // green, the window around it does. Region right but pixels wrong
            // would mean the window is shown elsewhere than it reports (#113).
            val glassRows = (0 until screen.height).filter { y ->
                val p = screen.getPixel(centreX.toInt(), y)
                android.graphics.Color.green(p) < 16 &&
                    android.graphics.Color.red(p) + android.graphics.Color.blue(p) > 200
            }
            val onScreen = semantics.positionOnScreen
            val width = semantics.size.width
            val height = semantics.size.height
            val onScreenVerdict = discriminate(screen, onScreen.x, onScreen.y, width, height)
            val laterVerdict = discriminate(laterScreen, onScreen.x, onScreen.y, width, height)
            val outside = surroundings(screen, outerBounds(popup, onScreen, width, height))
            val evidence = "on screen ${semantics.positionOnScreen}, size ${semantics.size}, host top $hostTop, " +
                "window height $windowHeight, region $region, drawn 100 ms later $later ($laterVerdict), " +
                "glass in the screenshot at x ${centreX.toInt()}: y ${glassRows.firstOrNull()}..${glassRows.lastOrNull()}, " +
                onScreenVerdict +
                (if (popup != null) ", " + windowState(popup) + ", " + describeCapture(capture, onScreen, width, height) else "") + ", " +
                outside + (if (popup != null) ", " + windows() else "") + "\n" +
                eventLog()
            // #113 was an "Application Not Responding" dialog of the CI
            // emulator's own launcher, covering whichever screen-sampling
            // test ran under it. It is dismissed before the tests now
            // (e2e/ci/l2-with-evidence.sh), so this check no longer accepts
            // a signature of its own: a fill over the popup is a failure
            // again, whoever put it there. The evidence above still names
            // what was in front, which is what identified it.
            android.util.Log.e("GlassSearchTest", "$tag failed:\n$evidence")
            assertEquals("$tag: backdrop row drawn vs where it is on screen; $evidence", expected, drawn, 0.03f)
        }
        check("window")
        check("popup")
        android.util.Log.i("GlassSearchTest", eventLog())
    }

    /**
     * #113: what an image shows inside a surface's bounds. The rim and the
     * specular draw even at tint 0, but only along the edges: they are
     * counted in a ring 3 dp inside the bounds, and the interior on its own.
     * A uniform neutral interior is neither glass (which shows the red/blue
     * backdrop there) nor the green host: a fill of unknown origin. Counting
     * every bright pixel as rim, as before, called such a fill "glass".
     */
    private enum class Verdict { Backdrop, Fill, Window, Glass, Unclassified }

    /** A [Verdict] and the counts behind it, for the evidence. */
    private class Discrimination(val kind: Verdict, private val text: String) {
        override fun toString() = text
    }

    private fun discriminate(image: Bitmap, left: Float, top: Float, width: Int, height: Int): Discrimination {
        val ring = (3 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
        var backdrop = 0
        var interiorBackdrop = 0
        var host = 0
        var interiorHost = 0
        var rim = 0
        var interior = 0
        var other = 0
        val interiorColors = mutableListOf<Int>()
        val x0 = left.toInt().coerceIn(0, image.width - 1)
        val y0 = top.toInt().coerceIn(0, image.height - 1)
        val x1 = (left.toInt() + width).coerceIn(0, image.width)
        val y1 = (top.toInt() + height).coerceIn(0, image.height)
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val p = image.getPixel(x, y)
            val r = android.graphics.Color.red(p)
            val g = android.graphics.Color.green(p)
            val b = android.graphics.Color.blue(p)
            val inRing = x - x0 < ring || x1 - 1 - x < ring || y - y0 < ring || y1 - 1 - y < ring
            when {
                g < 16 && r + b > 200 -> {
                    backdrop++
                    if (!inRing) interiorBackdrop++
                }
                g > 200 && r < 40 && b < 40 -> {
                    host++
                    if (!inRing) interiorHost++
                }
                inRing && r >= 48 && g >= 48 && b >= 48 -> rim++
                !inRing -> { interior++; interiorColors += p }
                else -> other++
            }
        }
        // Uniform: every interior pixel within a few levels of the first.
        val first = interiorColors.firstOrNull()
        val uniform = first != null && interiorColors.all { p ->
            kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(first)) <= 6 &&
                kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(first)) <= 6 &&
                kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(first)) <= 6
        }
        val centre = image.getPixel((left + width / 2f).toInt().coerceIn(0, image.width - 1), (top + height / 2f).toInt().coerceIn(0, image.height - 1))
        val (kind, verdict) = when {
            interiorBackdrop > 0 -> Verdict.Backdrop to "backdrop on screen"
            uniform && interiorHost == 0 -> Verdict.Fill to "FILL: a uniform #${Integer.toHexString(first!!)} covers the interior - neither glass nor host"
            host > 0 -> Verdict.Window to "WINDOW: nothing of the surface on screen, the host shows through"
            rim > 0 -> Verdict.Glass to "GLASS: rim/specular along the edges, no backdrop inside"
            else -> Verdict.Unclassified to "unclassified"
        }
        return Discrimination(
            kind,
            "pixels in bounds (every 2nd): backdrop $backdrop ($interiorBackdrop inside), host $host, rim ring $rim, interior $interior " +
                "(uniform $uniform), other $other; centre ARGB #${Integer.toHexString(centre)}; verdict: $verdict",
        )
    }

    /**
     * #113: what the popup window itself drew, captured from its own surface,
     * with the window's origin on screen. The backdrop here and a fill on
     * screen is composition; a fill here too is the popup's own draw.
     */
    private fun capturePopupWindow(root: android.view.View?): Pair<Bitmap, IntArray>? {
        if (root == null) return null
        val done = java.util.concurrent.CountDownLatch(1)
        var captured: Bitmap? = null
        val rootOnScreen = IntArray(2)
        var rootSize = 0 to 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root.getLocationOnScreen(rootOnScreen)
            rootSize = root.width to root.height
            val request = android.view.PixelCopy.Request.Builder.ofWindow(root).build()
            // A direct executor: no thread to leak when a capture times out.
            android.view.PixelCopy.request(request, java.util.concurrent.Executor(Runnable::run)) { result ->
                if (result.status == android.view.PixelCopy.SUCCESS) captured = result.bitmap
                done.countDown()
            }
        }
        if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) return null
        val bitmap = captured ?: return null
        // The surface is larger than the window by its insets (room for the
        // elevation shadow): 371 against 293 px on emulator-5562, 399 against
        // 315 in CI. Centred, so the capture's origin sits half of that
        // before the window's.
        val origin = intArrayOf(
            rootOnScreen[0] - (bitmap.width - rootSize.first) / 2,
            rootOnScreen[1] - (bitmap.height - rootSize.second) / 2,
        )
        return bitmap to origin
    }

    private fun describeCapture(capture: Pair<Bitmap, IntArray>?, onScreen: androidx.compose.ui.geometry.Offset, width: Int, height: Int): String {
        val (bitmap, origin) = capture ?: return "popup capture: none (no popup window, timeout or PixelCopy failure)"
        val left = onScreen.x - origin[0]
        val top = onScreen.y - origin[1]
        return "popup capture ${bitmap.width}x${bitmap.height}, node at ($left, $top): " +
            discriminate(bitmap, left, top, width, height)
    }

    /**
     * #113: the screen 8 dp outside each edge of a surface. Host green there
     * with a fill inside means the fill is the popup layer's own; the same
     * fill outside too means something larger covers that part of the screen
     * (a system dialog or overlay), and the popup is not the question.
     */
    private class Outside(val bounds: android.graphics.RectF, val above: Int?, val below: Int?, val left: Int?, val right: Int?) {
        override fun toString(): String {
            fun hex(p: Int?) = p?.let { "#" + Integer.toHexString(it) } ?: "off-screen"
            return "outside $bounds: above ${hex(above)}, below ${hex(below)}, left ${hex(left)}, right ${hex(right)}"
        }
    }

    private fun surroundings(image: Bitmap, bounds: android.graphics.RectF): Outside {
        val gap = 8 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        fun at(x: Float, y: Float): Int? {
            val xi = x.toInt()
            val yi = y.toInt()
            return if (xi in 0 until image.width && yi in 0 until image.height) image.getPixel(xi, yi) else null
        }
        return Outside(
            bounds,
            above = at(bounds.centerX(), bounds.top - gap),
            below = at(bounds.centerX(), bounds.bottom + gap),
            left = at(bounds.left - gap, bounds.centerY()),
            right = at(bounds.right + gap, bounds.centerY()),
        )
    }

    /**
     * #113: the windows on screen and the focus, so a firing names the
     * dialog in front of the popup instead of leaving it to be inferred.
     */
    private fun windows(): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window windows")
        val lines = android.os.ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().use { it.readLines() }
        return "windows: " + lines.map { it.trim() }
            .filter { it.startsWith("Window #") || it.startsWith("mCurrentFocus") || it.startsWith("mFocusedApp") }
            .joinToString("; ")
    }

    /**
     * The node's bounds on screen, grown to its popup window's when there is
     * one: the popup window can be larger than the node (371 against 315 px
     * on emulator-5562), and a sample inside it would read the popup's own
     * fill as something larger covering the screen (#157 review).
     */
    private fun outerBounds(root: android.view.View?, onScreen: androidx.compose.ui.geometry.Offset, width: Int, height: Int): android.graphics.RectF {
        val bounds = android.graphics.RectF(onScreen.x, onScreen.y, onScreen.x + width, onScreen.y + height)
        if (root == null) return bounds
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val at = IntArray(2).also(root::getLocationOnScreen)
            bounds.union(at[0].toFloat(), at[1].toFloat(), (at[0] + root.width).toFloat(), (at[1] + root.height).toFloat())
        }
        return bounds
    }

    /** The popup window as the view system sees it at the failure (#113). */
    private fun windowState(root: android.view.View?): String {
        if (root == null) return "popup window: none"
        var state = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val at = IntArray(2).also(root::getLocationOnScreen)
            state = "popup window: shown ${root.isShown}, visibility ${root.windowVisibility}, " +
                "attached ${root.isAttachedToWindow}, size ${root.width}x${root.height}, at ${at.toList()}"
        }
        return state
    }

}
