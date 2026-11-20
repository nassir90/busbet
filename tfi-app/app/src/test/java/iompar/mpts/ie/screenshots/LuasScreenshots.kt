package iompar.mpts.ie.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import iompar.mpts.ie.CompactSearchRow
import iompar.mpts.ie.Departure
import iompar.mpts.ie.DepartureRow
import iompar.mpts.ie.StopModeIcon
import iompar.mpts.ie.Stop
import iompar.mpts.ie.buildColorScheme
import iompar.mpts.ie.resolvePalette
import iompar.mpts.ie.searchLabel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Luas in the search dropdown and on a stop board: line-coloured Red/Green pills, and each
 * platform of a stop labelled with where it goes. Rows are built from the shapes the live
 * backend returns for "Jervis" and "Connolly", next to a bus and a rail stop for contrast.
 *
 * Writes build/screenshots/luas/<theme>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xxhdpi")
class LuasScreenshots {
    @get:Rule val compose = createComposeRule()

    private val stops = listOf(
        Stop("8220GA00404", "998020", "Jervis", routes = listOf("Red"), routeTypes = listOf(0), towards = "Tallaght"),
        Stop("8220GA00405", "998020", "Jervis", routes = listOf("Red"), routeTypes = listOf(0), towards = "The Point"),
        Stop("8220GA00058", "998024", "St. Stephen's Green", routes = listOf("Green"), routeTypes = listOf(0), towards = "Brides Glen"),
        Stop("8220IR0007", "0", "Connolly", routes = listOf("Commuter", "DART"), routeTypes = listOf(2)),
        Stop("8220DB000292", "292", "Abbey St Lower", routes = listOf("33", "33E"), routeTypes = listOf(3)),
    )

    private fun dep(route: String, type: Int, headsign: String, at: String) = Departure(
        tripId = "$route-$at", stopSequence = 1, routeShortName = route, routeType = type,
        directionId = 0, tripHeadsign = headsign, scheduledDeparture = at,
        estimatedDeparture = at, delaySeconds = 0, realtime = true,
    )

    private val departures = listOf(
        dep("Red", 0, "Tallaght", "10:03"),
        dep("Green", 0, "Brides Glen", "10:05"),
        dep("37", 3, "Blanchardstown", "10:08"),
    )

    @Test fun light() = render("light", dark = false)

    @Test fun dark() = render("dark", dark = true)

    private fun render(name: String, dark: Boolean) {
        compose.setContent {
            MaterialTheme(colorScheme = buildColorScheme(resolvePalette(null, emptyList()), dark)) {
                Surface {
                    Column {
                        Text("Stops", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 8.dp))
                        stops.forEach { s ->
                            CompactSearchRow(
                                label = s.searchLabel,
                                services = s.routes,
                                trailing = { StopModeIcon(s.routeTypes) },
                                onClick = {},
                            )
                            HorizontalDivider()
                        }
                        Text("Departures", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 8.dp))
                        departures.forEach { DepartureRow(it, nowMins = 10 * 60, onOpenRoute = {}) }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/luas/$name.png")
    }
}
