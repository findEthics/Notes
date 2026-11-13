package com.example.notes

import android.os.Process
import android.os.Bundle
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run separately with an app-only force-stop between methods. */
@RunWith(AndroidJUnit4::class)
class DraftRecoveryProcessTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val receipt=File(context.filesDir,"audit-recovery-process.json")
    // StaticLayout allocates per paragraph; keep the live UI huge without 200k paragraphs.
    private val payload="${"漢😀".repeat(200_000)}\nsecond line\nthird line"
    private val filePayload="漢😀\n".repeat(200_000)
    @Before @After fun clearInterruptedFailureTrigger() {
        DatabaseHelper(context).use { it.writableDatabase.execSQL("DROP TRIGGER IF EXISTS process_task_failure") }
    }
    @Test fun prepare() {
        check(!receipt.exists())
        val tokens=JSONObject().put("pid",Process.myPid())
        assertTrue(payload.length * 2 > 1_200_000)
        assertEquals(2,payload.count { it == '\n' })
        tokens.put("note",DraftRecovery(context,"note").write(null,filePayload))
        tokens.put("task-file",DraftRecovery(context,"task").write(null,filePayload))
        DatabaseHelper(context).use { it.writableDatabase.execSQL("CREATE TRIGGER process_task_failure BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT,'process probe'); END") }
        try {
            launchTask().use { scenario ->
                val saved=Bundle()
                scenario.onActivity { activity ->
                    val editor=activity.currentEditor as TaskScreen
                    val text=editor.javaClass.getDeclaredField("textState").apply { isAccessible=true }.get(editor) as TextFieldState
                    val now=android.os.SystemClock.uptimeMillis()
                    assertTrue(editor.handleShortcut(android.view.KeyEvent(now,now,android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_D,0,android.view.KeyEvent.META_CTRL_ON)))
                    text.edit { replace(0,length,payload) }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("task-text").assertTextEquals(payload)
                scenario.onActivity { activity ->
                    instrumentation.callActivityOnSaveInstanceState(activity,saved)
                }
                val inline=checkNotNull(saved.getBundle("inline_task"))
                assertEquals(-1L,inline.getLong(TaskScreen.EXTRA_TASK_ID))
                tokens.put("task",checkNotNull(inline.getString("task_recovery")))
                tokens.put("taskId",inline.getLong(TaskScreen.EXTRA_TASK_ID))
            }
        } finally {
            DatabaseHelper(context).use { it.writableDatabase.execSQL("DROP TRIGGER IF EXISTS process_task_failure") }
        }
        receipt.writeText(tokens.toString())
    }
    @Test fun restoreInFreshProcess() {
        val tokens=JSONObject(receipt.readText()); assertNotEquals(tokens.getInt("pid"),Process.myPid())
        for(kind in listOf("note","task-file","task")) {
            val recovery=DraftRecovery(context,if(kind=="task-file") "task" else kind); val old=tokens.getString(kind)
            val(next,text)=recovery.claim(old)
            try {
                assertEquals(text,recovery.claim(old).second)
                if(kind!="task") assertEquals(filePayload,text) else {
                    val draft=JSONObject(text)
                    assertEquals(payload,draft.getString("text")); assertTrue(draft.getBoolean("completed"))
                    // Replay the actual nested inline state into a task session in the fresh process.
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            val saved=Bundle().apply {
                                putLong(TaskScreen.EXTRA_TASK_ID,tokens.getLong("taskId"))
                                putString("task_recovery",old)
                            }
                            val editor=TaskScreen(activity,saved,null,{}, {})
                            assertEquals(-1L,editor.editingTaskId)
                            assertTrue(editor.saveForLeave())
                            val id=editor.editingTaskId
                            try {
                                DatabaseHelper(context).use { helper ->
                                    val task=checkNotNull(helper.getTaskById(id))
                                    assertEquals(payload,task.text); assertTrue(task.completed)
                                }
                                assertFalse(editor.captureState().containsKey("task_recovery"))
                            } finally {
                                editor.dispose()
                                DatabaseHelper(context).use { it.deleteTask(id) }
                            }
                        }
                    }
                }
            }
            finally { recovery.remove(next) }
            assertThrows(java.io.FileNotFoundException::class.java) { recovery.claim(old) }
        }
        receipt.delete()
    }
}
