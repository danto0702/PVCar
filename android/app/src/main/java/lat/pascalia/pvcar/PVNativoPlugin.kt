package lat.pascalia.pvcar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Puente entre la interfaz web (js/nativo.js) y el servicio de grabación. */
@CapacitorPlugin(
    name = "PVNativo",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA], alias = "camara"),
        Permission(strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION], alias = "ubicacion"),
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microfono"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notificaciones"),
    ],
)
class PVNativoPlugin : Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private val trabajo = Executors.newSingleThreadExecutor()
    private val almacen by lazy { Almacen(context) }
    private val oyente = Bus.Oyente { tipo, datos -> notifyListeners(tipo, JSObject.fromJSONObject(datos)) }

    override fun load() { Bus.agregar(oyente) }
    override fun handleOnDestroy() { Bus.quitar(oyente) }

    private fun js(o: JSONObject) = JSObject.fromJSONObject(o)

    @PluginMethod
    fun disponible(call: PluginCall) {
        call.resolve(JSObject().put("disponible", true).put("version", BuildConfig.VERSION_NAME).put("android", Build.VERSION.SDK_INT))
    }

    /* ---------------- Permisos ---------------- */

    private fun estadoPermisos(): JSObject {
        val r = JSObject()
        for (a in listOf("camara", "ubicacion", "microfono", "notificaciones")) {
            val s = if (a == "notificaciones" && Build.VERSION.SDK_INT < 33) PermissionState.GRANTED else getPermissionState(a)
            r.put(a, s.toString())
        }
        val pm = context.getSystemService(PowerManager::class.java)
        r.put("bateriaLibre", pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
        return r
    }

    @PluginMethod
    fun permisos(call: PluginCall) { call.resolve(estadoPermisos()) }

    @PluginMethod
    fun pedirPermisos(call: PluginCall) {
        val alias = mutableListOf("camara", "ubicacion")
        if (call.getBoolean("audio", false) == true) alias.add("microfono")
        if (Build.VERSION.SDK_INT >= 33) alias.add("notificaciones")
        val faltan = alias.filter { getPermissionState(it) != PermissionState.GRANTED }
        if (faltan.isEmpty()) call.resolve(estadoPermisos())
        else requestPermissionForAliases(faltan.toTypedArray(), call, "alPedirPermisos")
    }

    @PermissionCallback
    private fun alPedirPermisos(call: PluginCall) { call.resolve(estadoPermisos()) }

    /** Abre el permiso para que Android no detenga PV Car por ahorro de batería. */
    @SuppressLint("BatteryLife")
    @PluginMethod
    fun pedirSinRestriccionBateria(call: PluginCall) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { activity.startActivity(i) }.onFailure {
            runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        call.resolve()
    }

    /* ---------------- Grabación ---------------- */

    @PluginMethod
    fun iniciar(call: PluginCall) {
        if (getPermissionState("camara") != PermissionState.GRANTED) { call.reject("Sin permiso para la cámara.", "sin-permiso"); return }
        val a = Ajustes.de(call.getObject("ajustes"))
        Ajustes.guardar(context, a)
        GrabacionService.instancia?.configurar(a)
        val origen = call.getString("origen", "manual") ?: "manual"
        try {
            ContextCompat.startForegroundService(context, GrabacionService.intent(context, GrabacionService.ACC_INICIAR, origen))
            call.resolve()
        } catch (e: Exception) {
            call.reject("Android no permitió iniciar la grabación: ${e.message}", "no-permitido", e)
        }
    }

    @PluginMethod
    fun detener(call: PluginCall) {
        val origen = call.getString("origen", "manual") ?: "manual"
        main.post { GrabacionService.instancia?.detener(origen); call.resolve() }
    }

    @PluginMethod
    fun proteger(call: PluginCall) {
        val motivo = call.getString("motivo", "Protegido por el conductor") ?: "Protegido por el conductor"
        val origen = call.getString("origen", "manual") ?: "manual"
        main.post {
            val n = GrabacionService.instancia?.takeIf { it.activa }?.proteger(motivo, null, origen) ?: 0
            call.resolve(JSObject().put("n", n))
        }
    }

    @PluginMethod
    fun configurar(call: PluginCall) {
        val a = Ajustes.de(call.getObject("ajustes"))
        Ajustes.guardar(context, a)
        main.post { GrabacionService.instancia?.configurar(a); call.resolve() }
    }

    @PluginMethod
    fun estado(call: PluginCall) {
        main.post { call.resolve(js(GrabacionService.instancia?.estado() ?: JSONObject().put("grabando", false))) }
    }

    /* ---------------- Videos ---------------- */

    @PluginMethod
    fun listar(call: PluginCall) {
        trabajo.execute {
            val enCurso = GrabacionService.instancia?.estado()?.optString("clipActual")
            almacen.recuperarHuerfanos(enCurso.takeIf { GrabacionService.grabando })
            val lista = JSArray()
            for (c in almacen.listar()) lista.put(c)
            call.resolve(JSObject().put("clips", lista).put("dir", almacen.dir.absolutePath))
        }
    }

    /** Cambios hechos desde la interfaz (proteger, quitar protección, eventos). */
    @PluginMethod
    fun actualizar(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("Falta el id")
        trabajo.execute {
            val m = almacen.actualizar(id) { c ->
                if (call.data.has("protegido")) c.put("protegido", call.getBoolean("protegido", false))
                call.data.optJSONArray("eventos")?.let { c.put("eventos", it) }
            }
            if (m == null) { call.reject("No existe el clip $id"); return@execute }
            // Si quedó protegido y ya está cerrado, se calcula su huella SHA-256.
            if (m.optBoolean("protegido") && m.isNull("hash") && m.optString("estado") != "grabando") {
                val v = almacen.archivo(m)
                runCatching { Almacen.sha256(v) }.getOrNull()?.let { h ->
                    almacen.actualizar(id) { c -> c.put("hash", h).put("hashBytes", v.length()).put("hashEn", System.currentTimeMillis()) }
                }
            }
            call.resolve(js(almacen.leer(id) ?: m))
        }
    }

    @PluginMethod
    fun huella(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("Falta el id")
        trabajo.execute {
            try {
                val v = almacen.archivo(id)
                if (!v.exists()) throw IllegalStateException("el archivo ya no existe")
                val h = Almacen.sha256(v)
                almacen.actualizar(id) { c -> c.put("hash", h).put("hashBytes", v.length()).put("hashEn", System.currentTimeMillis()) }
                call.resolve(JSObject().put("hash", h).put("bytes", v.length()))
            } catch (e: Exception) { call.reject("No se pudo calcular la huella: ${e.message}") }
        }
    }

    @PluginMethod
    fun borrar(call: PluginCall) {
        val ids = call.getArray("ids")?.toList<String>() ?: emptyList()
        val enCurso = GrabacionService.instancia?.estado()?.optString("clipActual")
        trabajo.execute {
            for (id in ids) if (id != enCurso || !GrabacionService.grabando) almacen.borrar(id)
            call.resolve()
        }
    }

    /** Foto del cuadro actual con la franja de telemetría (solo mientras graba). */
    @PluginMethod
    fun foto(call: PluginCall) {
        main.post {
            val s = GrabacionService.instancia?.takeIf { it.activa }
            val cuadro = MainActivity.vista?.get()?.bitmap
            if (s == null || cuadro == null) { call.reject("Inicia la grabación para tomar fotos.", "sin-camara"); return@post }
            trabajo.execute {
                try { call.resolve(js(s.guardarFoto(cuadro, call.getString("origen", "manual") ?: "manual"))) }
                catch (e: Exception) { call.reject("No se pudo guardar la foto: ${e.message}") }
            }
        }
    }

    /** Eventos que el servicio anotó (también con la interfaz cerrada). */
    @PluginMethod
    fun registro(call: PluginCall) {
        call.resolve(JSObject().put("eventos", JSArray(Registro.recoger(context).toString())))
    }

    /* ---------------- Compartir ---------------- */

    /**
     * Comparte videos de la bóveda (por id) o archivos creados en la interfaz
     * (acta PDF, registro CSV) que llegan en base64.
     */
    @PluginMethod
    fun compartir(call: PluginCall) {
        trabajo.execute {
            try {
                val archivos = mutableListOf<Pair<File, String>>()
                call.getArray("ids")?.toList<String>()?.forEach { id ->
                    val m = almacen.leer(id) ?: return@forEach
                    val v = almacen.archivo(m)
                    if (v.exists()) archivos.add(v to m.optString("mime", "video/mp4"))
                }
                call.getArray("datos")?.let { arr ->
                    val carpeta = File(context.cacheDir, "compartir").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val nombre = o.getString("nombre").replace(Regex("[^\\w.\\-]"), "_")
                        val f = File(carpeta, nombre)
                        f.writeBytes(Base64.decode(o.getString("base64"), Base64.DEFAULT))
                        archivos.add(f to o.optString("mime", "application/octet-stream"))
                    }
                }
                if (archivos.isEmpty()) { call.reject("No hay archivos para compartir"); return@execute }
                val autoridad = "${context.packageName}.fileprovider"
                val uris = ArrayList(archivos.map { FileProvider.getUriForFile(context, autoridad, it.first) })
                val tipo = archivos.map { it.second }.distinct().singleOrNull() ?: "*/*"
                val envio = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                envio.setType(tipo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val titulo = call.getString("titulo", "Compartir desde PV Car")
                main.post {
                    runCatching { activity.startActivity(Intent.createChooser(envio, titulo)) }
                        .onSuccess { call.resolve() }.onFailure { call.reject("No se pudo compartir: ${it.message}") }
                }
            } catch (e: Exception) { call.reject("No se pudo compartir: ${e.message}") }
        }
    }
}
