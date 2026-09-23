package de.mm20.launcher2.ui.launcher.scaffold

import androidx.compose.ui.Modifier

/**
 * The launcher's content takes focus only where someone asks for it (#95).
 *
 * When a focused node clears its focus, the framework re-requests focus for
 * the window, and out of touch mode - after any key event, such as the Back
 * key of 3-button navigation - Compose answers by entering its content and
 * focusing the first focusable node it finds. Without a holder that was the
 * search field, and its focus gain opens search.
 *
 * The scaffold's root is that holder: focusable itself, so an entry from
 * outside stops at it instead of descending to the search field. A tap on
 * the bar still focuses the field directly (a request, not an entry), so
 * opening search is unchanged. A bare focus target, not `focusable()`: it
 * adds no semantics, so accessibility services do not see the whole screen
 * as one unlabeled element, and it draws no indication.
 */
fun Modifier.holdImplicitFocus(): Modifier = this
