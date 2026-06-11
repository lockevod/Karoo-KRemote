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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import timber.log.Timber


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class RemoteRepository(private val context: Context) {
    private val settingsKey = stringPreferencesKey("remote_config")

    // Json tolerante a schema drift. Con `Json` por defecto, renombrar o eliminar
    // un valor de enum (AntRemoteKey, KarooKey, PressType) o añadir un campo no
    // opcional hacía que decodeFromString lanzase SerializationException →
    // entrabas en el catch que devuelve GlobalConfig() vacío. La próxima edición
    // del usuario lo guardaba sobre el JSON original, perdiendo silenciosamente
    // toda su configuración. Con estas tres flags el parse sobrevive cambios
    // razonables de esquema sin destruir la config.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    // Cache del último (raw JSON -> GlobalConfig decodificado). currentConfig es un cold flow:
    // cada uno de los ~6 colectores de la app re-ejecutaba json.decodeFromString por cada
    // emisión de DataStore. Como el raw string es idéntico para todos los colectores tras un
    // mismo write, comparamos por igualdad de string ANTES de decodificar y reutilizamos el
    // objeto ya parseado. Volatile: lo leen/escriben colectores en hilos distintos; la
    // condición de carrera benigna (dos decodes simultáneos del mismo string) sólo desperdicia
    // un parse puntual, nunca devuelve datos incorrectos.
    @Volatile private var configCache: Pair<String, GlobalConfig>? = null

    val currentConfig: Flow<GlobalConfig> = context.dataStore.data
        .catch { exception ->
            Timber.e(exception, "Error loading config")
            emit(emptyPreferences())
        }
        .map { preferences ->
            try {
                val jsonString = preferences[settingsKey]
                if (jsonString != null) {
                    val cached = configCache
                    if (cached != null && cached.first == jsonString) {
                        cached.second
                    } else {
                        json.decodeFromString<GlobalConfig>(jsonString).also {
                            configCache = jsonString to it
                        }
                    }
                } else {
                    GlobalConfig()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error parsing config")
                GlobalConfig()
            }
        }

    fun getDevices(): Flow<List<RemoteDevice>> =
        currentConfig.map { it.devices }.distinctUntilChanged()

    fun getActiveDevice(): Flow<RemoteDevice?> = currentConfig.map { config ->
        config.devices.find { it.isActive }
    }.distinctUntilChanged()

    /**
     * Lee GlobalConfig directamente de las preferencias ya disponibles en el transform de edit{}.
     * Evita una segunda lectura de DataStore mientras se está editando.
     */
    private fun Preferences.getCurrentConfig(): GlobalConfig {
        return try {
            val configString = this[settingsKey]
            if (configString != null) json.decodeFromString(configString) else GlobalConfig()
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

                preferences[settingsKey] = json.encodeToString(
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

                preferences[settingsKey] = json.encodeToString(
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
                preferences[settingsKey] = json.encodeToString(
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
                
                preferences[settingsKey] = json.encodeToString(
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
                preferences[settingsKey] = json.encodeToString(
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

                preferences[settingsKey] = json.encodeToString(
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

    /**
     * Variante por lotes de [assignKeyCodeToCommand]: aplica varios mappings en UN solo
     * dataStore.edit (un único read-modify-write y un único parse/encode), en vez de una
     * edición por cada asignación. Replica exactamente la semántica de merge del método
     * individual (actualizar si existe (command, pressType); si no, añadir). Cada
     * [LearnedCommand] aporta su propio pressType.
     */
    suspend fun assignKeyCodesToCommands(
        deviceId: String,
        assignments: List<LearnedCommand>
    ) {
        if (assignments.isEmpty()) return
        try {
            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "DB_MAPPING_BATCH_START",
                details = "Assigning ${assignments.size} mappings for device $deviceId",
                source = "RemoteRepository"
            )

            context.dataStore.edit { preferences ->
                val current = preferences.getCurrentConfig()
                val updatedConfig = current.copy(
                    devices = current.devices.map { device ->
                        if (device.id == deviceId) {
                            val updatedCommands = device.learnedCommands.toMutableList()

                            for ((command, pressType, karooKey) in assignments) {
                                val existingCommandIndex = updatedCommands.indexOfFirst {
                                    it.command == command && it.pressType == pressType
                                }

                                if (existingCommandIndex >= 0) {
                                    val oldMapping = updatedCommands[existingCommandIndex].karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"
                                    updatedCommands[existingCommandIndex] = updatedCommands[existingCommandIndex].copy(karooKey = karooKey)

                                    DebugLogger.logConnectionEvent(
                                        deviceNumber = device.antDeviceId ?: 0,
                                        event = "DB_MAPPING_UPDATED",
                                        details = "Updated mapping for ${device.name}: $command ($pressType) changed from $oldMapping to ${karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"}",
                                        source = "RemoteRepository"
                                    )
                                } else {
                                    updatedCommands.add(LearnedCommand(command = command, pressType = pressType, karooKey = karooKey))

                                    DebugLogger.logConnectionEvent(
                                        deviceNumber = device.antDeviceId ?: 0,
                                        event = "DB_MAPPING_ADDED",
                                        details = "Added new mapping for ${device.name}: $command ($pressType) -> ${karooKey?.action?.let { it::class.simpleName } ?: "UNASSIGNED"}",
                                        source = "RemoteRepository"
                                    )
                                }
                            }

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

                preferences[settingsKey] = json.encodeToString(
                    GlobalConfig.serializer(),
                    updatedConfig
                )

                DebugLogger.logConnectionEvent(
                    deviceNumber = 0,
                    event = "DB_MAPPING_CONFIG_SAVED",
                    details = "Batch mapping configuration saved to DataStore successfully (${assignments.size} mappings)",
                    source = "RemoteRepository"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error assigning KeyCodes to commands (batch)")
            DebugLogger.logError("DB_MAPPING", "Error assigning batch mappings for device $deviceId", e, "RemoteRepository")
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
                preferences[settingsKey] = json.encodeToString(
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
                preferences[settingsKey] = json.encodeToString(
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