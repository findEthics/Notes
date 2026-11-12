package com.example.notes

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Lossless private recovery outside Binder; never a cache or a user-data cleanup target. */
internal class DraftRecovery(context: Context, private val kind: String) {
    private val directory = File(context.noBackupFilesDir, "editor-recovery")
    private fun file(token: String): AtomicFile {
        require(UUID.fromString(token).toString() == token)
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, "$kind-$token"))
    }
    fun write(previous: String?, text: String): String {
        val token = previous ?: UUID.randomUUID().toString()
        val target = file(token)
        val stream = target.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
        return token
    }
    /** Retain the durable copy until a successful commit/delete consumes it. A process death
     * during restoration must still be recoverable through the original saved-state token. */
    fun claim(token: String): Pair<String, String> {
        val text = file(token).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return token to text
    }
    fun remove(token: String?) { if (token != null) file(token).delete() }
}
