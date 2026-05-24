package com.example.tfiapp

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AppTheme(val label: String) {
    DEFAULT("Default (purple)"),
    TFI("TFI (blue & yellow)");

    companion object {
        fun from(s: String?): AppTheme = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

val DefaultColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
)

val TfiColorScheme = lightColorScheme(
    primary = Color(0xFF003B8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD200),
    onPrimaryContainer = Color(0xFF003B8C),
    secondary = Color(0xFFFFD200),
    onSecondary = Color(0xFF003B8C),
    secondaryContainer = Color(0xFFFFE680),
    onSecondaryContainer = Color(0xFF003B8C),
    background = Color(0xFFEEF1F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8ECF4),
)

val DefaultColorSchemeDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
)

val TfiColorSchemeDark = darkColorScheme(
    primary = Color(0xFF003B8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD200),
    onPrimaryContainer = Color(0xFF003B8C),
    secondary = Color(0xFFFFD200),
    onSecondary = Color(0xFF003B8C),
    secondaryContainer = Color(0xFF6B5A00),
    onSecondaryContainer = Color(0xFFFFD200),
    background = Color(0xFF101418),
    surface = Color(0xFF1A1F26),
    surfaceVariant = Color(0xFF252B33),
    onSurface = Color(0xFFE3E5E8),
    onSurfaceVariant = Color(0xFFB8BCC2),
)

fun colorsFor(theme: AppTheme, isDark: Boolean): ColorScheme = when {
    theme == AppTheme.TFI && isDark -> TfiColorSchemeDark
    theme == AppTheme.TFI -> TfiColorScheme
    isDark -> DefaultColorSchemeDark
    else -> DefaultColorScheme
}

private val THEME_KEY = stringPreferencesKey("theme")

class ThemeStore(private val context: Context) {
    val flow: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        AppTheme.from(prefs[THEME_KEY])
    }

    suspend fun set(theme: AppTheme) {
        context.dataStore.edit { it[THEME_KEY] = theme.name }
    }
}
