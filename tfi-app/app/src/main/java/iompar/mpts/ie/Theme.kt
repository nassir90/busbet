package iompar.mpts.ie

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The editable colours for a single light/dark face. Stored as packed ARGB [Int]s so they
 * serialise cleanly. Only the roles that visibly matter are editable; the rest of the
 * Material [ColorScheme] is derived from these in [buildColorScheme].
 */
data class ColorSet(
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val tertiary: Int,
    val background: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val error: Int,
)

/**
 * A palette is one identity with a [light] and a [dark] face. The app follows the system
 * light/dark setting and shows the matching face. When [linkedDark] is true the dark face is
 * generated from [light] automatically; the user can opt to give dark its own accents by
 * unlinking it.
 */
data class AppPalette(
    val id: String,
    val name: String,
    val light: ColorSet,
    val dark: ColorSet,
    val linkedDark: Boolean = true,
    /** Built-in palettes can't be edited or deleted; users duplicate them to customise. */
    val preset: Boolean = false,
) {
    /** The face to use for the given system theme. */
    fun faceFor(systemDark: Boolean): ColorSet =
        if (!systemDark) light else if (linkedDark) deriveDark(light) else dark
}

/** The editable colour roles, exposed generically so the editor can render one row each. */
data class PaletteRole(
    val label: String,
    val get: (ColorSet) -> Int,
    val set: (ColorSet, Int) -> ColorSet,
)

val PALETTE_ROLES: List<PaletteRole> = listOf(
    PaletteRole("Primary (app bar)", { it.primary }, { s, c -> s.copy(primary = c) }),
    PaletteRole("On primary (text on app bar)", { it.onPrimary }, { s, c -> s.copy(onPrimary = c) }),
    PaletteRole("Secondary / accent", { it.secondary }, { s, c -> s.copy(secondary = c) }),
    PaletteRole("Tertiary (live/early)", { it.tertiary }, { s, c -> s.copy(tertiary = c) }),
    PaletteRole("Background", { it.background }, { s, c -> s.copy(background = c) }),
    PaletteRole("Surface (cards)", { it.surface }, { s, c -> s.copy(surface = c) }),
    PaletteRole("On surface (text)", { it.onSurface }, { s, c -> s.copy(onSurface = c) }),
    PaletteRole("On surface muted", { it.onSurfaceVariant }, { s, c -> s.copy(onSurfaceVariant = c) }),
    PaletteRole("Error", { it.error }, { s, c -> s.copy(error = c) }),
)

private fun argb(hex: Long): Int = hex.toInt()

private fun lightSet(
    primary: Long, onPrimary: Long, secondary: Long, tertiary: Long,
    background: Long, surface: Long, onSurface: Long, onSurfaceVariant: Long, error: Long,
) = ColorSet(
    argb(primary), argb(onPrimary), argb(secondary), argb(tertiary),
    argb(background), argb(surface), argb(onSurface), argb(onSurfaceVariant), argb(error),
)

