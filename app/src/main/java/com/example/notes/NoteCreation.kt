package com.example.notes

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

/** One owner survives recreation; leaving Main cancels rather than surprising the next screen. */
class NoteCreation(application: Application) : AndroidViewModel(application) {
    val result = MutableLiveData<Result<Long>?>()
    var pending = false
        private set
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var abandoned = false
    private var cleared = false
    // Narrow injection seam for the delayed-insert lifecycle regression.
    internal var insert: () -> Long = { DatabaseHelper(getApplication<Application>()).use { it.insertNote("", "") } }

    fun begin() {
        if (pending) return
        pending = true; abandoned = false
        executor.execute {
            val created = runCatching(insert)
            main.post {
                if (abandoned) discard(created) else result.value = created
            }
        }
    }
    fun consume(): Result<Long>? {
        val value = result.value ?: return null
        result.value = null; pending = false
        return value
    }
    fun abandon() {
        if (!pending) return
        abandoned = true
        result.value?.let { result.value = null; discard(it) }
    }
    private fun discard(created: Result<Long>) {
        executor.execute {
            val cleaned = runCatching {
                created.getOrNull()?.let { id ->
                    DatabaseHelper(getApplication<Application>()).use { helper ->
                        helper.writableDatabase.use { db ->
                            db.delete("notes", "id=? AND title='' AND coalesce(content, '')=''", arrayOf(id.toString()))
                        }
                    }
                }
            }
            main.post {
                pending = false
                if (cleared) executor.shutdown()
                if (cleaned.isFailure) result.value = Result.failure(cleaned.exceptionOrNull()!!)
            }
        }
    }
    override fun onCleared() { cleared = true; abandon(); if (!pending) executor.shutdown() }
}
