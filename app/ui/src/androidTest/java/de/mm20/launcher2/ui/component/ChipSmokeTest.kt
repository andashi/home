package de.mm20.launcher2.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L2 smoke test: validates the Compose androidTest pipeline on an emulator.
 * Real UI tests land with the widget grid (Phase 3, ADR 0001).
 */
@RunWith(AndroidJUnit4::class)
class ChipSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chipRenders() {
        composeRule.setContent {
            MaterialTheme {
                Chip(text = "Smoke")
            }
        }
        composeRule.onNodeWithText("Smoke, deliberately wrong (#132 proof)").assertIsDisplayed()
    }
}