/** Built-in palettes — the old fixed themes, now offered as starting points. */
val PRESETS: List<AppPalette> = listOf(
    AppPalette(
        id = "preset-default", name = "Default (purple)", preset = true, linkedDark = false,
        light = lightSet(
            0xFF6750A4, 0xFFFFFFFF, 0xFF625B71, 0xFF7D5260,
            0xFFFFFBFE, 0xFFFFFBFE, 0xFF1C1B1F, 0xFF49454F, 0xFFB3261E,
        ),
        dark = lightSet(
            0xFFD0BCFF, 0xFF381E72, 0xFFCCC2DC, 0xFFEFB8C8,
            0xFF1C1B1F, 0xFF1C1B1F, 0xFFE6E1E5, 0xFFCAC4D0, 0xFFF2B8B5,
        ),
    ),
    AppPalette(
        id = "preset-tfi", name = "Blue & yellow", preset = true, linkedDark = false,
        light = lightSet(
            0xFF003B8C, 0xFFFFFFFF, 0xFFFFD200, 0xFF2E7D32,
            0xFFEEF1F7, 0xFFFFFFFF, 0xFF1C1B1F, 0xFF49454F, 0xFFB3261E,
        ),
        dark = lightSet(
            0xFF4D8AE0, 0xFF001A40, 0xFFFFD200, 0xFF81C784,
            0xFF101418, 0xFF1A1F26, 0xFFE3E5E8, 0xFFB8BCC2, 0xFFFF6679,
        ),
    ),
    AppPalette(
        id = "preset-green", name = "Green", preset = true, linkedDark = false,
        light = lightSet(
            0xFF3D5663, 0xFFFFFFFF, 0xFF3D5663, 0xFF2E7D32,
            0xFFFFFBFE, 0xFFFFFBFE, 0xFF1C1B1F, 0xFF49454F, 0xFFB3261E,
        ),
        dark = lightSet(
            0xFF8FB0BF, 0xFF10242E, 0xFF8FB0BF, 0xFF81C784,
            0xFF14181C, 0xFF1B1F24, 0xFFE3E5E8, 0xFFB8BCC2, 0xFFF2B8B5,
        ),
    ),
)

val DEFAULT_PALETTE: AppPalette get() = PRESETS[0]

private fun relLum(c: Int): Double {
    val r = (c shr 16 and 0xFF) / 255.0
    val g = (c shr 8 and 0xFF) / 255.0
    val b = (c and 0xFF) / 255.0
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/** Best black/white text colour for legibility on [c]. */
fun contrastOn(c: Int): Int = if (relLum(c) > 0.55) argb(0xFF000000) else argb(0xFFFFFFFF)

/** Linear ARGB blend: t=0 → a, t=1 → b. Alpha forced opaque. */
fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(sh: Int) = (((a shr sh and 0xFF) * (1 - t) + (b shr sh and 0xFF) * t)).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

// Neutral dark surfaces used when auto-generating a dark face.
private val DARK_BG = argb(0xFF14181C)
private val DARK_SURFACE = argb(0xFF1B1F24)
private val DARK_ON_SURFACE = argb(0xFFE3E5E8)
private val DARK_ON_SURFACE_VARIANT = argb(0xFFB8BCC2)

/** Lighten a colour just enough to read on a dark surface. */
private fun liftForDark(c: Int): Int = if (relLum(c) < 0.5) blend(c, argb(0xFFFFFFFF), 0.45f) else c

/**
 * Generate a dark face from a light one: keep the brand accents, swap to dark surfaces, and
 * lighten the on-surface accents (tertiary/error) so they stay legible.
 */
fun deriveDark(light: ColorSet): ColorSet = light.copy(
    background = DARK_BG,
    surface = DARK_SURFACE,
    onSurface = DARK_ON_SURFACE,
    onSurfaceVariant = DARK_ON_SURFACE_VARIANT,
    tertiary = liftForDark(light.tertiary),
    error = liftForDark(light.error),
)

/** Expands a [ColorSet]'s key colours into a full Material [ColorScheme]. */
fun buildColorScheme(set: ColorSet, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(set.primary),
        onPrimary = Color(set.onPrimary),
        primaryContainer = Color(blend(set.surface, set.primary, if (dark) 0.30f else 0.16f)),
        onPrimaryContainer = Color(set.primary),
        secondary = Color(set.secondary),
        onSecondary = Color(contrastOn(set.secondary)),
        secondaryContainer = Color(blend(set.surface, set.secondary, if (dark) 0.30f else 0.20f)),
        onSecondaryContainer = Color(set.onSurface),
        tertiary = Color(set.tertiary),
        onTertiary = Color(contrastOn(set.tertiary)),
        background = Color(set.background),
        onBackground = Color(set.onSurface),
        surface = Color(set.surface),
        onSurface = Color(set.onSurface),
        surfaceVariant = Color(blend(set.surface, set.onSurface, 0.08f)),
        onSurfaceVariant = Color(set.onSurfaceVariant),
        // Cards (ElevatedCard) read these; step them off the surface so they read grayish
        // against the background instead of blending into it.
        surfaceContainerLowest = Color(set.surface),
        surfaceContainerLow = Color(blend(set.surface, set.onSurface, 0.04f)),
        surfaceContainer = Color(blend(set.surface, set.onSurface, 0.06f)),
        surfaceContainerHigh = Color(blend(set.surface, set.onSurface, 0.09f)),
        surfaceContainerHighest = Color(blend(set.surface, set.onSurface, 0.12f)),
        error = Color(set.error),
        onError = Color(contrastOn(set.error)),
        outline = Color(set.onSurfaceVariant),
        outlineVariant = Color(blend(set.surface, set.onSurface, 0.22f)),
    )
}

