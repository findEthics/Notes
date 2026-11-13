package com.example.notes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

fun isAuthorizedCaller(pkg: String, certSha256: String, ownCertSha256: String): Boolean {
    if (!certSha256.matches(Regex("[0-9a-f]{64}"))) return false
    return when (pkg) {
        "com.example.notes" -> certSha256 == ownCertSha256
        "com.galaxyrio.gracelauncher" -> certSha256 == ownCertSha256 || certSha256 in setOf(
            "f1849f419e633e9f078b53a0095d6e963683da46fe439b25e705e9b267665ce2",
            "7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b")
        else -> false
    }
}

internal object NotesDataEvents {
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun changed() { Handler(Looper.getMainLooper()).post { listeners.forEach { it() } } }
}

private fun checkboxPrefixed(s: String): String =
    if (s.startsWith("☐ ") || s.startsWith("☑ ")) s else "☐ $s"

class NotesCaptureProvider : ContentProvider() {
    override fun onCreate() = true

    private fun authorize() {
        val ctx = checkNotNull(context)
        val pm = ctx.packageManager
        val packages = pm.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        val caller = callingPackage
        if (caller != null && caller !in packages) throw SecurityException("Caller UID mismatch")
        fun signer(pkg: String): String? = runCatching {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching null
            if (info.hasMultipleSigners()) return@runCatching null
            val signature = info.apkContentsSigners.singleOrNull() ?: return@runCatching null
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }.getOrNull()
        val own = signer(ctx.packageName) ?: throw SecurityException("Missing Notes signer")
        val candidates = if (caller != null) arrayOf(caller) else packages
        if (candidates.none { pkg -> signer(pkg)?.let { isAuthorizedCaller(pkg, it, own) } == true })
            throw SecurityException("Unauthorized capture caller")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        val result = Bundle().apply { putBoolean("ok", false) }
        DatabaseHelper(checkNotNull(context)).use { db ->
            if (method == "list_notes") {
                val notes = db.getNotePreviews().filter { preview ->
                    arg.isNullOrBlank() || preview.title.ifBlank {
                        preview.content.lineSequence().firstOrNull()?.trim().orEmpty()
                    }.contains(arg, ignoreCase = true)
                }.take((extras?.getInt("limit", 20) ?: 20).coerceIn(1, 100))
                result.putBoolean("ok", true)
                result.putLongArray("ids", notes.map { it.id }.toLongArray())
                result.putStringArrayList("titles", ArrayList(notes.map { preview -> preview.title.ifBlank {
                    preview.content.lineSequence().firstOrNull()?.trim()?.take(80).orEmpty().ifBlank { "Untitled" }
                } }))
                return result
            }
            val text = extras?.getString("text")?.trim().orEmpty().take(10_000)
            if (text.isBlank()) return result
            when (method) {
                "insert_task", "create_note" -> {
                    val id = if (method == "insert_task") db.insertTask(text, false) ?: -1L
                        else {
                            val parts = text.split(Regex("\\s+"), limit = 2)
                            val title = parts[0]
                            val rest = parts.getOrElse(1) { "" }
                            val content = if (rest.isEmpty()) "" else checkboxPrefixed(rest)
                            db.insertNote(title, content)
                        }
                    result.putBoolean("ok", id > 0); result.putLong("id", id)
                }
                "append_note" -> {
                    val id = extras?.getLong("id", -1L) ?: -1L
                    if (id <= 0) return result
                    val note = db.getNoteById(id) ?: return result
                    val entry = checkboxPrefixed(text)
                    val content = if (note.content.isEmpty()) "\n$entry" else "${note.content}\n\n$entry"
                    result.putBoolean("ok", db.updateNote(id, note.title, content) > 0)
                }
                else -> throw UnsupportedOperationException(method)
            }
        }
        if (result.getBoolean("ok")) NotesDataEvents.changed()
        return result
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = throw UnsupportedOperationException()
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
}
