package iompar.mpts.ie.screenshots

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import iompar.mpts.ie.Api
import iompar.mpts.ie.AppClock
import iompar.mpts.ie.AppRoot
import iompar.mpts.ie.BackendConfigHolder
import iompar.mpts.ie.Departure
import iompar.mpts.ie.Favourite
import iompar.mpts.ie.FavouritesStore
import iompar.mpts.ie.FeatureRequestDraft
import iompar.mpts.ie.LocalMapTileProvider
import iompar.mpts.ie.PaletteCache
import iompar.mpts.ie.PaletteStore
import iompar.mpts.ie.PlaceKind
import iompar.mpts.ie.SavedPlace
import iompar.mpts.ie.SavedPlacesStore
import iompar.mpts.ie.SERVICE_ZONE
import iompar.mpts.ie.ThemeMode
import iompar.mpts.ie.Screen
import iompar.mpts.ie.WidgetStopRequest
import iompar.mpts.ie.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
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

/** Runs the app's own init, minus Sentry: nothing a screenshot needs, and it would phone home. */
class ScreenshotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BackendConfigHolder.init(this)
        Api.init(this)
        PaletteCache.init(this)
    }
}

/**
 * Every screen of the app, rendered on the JVM against data recorded at [FIXTURE_AT_SEC].
 *
 * Each test composes [AppRoot] — the same root the activity draws — placed on one screen, with
 * the clock pinned and the API answered from fixtures, then captures it. Light and dark run as
 * separate classes so each gets its own configuration from the start.
 *
 *   ./gradlew :app:recordRoborazziDebug --tests 'iompar.mpts.ie.screenshots.*'
 *
 * writes build/screenshots/<theme>/<screen>.png. A screen that asks for something with no
 * recording logs it to build/screenshot-fixture-misses.txt; run
 * scripts/record_screenshot_fixtures.py to fetch those, then render again.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class)
abstract class AppScreenshots(private val theme: String) {
    @get:Rule val compose = createComposeRule()

    private val fixtures = FixtureGtfsApi()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun pin() {
        AppClock.clock = Clock.fixed(Instant.ofEpochSecond(FIXTURE_AT_SEC), SERVICE_ZONE)
        Api.gtfsOverride = fixtures
        // Granted, as on a phone where the user said yes; otherwise the notifications page opens
        // under the "Allow exact alarms?" prompt and the screen itself can't be seen.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        runBlocking {
            // The app's DataStore is created once per process, not per test, so whatever the last
            // test set (a pinned theme, a palette, location sorting) would carry into this one.
            app.dataStore.edit { it.clear() }
            FavouritesStore(app).replaceAll(FAVOURITES)
        }
    }

    @After fun unpin() {
        AppClock.clock = Clock.systemDefaultZone()
        Api.gtfsOverride = null
    }

    @Test fun home() = render("01-home")

    @Test fun stopAstonQuay() = render("02-stop-aston-quay", listOf(Screen.StopBoard("7392")))

    @Test fun stopFinnstownAbbey() = render("03-stop-finnstown-abbey", listOf(Screen.StopBoard("3370")))

    @Test fun trip() {
        val d = liveDeparture("3370")
        render("04-trip", listOf(Screen.StopBoard("3370"), Screen.TripView(d.tripId, "3370")))
    }

    @Test fun route() {
        val d = liveDeparture("7392")
        render("05-route", listOf(Screen.RouteView(d.routeShortName, d.directionId)))
    }

    @Test fun settings() = render("06-settings", listOf(Screen.Settings))

    @Test fun notifications() = render("07-notifications", page = 1)

    /** Theme pinned against the phone: Dark on a light phone, Light on a dark one. */
    @Test fun themePinned() {
        val pinned = if (theme == "light") ThemeMode.DARK else ThemeMode.LIGHT
        runBlocking { PaletteStore(app).setThemeMode(pinned) }
        render("13-settings-theme-pinned", listOf(Screen.Settings))
    }

    @Test fun report() {
        val d = liveDeparture("7392")
        render(
            "08-report",
            listOf(
                Screen.Report(
                    routeShortName = d.routeShortName,
                    stopCode = "7392",
                    stopName = "Aston Quay",
                    tripId = d.tripId,
                    scheduledDeparture = d.scheduledDeparture,
                    estimatedDeparture = d.estimatedDeparture,
                )
            ),
        )
    }

    @Test fun featureRequest() = render(
        "09-feature-request",
        listOf(Screen.FeatureRequest(FeatureRequestDraft("Stop 7392", "{\"screen\":\"StopBoard\"}", null))),
    )

    /**
     * Feedback from Settings, end to end: the screen with nothing attached, out to the main menu
     * to pick a screen, and back with that screen attached and the typed text intact.
     */
    @Test fun feedbackFlow() {
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
        capture("10-feedback")

        compose.onNodeWithText("Tap to attach a screenshot").performClick()
        settle()
        capture("11-feedback-pick-screen")

        compose.onNodeWithText("Aston Quay", substring = true).performClick()
        settle()
        compose.onNodeWithContentDescription("Attach this screen to your feedback").performClick()
        settle()
        capture("12-feedback-attached")
    }

    /**
     * The journey planner, from a phone standing at home in Lucan to "Work" by Aston Quay: the
     * empty form, the options, and one itinerary on the map.
     */
    @Test fun journey() {
        runBlocking {
            SavedPlacesStore(app).replaceAll(SAVED_PLACES)
        }
        val lm = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(lm).setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).simulateLocation(android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = HOME.lat
            longitude = HOME.lon
            accuracy = 10f
            time = FIXTURE_AT_SEC * 1000
        })

        show(listOf(Screen.Journey))
        capture("14-journey")

        compose.onNodeWithText("Work").performClick()
        settle()
        capture("15-journey-options")

        compose.onAllNodesWithText(" – ", substring = true)[0].performClick()
        settle()
        capture("16-journey-itinerary")

        // Not captured: the save-place dialog. A text field inside a dialog never goes idle under
        // Robolectric (the same as the favourite rename dialog), though it is fine on a phone.
    }

    /** A departure with live data, so the trip and route screens open on something running. */
    private fun liveDeparture(code: String): Departure {
        val board = runCatching { fixtures.recordedDepartures(code) }.getOrNull()
        if (board == null) {
            // Not recorded yet: ask for it, and let this screen wait for the next run.
            runCatching { runBlocking { fixtures.departures(code) } }
        }
        assumeTrue("departures/$code not recorded yet", board != null)
        return board!!.departures.firstOrNull { it.realtime } ?: board.departures.first()
    }

    private fun render(name: String, stack: List<Screen> = emptyList(), page: Int = 0) {
        show(stack, page)
        capture(name)
    }

    private fun show(stack: List<Screen> = emptyList(), page: Int = 0) {
        compose.setContent {
            CompositionLocalProvider(LocalMapTileProvider provides { CachedTileProvider(it) }) {
                AppRoot(
                    seedPalette = PaletteCache.cached(app),
                    widgetStopRequest = remember { mutableStateOf<WidgetStopRequest?>(null) },
                    initialStack = stack,
                    initialPage = page,
                )
            }
        }
        settle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$theme/$name.png")
    }

    /**
     * Lets fetches land. Screens load on IO threads and post back to the main looper, which
     * Robolectric only drains when told to; poll loops sleep on that looper's clock, which doesn't
     * advance here, so this settles after the first load instead of spinning on refreshes.
     */
    private fun settle() {
        repeat(40) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    companion object {
        val HOME = SavedPlace(id = "home", label = "Home", kind = PlaceKind.HOME, lat = 53.3433, lon = -6.4498)
        val WORK = SavedPlace(id = "work", label = "Work", kind = PlaceKind.WORK, lat = 53.3468, lon = -6.2585)
        val SAVED_PLACES = listOf(HOME, WORK)

        /** The stops this was built around: a city-centre stop and the C1/C2 commute out to Lucan. */
        val FAVOURITES = listOf(
            Favourite(code = "7392", name = "Aston Quay"),
            Favourite(code = "3370", name = "Finnstown Abbey"),
            Favourite(code = "7796", name = "Somerton"),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
class LightScreenshots : AppScreenshots("light")

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class DarkScreenshots : AppScreenshots("dark")
