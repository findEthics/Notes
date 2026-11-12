package com.example.notes.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.notes.FontChoice
import com.example.notes.R
import com.example.notes.rememberFontSettings

fun notesTypography(choice: FontChoice): Typography {
    val family = when (choice) {
        FontChoice.LATO -> FontFamily(
            Font(R.font.lato_regular, FontWeight.Normal),
            Font(R.font.lato_bold, FontWeight.Bold),
            Font(R.font.lato_black, FontWeight.Black))
        FontChoice.SYSTEM_DEFAULT -> FontFamily.Default
        FontChoice.MONOSPACE -> FontFamily.Monospace
        FontChoice.JETBRAINS_MONO_BOLD, FontChoice.JETBRAINS_MONO_SEMIBOLD -> FontFamily(
            Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
            Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold))
    }
    val bodyWeight = when (choice) {
        FontChoice.JETBRAINS_MONO_BOLD -> FontWeight.Bold
        FontChoice.JETBRAINS_MONO_SEMIBOLD -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }
    val headingWeight = if (choice == FontChoice.JETBRAINS_MONO_BOLD ||
        choice == FontChoice.JETBRAINS_MONO_SEMIBOLD) FontWeight.Bold else FontWeight.Black
    fun style(size: Int, height: Int, weight: FontWeight) = TextStyle(
        fontSize = size.sp, lineHeight = height.sp, fontFamily = family, fontWeight = weight,
        letterSpacing = 0.sp,
        fontSynthesis = FontSynthesis.None)
    return Typography(
        displayLarge = style(56, 64, headingWeight),
        displayMedium = style(44, 52, headingWeight),
        displaySmall = style(36, 44, headingWeight),
        headlineLarge = style(32, 40, headingWeight),
        headlineMedium = style(28, 36, headingWeight),
        headlineSmall = style(24, 32, headingWeight),
        titleLarge = style(28, 36, headingWeight),
        titleMedium = style(20, 28, FontWeight.Bold),
        titleSmall = style(16, 24, FontWeight.Bold),
        bodyLarge = style(20, 28, bodyWeight),
        bodyMedium = style(16, 24, bodyWeight),
        bodySmall = style(12, 16, bodyWeight),
        labelLarge = style(16, 24, FontWeight.Bold),
        labelMedium = style(14, 20, FontWeight.Bold),
        labelSmall = style(12, 16, FontWeight.Bold)
    )
}

fun notesColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    if (!darkTheme) return dynamicLightColorScheme(context)
    return dynamicDarkColorScheme(context).copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceTint = Color.Transparent
    )
}

/** Explicit textStyle for subsequent BasicTextField/editor integration. */
@Composable
fun notesEditorTextStyle(): TextStyle = MaterialTheme.typography.bodyLarge

@Composable
fun NotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontChoice: FontChoice = rememberFontSettings().choice,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = notesColorScheme(context, darkTheme)
    val typography = remember(fontChoice) { notesTypography(fontChoice) }
    CompositionLocalProvider(LocalTonalElevationEnabled provides false) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
