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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import com.github.takahirom.roborazzi.captureRoboImage
import iompar.mpts.ie.Api
import iompar.mpts.ie.AppClock
import iompar.mpts.ie.AppRoot
import iompar.mpts.ie.Favourite
import iompar.mpts.ie.FavouritesStore
import iompar.mpts.ie.FeatureRequestDraft
import iompar.mpts.ie.LocalMapTileProvider
import iompar.mpts.ie.PaletteCache
import iompar.mpts.ie.PaletteStore
import iompar.mpts.ie.SERVICE_ZONE
import iompar.mpts.ie.SavedPlacesStore
import iompar.mpts.ie.Screen
import iompar.mpts.ie.SettingsStore
import iompar.mpts.ie.WidgetStopRequest
import iompar.mpts.ie.dataStore
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
 * The screens on the website (scripts/gen-site.py), in the Green palette and light theme, as a
 * commuter at home in Lucan sees them: favourites sorted by distance, home and work saved.
 *
 * Writes build/screenshots/site/<screen>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
class SiteScreenshots {
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
            SavedPlacesStore(app).replaceAll(AppScreenshots.SAVED_PLACES)
            SettingsStore(app).setLocationAware(true)
            PaletteStore(app).select(GREEN)
        }

        // Standing at "Home": the journey planner starts from here, and its recorded plan is keyed on it.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = AppScreenshots.HOME.lat
                longitude = AppScreenshots.HOME.lon
                accuracy = 10f
                time = FIXTURE_AT_SEC * 1000
            }
        )
    }

    @After fun tearDown() {
        AppClock.clock = Clock.systemDefaultZone()
        Api.gtfsOverride = null
    }

    @Test fun home() = render("01-home")

    @Test fun stop() = render("02-stop", listOf(Screen.StopBoard("7392")))

    @Test fun trip() {
        val board = fixtures.recordedDepartures("3370")
        val d = board.departures.firstOrNull { it.realtime } ?: board.departures.first()
        render("03-trip", listOf(Screen.StopBoard("3370"), Screen.TripView(d.tripId, "3370")))
    }

    @Test fun route() {
        val board = fixtures.recordedDepartures("7392")
        val d = board.departures.firstOrNull { it.realtime } ?: board.departures.first()
        render("04-route", listOf(Screen.RouteView(d.routeShortName, d.directionId)))
    }

    @Test fun journey() {
        show(listOf(Screen.Journey))
        compose.onNodeWithText("Work").performClick()
        settle()
        capture("05-journey-options")
        compose.onAllNodesWithText(" – ", substring = true)[0].performClick()
        settle()
        capture("06-journey-itinerary")
    }

    @Test fun settings() = render("07-settings", listOf(Screen.Settings))

    @Test fun feedback() {
        show(
            listOf(
                Screen.Settings,
                Screen.FeatureRequest(
                    FeatureRequestDraft(screen = "General feedback", context = "{}", screenshot = null, title = "Feedback")
                ),
            ),
        )
        compose.onNodeWithText("What should change?").performTextInput("The departures list should show platform numbers.")
        settle()
        capture("08-feedback")
    }

    private fun render(name: String, stack: List<Screen> = emptyList()) {
        show(stack)
        capture(name)
    }

    private fun show(stack: List<Screen>) {
        compose.setContent {
            CompositionLocalProvider(LocalMapTileProvider provides { CachedTileProvider(it) }) {
                AppRoot(
                    seedPalette = PaletteCache.cached(app),
                    widgetStopRequest = remember { mutableStateOf<WidgetStopRequest?>(null) },
                    initialStack = stack,
                )
            }
        }
        settle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/site/$name.png")
    }

    private fun settle() {
        repeat(40) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private companion object {
        const val GREEN = "preset-green"
    }
}
