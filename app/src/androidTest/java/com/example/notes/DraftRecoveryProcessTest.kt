package com.example.notes

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run separately with an app-only force-stop between methods. */
@RunWith(AndroidJUnit4::class)
class DraftRecoveryProcessTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val receipt=File(context.filesDir,"audit-recovery-process.json")
    private val payload="漢😀\n".repeat(200_000)
    @Test fun prepare() {
        check(!receipt.exists())
        val tokens=JSONObject().put("pid",Process.myPid())
        for(kind in listOf("note","task")) tokens.put(kind,DraftRecovery(context,kind).write(null,payload))
        receipt.writeText(tokens.toString())
    }
    @Test fun restoreInFreshProcess() {
        val tokens=JSONObject(receipt.readText()); assertNotEquals(tokens.getInt("pid"),Process.myPid())
        for(kind in listOf("note","task")) {
            val recovery=DraftRecovery(context,kind); val old=tokens.getString(kind)
            val(next,text)=recovery.claim(old)
            try { assertEquals(payload,text); assertEquals(payload,recovery.claim(old).second) }
            finally { recovery.remove(next) }
            assertThrows(java.io.FileNotFoundException::class.java) { recovery.claim(old) }
        }
        receipt.delete()
    }
}
