package iompar.mpts.ie

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
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
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first

val STOP_CODE_KEY = stringPreferencesKey("stopCode")
val STOP_NAME_KEY = stringPreferencesKey("stopName")

private data class Palette(
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

private fun glancePalette(p: ColorSet): Palette = Palette(
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

class StopWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val code = prefs[STOP_CODE_KEY]
        val name = prefs[STOP_NAME_KEY]
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        val appPalette = runCatching { PaletteStore(context).current() }.getOrDefault(DEFAULT_PALETTE)
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pal = glancePalette(appPalette.faceFor(isDark))

        // A failed fetch must not destroy what's on screen. Fall back to the last good result
        // and mark it stale rather than blanking the widget.
        val fresh: DeparturesResponse? = if (code != null) {
            runCatching { Api.service().departures(code) }.getOrNull()
        } else null
        val freshDeps = fresh?.departures?.take(3)
        if (freshDeps != null && code != null) WidgetCache.save(context, code, freshDeps, fresh?.feedTimestamp)

        val cached = if (fresh == null && code != null) WidgetCache.load(context, code) else null
        val deps = freshDeps ?: cached?.departures
        val staleLabel = cached?.let { "stale · ${WidgetCache.ageLabel(it.ageMinutes)}" }

        // The snapshot behind the times on screen, whether that's the one just fetched or the one
        // still sitting in the cache — never the moment this refresh happened to run.
        val asOf = asOfLabel(fresh?.feedTimestamp ?: cached?.at)

        provideContent {
            WidgetUI(context, appWidgetId, code, name, deps, pal, asOf, staleLabel)
        }
    }

    @Composable
    private fun WidgetUI(
        context: Context,
        appWidgetId: Int,
        code: String?,
        name: String?,
        deps: List<Departure>?,
        pal: Palette,
        asOf: String,
        staleLabel: String?,
    ) {
        val openIntent = if (code == null) {
            Intent(context, StopWidgetConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                putExtra("stopCode", code)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(pal.cardBg)
                .cornerRadius(20.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable(actionStartActivity(openIntent)),
        ) {
            if (code == null) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Tap to choose a stop",
                        style = TextStyle(color = ColorProvider(pal.textSecondary), fontSize = 14.sp),
                    )
                }
                return@Column
            }

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        name ?: "Stop $code",
                        style = TextStyle(
                            color = ColorProvider(pal.textPrimary),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        "Stop $code · as of $asOf",
                        style = TextStyle(color = ColorProvider(pal.textTertiary), fontSize = 10.sp),
                    )
                }
                Box(
                    modifier = GlanceModifier
                        .background(pal.refreshBg)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<RefreshStopWidget>()),
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

            if (staleLabel != null) {
                Text(
                    staleLabel,
                    style = TextStyle(color = ColorProvider(pal.dueRed), fontSize = 10.sp),
                )
                Spacer(GlanceModifier.height(4.dp))
            }

            when {
                deps == null -> Text(
                    "Could not load",
                    style = TextStyle(color = ColorProvider(pal.dueRed), fontSize = 12.sp),
                )
                deps.isEmpty() -> Text(
                    "No upcoming departures",
                    style = TextStyle(color = ColorProvider(pal.textSecondary), fontSize = 12.sp),
                )
                else -> deps.forEach { d -> DepRow(d, pal) }
            }
        }
    }

    @Composable
    private fun DepRow(d: Departure, pal: Palette) {
        val effective = d.estimatedDeparture ?: d.scheduledDeparture
        val parts = effective.split(":").map { it.toInt() }
        val due = minutesUntil(parts[0] * 60 + parts[1], serviceNowMinutes())
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

class RefreshStopWidget : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        StopWidget().update(context, glanceId)
    }
}

class StopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StopWidget()
}
