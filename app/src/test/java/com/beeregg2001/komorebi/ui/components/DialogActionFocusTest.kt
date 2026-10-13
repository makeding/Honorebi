package com.beeregg2001.komorebi.ui.components

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class DialogActionFocusTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cancelKeepsItsNeutralRoleThroughFocusAndHover() {
        compose.setContent {
            KomorebiTheme {
                ThemedDialogSurface(540.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DialogActionButton(DialogActionRole.Secondary, {}, Modifier.weight(1f)) { Text("Cancel") }
                        DialogActionButton(DialogActionRole.Primary, {}, Modifier.weight(1f)) { Text("OK") }
                    }
                }
            }
        }
        val cancel = compose.onNodeWithText("Cancel")
        val ok = compose.onNodeWithText("OK")
        val bounds = cancel.getUnclippedBoundsInRoot()
        cancel.requestFocus().assertIsFocused()
        assertEquals(Color.White, textColor("Cancel"))
        assertEquals(Color.Black, textColor("OK"))
        ok.requestFocus().assertIsFocused()
        cancel.performMouseInput { enter(center) }
        assertEquals(Color.White, textColor("Cancel"))
        assertEquals(Color.Black, textColor("OK"))
        assertEquals(bounds, cancel.getUnclippedBoundsInRoot())
    }
    private fun textColor(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.style.color
    }
}
