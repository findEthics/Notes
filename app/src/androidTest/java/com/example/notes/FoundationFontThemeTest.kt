package com.example.notes

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notes.ui.NotesTheme
import com.example.notes.ui.FontSettingsDialog
import com.example.notes.ui.notesColorScheme
import com.example.notes.ui.notesEditorTextStyle
import com.example.notes.ui.notesTypography
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FoundationFontThemeTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "foundation-font-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val settings = FontSettings(context, name)

    @After
    fun cleanup() {
        settings.close()
        context.deleteSharedPreferences(name)
    }

    @Test
    fun checkboxButtonDefaultsVisiblePersistsAndObservesExternalChanges() {
        assertTrue(settings.showCheckboxButton)
        for (show in listOf(false, true)) {
            instrumentation.runOnMainSync { settings.setShowCheckboxButton(show) }
            assertEquals(show, preferences.getBoolean(FontSettings.SHOW_CHECKBOX_BUTTON_KEY, !show))
            FontSettings(context, name).use { assertEquals(show, it.showCheckboxButton) }
        }
        instrumentation.runOnMainSync {
            preferences.edit().putBoolean(FontSettings.SHOW_CHECKBOX_BUTTON_KEY, false).apply()
        }
        instrumentation.waitForIdleSync()
        assertFalse(settings.showCheckboxButton)
        assertTrue(preferences.edit().putString(FontSettings.SHOW_CHECKBOX_BUTTON_KEY, "invalid").commit())
        settings.refresh()
        assertTrue(settings.showCheckboxButton)
        FontSettings(context, name).use { assertTrue(it.showCheckboxButton) }
        instrumentation.runOnMainSync {
            settings.setShowCheckboxButton(false)
            preferences.edit().remove(FontSettings.SHOW_CHECKBOX_BUTTON_KEY).apply()
        }
        instrumentation.waitForIdleSync()
        assertTrue(settings.showCheckboxButton)
    }

    @Test
    fun settingsToggleIsAccessibleAndShortcutGuideScrollsAndClosesBackToSettings() {
        compose.setContent { NotesTheme { FontSettingsDialog(settings) {} } }
        compose.onNodeWithTag("show-checkbox-toggle").performScrollTo()
            .assertIsOn().assertHeightIsAtLeast(48.dp).performClick().assertIsOff()
        compose.runOnIdle { assertFalse(settings.showCheckboxButton) }
        compose.onNodeWithTag("keyboard-shortcuts").performScrollTo().performClick()
        compose.onNodeWithTag("shortcuts-dialog").assertIsDisplayed()
        compose.onNodeWithText("Anywhere (outside text fields)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lists (Notes & To-dos)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ctrl+Q:", substring = true).performScrollTo()
            .assertTextContains("Ctrl+I / Ctrl+O", substring = true)
        compose.onNodeWithText("To-do Editor").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter: Save and add another to-do", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shortcuts-done").performClick()
        compose.onNodeWithTag("shortcuts-dialog").assertDoesNotExist()
        compose.onNodeWithTag("show-checkbox-toggle").performScrollTo().assertIsOff()
    }

    @Test
    fun fiveChoicesLatoDefaultAndRecreatedPersistence() {
        assertEquals(listOf("Lato", "System Default", "Monospace", "JetBrainsMono-Bold", "JetBrainsMono-SemiBold"),
            FontChoice.entries.map { it.displayName })
        assertEquals(FontChoice.LATO, settings.choice)
        for (choice in FontChoice.entries) {
            instrumentation.runOnMainSync { settings.select(choice) }
            assertEquals(choice.displayName, preferences.getString(FontSettings.KEY, null))
            FontSettings(context, name).use { assertEquals(choice, it.choice) }
        }
    }

    @Test
    fun existingChoiceResetsToLatoOnlyOnceThenUserChoiceSurvivesRecreation() {
        val legacyName = "$name-legacy"
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        try {
            assertTrue(legacy.edit().putString(FontSettings.KEY, "Monospace").commit())
            FontSettings(context, legacyName).use {
                assertEquals(FontChoice.LATO, it.choice)
                it.select(FontChoice.JETBRAINS_MONO_SEMIBOLD)
            }
            FontSettings(context, legacyName).use {
                assertEquals(FontChoice.JETBRAINS_MONO_SEMIBOLD, it.choice)
                it.refresh()
                assertEquals(FontChoice.JETBRAINS_MONO_SEMIBOLD, it.choice)
            }
        } finally { context.deleteSharedPreferences(legacyName) }
    }

    @Test
    fun unknownAndMalformedPreferencesFallBackAndRefreshObservesExternalSelection() {
        assertTrue(preferences.edit().putString(FontSettings.KEY, "unknown font").commit())
        settings.refresh()
        assertEquals(FontChoice.LATO, settings.choice)
        assertTrue(preferences.edit().putInt(FontSettings.KEY, 8).commit())
        FontSettings(context, name).use { assertEquals(FontChoice.LATO, it.choice) }
        instrumentation.runOnMainSync {
            preferences.edit().putString(FontSettings.KEY, "Monospace").apply()
        }
        instrumentation.waitForIdleSync()
        assertEquals(FontChoice.MONOSPACE, settings.choice)
        assertTrue(preferences.edit().clear().commit())
        settings.refresh()
        assertEquals(FontChoice.LATO, settings.choice)
    }

    private fun roles(t: Typography) = listOf(t.displayLarge, t.displayMedium, t.displaySmall,
        t.headlineLarge, t.headlineMedium, t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall,
        t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall)

    @Test
    fun bundledFontsLoadAtActualWeightsAndAllRolesRetainScalableMetrics() {
        assertEquals(700, ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)!!.weight)
        assertEquals(600, ResourcesCompat.getFont(context, R.font.jetbrains_mono_semibold)!!.weight)
        assertEquals(400, ResourcesCompat.getFont(context, R.font.lato_regular)!!.weight)
        assertEquals(700, ResourcesCompat.getFont(context, R.font.lato_bold)!!.weight)
        assertEquals(900, ResourcesCompat.getFont(context, R.font.lato_black)!!.weight)
        val base = roles(notesTypography(FontChoice.LATO))
        for (choice in FontChoice.entries) {
            val actual = roles(notesTypography(choice))
            assertEquals(15, actual.size)
            actual.zip(base).forEach { (selected, original) ->
                assertEquals(original.fontSize, selected.fontSize)
                assertEquals(original.lineHeight, selected.lineHeight)
                assertEquals(original.letterSpacing, selected.letterSpacing)
                assertEquals(FontSynthesis.None, selected.fontSynthesis)
                assertEquals(0f, selected.lineHeight.value % 4f)
                when (choice) {
                    FontChoice.LATO -> assertEquals(original.fontWeight, selected.fontWeight)
                    FontChoice.SYSTEM_DEFAULT -> {
                        assertEquals(FontFamily.Default, selected.fontFamily)
                        assertEquals(original.fontWeight, selected.fontWeight)
                    }
                    FontChoice.MONOSPACE -> {
                        assertEquals(FontFamily.Monospace, selected.fontFamily)
                        assertEquals(original.fontWeight, selected.fontWeight)
                    }
                    FontChoice.JETBRAINS_MONO_BOLD -> assertEquals(FontWeight.Bold, selected.fontWeight)
                    FontChoice.JETBRAINS_MONO_SEMIBOLD -> assertEquals(
                        if (original.fontWeight == FontWeight.Normal) FontWeight.SemiBold else FontWeight.Bold,
                        selected.fontWeight)
                }
            }
        }
        val lato = notesTypography(FontChoice.LATO)
        assertEquals(28.sp, lato.titleLarge.fontSize)
        assertEquals(36.sp, lato.titleLarge.lineHeight)
        assertEquals(FontWeight.Black, lato.titleLarge.fontWeight)
        assertEquals(20.sp, lato.bodyLarge.fontSize)
        assertEquals(28.sp, lato.bodyLarge.lineHeight)
        assertEquals(FontWeight.Normal, lato.bodyLarge.fontWeight)
        assertEquals(16.sp, lato.labelLarge.fontSize)
        assertEquals(24.sp, lato.labelLarge.lineHeight)
        assertEquals(FontWeight.Bold, lato.labelLarge.fontWeight)
    }

    private fun colors(c: ColorScheme) = listOf(c.primary, c.onPrimary, c.primaryContainer,
        c.onPrimaryContainer, c.inversePrimary, c.secondary, c.onSecondary, c.secondaryContainer,
        c.onSecondaryContainer, c.tertiary, c.onTertiary, c.tertiaryContainer, c.onTertiaryContainer,
        c.background, c.onBackground, c.surface, c.onSurface, c.surfaceVariant, c.onSurfaceVariant,
        c.surfaceTint, c.inverseSurface, c.inverseOnSurface, c.error, c.onError, c.errorContainer,
        c.onErrorContainer, c.outline, c.outlineVariant, c.scrim, c.surfaceBright, c.surfaceDim,
        c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest, c.surfaceContainerLow,
        c.surfaceContainerLowest)

    @Test
    fun darkSurfacesAreBlackWhileDynamicAccentsAndReadableOnColorsRemain() {
        val dark = notesColorScheme(context, true)
        val dynamic = dynamicDarkColorScheme(context)
        assertEquals(dynamic.primary, dark.primary)
        assertEquals(dynamic.onPrimary, dark.onPrimary)
        assertEquals(dynamic.onSurface, dark.onSurface)
        listOf(dark.background, dark.surface, dark.surfaceDim, dark.surfaceContainerLowest).forEach {
            assertEquals(Color.Black, it)
        }
        assertEquals(dynamic.surfaceContainerHigh, dark.surfaceContainerHigh)
        assertNotEquals(Color.Black, dark.surfaceContainerHigh)
        assertEquals(dynamic.surfaceContainer, dark.surfaceContainer)
        assertEquals(Color.Transparent, dark.surfaceTint)
        assertTrue((dark.onSurface.luminance() + 0.05f) / 0.05f >= 4.5f)
        assertTrue((dark.onBackground.luminance() + 0.05f) / 0.05f >= 4.5f)
        assertTrue((dark.onSurfaceVariant.luminance() + 0.05f) / 0.05f >= 4.5f)
        assertEquals(colors(dynamicLightColorScheme(context)), colors(notesColorScheme(context, false)))
    }

    @Test
    fun selectionImmediatelyRecomposesMaterialRolesAndEditorStyle() {
        var typography: Typography? = null
        var editor: androidx.compose.ui.text.TextStyle? = null
        compose.setContent {
            NotesTheme(fontChoice = settings.choice) {
                val current = MaterialTheme.typography
                val currentEditor = notesEditorTextStyle()
                SideEffect { typography = current; editor = currentEditor }
            }
        }
        compose.runOnIdle { settings.select(FontChoice.JETBRAINS_MONO_SEMIBOLD) }
        compose.runOnIdle {
            assertEquals(FontWeight.SemiBold, typography!!.bodyLarge.fontWeight)
            assertEquals(FontWeight.Bold, typography!!.titleLarge.fontWeight)
            assertEquals(typography!!.bodyLarge, editor)
            settings.select(FontChoice.MONOSPACE)
        }
        compose.runOnIdle {
            assertTrue(roles(typography!!).all { it.fontFamily == FontFamily.Monospace })
            assertEquals(typography!!.bodyLarge, editor)
        }
    }
}
