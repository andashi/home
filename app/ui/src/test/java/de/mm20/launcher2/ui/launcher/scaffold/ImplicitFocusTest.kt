package de.mm20.launcher2.ui.launcher.scaffold

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Back key reopened search (#95): closing search cleared the field's
 * focus, the framework then re-requested focus for the window - out of touch
 * mode after a key event - and Compose handed it to the first focusable
 * node, the search field, whose focus gain reads as a tap on the bar.
 */
@RunWith(AndroidJUnit4::class)
class ImplicitFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var fieldFocused = false
    private lateinit var view: View

    private fun show() {
        composeRule.setContent {
            view = LocalView.current
            var text by mutableStateOf("")
            Column(Modifier.holdImplicitFocus()) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .testTag("search")
                        .onFocusChanged { fieldFocused = it.hasFocus },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `focus the framework hands back after a clear lands nowhere`() {
        show()
        composeRule.runOnUiThread {
            view.clearFocus()
            view.requestFocus()
        }
        composeRule.waitForIdle()
        assertFalse("the search field took focus nobody asked for", fieldFocused)
    }

    /** Control: a tap on the bar still focuses the field, so search still opens. */
    @Test
    fun `a tap still focuses the field`() {
        show()
        composeRule.onNodeWithTag("search").performClick()
        composeRule.waitForIdle()
        assertTrue(fieldFocused)
    }
}