fun buildColorScheme(palette: AppPalette, dark: Boolean): ColorScheme =
    buildColorScheme(palette.faceFor(dark), dark)

/** Resolve the selected id against presets + custom palettes, falling back to default. */
fun resolvePalette(id: String?, custom: List<AppPalette>): AppPalette =
    (PRESETS + custom).firstOrNull { it.id == id } ?: DEFAULT_PALETTE

/** A fresh editable copy of [base] with a new id, ready to customise. */
fun duplicatePalette(base: AppPalette, name: String): AppPalette =
    base.copy(id = "custom-${UUID.randomUUID()}", name = name, preset = false)

private val SELECTED_KEY = stringPreferencesKey("palette_selected_v2")
private val CUSTOM_KEY = stringPreferencesKey("palette_custom_v2")
private val paletteGson = Gson()
private val paletteListType = object : TypeToken<List<AppPalette>>() {}.type

class PaletteStore(private val context: Context) {
    val selectedId: Flow<String> = context.dataStore.data.map { it[SELECTED_KEY] ?: DEFAULT_PALETTE.id }

    val customPalettes: Flow<List<AppPalette>> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_KEY]?.let { paletteGson.fromJson<List<AppPalette>>(it, paletteListType) } ?: emptyList()
    }

    suspend fun select(id: String) {
        context.dataStore.edit { it[SELECTED_KEY] = id }
    }

    /** Insert or replace a custom palette (matched by id) and leave it selected. */
    suspend fun upsert(palette: AppPalette) {
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_KEY]?.let { paletteGson.fromJson<List<AppPalette>>(it, paletteListType) } ?: emptyList()
            val saved = palette.copy(preset = false)
            prefs[CUSTOM_KEY] = paletteGson.toJson(current.filterNot { it.id == saved.id } + saved)
            prefs[SELECTED_KEY] = saved.id
        }
    }

    suspend fun delete(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_KEY]?.let { paletteGson.fromJson<List<AppPalette>>(it, paletteListType) } ?: emptyList()
            prefs[CUSTOM_KEY] = paletteGson.toJson(current.filterNot { it.id == id })
            if (prefs[SELECTED_KEY] == id) prefs[SELECTED_KEY] = DEFAULT_PALETTE.id
        }
    }

    /** Overwrites the selection and custom palettes, e.g. when restoring from a config backup. */
    suspend fun restore(selectedId: String, customPalettes: List<AppPalette>) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_KEY] = selectedId
            prefs[CUSTOM_KEY] = paletteGson.toJson(customPalettes)
        }
    }

    /** One-shot read of the selected palette, for non-Compose consumers (widget, service). */
    suspend fun current(): AppPalette = resolvePalette(selectedId.first(), customPalettes.first())
}

