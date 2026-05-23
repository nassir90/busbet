package com.example.tfiapp

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val STOP_CODE_KEY = stringPreferencesKey("stopCode")
val STOP_NAME_KEY = stringPreferencesKey("stopName")

private val FG_DEFAULT = Color(0xFF6750A4)
private val FG_TFI = Color(0xFF003B8C)
private val BG_CARD = Color(0xFFFFFFFF)
private val TEXT_PRIMARY = Color(0xFF111111)
private val TEXT_SECONDARY = Color(0xFF666666)
private val TEXT_TERTIARY = Color(0xFF999999)
private val DIVIDER = Color(0xFFE6E0EC)
private val DUE_RED = Color(0xFFB00020)

class StopWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val code = prefs[STOP_CODE_KEY]
        val name = prefs[STOP_NAME_KEY]
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        val theme = runCatching { ThemeStore(context).flow.first() }.getOrDefault(AppTheme.DEFAULT)
        val accent = if (theme == AppTheme.TFI) FG_TFI else FG_DEFAULT

        val deps: List<Departure>? = if (code != null) {
            runCatching { Api.service.departures(code).departures.take(3) }.getOrNull()
        } else null

        val asOf = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        provideContent {
            WidgetUI(context, appWidgetId, code, name, deps, accent, asOf)
        }
    }

    @Composable
    private fun WidgetUI(
        context: Context,
        appWidgetId: Int,
        code: String?,
        name: String?,
        deps: List<Departure>?,
        accent: Color,
        asOf: String,
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
                .background(BG_CARD)
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
                        style = TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = 14.sp),
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
                            color = ColorProvider(TEXT_PRIMARY),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        "Stop $code · as of $asOf",
                        style = TextStyle(color = ColorProvider(TEXT_TERTIARY), fontSize = 10.sp),
                    )
                }
                Box(
                    modifier = GlanceModifier
                        .background(Color(0xFFF1ECF6))
                        .cornerRadius(20.dp)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<RefreshStopWidget>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "↻",
                        style = TextStyle(
                            color = ColorProvider(accent),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(GlanceModifier.height(8.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(DIVIDER)) {}
            Spacer(GlanceModifier.height(6.dp))

            when {
                deps == null -> Text(
                    "Could not load",
                    style = TextStyle(color = ColorProvider(DUE_RED), fontSize = 12.sp),
                )
                deps.isEmpty() -> Text(
                    "No upcoming departures",
                    style = TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = 12.sp),
                )
                else -> deps.forEach { d -> DepRow(d, accent) }
            }
        }
    }

    @Composable
    private fun DepRow(d: Departure, accent: Color) {
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
                    .background(accent)
                    .cornerRadius(6.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    d.routeShortName,
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.width(10.dp))
            Text(
                d.tripHeadsign,
                style = TextStyle(color = ColorProvider(TEXT_PRIMARY), fontSize = 13.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                dueLabel,
                style = TextStyle(
                    color = ColorProvider(if (due <= 0) DUE_RED else TEXT_PRIMARY),
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
