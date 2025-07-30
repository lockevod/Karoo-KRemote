package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.utils.PerformanceOptimizer
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ConnectionState
import com.enderthor.kremote.viewmodel.DebugViewModel
import com.enderthor.kremote.data.RemoteRepository
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
                    text = "Optimizaciones de Rendimiento",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                val optimizerEnabled by PerformanceOptimizer.isOptimizationEnabled.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Optimizaciones habilitadas",
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
                    text = PerformanceOptimizer.getFormattedStats(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        PerformanceOptimizer.clearCaches()
                    }
                ) {
                    Text("Limpiar Cachés")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // === SISTEMA DE DEBUG LOGGING ===
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Logging de Debug",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Debug logging habilitado",
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
                        "✅ Logging activado - Los eventos se guardan en archivo"
                    } else {
                        "❌ Logging desactivado - No se guardan eventos"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDebugEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isDebugEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "⚠️ El logging consume recursos. Desactivar cuando no sea necesario.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.clearLog()
                            }
                        }
                    ) {
                        Text("Limpiar Log")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // === ESTADO DE CONEXIONES (SOLO SI DEBUG HABILITADO) ===
        if (isDebugEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Estado de Conexiones",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (connectionStates.isEmpty()) {
                        Text(
                            text = "No hay dispositivos monitoreados. Asegúrate de tener un dispositivo ANT+ conectado y activo.",
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
                                    text = "🔍 Diagnóstico del Sistema",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )

                                val reconnectionManager = ReconnectionManagerSingleton.getInstance()
                                Text(
                                    text = if (reconnectionManager != null) {
                                        "✅ ReconnectionManager: Disponible"
                                    } else {
                                        "❌ ReconnectionManager: NULL - El servicio puede no estar ejecutándose"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Text(
                                    text = "💡 Si el ReconnectionManager es NULL, significa que el ConnectionService no se ha iniciado o ha fallado.",
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
                                    text = if (isRealMonitoring) "🟢 MONITOREO REAL ACTIVO" else "🟡 ESTADOS SIMULADOS",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isRealMonitoring) {
                                    Text(
                                        text = "Solo muestra dispositivos registrados",
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
                                        text = "Dispositivo #$deviceId",
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Row {
                                        Text("Estado: ")
                                        Text(
                                            text = when {
                                                state.isConnected -> "✅ Conectado"
                                                state.isReconnecting -> "🔄 Reconectando (${state.reconnectAttempts})"
                                                else -> "❌ Desconectado"
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
                                            text = "Error: ${state.lastError}",
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
                                            Text("Forzar Reconexión")
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
