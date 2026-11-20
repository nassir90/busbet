package iompar.mpts.ie.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import iompar.mpts.ie.DepartureRow
import iompar.mpts.ie.DeparturesResponse
import iompar.mpts.ie.Favourite
import iompar.mpts.ie.SERVICE_ZONE
import iompar.mpts.ie.StopModeIcon
import iompar.mpts.ie.buildColorScheme
import iompar.mpts.ie.resolvePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Design proposals for the favourite card on narrow screens. These are not the app's card. They
 * are throwaway prototypes built from the app's own parts (DepartureRow, StopModeIcon, the
 * theme) and recorded departures, so the pictures in the design PR are real renders at a real
 * width rather than mock-ups.
 *
 *   ./gradlew :app:recordRoborazziPlayDebug --tests '*NarrowCardProposals*'
 *
 * writes build/screenshots/proposals/<name>.png. The app's current card at the same width comes
 * from NarrowScreenshots.home.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w320dp-h900dp-notnight-xxhdpi")
class NarrowCardProposals {
    @get:Rule val compose = createComposeRule()

    private val fixtures = FixtureGtfsApi()
    private val nowMins = Instant.ofEpochSecond(FIXTURE_AT_SEC).atZone(SERVICE_ZONE)
        .let { it.hour * 60 + it.minute }

    private val cards = listOf(
        Triple(Favourite(code = "3370", name = "Finnstown Abbey"), "350 m", false),
        Triple(Favourite(code = "7392", name = "Aston Quay", customName = "Work – Aston Quay (towards Lucan)"), "14 km", false),
        Triple(Favourite(code = "7796", name = "Somerton"), "1.2 km", true),
    )

    /** A: everything behind one ⋮ menu, top right. The name gets the whole row. */
    @Test fun overflowTopRight() = render("01-overflow-top-right") {
        cards.forEach { (f, dist, collapsed) ->
            ProtoCard(f, dist, collapsed) { Header(f, dist, menu = MenuAt.END) }
        }
    }

    /** A, with the menu open. */
    @Test fun overflowMenuOpen() = render("02-overflow-menu-open") {
        Box {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { (f, dist, collapsed) ->
                    ProtoCard(f, dist, collapsed) { Header(f, dist, menu = MenuAt.END) }
                }
            }
            OverflowMenu(Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 8.dp))
        }
    }

    /** A′: the same menu, top left, as first suggested. */
    @Test fun overflowTopLeft() = render("03-overflow-top-left") {
        cards.forEach { (f, dist, collapsed) ->
            ProtoCard(f, dist, collapsed) { Header(f, dist, menu = MenuAt.START) }
        }
    }

    /** B: no buttons at rest; swipe a card left to reveal Rename / Routes / Remove. */
    @Test fun swipeReveal() = render("04-swipe-reveal") {
        cards.forEachIndexed { i, (f, dist, collapsed) ->
            if (i == 0) {
                SwipedCard(f, dist, collapsed, reveal = 216.dp)
            } else {
                ProtoCard(f, dist, collapsed) { Header(f, dist, menu = MenuAt.NONE) }
            }
        }
    }

    /** C (recommended): ⋮ for everything, a chevron for collapse, swipe as the shortcut. */
    @Test fun recommended() = render("05-recommended") {
        cards.forEachIndexed { i, (f, dist, collapsed) ->
            if (i == 1) {
                SwipedCard(f, dist, collapsed, reveal = 144.dp, chevron = true, twoActions = true)
            } else {
                ProtoCard(f, dist, collapsed) { Header(f, dist, menu = MenuAt.END, chevron = true, collapsed = collapsed) }
            }
        }
    }

    /** C in reorder mode, entered from the ⋮ menu or a long press: handles appear, departures fold. */
    @Test fun recommendedReorder() = render("06-recommended-reorder") {
        cards.forEach { (f, dist, _) ->
            ProtoCard(f, dist, collapsed = true) { Header(f, dist, menu = MenuAt.NONE, handle = true) }
        }
        Text(
            "Done",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.End).padding(8.dp),
        )
    }

    // ── Prototype parts ─────────────────────────────────────────────────────

    private enum class MenuAt { START, END, NONE }

    private fun board(f: Favourite): DeparturesResponse = fixtures.recordedDepartures(f.code)

    @Composable
    private fun ProtoCard(
        f: Favourite,
        dist: String,
        collapsed: Boolean,
        modifier: Modifier = Modifier,
        header: @Composable () -> Unit,
    ) {
        ElevatedCard(modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(1.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = if (collapsed) 8.dp else 12.dp)) {
                header()
                if (!collapsed) {
                    board(f).departures.take(3).forEach { d ->
                        DepartureRow(d, nowMins = nowMins, onOpenRoute = {})
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(
        f: Favourite,
        dist: String,
        menu: MenuAt,
        chevron: Boolean = false,
        collapsed: Boolean = false,
        handle: Boolean = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (handle) {
                Icon(Icons.Filled.DragIndicator, contentDescription = "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            if (menu == MenuAt.START) {
                IconButton(onClick = {}, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
            } else {
                StopModeIcon(board(f).stop.routeTypes)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                // Two lines, then an ellipsis: a long custom name wraps by word, never mid-word.
                Text(
                    f.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (menu == MenuAt.START) {
                        StopModeIcon(board(f).stop.routeTypes, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (f.customName != null) "${f.code} · ${f.name}" else f.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(dist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (chevron) {
                IconButton(onClick = {}) {
                    Icon(
                        if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (collapsed) "Expand" else "Collapse",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (menu == MenuAt.END) {
                IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            }
        }
    }

    @Composable
    private fun OverflowMenu(modifier: Modifier) {
        Surface(
            modifier = modifier.widthIn(min = 196.dp),
            shape = MaterialTheme.shapes.extraSmall,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MenuRow(Icons.Filled.ExpandLess, "Collapse")
                MenuRow(Icons.Filled.Edit, "Rename")
                MenuRow(Icons.Filled.FilterList, "Choose routes…")
                MenuRow(Icons.Filled.SwapVert, "Reorder")
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuRow(Icons.Filled.Delete, "Remove", MaterialTheme.colorScheme.error)
            }
        }
    }

    @Composable
    private fun MenuRow(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** A card caught mid-swipe, with the actions it uncovers. */
    @Composable
    private fun SwipedCard(
        f: Favourite,
        dist: String,
        collapsed: Boolean,
        reveal: Dp,
        chevron: Boolean = false,
        twoActions: Boolean = false,
    ) {
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) {
            Row(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant),
                horizontalArrangement = Arrangement.End,
            ) {
                SwipeAction(Icons.Filled.Edit, "Rename", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                if (!twoActions) {
                    SwipeAction(Icons.Filled.FilterList, "Routes", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
                SwipeAction(Icons.Filled.Delete, "Remove", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
            }
            ProtoCard(f, dist, collapsed, Modifier.offset(x = -reveal)) {
                Header(f, dist, menu = if (chevron) MenuAt.END else MenuAt.NONE, chevron = chevron, collapsed = collapsed)
            }
        }
    }

    @Composable
    private fun SwipeAction(icon: ImageVector, label: String, bg: Color, fg: Color) {
        Column(
            Modifier.width(72.dp).fillMaxHeight().background(bg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, tint = fg)
            Spacer(Modifier.height(4.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }

    private fun render(name: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = buildColorScheme(resolvePalette(null, emptyList()), dark = false)) {
                Surface(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/proposals/$name.png")
    }
}

/** The app's real home screen at the same narrow width, for the "today" picture. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h900dp-notnight-xxhdpi")
class NarrowScreenshots : AppScreenshots("narrow")
