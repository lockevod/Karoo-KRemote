package com.enderthor.kremote.extension

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.enderthor.kremote.data.RemoteSettings
import com.enderthor.kremote.data.RemoteSettingsKaroo


val settingsKey = stringPreferencesKey("remote")
val settingsKeyKaroo = stringPreferencesKey("remoteKaroo")

suspend fun saveSettings(context: Context, settings: RemoteSettings) {
    context.dataStore.edit { t ->
        t[settingsKey] = Json.encodeToString(settings)
    }
}

suspend fun saveRemoteSettings(context: Context, settings: RemoteSettingsKaroo) {
    context.dataStore.edit { t ->
        t[settingsKeyKaroo] = Json.encodeToString(settings)
    }
}

fun Context.streamSettings(): Flow<RemoteSettings> {
    return dataStore.data.map { settingsJson: Preferences ->
        try {
            Json.decodeFromString<RemoteSettings>(
                settingsJson[settingsKey] ?: RemoteSettings.defaultSettings
            )
        } catch(e: Throwable){
            Json.decodeFromString<RemoteSettings>(RemoteSettings.defaultSettings)
        }
    }.distinctUntilChanged()
}

fun Context.streamRemoteSettings(): Flow<RemoteSettingsKaroo> {
    return dataStore.data.map { settingsJson: Preferences ->
        try {
            Json.decodeFromString<RemoteSettingsKaroo>(
                settingsJson[settingsKeyKaroo] ?: RemoteSettingsKaroo.defaultSettings
            )
        } catch(e: Throwable){
            Json.decodeFromString<RemoteSettingsKaroo>(RemoteSettingsKaroo.defaultSettings)
        }
    }.distinctUntilChanged()
}


fun KarooSystemService.streamRideState(): Flow<RideState> {
    return callbackFlow {
        val listenerId = addConsumer { rideState: RideState ->
            trySendBlocking(rideState)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

