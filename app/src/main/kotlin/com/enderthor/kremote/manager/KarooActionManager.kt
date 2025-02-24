package com.enderthor.kremote.manager

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.ShowMapPage
import io.hammerhead.karooext.models.TurnScreenOn

class KarooActionManager(
    private val karooSystem: KarooSystemService
) {
    fun executeAction(action: PerformHardwareAction) {
        karooSystem.dispatch(TurnScreenOn)

        if (action == PerformHardwareAction.DrawerActionComboPress) {
            karooSystem.dispatch(ShowMapPage(true))
        } else {
            karooSystem.dispatch(action)
        }
    }
}