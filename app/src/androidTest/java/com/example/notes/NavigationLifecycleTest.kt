package com.example.notes

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun canceledPredictiveBackDoesNotSaveOrDeleteBlankNoteButCommittedBackDoes() {
        DatabaseHelper(context).use { helper ->
            val id = helper.insertNote("", "")
            try {
                launchNote(id).use { scenario ->
                    compose.onNodeWithTag("leave-input").performClick()
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
                        it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 600f, 0.6f, BackEventCompat.EDGE_LEFT))
                    }
                    compose.waitForIdle()
                    assertNotNull(helper.getNoteById(id))
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    compose.onNodeWithTag("note-text").assertExists()
                    assertNotNull(helper.getNoteById(id))
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    compose.onNodeWithTag("items").assertExists()
                    assertNull(helper.getNoteById(id))
                }
            } finally { helper.deleteNotes(listOf(id)) }
        }
    }
}
