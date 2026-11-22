package lat.pascalia.pvcar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * GPS (posición y velocidad) y acelerómetro (fuerza G), igual que js/telemetria.js,
 * pero dentro del servicio: sigue midiendo con la app en segundo plano.
 */
class Telemetria(private val ctx: Context, private val alImpacto: (Double) -> Unit) : SensorEventListener, LocationListener {
    @Volatile var lat: Double? = null; private set
    @Volatile var lon: Double? = null; private set
    @Volatile var precision: Float? = null; private set
    @Volatile var velKmh: Double? = null; private set
    @Volatile var gps = "apagado"; private set
    @Volatile var sensor = "apagado"; private set
    @Volatile var g = 0.0; private set
    @Volatile var gPico = 0.0; private set
    @Volatile var umbral = 2.5

    private var picoT = 0L
    private var ultimoImpacto = 0L
    private var ultimoFix: Location? = null
    private var activa = false
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun tienePermisoUbicacion(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun iniciar() {
        if (activa) return
        activa = true
        if (tienePermisoUbicacion()) {
            gps = "buscando"
            try {
                val proveedor = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                lm.requestLocationUpdates(proveedor, 1000L, 0f, this, Looper.getMainLooper())
                if (proveedor != LocationManager.GPS_PROVIDER) gps = "sin señal"
            } catch (e: Exception) { gps = "sin permiso" }
        } else gps = "sin permiso"
        val s = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s != null) { sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME); sensor = "esperando" }
    }

    fun detener() {
        if (!activa) return
        activa = false
        runCatching { lm.removeUpdates(this) }
        sm.unregisterListener(this)
        gps = "apagado"; sensor = "apagado"
    }

    override fun onLocationChanged(l: Location) {
        var v: Double? = if (l.hasSpeed()) l.speed * 3.6 else null
        val previo = ultimoFix
        if (v == null && previo != null) {
            val dt = (l.time - previo.time) / 1000.0
            if (dt >= 1 && l.accuracy < 30) v = previo.distanceTo(l) / dt * 3.6
        }
        ultimoFix = l
        lat = l.latitude; lon = l.longitude; precision = l.accuracy
        if (v != null) velKmh = max(0.0, v)
        gps = if (l.accuracy <= 30) "fijo" else "impreciso"
    }

    override fun onProviderDisabled(provider: String) { gps = "sin señal" }
    override fun onProviderEnabled(provider: String) { if (gps == "sin señal") gps = "buscando" }
    @Deprecated("Android lo sigue llamando en versiones anteriores a la 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
        val valor = if (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) m / G0 else abs(m - G0) / G0
        val ahora = System.currentTimeMillis()
        sensor = "activo"
        g = valor
        // Pico de los últimos 2 s, para que el número del video sea legible.
        if (valor >= gPico || ahora - picoT > 2000) { gPico = valor; picoT = ahora }
        if (valor >= umbral && ahora - ultimoImpacto > 10_000) {
            ultimoImpacto = ahora
            alImpacto(valor)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Muestra para el registro del clip (una por segundo), con los campos de la versión web. */
    fun muestra(): JSONObject = JSONObject()
        .put("t", System.currentTimeMillis())
        .put("lat", lat ?: JSONObject.NULL)
        .put("lon", lon ?: JSONObject.NULL)
        .put("vel", velKmh?.let { round(it).toInt() } ?: JSONObject.NULL)
        .put("g", round(gPico * 100) / 100)

    fun estado(): JSONObject = JSONObject()
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("precision", precision?.toDouble() ?: JSONObject.NULL)
        .put("velKmh", velKmh ?: JSONObject.NULL)
        .put("g", g).put("gPico", gPico).put("gps", gps).put("sensor", sensor).put("umbral", umbral)

    companion object { const val G0 = 9.80665 }
}
