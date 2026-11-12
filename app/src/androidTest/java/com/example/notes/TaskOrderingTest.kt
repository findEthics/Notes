package com.example.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskOrderingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "task-order-${UUID.randomUUID()}.db"
    private val helper = DatabaseHelper(context, name)
    @After fun cleanup() { helper.close(); context.deleteDatabase(name) }
    private fun order() = helper.getTaskSummaries().map { it.task.id }

    @Test fun completedLastAndEqualTimestampIdTiesSurviveReopen() {
        val a = helper.insertTask("a")!!
        val b = helper.insertTask("b", true)!!
        val c = helper.insertTask("c")!!
        val d = helper.insertTask("d", true)!!
        helper.writableDatabase.use { it.execSQL("UPDATE tasks SET updated_at='2099-01-01 00:00:00'") }
        assertEquals(listOf(c, a, d, b), order())
        DatabaseHelper(context, name).use { reopened ->
            assertEquals(listOf(c, a, d, b), reopened.getTaskSummaries().map { it.task.id })
        }
    }

    @Test fun immediateOlderIdUncheckIsStrictlyNewestWithoutChangingNotes() {
        val noteId = helper.insertNote("note", "☑ literal\nbody")
        val note = helper.getNoteById(noteId)
        val a = helper.insertTask("a", true)!!
        val b = helper.insertTask("b")!!
        // Coarse old rows, clock rollback and a second rollover, without sleeping.
        helper.writableDatabase.use { it.execSQL("UPDATE tasks SET updated_at='2099-01-01 00:00:00.999'") }
        repeat(5) {
            assertEquals(1, helper.setTaskCompleted(a, false))
            assertEquals(a, order().first())
            assertTrue(helper.getTaskById(a)!!.updatedAt > helper.getTaskById(b)!!.updatedAt)
            helper.updateTask(b, "b")
            helper.setTaskCompleted(a, true)
            assertEquals(listOf(b, a), order())
        }
        helper.setTaskCompleted(a, false)
        DatabaseHelper(context, name).use {
            assertEquals(listOf(a, b), it.getTaskSummaries().map { s -> s.task.id })
            assertEquals(note, it.getNoteById(noteId))
        }
    }

    @Test fun draftUncheckUsesSameOrderingAndKeepsIdentity() {
        val saved = helper.saveTaskDraft(TaskDraft(text = "old", completed = true))
        val newer = helper.insertTask("newer")!!
        helper.writableDatabase.use { it.execSQL("UPDATE tasks SET updated_at='2099-01-01 00:00:00'") }
        helper.saveTaskDraft(saved.copy(completed = false))
        DatabaseHelper(context, name).use {
            assertEquals(listOf(saved.id, newer), it.getTaskSummaries().map { s -> s.task.id })
            assertFalse(it.getTaskById(saved.id!!)!!.completed)
        }
    }
}
