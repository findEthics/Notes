package com.example.notes

import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dedicated fixture emulator only; cleanup removes only this class's rows. */
@RunWith(AndroidJUnit4::class)
class NotesCaptureProviderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val helper = DatabaseHelper(context)
    private val notes = mutableListOf<Long>()
    private val tasks = mutableListOf<Long>()
    private val uri = Uri.parse("content://com.example.notes.capture")

    @After fun cleanup() {
        helper.deleteNotes(notes)
        tasks.forEach { helper.deleteTask(it) }
        helper.close()
    }

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        requireNotNull(context.contentResolver.call(uri, method, arg, extras))

    @Test fun insertTaskCreatesIncompleteTaskAndRejectsBlankText() {
        val result = call("insert_task", extras = Bundle().apply { putString("text", "Buy milk") })
        val id = result.getLong("id", -1L)
        if (id > 0) tasks.add(id)
        assertTrue(result.getBoolean("ok"))
        assertTrue(id > 0)
        val task = requireNotNull(helper.getTaskById(id))
        assertEquals("Buy milk", task.text)
        assertFalse(task.completed)

        val before = helper.getTaskSummaries().map { it.task.id }.toSet()
        val blank = call("insert_task", extras = Bundle().apply { putString("text", " \n\t ") })
        assertFalse(blank.getBoolean("ok"))
        assertEquals(before, helper.getTaskSummaries().map { it.task.id }.toSet())
    }

    @Test fun createNoteUsesFirstWordAsTitleAndListFiltersCaseInsensitively() {
        val result = call("create_note", extras = Bundle().apply { putString("text", "Groceries buy milk and eggs") })
        val id = result.getLong("id", -1L)
        if (id > 0) notes.add(id)
        assertTrue(result.getBoolean("ok"))
        assertTrue(id > 0)
        val note = requireNotNull(helper.getNoteById(id))
        assertEquals("Groceries", note.title)
        assertEquals("☐ buy milk and eggs", note.content)

        for (filter in listOf(null, "gROcErIeS")) {
            val listed = call("list_notes", filter)
            assertTrue(listed.getBoolean("ok"))
            val ids = requireNotNull(listed.getLongArray("ids"))
            val titles = requireNotNull(listed.getStringArrayList("titles"))
            assertEquals(ids.size, titles.size)
            val index = ids.indexOf(id)
            assertTrue("Created note is listed for filter $filter", index >= 0)
            assertEquals("Groceries", titles[index])
            if (filter != null) assertTrue(titles.all { it.contains(filter, ignoreCase = true) })
        }
        val excluded = call("list_notes", "Groceries-no-match")
        assertTrue(excluded.getBoolean("ok"))
        assertFalse(requireNotNull(excluded.getLongArray("ids")).contains(id))
    }

    @Test fun createNoteSupportsTitleOnlyAndPreservesExistingCheckboxes() {
        for ((text, content) in listOf("Shopping" to "", "Shopping ☐ Milk" to "☐ Milk", "Shopping ☑ Milk" to "☑ Milk")) {
            val result = call("create_note", extras = Bundle().apply { putString("text", text) })
            val id = result.getLong("id", -1L)
            if (id > 0) notes.add(id)
            assertTrue(result.getBoolean("ok"))
            val note = requireNotNull(helper.getNoteById(id))
            assertEquals("Shopping", note.title)
            assertEquals(content, note.content)
        }
        val before = helper.getNotePreviews().map { it.id }.toSet()
        assertFalse(call("create_note", extras = Bundle().apply { putString("text", " \n\t ") }).getBoolean("ok"))
        assertEquals(before, helper.getNotePreviews().map { it.id }.toSet())
    }

    @Test fun appendToTitleOnlyNoteKeepsBlankLineAfterEditorSaveAndReopen() {
        val id = helper.insertNote("Shopping", "").also { notes.add(it) }
        val result = call("append_note", extras = Bundle().apply {
            putLong("id", id); putString("text", "Milk")
        })
        assertTrue(result.getBoolean("ok"))
        assertEquals("Shopping", helper.getNoteById(id)!!.title)
        assertEquals("\n☐ Milk", helper.getNoteById(id)!!.content)
        launchNote(id).use {
            compose.onNodeWithTag("note-text").assertTextEquals("Shopping\n\n☐ Milk")
            // Force a real normalized write rather than the clean-editor save fast path.
            compose.onNodeWithTag("note-text").performTextReplacement("Shopping\n\n☐ Milk ")
            compose.onNodeWithTag("done").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("items").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Shopping", helper.getNoteById(id)!!.title)
            assertEquals("\n☐ Milk", helper.getNoteById(id)!!.content)
        }
        launchNote(id).use {
            compose.onNodeWithTag("note-text").assertTextEquals("Shopping\n\n☐ Milk")
            compose.onNodeWithTag("done").performClick()
        }
        assertEquals("\n☐ Milk", helper.getNoteById(id)!!.content)
    }

    @Test fun appendToNoteWithBodySeparatesExistingAndAppendedTextWithBlankLine() {
        val id = helper.insertNote("Work", "Line 1").also { notes.add(it) }
        val result = call("append_note", extras = Bundle().apply {
            putLong("id", id); putString("text", "Line 2")
        })
        assertTrue(result.getBoolean("ok"))
        assertEquals("Work", helper.getNoteById(id)!!.title)
        assertEquals("Line 1\n\n☐ Line 2", helper.getNoteById(id)!!.content)
    }

    @Test fun appendPreservesExistingCheckboxesWithoutAddingAnotherMarker() {
        for (entry in listOf("☐ Milk", "☑ Milk")) {
            val id = helper.insertNote("Shopping", "Existing").also { notes.add(it) }
            val result = call("append_note", extras = Bundle().apply {
                putLong("id", id); putString("text", entry)
            })
            assertTrue(result.getBoolean("ok"))
            assertEquals("Existing\n\n$entry", helper.getNoteById(id)!!.content)
        }
    }

    @Test fun concurrentAppendsPreserveEveryEntryAndTitle() {
        val id = helper.insertNote("Concurrent capture", "Original").also { notes.add(it) }
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..32).map { index ->
                workers.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    call("append_note", extras = Bundle().apply {
                        putLong("id", id); putString("text", "Entry $index")
                    }).getBoolean("ok")
                })
            }
            start.countDown()
            results.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
            val note = requireNotNull(helper.getNoteById(id))
            assertEquals("Concurrent capture", note.title)
            val entries = note.content.split("\n\n")
            assertEquals("Original", entries.first())
            assertEquals(33, entries.size)
            assertEquals((1..32).map { "☐ Entry $it" }.toSet(), entries.drop(1).toSet())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun appendRejectsMissingNoteWithoutCreatingOne() {
        val id = helper.insertNote("Deleted append target").also { notes.add(it) }
        helper.deleteNotes(listOf(id))
        assertFalse(call("append_note", extras = Bundle().apply {
            putLong("id", id); putString("text", "Must not create")
        }).getBoolean("ok"))
        assertNull(helper.getNoteById(id))
    }
}
