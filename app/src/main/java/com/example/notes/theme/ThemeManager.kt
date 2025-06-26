package com.example.notes.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    
    private const val PREFS_NAME = "theme_preferences"
    private const val KEY_THEME_MODE = "theme_mode"
    
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getCurrentTheme(context: Context): String {
        return getPreferences(context).getString(KEY_THEME_MODE, THEME_LIGHT) ?: THEME_LIGHT
    }
    
    fun setTheme(context: Context, theme: String) {
        getPreferences(context).edit()
            .putString(KEY_THEME_MODE, theme)
            .apply()
        
        applyTheme(theme)
    }
    
    fun applyTheme(theme: String) {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
    
    fun initializeTheme(context: Context) {
        val currentTheme = getCurrentTheme(context)
        applyTheme(currentTheme)
    }
    
    fun toggleTheme(context: Context) {
        val currentTheme = getCurrentTheme(context)
        val newTheme = when (currentTheme) {
            THEME_LIGHT -> THEME_DARK
            THEME_DARK -> THEME_LIGHT
            else -> THEME_LIGHT
        }
        setTheme(context, newTheme)
    }
    
    fun isDarkMode(context: Context): Boolean {
        return getCurrentTheme(context) == THEME_DARK
    }
    
    fun getThemeDisplayName(theme: String): String {
        return when (theme) {
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            THEME_SYSTEM -> "System"
            else -> "Light"
        }
    }
}