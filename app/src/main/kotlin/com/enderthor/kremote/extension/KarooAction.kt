package com.enderthor.kremote.extension

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.RemoteDevice
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.ShowMapPage
import timber.log.Timber

class KarooAction(
    private val karooSystem: KarooSystemService,
    private val isServiceConnected: () -> Boolean,
    private val isRiding: () -> Boolean,
    private val onlyWhileRiding: () -> Boolean,
    private val activeDevice: () -> RemoteDevice?
) {

    fun handleAntCommand(commandNumber: GenericCommandNumber) {
        if (!isServiceConnected() || (!isRiding() && onlyWhileRiding())) {
            Timber.d("[KRemote] Ignorando comando: servicio ${if (!isServiceConnected()) "no conectado" else "no en modo riding"}")
            return
        }

        try {
            val antRemoteKey = AntRemoteKey.entries.find { it.gCommand == commandNumber }
            if (antRemoteKey == null) {
                Timber.d("Comando no reconocido: $commandNumber")
                return
            }

            activeDevice()?.let { device ->
                device.getKarooKey(antRemoteKey.gCommand)?.let { karooKey ->
                    executeKarooAction(karooKey.action)
                } ?: Timber.d("No se encontró tecla asignada para el comando: ${antRemoteKey.label}")
            }
        } catch (e: Exception) {
            Timber.e(e, "[KRemote] Error en handleAntCommand")
        }
    }

    fun executeKarooAction(action: PerformHardwareAction) {
        Timber.d("executeKarooAction: $action")

        if (!isServiceConnected()) {
            Timber.w("No se puede ejecutar acción: servicio Karoo no conectado")
            return
        }

        try {
            Timber.d("Enviando TurnScreenOn")
            //karooSystem.dispatch(TurnScreenOn)

            if (action == PerformHardwareAction.DrawerActionComboPress) {
                Timber.d("Mostrando página de mapa")
                karooSystem.dispatch(ShowMapPage(true))
            } else {
                Timber.d("Enviando acción: $action")
                karooSystem.dispatch(action)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error ejecutando acción Karoo: $action")
        }
    }
}