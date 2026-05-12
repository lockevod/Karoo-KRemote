package com.enderthor.kremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enderthor.kremote.utils.DebugLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    /**
     * Lee GlobalConfig directamente de las preferencias ya disponibles en el transform de edit{}.
     * Evita una segunda lectura de DataStore mientras se está editando.
     */
    private fun Preferences.getCurrentConfig(): GlobalConfig {
        return try {
            val configString = this[settingsKey]
            if (configString != null) Json.decodeFromString(configString) else GlobalConfig()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing config inside edit transform")
            GlobalConfig()
        }
    }


    suspend fun addDevice(device: RemoteDevice) {
        try {
            context.dataStore.edit { preferences ->
                val currentConfig = preferences.getCurrentConfig()
                Timber.d("Current config before adding: $currentConfig")

                val updatedDevices = currentConfig.devices + device
                val updatedConfig = currentConfig.copy(devices = updatedDevices)

                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    updatedConfig
                )

                Timber.d("Updated config after adding: $updatedConfig")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error adding device to DataStore")
            throw e
        }
    }

    suspend fun removeDevice(deviceId: String) {
        try {
            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedDevices = current.devices.filter { it.id != deviceId }

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
                val current = preferences.getCurrentConfig()
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

    suspend fun clearDeviceCommands(deviceId: String) {
        try {
            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedDevices = current.devices.map { device ->
                    if (device.id == deviceId) {
                        device.copy(learnedCommands = mutableListOf())
                    } else {
                        device
                    }
                }
                
                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    current.copy(devices = updatedDevices)
                )
                
                Timber.d("🗑️ [RemoteRepository] Learned commands cleared for device: $deviceId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating learned command")
            throw e
        }
    }

   suspend fun updateLearnedCommand(deviceId: String, command: AntRemoteKey, pressType: PressType = PressType.SINGLE) {
        try {
            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "DB_UPDATE_START",
                details = "Updating learned command: $command ($pressType) for device $deviceId",
                source = "RemoteRepository"
            )

            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedDevices = current.devices.map { device ->
                    if (device.id == deviceId) {
                        val commandExists = device.learnedCommands.any {
                            it.command == command && it.pressType == pressType
                        }

                        if (!commandExists) {
                            val newCommand = LearnedCommand(command = command, pressType = pressType)
                            val updatedCommands = (device.learnedCommands + newCommand).toMutableList()

                            DebugLogger.logConnectionEvent(
                                deviceNumber = device.antDeviceId ?: 0,
                                event = "DB_COMMAND_ADDED",
                                details = "Added new command: $command ($pressType). Total commands for ${device.name}: ${updatedCommands.size}",
                                source = "RemoteRepository"
                            )

                            // Log detallado de todos los comandos del dispositivo
                            DebugLogger.logConnectionEvent(
                                deviceNumber = device.antDeviceId ?: 0,
                                event = "DB_DEVICE_COMMANDS",
                                details = "All commands for ${device.name}: ${
                                    updatedCommands.joinToString(
                                        ", "
                                    ) { "${it.command.name}(${it.pressType})" }
                                }",
                                source = "RemoteRepository"
                            )

                            device.copy(learnedCommands = updatedCommands)
                        } else {
                            DebugLogger.logConnectionEvent(
                                deviceNumber = device.antDeviceId ?: 0,
                                event = "DB_COMMAND_EXISTS",
                                details = "Command already exists: $command ($pressType) for device ${device.name}",
                                source = "RemoteRepository"
                            )
                            device
                        }
                    } else {
                        device
                    }
                }

                val updatedConfig = current.copy(devices = updatedDevices)
                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    updatedConfig
                )

                DebugLogger.logConnectionEvent(
                    deviceNumber = 0,
                    event = "DB_CONFIG_SAVED",
                    details = "Configuration saved to DataStore. Total devices: ${updatedConfig.devices.size}",
                    source = "RemoteRepository"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating learned command")
            throw e
        }
    }

    suspend fun assignKeyCodeToCommand(
        deviceId: String,
        command: AntRemoteKey,
        karooKey: KarooKey?,
        pressType: PressType
    ) {
        try {
            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "DB_MAPPING_START",
                details = "Assigning mapping: $command ($pressType) -> ${karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"} for device $deviceId",
                source = "RemoteRepository"
            )

            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedConfig = current.copy(
                    devices = current.devices.map { device ->
                        if (device.id == deviceId) {

                            val existingCommandIndex = device.learnedCommands.indexOfFirst {
                                it.command == command && it.pressType == pressType
                            }

                            val updatedCommands = device.learnedCommands.toMutableList()

                            if (existingCommandIndex >= 0) {
                                // Actualizar comando existente
                                val oldMapping = updatedCommands[existingCommandIndex].karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"
                                updatedCommands[existingCommandIndex] = updatedCommands[existingCommandIndex].copy(karooKey = karooKey)

                                DebugLogger.logConnectionEvent(
                                    deviceNumber = device.antDeviceId ?: 0,
                                    event = "DB_MAPPING_UPDATED",
                                    details = "Updated mapping for ${device.name}: $command ($pressType) changed from $oldMapping to ${karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"}",
                                    source = "RemoteRepository"
                                )
                            } else {
                                // Add new command
                                updatedCommands.add(LearnedCommand(command = command, pressType = pressType, karooKey = karooKey))

                                DebugLogger.logConnectionEvent(
                                    deviceNumber = device.antDeviceId ?: 0,
                                    event = "DB_MAPPING_ADDED",
                                    details = "Added new mapping for ${device.name}: $command ($pressType) -> ${karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"}",
                                    source = "RemoteRepository"
                                )
                            }

                            // Log final de todas las configuraciones del dispositivo
                            DebugLogger.logConnectionEvent(
                                deviceNumber = device.antDeviceId ?: 0,
                                event = "DB_DEVICE_MAPPINGS",
                                details = "All mappings for ${device.name}: ${
                                    updatedCommands.joinToString(
                                        ", "
                                    ) { "${it.command.name}(${it.pressType}) -> ${it.karooKey?.action?.let { action -> action::class.simpleName } ?: "UNASSIGNED"}" }
                                }",
                                source = "RemoteRepository"
                            )

                            device.copy(learnedCommands = updatedCommands)
                        } else device
                    }
                )

                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    updatedConfig
                )

                DebugLogger.logConnectionEvent(
                    deviceNumber = 0,
                    event = "DB_MAPPING_CONFIG_SAVED",
                    details = "Mapping configuration saved to DataStore successfully",
                    source = "RemoteRepository"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error assign KeyCode to command")
            DebugLogger.logError("DB_MAPPING", "Error assigning mapping: $command ($pressType) -> ${karooKey?.action?.let { it::class.simpleName }}", e, "RemoteRepository")
            throw e
        }
    }

    suspend fun updateDeviceProperty(deviceId: String, update: (RemoteDevice) -> RemoteDevice) {
        try {
            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedDevices = current.devices.map { device ->
                    if (device.id == deviceId) {
                        update(device)
                    } else {
                        device
                    }
                }
                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    current.copy(devices = updatedDevices)
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating device property")
            throw e
        }
    }


    suspend fun updateGlobalSetting(update: (GlobalSettings) -> GlobalSettings) {
        try {
            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedSettings = update(current.globalSettings)
                preferences[settingsKey] = Json.encodeToString(
                    GlobalConfig.serializer(),
                    current.copy(globalSettings = updatedSettings)
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating global configuration")
            throw e
        }
    }
}