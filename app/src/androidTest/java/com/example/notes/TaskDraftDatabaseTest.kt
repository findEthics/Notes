package com.example.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskDraftDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "task-draft-${UUID.randomUUID()}.db"
    private val helper = DatabaseHelper(context, name)
    @After fun cleanup() { helper.close(); context.deleteDatabase(name) }
    private fun draft() = TaskDraft(text = "  task\ntext  ")

    @Test fun repeatedSaveReopensWithStableIdAndCompletion() {
        val saved = helper.saveTaskDraft(draft())
        val edited = helper.saveTaskDraft(saved.copy(completed = true))
        assertEquals(saved.id, edited.id)
        helper.saveTaskDraft(edited)
        DatabaseHelper(context, name).use { reopened ->
            assertEquals(1, reopened.getTaskSummaries().size)
            assertEquals("  task\ntext  ", reopened.getTaskById(saved.id!!)!!.text)
            assertTrue(reopened.getTaskById(saved.id)!!.completed)
        }
    }
    @Test fun writeFailureLeavesNewAndExistingTasksIntact() {
        helper.writableDatabase.use { it.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON tasks " +
            "BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        assertThrows(Exception::class.java) { helper.saveTaskDraft(draft()) }
        assertTrue(helper.getTaskSummaries().isEmpty())
        helper.writableDatabase.use { it.execSQL("DROP TRIGGER fail_insert") }
        val saved = helper.saveTaskDraft(draft())
        helper.writableDatabase.use { it.execSQL("CREATE TRIGGER fail_edit BEFORE UPDATE ON tasks " +
            "WHEN NEW.text='fail' BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        assertThrows(Exception::class.java) { helper.saveTaskDraft(saved.copy(text = "fail", completed = true)) }
        assertEquals(saved.text, helper.getTaskById(saved.id!!)!!.text)
        assertFalse(helper.getTaskById(saved.id)!!.completed)
    }
    @Test fun blankValidationAndMissingIdsNeverDropStoredData() {
        assertThrows(IllegalArgumentException::class.java) { helper.saveTaskDraft(TaskDraft(text = " \n")) }
        val saved = helper.saveTaskDraft(draft())
        assertThrows(IllegalArgumentException::class.java) { helper.saveTaskDraft(saved.copy(text = "")) }
        assertEquals(saved.text, helper.getTaskById(saved.id!!)!!.text)
        helper.deleteTask(saved.id)
        assertThrows(IllegalStateException::class.java) { helper.saveTaskDraft(saved) }
        assertTrue(helper.getTaskSummaries().isEmpty())
    }
    @Test fun deletePreservesOtherTasks() {
        val a = helper.saveTaskDraft(draft())
        val other = helper.saveTaskDraft(TaskDraft(text = "other"))
        helper.deleteTask(a.id!!)
        assertNull(helper.getTaskById(a.id))
        assertEquals("other", helper.getTaskById(other.id!!)!!.text)
    }
}
