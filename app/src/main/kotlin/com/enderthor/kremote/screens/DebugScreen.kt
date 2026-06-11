package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.utils.PerformanceOptimizer
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ConnectionState
import com.enderthor.kremote.viewmodel.DebugViewModel
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    repository: RemoteRepository,
    viewModel: DebugViewModel = viewModel { DebugViewModel(repository) }
) {

    val scope = rememberCoroutineScope()

    val isDebugEnabled by viewModel.isDebugEnabled.collectAsState()
    val connectionStates: Map<Int, ConnectionState> by viewModel.connectionStates.collectAsState(initial = emptyMap())

    // Fix: arrancar/parar la verificación periódica solo mientras la pantalla está visible
    DisposableEffect(Unit) {
        viewModel.startPeriodicActiveCheck()
        onDispose { viewModel.stopPeriodicActiveCheck() }
    }

    // DIAGNÓSTICO: Verificar estado del singleton cada vez que se abre la pantalla
    LaunchedEffect(isDebugEnabled) {
        if (isDebugEnabled) {
            val reconnectionManager = ReconnectionManagerSingleton.getInstance()
            if (reconnectionManager != null) {
                DebugLogger.logConnectionEvent(0, "DEBUG_SCREEN_CHECK", "Singleton available, states count: ${connectionStates.size}", "DebugScreen")
            } else {
                DebugLogger.logConnectionEvent(0, "DEBUG_SCREEN_CHECK", "Singleton is NULL - ConnectionService may not be running", "DebugScreen")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // === OPTIMIZACIONES DE RENDIMIENTO (SIEMPRE VISIBLE) ===
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.debug_performance_optimizations),
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                val optimizerEnabled by PerformanceOptimizer.isOptimizationEnabled.collectAsState()

                // Fix: getFormattedStats se recalcula solo cuando optimizerEnabled cambia,
                // no en cada recomposición. Los strings de recursos son estables.
                val optimizerStatusStr = stringResource(R.string.debug_optimizations_status)
                val optimizerEnabledStr = stringResource(R.string.debug_optimizations_enabled_status)
                val optimizerDisabledStr = stringResource(R.string.debug_optimizations_disabled_status)
                val commandCacheStr = stringResource(R.string.debug_command_cache)
                val connectionCacheStr = stringResource(R.string.debug_connection_cache)
                val coroutinePoolStr = stringResource(R.string.debug_coroutine_pool)
                val formattedStats = remember(optimizerEnabled) {
                    PerformanceOptimizer.getFormattedStats(
                        optimizationsStatus = optimizerStatusStr,
                        enabledStatus = optimizerEnabledStr,
                        disabledStatus = optimizerDisabledStr,
                        commandCacheLabel = commandCacheStr,
                        connectionCacheLabel = connectionCacheStr,
                        coroutinePoolLabel = coroutinePoolStr
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.debug_optimizations_enabled),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = optimizerEnabled,
                        onCheckedChange = { enabled ->
                            PerformanceOptimizer.setOptimizationEnabled(enabled)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = formattedStats,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        PerformanceOptimizer.clearCaches()
                    }
                ) {
                    Text(stringResource(R.string.debug_clear_caches))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // === DEBUG LOGGING SYSTEM ===
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.debug_logging_title),
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.debug_logging_enabled),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isDebugEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                viewModel.setDebugEnabled(enabled)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isDebugEnabled) {
                        stringResource(R.string.debug_logging_active)
                    } else {
                        stringResource(R.string.debug_logging_inactive)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDebugEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isDebugEnabled) {
                    // Fix: getDebugInfo() lee SharedPreferences → sacarlo de la ruta de
                    // recomposición; se recalcula solo cuando cambia isDebugEnabled.
                    val debugInfo = remember(isDebugEnabled) { DebugLogger.getDebugInfo() }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.debug_logging_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Show detailed debug information using getDebugInfo()
                    Text(
                        text = debugInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.clearLog()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.debug_clear_log))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // === CONNECTION STATUS (ONLY IF DEBUG ENABLED) ===
        if (isDebugEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.debug_connection_status),
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (connectionStates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.debug_no_devices_monitored),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // NUEVO: Información de diagnóstico
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.debug_system_diagnosis),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )

                                val reconnectionManager = ReconnectionManagerSingleton.getInstance()
                                Text(
                                    text = if (reconnectionManager != null) {
                                        stringResource(R.string.debug_reconnection_manager_available)
                                    } else {
                                        stringResource(R.string.debug_reconnection_manager_null)
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Text(
                                    text = stringResource(R.string.debug_reconnection_manager_explanation),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    } else {
                        // NUEVO: Indicador de fuente de datos
                        val reconnectionManager = ReconnectionManagerSingleton.getInstance()
                        val isRealMonitoring = reconnectionManager != null

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRealMonitoring)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isRealMonitoring) {
                                        stringResource(R.string.debug_real_monitoring_active)
                                    } else {
                                        stringResource(R.string.debug_simulated_states)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isRealMonitoring) {
                                    Text(
                                        text = stringResource(R.string.debug_shows_registered_devices),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        connectionStates.forEach { (deviceId, state) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (state.isConnected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.debug_device_number, deviceId),
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Row {
                                        Text(stringResource(R.string.debug_device_status))
                                        Text(
                                            text = when {
                                                state.isConnected -> stringResource(R.string.debug_device_connected)
                                                state.isReconnecting -> stringResource(R.string.debug_device_reconnecting, state.reconnectAttempts)
                                                else -> stringResource(R.string.debug_device_disconnected)
                                            },
                                            color = when {
                                                state.isConnected -> MaterialTheme.colorScheme.primary
                                                state.isReconnecting -> MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.error
                                            }
                                        )
                                    }

                                    if (state.lastError != null) {
                                        Text(
                                            text = stringResource(R.string.debug_device_error, state.lastError),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }

                                    if (state.isReconnecting) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    viewModel.forceReconnect(deviceId)
                                                }
                                            },
                                            modifier = Modifier.padding(top = 8.dp)
                                        ) {
                                            Text(stringResource(R.string.debug_force_reconnection))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
