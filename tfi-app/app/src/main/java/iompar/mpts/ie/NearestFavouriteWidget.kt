package iompar.mpts.ie

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class NearestPalette(
    val cardBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val dueRed: Color,
    val refreshBg: Color,
    val routePillText: Color,
    val accent: Color,
)

private fun glanceNearestPalette(p: ColorSet): NearestPalette = NearestPalette(
    cardBg = Color(p.surface),
    textPrimary = Color(p.onSurface),
    textSecondary = Color(p.onSurfaceVariant),
    textTertiary = Color(blend(p.surface, p.onSurfaceVariant, 0.6f)),
    divider = Color(blend(p.surface, p.onSurface, 0.12f)),
    dueRed = Color(p.error),
    refreshBg = Color(blend(p.surface, p.onSurface, 0.06f)),
    routePillText = Color(p.onPrimary),
    accent = Color(p.primary),
)

private fun formatDistance(metres: Float): String =
    if (metres < 1000) "${metres.toInt()} m" else "%.1f km".format(metres / 1000f)

/** One favourite stop plus what we managed to load for it. */
private data class NearestStop(
    val favourite: Favourite,
    val departures: List<Departure>?,
    val distanceMetres: Float?,
)

/**
 * Shows live times for the nearest favourite stop, or the nearest two when more than one
 * favourite falls within the configured nearby radius. Unlike [StopWidget], this widget isn't
 * pinned to a single stop chosen via a configuration screen — it always tracks whichever
 * favourite(s) are currently closest.
 */
class NearestFavouriteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val favourites = FavouritesStore(context).flow.first()
        val farStopThresholdM = SettingsStore(context).farStopThresholdM.first()
        val location = runCatching { LocationProvider.current(context) }.getOrNull()

        val withDistance = favourites.map { fav ->
            val distance = if (location != null && fav.lat != null && fav.lon != null) {
                LocationProvider.distanceMeters(location.latitude, location.longitude, fav.lat, fav.lon)
            } else null
            fav to distance
        }

        val ordered = if (location != null) {
            withDistance.sortedBy { (_, distance) -> distance ?: Float.MAX_VALUE }
        } else withDistance

        // Normally just the nearest stop; show a second when more than one favourite is
        // currently within the nearby radius, mirroring the "active window" used elsewhere
        // (e.g. the auto-hide-far-stops threshold) to decide what's close enough to matter.
        val withinWindow = ordered.count { (_, distance) -> distance != null && distance <= farStopThresholdM }
        val showCount = if (location != null && withinWindow >= 2) 2 else 1
        val nearest = ordered.take(showCount)

        // Per stop: a failed fetch falls back to that stop's last good result, flagged stale,
        // rather than blanking it. One stop failing no longer wipes the others either.
        val staleLabels = HashMap<String, String>()
        val stops = nearest.map { (fav, distance) ->
            val fresh = runCatching { Api.service.departures(fav.code).departures.take(3) }.getOrNull()
            if (fresh != null) {
                WidgetCache.save(context, fav.code, fresh)
                NearestStop(fav, fresh, distance)
            } else {
                val cached = WidgetCache.load(context, fav.code)
                cached?.let { staleLabels[fav.code] = "stale · ${WidgetCache.ageLabel(it.ageMinutes)}" }
                NearestStop(fav, cached?.departures, distance)
            }
        }

        val appPalette = runCatching { PaletteStore(context).current() }.getOrDefault(DEFAULT_PALETTE)
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pal = glanceNearestPalette(appPalette.faceFor(isDark))

        val asOf = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        provideContent {
            WidgetUI(context, stops, pal, asOf, staleLabels)
        }
    }

    @Composable
    private fun WidgetUI(
        context: Context,
        stops: List<NearestStop>,
        pal: NearestPalette,
        asOf: String,
        staleLabels: Map<String, String> = emptyMap(),
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(pal.cardBg)
                .cornerRadius(20.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable(actionStartActivity(openIntent)),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        "Nearest favourites",
                        style = TextStyle(
                            color = ColorProvider(pal.textPrimary),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        "as of $asOf",
                        style = TextStyle(color = ColorProvider(pal.textTertiary), fontSize = 10.sp),
                    )
                }
                Box(
                    modifier = GlanceModifier
                        .background(pal.refreshBg)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<RefreshNearestFavouriteWidget>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "↻",
                        style = TextStyle(
                            color = ColorProvider(pal.accent),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(GlanceModifier.height(8.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(pal.divider)) {}
            Spacer(GlanceModifier.height(6.dp))

            if (stops.isEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Add a favourite stop to see it here",
                        style = TextStyle(color = ColorProvider(pal.textSecondary), fontSize = 14.sp),
                    )
                }
                return@Column
            }

            stops.forEachIndexed { index, stop ->
                if (index > 0) {
                    Spacer(GlanceModifier.height(6.dp))
                    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(pal.divider)) {}
                    Spacer(GlanceModifier.height(6.dp))
                }
                StopBlock(stop, pal, staleLabels[stop.favourite.code])
            }
        }
    }

    @Composable
    private fun StopBlock(stop: NearestStop, pal: NearestPalette, staleLabel: String? = null) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stop.favourite.displayName,
                style = TextStyle(
                    color = ColorProvider(pal.textPrimary),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (stop.distanceMetres != null) {
                Text(
                    formatDistance(stop.distanceMetres),
                    style = TextStyle(color = ColorProvider(pal.accent), fontSize = 11.sp),
                )
            }
        }

        staleLabel?.let {
            Text(it, style = TextStyle(color = ColorProvider(pal.dueRed), fontSize = 10.sp))
            Spacer(GlanceModifier.height(4.dp))
        }

        when {
            stop.departures == null -> Text(
                "Could not load",
                style = TextStyle(color = ColorProvider(pal.dueRed), fontSize = 12.sp),
            )
            stop.departures.isEmpty() -> Text(
                "No upcoming departures",
                style = TextStyle(color = ColorProvider(pal.textSecondary), fontSize = 12.sp),
            )
            else -> stop.departures.forEach { d -> DepRow(d, pal) }
        }
    }

    @Composable
    private fun DepRow(d: Departure, pal: NearestPalette) {
        val nowMins = LocalTime.now().let { it.hour * 60 + it.minute }
        val effective = d.estimatedDeparture ?: d.scheduledDeparture
        val parts = effective.split(":").map { it.toInt() }
        val due = (parts[0] * 60 + parts[1]) - nowMins
        val dueLabel = when {
            due <= 0 -> "Due"
            due == 1 -> "1 min"
            else -> "$due min"
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .background(pal.accent)
                    .cornerRadius(6.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    d.routeShortName,
                    style = TextStyle(
                        color = ColorProvider(pal.routePillText),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.width(10.dp))
            Text(
                d.tripHeadsign,
                style = TextStyle(color = ColorProvider(pal.textPrimary), fontSize = 13.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                dueLabel,
                style = TextStyle(
                    color = ColorProvider(if (due <= 0) pal.dueRed else pal.textPrimary),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

class RefreshNearestFavouriteWidget : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        NearestFavouriteWidget().update(context, glanceId)
    }
}

class NearestFavouriteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NearestFavouriteWidget()
}
