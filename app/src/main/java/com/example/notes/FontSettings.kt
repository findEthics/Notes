package com.example.notes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class FontChoice(val displayName: String) {
    LATO("Lato"),
    SYSTEM_DEFAULT("System Default"),
    MONOSPACE("Monospace"),
    JETBRAINS_MONO_BOLD("JetBrainsMono-Bold"),
    JETBRAINS_MONO_SEMIBOLD("JetBrainsMono-SemiBold");

    companion object {
        fun fromPersisted(value: String?): FontChoice =
            entries.firstOrNull { it.displayName == value } ?: LATO
    }
}

/** Close when owned outside Compose; use rememberFontSettings in a composition. */
class FontSettings(context: Context, preferencesName: String = "notes_font_settings") : AutoCloseable {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    var choice: FontChoice by mutableStateOf(readChoice())
        private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY || key == null) refresh()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun select(choice: FontChoice) {
        this.choice = choice
        preferences.edit().putString(KEY, choice.displayName).apply()
    }

    fun refresh() {
        choice = readChoice()
    }

    private fun readChoice(): FontChoice {
        // Reset the pre-Lato choice once; subsequent user selections are untouched.
        if (!preferences.getBoolean("lato_default_migrated", false)) {
            preferences.edit().putString(KEY, FontChoice.LATO.displayName)
                .putBoolean("lato_default_migrated", true).apply()
        }
        return FontChoice.fromPersisted(runCatching { preferences.getString(KEY, null) }.getOrNull())
    }

    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY = "font_choice"
    }
}

@Composable
fun rememberFontSettings(): FontSettings {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { FontSettings(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(settings) { onDispose { settings.close() } }
    DisposableEffect(settings, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settings.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        settings.refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return settings
}
