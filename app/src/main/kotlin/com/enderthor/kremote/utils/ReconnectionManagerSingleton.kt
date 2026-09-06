package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton  ReconnectionManager
 * between ConnectionService and DebugViewModel
 */
object ReconnectionManagerSingleton {
    // StateFlow en vez de var suelta: la pantalla de debug necesita enterarse de cuándo
    // aparece el manager (antes resolvía el singleton una sola vez y se quedaba a null
    // si el servicio aún no había arrancado).
    private val _instance = MutableStateFlow<ReconnectionManager?>(null)
    val instance: StateFlow<ReconnectionManager?> = _instance.asStateFlow()

    // Scope que se usó para construir la instancia viva. onStartCommand puede ejecutarse
    // varias veces sobre el MISMO objeto Service (la app y la extensión emiten cada una
    // su broadcast de arranque), y en ese caso el scope es el mismo.
    private var currentScope: CoroutineScope? = null

    /**
     * Idempotente dentro de una misma sesión de servicio: si ya hay una instancia creada
     * con este mismo scope, la reutiliza en vez de destruirla y crear otra.
     *
     * Antes destruía siempre: el segundo `onStartCommand` del arranque mataba el monitoreo
     * que acababa de configurar el primero, y el throttle de ConnectionService descartaba
     * la reconfiguración → el dispositivo se quedaba SIN monitoreo de reconexión durante
     * toda la sesión (si el mando se caía a mitad de ruta, no volvía hasta reiniciar).
     */
    fun initialize(antManager: AntManager, scope: CoroutineScope): ReconnectionManager {
        val existing = _instance.value
        // Comparar TAMBIÉN el AntManager, no sólo el scope: KremoteExtension construye uno
        // nuevo en cada onCreate y hace cleanup() del viejo en onDestroy. Si la extensión se
        // reinicia mientras ConnectionService sobrevive (START_STICKY), el scope es el mismo
        // pero el manager reutilizado apuntaría al AntManager ya limpiado → todas las
        // reconexiones irían contra un objeto muerto, en silencio, el resto de la sesión.
        if (existing != null && currentScope === scope && existing.getAntManager() === antManager) {
            DebugLogger.logConnectionEvent(0, "RECONNECTION_MANAGER_REUSED", "Singleton reused for same service scope")
            return existing
        }

        // Scope o AntManager distintos (servicio o extensión recreados): el anterior ya no sirve.
        existing?.stopAllMonitoring()
        currentScope = scope
        return ReconnectionManager(antManager, scope).also {
            _instance.value = it
            DebugLogger.logConnectionEvent(0, "RECONNECTION_MANAGER_INITIALIZED", "Singleton instance created")
        }
    }

    fun getInstance(): ReconnectionManager? = _instance.value

    fun destroy() {
        _instance.value?.stopAllMonitoring()
        _instance.value = null
        currentScope = null
        DebugLogger.logConnectionEvent(0, "RECONNECTION_MANAGER_DESTROYED", "Singleton instance destroyed")
    }
}
