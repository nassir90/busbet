package iompar.mpts.ie

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The Material glyph for a transit mode: bus, tram (Luas) or train (rail). */
val TransitMode.icon: ImageVector
    get() = when (this) {
        TransitMode.BUS -> Icons.Filled.DirectionsBus
        TransitMode.TRAM -> Icons.Filled.Tram
        TransitMode.TRAIN -> Icons.Filled.Train
    }

/**
 * A small mode logo (bus / Luas tram / Irish Rail train) for a stop, chosen from its route_types.
 * Returns nothing when [routeTypes] is null or empty — i.e. an old backend or a board that hasn't
 * loaded yet — so a card doesn't flash a wrong-mode default before the real data arrives. Once the
 * data is in, a bus stop shows the bus glyph like everything else.
 */
@Composable
fun StopModeIcon(
    routeTypes: List<Int>?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (routeTypes.isNullOrEmpty()) return
    val mode = TransitMode.ofStop(routeTypes)
    Icon(mode.icon, contentDescription = mode.label, tint = tint, modifier = modifier.size(18.dp))
}

/** Mode logo for a single service, from its route_type. Always renders (bus is the fallback). */
@Composable
fun ServiceModeIcon(
    routeType: Int?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val mode = TransitMode.of(routeType)
    Icon(mode.icon, contentDescription = mode.label, tint = tint, modifier = modifier.size(18.dp))
}
