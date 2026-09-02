package io.github.lightheaded.lugu.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.MainActivity
import io.github.lightheaded.lugu.core.model.formatLengthCompact

/**
 * What lugu puts on a home screen: the one thing to carry on with.
 *
 * ## Why this shows one book and not a list
 *
 * A widget is read at arm's length and tapped once. Tom's own argument about the car
 * applies here word for word — *"I never use the car UI to discover what I'm going to
 * listen next. It's always to continue something."* A home screen is the same kind of
 * surface, so the widget answers the same question the Continue shelf does, from the same
 * query, and stops there.
 *
 * ## Why the buttons broadcast
 *
 * Play and pause go out as the same broadcasts an automation app sends, through
 * `AutomationReceiver`. That receiver already parses, checks and carries out a transport
 * command, and already survives being called with no player running. A widget with its own
 * route to the player would be a second such path, and the second one is always the one
 * that stops working — see `applyRememberedSpeed`, which had exactly that shape.
 *
 * The action names carry the application id, so a debug build's widget drives the debug
 * build and nothing else.
 *
 * ## Not proven
 *
 * No part of this has been on a home screen. Glance renders through `RemoteViews`, which
 * has no host outside a real launcher, so there is no test here that would mean anything.
 * See docs/M4-PLAN.md.
 */
class ContinueWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
        val items = WidgetState.from(context).items()
        provideContent {
            val item by items.collectAsStateWithLifecycle(initialValue = null)
            GlanceTheme {
                WidgetBody(item)
            }
        }
    }
}

/** The receiver named in the manifest. Holds nothing; the widget above is the whole of it. */
class ContinueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueWidget()
}

/** One book, reduced to what fits on a widget. */
data class WidgetItem(
    val libraryItemId: String,
    val title: String,
    val author: String?,
    val progressFraction: Float,
    val remainingSeconds: Double,
)

@androidx.compose.runtime.Composable
private fun WidgetBody(item: WidgetItem?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (item == null) {
            // Named rather than left blank. A widget with nothing in it reads as a widget
            // that failed, and the reason here is ordinary: nothing has been started yet.
            Text(
                text = "Nothing started yet",
                style = TextStyle(color = GlanceTheme.colors.onSurface),
            )
            Text(
                text = "Open lugu to pick a book",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
            return@Column
        }

        Text(
            text = item.title,
            maxLines = 2,
            style = TextStyle(color = GlanceTheme.colors.onSurface),
        )
        item.author?.let {
            Text(text = it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
        }

        Spacer(GlanceModifier.height(8.dp))
        LinearProgressIndicator(
            progress = item.progressFraction,
            modifier = GlanceModifier.fillMaxWidth(),
            color = GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )

        Spacer(GlanceModifier.height(8.dp))
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            // One button, and it toggles. A widget has no way to know whether the player
            // is playing right now — the state it renders came from Room — so two separate
            // buttons would mean one of them was always the wrong one to press.
            Text(
                text = "Play / pause",
                style = TextStyle(color = GlanceTheme.colors.primary),
                modifier = GlanceModifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(actionSendBroadcast(WidgetActions.playPause(LocalContext.current))),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "${formatLengthCompact(item.remainingSeconds)} left",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/**
 * The broadcast the widget's button sends.
 *
 * Built from the application id at run time rather than written out, because the two
 * builds answer to two different action names on purpose and a literal here would make the
 * debug widget drive the release app.
 */
internal object WidgetActions {
    fun playPause(context: Context): android.content.Intent =
        android.content.Intent("${context.packageName}.action.PLAY_PAUSE")
            .setPackage(context.packageName)
}

