package com.enderthor.kremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import timber.log.Timber

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class RemoteRepository(private val context: Context) {
    private val settingsKey = stringPreferencesKey("remote_config")


    val currentConfig: Flow<GlobalConfig> = context.dataStore.data
        .catch { exception ->
            Timber.e(exception, "Error loading config")
            emit(emptyPreferences())
        }
        .map { preferences ->
            try {
                val jsonString = preferences[settingsKey]
                if (jsonString != null) {
                    Json.decodeFromString<GlobalConfig>(jsonString)
                } else {
                    GlobalConfig()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error parsing config")
                GlobalConfig()
            }
        }


    fun getDevices(): Flow<List<RemoteDevice>> = currentConfig.map { it.devices }


    fun getActiveDevice(): Flow<RemoteDevice?> = currentConfig.map { config ->
        config.devices.find { it.isActive }
    }

    // Función de utilidad para obtener la configuración actual
    private suspend fun getCurrentConfig(): GlobalConfig {
        return try {
            val preferences = context.dataStore.data.first()
            val configString = preferences[settingsKey]
            Timber.d("Config actual leída: $configString")
            if (configString != null) {
                Json.decodeFromString(configString)
            } else {
                GlobalConfig() // Usa el constructor por defecto
            }
        } catch (e: Exception) {
            Timber.e(e, "Error leyendo configuración")
            throw e
        }
    }
    suspend fun deactivateAllDevices() {
        val config = currentConfig.firstOrNull() ?: GlobalConfig()
        val updatedDevices = config.devices.map { device ->
            if (device.isActive) device.copy(isActive = false) else device
        }

        context.dataStore.edit { preferences ->
            preferences[settingsKey] = Json.encodeToString(
                GlobalConfig.serializer(),
                config.copy(devices = updatedDevices)
            )
        }
    }


    suspend fun getDeviceById(id: String): RemoteDevice? {
        val config = currentConfig.firstOrNull() ?: GlobalConfig()
        return config.devices.find { it.id == id }
    }


    suspend fun addDevice(device: RemoteDevice) {
        try {
            context.dataStore.edit { preferences ->
                val currentConfig = getCurrentConfig()
                Timber.d("Config actual antes de añadir: $currentConfig")

                val updatedDevices = currentConfig.devices + device
                val updatedConfig = currentConfig.copy(devices = updatedDevices)

                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    updatedConfig
                )

                Timber.d("Config actualizada después de añadir: $updatedConfig")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error añadiendo dispositivo al DataStore")
            throw e
        }
    }

    suspend fun removeDevice(deviceId: String) {
        try {
            context.dataStore.edit { preferences ->
                val current = getCurrentConfig()
                val updatedDevices = current.devices.filter { it.id != deviceId }

                // Si eliminamos el dispositivo activo y hay otros dispositivos,
                // activamos el primero de la lista
                val finalDevices = if (current.devices.find { it.isActive }?.id == deviceId && updatedDevices.isNotEmpty()) {
                    updatedDevices.mapIndexed { index, device ->
                        device.copy(isActive = index == 0)
                    }
                } else {
                    updatedDevices
                }

                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    current.copy(devices = finalDevices)
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error removing device")
            throw e
        }
    }

    suspend fun setActiveDevice(deviceId: String) {
        try {
            context.dataStore.edit { preferences ->
                val current = getCurrentConfig()
                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    current.copy(
                        devices = current.devices.map { device ->
                            device.copy(isActive = device.id == deviceId)
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error setting active device")
            throw e
        }
    }

    suspend fun updateDeviceMapping(deviceId: String, mappings: RemoteSettings) {
        try {
            context.dataStore.edit { preferences ->
                val current = getCurrentConfig()
                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    current.copy(
                        devices = current.devices.map { device ->
                            if (device.id == deviceId) {
                                device.copy(keyMappings = mappings)
                            } else device
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating device mapping")
            throw e
        }
    }
}