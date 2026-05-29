package tech.csalliance.unstuck.surface

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// DataStore snapshot of the current "Start Next" recommendation, shared with
// the Glance widget. The app writes it whenever the recommendation changes;
// the widget reads it. Analog of the iOS UnstuckShared App Group snapshot.

val Context.startNextDataStore by preferencesDataStore(name = "start_next")

object StartNextKeys {
    val NAME = stringPreferencesKey("name")
    val ESTIMATE = intPreferencesKey("estimate")
}

suspend fun writeStartNext(context: Context, name: String?, estimateMin: Int?) {
    context.startNextDataStore.edit { prefs ->
        if (name == null) {
            prefs.remove(StartNextKeys.NAME)
            prefs.remove(StartNextKeys.ESTIMATE)
        } else {
            prefs[StartNextKeys.NAME] = name
            prefs[StartNextKeys.ESTIMATE] = estimateMin ?: 25
        }
    }
}
