package iompar.mpts.ie.screenshots

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import iompar.mpts.ie.Api
import iompar.mpts.ie.AppClock
import iompar.mpts.ie.AppRoot
import iompar.mpts.ie.Favourite
import iompar.mpts.ie.FavouritesStore
import iompar.mpts.ie.LocalMapTileProvider
import iompar.mpts.ie.PaletteCache
import iompar.mpts.ie.PaletteStore
import iompar.mpts.ie.SERVICE_ZONE
import iompar.mpts.ie.Screen
import iompar.mpts.ie.SettingsStore
import iompar.mpts.ie.WidgetStopRequest
import iompar.mpts.ie.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant

/**
 * The three screens in the tester invite email, in the Green palette: home as a commuter in Lucan
 * sees it (sorted by distance, so no reorder handles), a stop page with its map, and a trip.
 *
 * Writes build/screenshots/invite/<screen>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
class InviteScreenshots {
    @get:Rule val compose = createComposeRule()

    private val fixtures = FixtureGtfsApi()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        AppClock.clock = Clock.fixed(Instant.ofEpochSecond(FIXTURE_AT_SEC), SERVICE_ZONE)
        Api.gtfsOverride = fixtures
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        // Favourites carry the coordinates their recorded boards report, so distances are real.
        val favourites = listOf("3370" to "Finnstown Abbey", "7796" to "Somerton", "7392" to "Aston Quay").map { (code, name) ->
            val stop = fixtures.recordedDepartures(code).stop
            Favourite(code = code, name = name, lat = stop.stopLat, lon = stop.stopLon)
        }
        runBlocking {
            // Settings left by other tests share this process's DataStore; start from nothing.
            app.dataStore.edit { it.clear() }
            FavouritesStore(app).replaceAll(favourites)
            SettingsStore(app).setLocationAware(true)
            PaletteStore(app).select(GREEN)
        }

        // A fix a few hundred metres from Finnstown Abbey.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 53.3420
                longitude = -6.4520
                accuracy = 20f
                time = FIXTURE_AT_SEC * 1000
            }
        )
    }

    @After fun tearDown() {
        AppClock.clock = Clock.systemDefaultZone()
        Api.gtfsOverride = null
    }

    @Test fun home() = render("home")

    @Test fun stop() = render("stop", listOf(Screen.StopBoard("7392")))

    @Test fun trip() {
        val board = fixtures.recordedDepartures("3370")
        val d = board.departures.firstOrNull { it.realtime } ?: board.departures.first()
        render("trip", listOf(Screen.StopBoard("3370"), Screen.TripView(d.tripId, "3370")))
    }

    private fun render(name: String, stack: List<Screen> = emptyList()) {
        compose.setContent {
            CompositionLocalProvider(LocalMapTileProvider provides { CachedTileProvider(it) }) {
                AppRoot(
                    seedPalette = PaletteCache.cached(app),
                    widgetStopRequest = remember { mutableStateOf<WidgetStopRequest?>(null) },
                    initialStack = stack,
                )
            }
        }
        repeat(40) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/screenshots/invite/$name.png")
    }

    private companion object {
        const val GREEN = "preset-green"
    }
}
