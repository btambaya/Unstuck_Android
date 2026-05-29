package tech.csalliance.unstuck.surface

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import tech.csalliance.unstuck.MainActivity

// Glance "Start Next" home-screen widget — shows the current recommendation
// from the DataStore snapshot; tapping opens the app. Analog of the iOS
// WidgetKit Start Next widget.
class StartNextWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs: Preferences = context.startNextDataStore.data.first()
        val name = prefs[StartNextKeys.NAME]
        val estimate = prefs[StartNextKeys.ESTIMATE]
        provideContent { Content(name, estimate) }
    }

    @Composable
    private fun Content(name: String?, estimate: Int?) {
        val ink = ColorProvider(Color(0xFF2A2A33))
        val muted = ColorProvider(Color(0xFF8A8A95))
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Color(0xFFFAFAF7)).padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text("START NEXT", style = TextStyle(color = muted, fontSize = 11.sp, fontWeight = FontWeight.Medium))
            if (name == null) {
                Text("All clear", style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            } else {
                Text(name, style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                Text("${estimate ?: 25} min", style = TextStyle(color = muted, fontSize = 12.sp))
            }
        }
    }
}

class StartNextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StartNextWidget()
}
