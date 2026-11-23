package lat.pascalia.pvcar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Vigila la temperatura del teléfono (estado térmico de Android y batería) y la
 * resume en cuatro niveles. Con histéresis: para bajar de nivel la batería debe
 * enfriarse 2 °C por debajo del umbral, así la calidad no sube y baja a cada rato.
 *
 *  0 normal · 1 tibio (menos bits) · 2 caliente (720p a 24 cuadros) · 3 crítico (pausa)
 */
class Termico(private val ctx: Context, private val alCambiar: (Int) -> Unit) {
    @Volatile var nivel = 0; private set
    @Volatile var tempBateria: Double? = null; private set
    @Volatile var cargando = false; private set
    @Volatile var estadoSistema = 0; private set

    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var oyenteSistema: Any? = null

    private val bateria = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != Int.MIN_VALUE) tempBateria = t / 10.0
            cargando = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            recalcular()
        }
    }

    fun iniciar() {
        ContextCompat.registerReceiver(ctx, bateria, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            estadoSistema = pm.currentThermalStatus
            val o = PowerManager.OnThermalStatusChangedListener { s -> estadoSistema = s; recalcular() }
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(ctx), o)
            oyenteSistema = o
        }
        recalcular()
    }

    fun detener() {
        runCatching { ctx.unregisterReceiver(bateria) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (oyenteSistema as? PowerManager.OnThermalStatusChangedListener)?.let { pm.removeThermalStatusListener(it) }
        }
        oyenteSistema = null
    }

    private fun nivelSistema(): Int = when (estadoSistema) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> 0
        PowerManager.THERMAL_STATUS_MODERATE -> 1
        PowerManager.THERMAL_STATUS_SEVERE -> 2
        else -> 3 // CRITICAL, EMERGENCY, SHUTDOWN
    }

    private fun nivelBateria(): Int {
        val t = tempBateria ?: return 0
        val umbrales = doubleArrayOf(41.0, 44.0, 47.0) // °C para pasar a 1, 2 y 3
        var n = umbrales.count { t >= it }
        // Histéresis: se mantiene el nivel actual mientras no baje 2 °C del umbral.
        if (n < nivel && nivel in 1..3 && t >= umbrales[nivel - 1] - 2.0) n = nivel
        return n
    }

    private fun recalcular() {
        val n = maxOf(nivelSistema(), nivelBateria())
        if (n == nivel) return
        nivel = n
        alCambiar(n)
    }

    fun json(): JSONObject = JSONObject()
        .put("nivel", nivel)
        .put("temp", tempBateria ?: JSONObject.NULL)
        .put("cargando", cargando)
        .put("sistema", estadoSistema)
}