private val LOCATION_AWARE_KEY = booleanPreferencesKey("location_aware")
private val HIDE_FAR_STOPS_KEY = booleanPreferencesKey("hide_far_stops")
private val ROUTE_LINES_KEY = booleanPreferencesKey("route_lines")
private val ROUTE_LINE_DISTANCE_KEY = intPreferencesKey("route_line_distance_m")
private val BUS_DISPLAY_THRESHOLD_KEY = intPreferencesKey("bus_display_threshold_min")
private val FAR_STOP_THRESHOLD_KEY = intPreferencesKey("far_stop_threshold_m")

/** Default: favourites farther than this (metres) are dimmed + collapsed when hide-far is on. */
const val DEFAULT_FAR_STOP_THRESHOLD_M = 5000

/** Default: only render a bus marker once it's due within this many minutes. */
const val DEFAULT_BUS_DISPLAY_THRESHOLD_MIN = 60

/**
 * How far ahead of a bus its route line is drawn. Default from the data: city-centre stops sit
 * 135-380m apart, so 700m is roughly two stops.
 */
const val DEFAULT_ROUTE_LINE_DISTANCE_M = 700
const val MIN_ROUTE_LINE_DISTANCE_M = 100
const val MAX_ROUTE_LINE_DISTANCE_M = 3000

class SettingsStore(private val context: Context) {
    val locationAware: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[LOCATION_AWARE_KEY] ?: false
    }

    suspend fun setLocationAware(enabled: Boolean) {
        context.dataStore.edit { it[LOCATION_AWARE_KEY] = enabled }
    }

    val hideFarStops: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[HIDE_FAR_STOPS_KEY] ?: false
    }

    suspend fun setHideFarStops(enabled: Boolean) {
        context.dataStore.edit { it[HIDE_FAR_STOPS_KEY] = enabled }
    }

    /** Draw each shown bus's road geometry on the stop map. Off by default — several routes
     *  along one street overlap into a thick smear, so it's opt-in. */
    val routeLines: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ROUTE_LINES_KEY] ?: false
    }

    suspend fun setRouteLines(enabled: Boolean) {
        context.dataStore.edit { it[ROUTE_LINES_KEY] = enabled }
    }

    val routeLineDistanceM: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ROUTE_LINE_DISTANCE_KEY] ?: DEFAULT_ROUTE_LINE_DISTANCE_M
    }

    suspend fun setRouteLineDistanceM(metres: Int) {
        context.dataStore.edit {
            it[ROUTE_LINE_DISTANCE_KEY] = metres.coerceIn(MIN_ROUTE_LINE_DISTANCE_M, MAX_ROUTE_LINE_DISTANCE_M)
        }
    }

    val farStopThresholdM: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[FAR_STOP_THRESHOLD_KEY] ?: DEFAULT_FAR_STOP_THRESHOLD_M
    }

    suspend fun setFarStopThresholdM(metres: Int) {
        context.dataStore.edit { it[FAR_STOP_THRESHOLD_KEY] = metres }
    }

    val busDisplayThresholdMin: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BUS_DISPLAY_THRESHOLD_KEY] ?: DEFAULT_BUS_DISPLAY_THRESHOLD_MIN
    }

    suspend fun setBusDisplayThresholdMin(minutes: Int) {
        context.dataStore.edit { it[BUS_DISPLAY_THRESHOLD_KEY] = minutes }
    }

    /** Overwrites all settings, e.g. when restoring from a config backup. */
    suspend fun restore(
        locationAware: Boolean,
        hideFarStops: Boolean,
        busDisplayThresholdMin: Int,
        farStopThresholdM: Int,
    ) {
        context.dataStore.edit { prefs ->
            prefs[LOCATION_AWARE_KEY] = locationAware
            prefs[HIDE_FAR_STOPS_KEY] = hideFarStops
            prefs[BUS_DISPLAY_THRESHOLD_KEY] = busDisplayThresholdMin
            prefs[FAR_STOP_THRESHOLD_KEY] = farStopThresholdM
        }
    }
}
