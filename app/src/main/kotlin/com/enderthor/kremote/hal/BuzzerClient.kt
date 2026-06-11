package com.enderthor.kremote.hal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import io.hammerhead.karooext.models.PlayBeepPattern
import timber.log.Timber

/**
 * Cliente del servicio HAL privado del Karoo (`io.hammerhead.hal/.HIDLTranslationService`),
 * que expone el AIDL privado `IPhoneROMController`. Su transacción `beep` (ID 14) toca el
 * zumbador físico directamente, **saltándose el mute de audio del rider** y el dispatcher
 * de alertas — algo que `karoo-ext` (`PlayBeepPattern`) no permite porque siempre respeta
 * el mute.
 *
 * Es API privada: Hammerhead puede caparla en cualquier OTA (añadiendo permiso,
 * `enforceCallingPermission`, o reordenando los IDs de transacción). Por eso:
 *  - cada fallo se mapea a un [BeepResult] distinto para poder diagnosticarlo, y
 *  - el llamante (KarooAction) hace fallback al SDK si `beep()` no devuelve `true`,
 *    de modo que el peor caso post-OTA es "suena como antes", nunca silencio.
 *
 * Valores fijados (re-verificar tras cualquier OTA del Karoo, ver skill karoo-dev):
 *  - ComponentName: io.hammerhead.hal/.HIDLTranslationService  (sin action)
 *  - descriptor AIDL: io.hammerhead.hal.service.IPhoneROMController
 *  - transacción beep: 14, parámetro List<BeeperCommand> (cada uno: frequency Hz, durationMs)
 */
class BuzzerClient(private val context: Context) {

    enum class BeepResult {
        NEVER_CALLED,
        SUCCESS,                 // transact devolvió true y readException no lanzó
        BIND_NOT_READY,          // binder == null (sin conectar / onServiceDisconnected)
        TRANSACT_RETURNED_FALSE, // raro: transact devolvió false sin excepción
        GATED_BY_SECURITY,       // SecurityException — un OTA añadió enforceCallingPermission
        TRANSACT_THREW,          // cualquier otro: drift de descriptor/ID, binder muerto
    }

    data class Tone(val frequencyHz: Int, val durationMs: Int)

    @Volatile private var binder: IBinder? = null
    @Volatile private var bound: Boolean = false
    @Volatile var lastResult: BeepResult = BeepResult.NEVER_CALLED
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service }
        // El binder murió pero la conexión sigue registrada: limpiar binder, sin tocar `bound`.
        override fun onServiceDisconnected(name: ComponentName) { binder = null }

        // El binding murió definitivamente (p.ej. el HAL crasheó o un OTA lo capó). Si dejábamos
        // `bound = true`, connect() devolvía "already bound" para siempre y el canal HAL quedaba
        // muerto hasta reiniciar el proceso. Liberamos la conexión, reseteamos estado e intentamos
        // UN rebind automático (bind es idempotente y barato). Si vuelve a fallar, el llamante
        // (KarooAction) seguirá cayendo al beep del SDK — nunca silencio.
        override fun onBindingDied(name: ComponentName) {
            binder = null
            resetBinding()
            reconnect()
        }

        // El servicio devolvió un binder null en el bind: el bind quedó registrado pero inútil.
        // Reseteamos `bound` para que un connect() posterior pueda reintentar.
        override fun onNullBinding(name: ComponentName) {
            binder = null
            resetBinding()
        }
    }

    /** Libera la conexión actual y deja el estado como "no vinculado", de forma idempotente. */
    @Synchronized
    private fun resetBinding() {
        if (bound) {
            try { context.applicationContext.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
    }

    /** Reintento único de bind tras una muerte del binding. */
    private fun reconnect() {
        val status = connect()
        Timber.d("[KRemote] BuzzerClient auto-rebind after binding death: $status")
    }

    fun isReady(): Boolean = binder != null

    /**
     * Lanza el bind (asíncrono: el binder llega luego vía [connection]). Devuelve un
     * string diagnóstico apto para mostrar en un botón de test — distingue "paquete no
     * visible" (falta <queries>) de "servicio no resuelve" (capado/renombrado) de
     * "bindService devolvió false".
     */
    @Synchronized
    fun connect(): String {
        if (bound) return "already bound"
        val pm = context.packageManager
        try {
            pm.getPackageInfo(HAL_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return "HAL package not visible — missing <queries>"
        }
        val intent = Intent().apply { component = ComponentName(HAL_PACKAGE, HAL_SERVICE) }
        if (pm.resolveService(intent, 0) == null) {
            return "HAL service does not resolve — not exported or renamed"
        }
        bound = try {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            return "SecurityException: ${e.message}"
        }
        return if (bound) "bind dispatched" else "bindService returned false"
    }

    @Synchronized
    fun disconnect() {
        if (!bound) return
        try { context.applicationContext.unbindService(connection) } catch (_: Exception) {}
        bound = false
        binder = null
    }

    /**
     * Reproduce los tonos en el zumbador vía HAL, saltándose el mute. Devuelve true solo si
     * la transacción llegó al HAL; en cualquier fallo deja [lastResult] con la causa y
     * devuelve false para que el llamante haga fallback al SDK.
     */
    fun beep(tones: List<Tone>): Boolean {
        if (tones.isEmpty()) return false
        val b = binder ?: run {
            lastResult = BeepResult.BIND_NOT_READY
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AIDL_DESCRIPTOR) // crítico: el receptor hace enforceInterface
            // Layout de writeTypedList(List<BeeperCommand>):
            //   writeInt(N); por cada item: writeInt(1) /* not-null */, writeInt(freq), writeInt(dur)
            // Olvidar el writeInt(1) desplaza 4 bytes todos los campos → el HAL ignora la orden.
            data.writeInt(tones.size)
            for (t in tones) {
                data.writeInt(1)
                data.writeInt(t.frequencyHz)
                data.writeInt(t.durationMs)
            }
            val ok = b.transact(TRANSACTION_BEEP, data, reply, 0)
            reply.readException() // espejo del writeNoException() del receptor
            lastResult = if (ok) BeepResult.SUCCESS else BeepResult.TRANSACT_RETURNED_FALSE
            ok
        } catch (e: SecurityException) {
            Timber.w(e, "[KRemote] HAL beep gated by security (¿OTA?)")
            lastResult = BeepResult.GATED_BY_SECURITY
            false
        } catch (e: Exception) {
            Timber.w(e, "[KRemote] HAL beep transact failed")
            lastResult = BeepResult.TRANSACT_THREW
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val HAL_PACKAGE = "io.hammerhead.hal"
        private const val HAL_SERVICE = "io.hammerhead.hal.HIDLTranslationService"
        private const val AIDL_DESCRIPTOR = "io.hammerhead.hal.service.IPhoneROMController"
        private const val TRANSACTION_BEEP = 14 // fijado del HAL decompilado; re-verificar tras OTA

        /** Tono corto de prueba para el botón de diagnóstico en ajustes. */
        val TEST_TONES: List<Tone> = listOf(Tone(3_000, 250))

        /** Convierte el patrón del SDK (frequency null = silencio) al formato del HAL (0 = silencio). */
        fun List<PlayBeepPattern.Tone>.toBuzzerTones(): List<Tone> =
            map { Tone(it.frequency ?: 0, it.durationMs) }
    }
}
