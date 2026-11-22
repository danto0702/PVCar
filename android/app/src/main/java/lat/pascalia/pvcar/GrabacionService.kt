package lat.pascalia.pvcar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Servicio en primer plano que graba en bucle aunque PV Car no esté en pantalla:
 * con otra app abierta, con la pantalla apagada o durante una llamada.
 * La notificación fija (obligatoria en Android) trae los botones Proteger y Detener.
 */
class GrabacionService : LifecycleService() {

    companion object {
        private const val TAG = "PVCar"
        const val ACC_INICIAR = "lat.pascalia.pvcar.INICIAR"
        const val ACC_DETENER = "lat.pascalia.pvcar.DETENER"
        const val ACC_PROTEGER = "lat.pascalia.pvcar.PROTEGER"
        const val EXTRA_ORIGEN = "origen"
        private const val CANAL = "grabacion"
        private const val CANAL_AVISOS = "avisos"
        private const val NOTIF = 7
        private const val NOTIF_AVISO = 8

        @Volatile var instancia: GrabacionService? = null
            private set

        val grabando: Boolean get() = instancia?.activa == true

        fun intent(ctx: Context, accion: String, origen: String = "manual"): Intent =
            Intent(ctx, GrabacionService::class.java).setAction(accion).putExtra(EXTRA_ORIGEN, origen)

        fun pendiente(ctx: Context, accion: String, origen: String, codigo: Int): PendingIntent =
            PendingIntent.getService(ctx, codigo, intent(ctx, accion, origen), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    lateinit var almacen: Almacen; private set
    lateinit var tel: Telemetria; private set
    var ajustes = Ajustes(); private set

    @Volatile var activa = false; private set
    var sesion = ""; private set
    var inicioSesion = 0L; private set
    @Volatile var enLlamada = false; private set

    private val main = Handler(Looper.getMainLooper())
    private val trabajo = Executors.newSingleThreadExecutor()
    private lateinit var hiloEfecto: HandlerThread
    private var proveedor: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var captura: VideoCapture<Recorder>? = null
    private var efecto: OverlayEffect? = null
    private var grabacion: Recording? = null
    @Volatile private var actual: JSONObject? = null
    private var ultimoCierre = 0L
    private var ultimoIdCerrado: String? = null
    private var cortesSeguidos = 0
    private var vista: PreviewView? = null
    private var bloqueo: PowerManager.WakeLock? = null
    private var orientacion: OrientationEventListener? = null
    @Volatile private var rotacion = Surface.ROTATION_90
    private var detenerPedido = false

    /* ---------------- Ciclo de vida ---------------- */

    override fun onCreate() {
        super.onCreate()
        instancia = this
        almacen = Almacen(this)
        tel = Telemetria(this) { g -> main.post { impacto(g) } }
        hiloEfecto = HandlerThread("pvcar-telemetria").also { it.start() }
        crearCanales()
        trabajo.execute { almacen.recuperarHuerfanos(null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val origen = intent?.getStringExtra(EXTRA_ORIGEN) ?: "manual"
        when (intent?.action) {
            ACC_INICIAR -> iniciar(origen)
            ACC_DETENER -> detener(origen)
            ACC_PROTEGER -> if (activa) proteger(if (origen == "notificacion") "Protegido desde la notificación" else if (origen == "ventana") "Protegido desde la ventana flotante" else "Protegido por el conductor", null, origen)
            else -> if (!activa) stopSelf()
        }
        // Android no puede reabrir la cámara desde segundo plano: si mata el
        // proceso, no se reinicia solo (se avisa al volver a abrir la app).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activa) detener("sistema")
        instancia = null
        hiloEfecto.quitSafely()
        trabajo.shutdown()
        super.onDestroy()
    }

    /** La interfaz conecta aquí la vista previa de la actividad (o null al salir). */
    fun conectarVista(v: PreviewView?) {
        vista = v
        preview?.surfaceProvider = v?.surfaceProvider
    }

    fun configurar(a: Ajustes) {
        ajustes = a
        Ajustes.guardar(this, a)
        tel.umbral = a.umbralG
    }

    /* ---------------- Grabar y detener ---------------- */

    private fun tiene(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun tiposServicio(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (tel.tienePermisoUbicacion()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (ajustes.audio && tiene(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    private fun iniciar(origen: String) {
        if (activa) { emitirEstado(); return }
        ajustes = Ajustes.leer(this)
        tel.umbral = ajustes.umbralG
        if (!tiene(Manifest.permission.CAMERA)) {
            Bus.emitir("error", JSONObject().put("mensaje", "Sin permiso para la cámara."))
            stopSelf(); return
        }
        try {
            ServiceCompat.startForeground(this, NOTIF, notificacion(), tiposServicio())
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo pasar a primer plano", e)
            Bus.emitir("error", JSONObject().put("mensaje", "Android no permitió grabar en segundo plano (${e.javaClass.simpleName}). Abre PV Car y vuelve a intentarlo."))
            stopSelf(); return
        }
        activa = true
        detenerPedido = false
        cortesSeguidos = 0
        inicioSesion = System.currentTimeMillis()
        sesion = "s$inicioSesion"
        tel.iniciar()
        bloqueo = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pvcar:grabacion").also { it.acquire(12 * 60 * 60 * 1000L) }
        vigilarOrientacion(true)
        anotar("inicio", origen, null)
        ProcessCameraProvider.getInstance(this).addListener({
            try {
                proveedor = ProcessCameraProvider.getInstance(this).get()
                vincular()
                abrirFragmento(false)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir la cámara", e)
                Bus.emitir("error", JSONObject().put("mensaje", "No se pudo abrir la cámara: ${e.message}"))
                detener("sistema")
            }
        }, ContextCompat.getMainExecutor(this))
        main.post(pulso)
        emitirEstado()
    }

    fun detener(origen: String) {
        if (!activa) { stopSelf(); return }
        activa = false
        detenerPedido = true
        main.removeCallbacks(pulso)
        val g = grabacion
        grabacion = null
        g?.stop() // el cierre de la ficha llega en el evento Finalize
        tel.detener()
        vigilarOrientacion(false)
        anotar("detencion", origen, null)
        vibrar(longArrayOf(0, 50, 60, 50))
        // Se espera un instante a que el último archivo se cierre antes de soltar la cámara.
        main.postDelayed({
            if (!activa) {
                runCatching { proveedor?.unbindAll() }
                runCatching { efecto?.close() }
                efecto = null; captura = null; preview = null
                bloqueo?.let { if (it.isHeld) it.release() }
                bloqueo = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, 1500)
        emitirEstado()
    }

    private fun vincular() {
        val p = proveedor ?: return
        val calidad = when (ajustes.resolucion) { "720p" -> Quality.HD; "4k" -> Quality.UHD; else -> Quality.FHD }
        val selector = QualitySelector.from(calidad, FallbackStrategy.lowerQualityOrHigherThan(calidad))
        val recorder = Recorder.Builder().setQualitySelector(selector).setTargetVideoEncodingBitRate(ajustes.bps).build()
        val cap = VideoCapture.Builder(recorder).setTargetRotation(rotacion).build()
        val pre = Preview.Builder().setTargetRotation(rotacion).build()
        (vista ?: MainActivity.vista?.get())?.let { vista = it; pre.surfaceProvider = it.surfaceProvider }
        // La telemetría se dibuja dentro de cada cuadro del video (marca de agua probatoria).
        val ef = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(hiloEfecto.looper)) { t -> Log.e(TAG, "Error en la superposición", t) }
        ef.setOnDrawListener { frame -> dibujar(frame) }
        val grupo = UseCaseGroup.Builder().addUseCase(pre).addUseCase(cap).addEffect(ef).build()
        p.unbindAll()
        p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, grupo)
        preview = pre; captura = cap; efecto = ef
    }

    /* ---------------- Fragmentos en bucle ---------------- */

    @SuppressLint("MissingPermission")
    private fun abrirFragmento(protegerDesdeInicio: Boolean) {
        if (!activa) return
        val cap = captura ?: return
        val n = almacen.reciclar(ajustes.cuotaBytes, ajustes.bytesFragmento, actual?.optString("id"))
        if (n < 0) {
            anotar("sinEspacio", "sistema", null)
            Bus.emitir("sin-espacio")
            avisoSistema("Memoria llena", "Los videos protegidos llenaron la cuota. Libera espacio en la bóveda de PV Car.")
            detener("sistema")
            return
        }
        if (n > 0) Bus.emitir("reciclado", JSONObject().put("n", n))
        cap.targetRotation = rotacion
        val inicio = System.currentTimeMillis()
        val id = "v$inicio"
        val archivo = almacen.video(id)
        val vertical = rotacion == Surface.ROTATION_0 || rotacion == Surface.ROTATION_180
        val (ancho, alto) = when (ajustes.resolucion) { "720p" -> 1280 to 720; "4k" -> 3840 to 2160; else -> 1920 to 1080 }
        val ficha = JSONObject()
            .put("id", id).put("tipo", "video").put("inicio", inicio).put("fin", JSONObject.NULL)
            .put("bytes", 0).put("partes", 1).put("mime", "video/mp4")
            .put("resolucion", ajustes.resolucion)
            .put("ancho", if (vertical) alto else ancho).put("alto", if (vertical) ancho else alto)
            .put("protegido", protegerDesdeInicio).put("estado", "grabando")
            .put("eventos", JSONArray().apply { if (protegerDesdeInicio) put(JSONObject().put("t", inicio).put("motivo", "Continuación de un evento en curso")) })
            .put("telemetria", JSONArray().put(tel.muestra()))
            .put("placa", ajustes.placa).put("hash", JSONObject.NULL).put("sesion", sesion)
            .put("nativo", true).put("ruta", archivo.absolutePath)
        almacen.guardar(ficha)
        actual = ficha
        var pendiente = cap.output.prepareRecording(this, FileOutputOptions.Builder(archivo).build())
        val conAudio = ajustes.audio && tiene(Manifest.permission.RECORD_AUDIO)
        if (conAudio) pendiente = pendiente.withAudioEnabled()
        try {
            grabacion = pendiente.start(ContextCompat.getMainExecutor(this)) { ev -> alEvento(id, ev) }
        } catch (e: IllegalStateException) {
            // El grabador aún cierra el anterior: se reintenta en un momento.
            Log.w(TAG, "El grabador no aceptó el fragmento nuevo", e)
            almacen.borrar(id)
            actual = null
            main.postDelayed({ if (activa && actual == null) abrirFragmento(protegerDesdeInicio) }, 800)
            return
        }
        Bus.emitir("fragmento", JSONObject().put("id", id))
        actualizarNotificacion()
    }

    private fun alEvento(id: String, ev: VideoRecordEvent) {
        if (ev !is VideoRecordEvent.Finalize) return
        val errorGrave = ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE &&
            ev.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED && ev.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (ev.hasError()) Log.w(TAG, "Fragmento $id cerrado con código ${ev.error}", ev.cause)
        cerrarFicha(id)
        // Si la cámara se cortó sin que nadie lo pidiera (otra app la tomó, por
        // ejemplo), se reintenta: la grabación del viaje continúa.
        if (activa && !detenerPedido && actual?.optString("id") == id) {
            actual = null
            grabacion = null
            cortesSeguidos++
            if (cortesSeguidos > 5) {
                avisoSistema("La cámara se detuvo", "Otra app está usando la cámara. Abre PV Car para seguir grabando.")
                detener("sistema"); return
            }
            Log.w(TAG, "Corte inesperado (grave=$errorGrave); se reabre el fragmento")
            main.postDelayed({ if (activa) { runCatching { vincular() }; abrirFragmento(true) } }, 2000)
        }
    }

    private fun cerrarFicha(id: String) {
        val fin = System.currentTimeMillis()
        val ficha = almacen.actualizar(id) { c ->
            c.put("fin", fin).put("estado", "listo")
            val v = almacen.video(id)
            c.put("bytes", if (v.exists()) v.length() else 0)
            val enMemoria = actual?.takeIf { it.optString("id") == id }
            if (enMemoria != null) { c.put("telemetria", enMemoria.optJSONArray("telemetria")); c.put("eventos", enMemoria.optJSONArray("eventos")); c.put("protegido", enMemoria.optBoolean("protegido") || c.optBoolean("protegido")) }
        } ?: return
        ultimoCierre = fin
        ultimoIdCerrado = id
        if (ficha.optLong("bytes") == 0L) { almacen.borrar(id); return }
        trabajo.execute {
            val v = almacen.video(id)
            val mini = Almacen.miniatura(v)
            val huella = if (ficha.optBoolean("protegido")) runCatching { Almacen.sha256(v) }.getOrNull() else null
            val final = almacen.actualizar(id) { c ->
                if (mini != null) c.put("miniatura", mini)
                if (huella != null) c.put("hash", huella).put("hashBytes", v.length()).put("hashEn", System.currentTimeMillis())
            }
            Bus.emitir("cerrado", final ?: ficha)
        }
    }

    /** Cada minuto exacto de la duración elegida se cierra el fragmento y se abre otro. */
    private fun rotar() {
        val f = actual ?: return
        val ultimo = f.optJSONArray("eventos")?.let { if (it.length() > 0) it.optJSONObject(it.length() - 1) else null }
        val seguir = ultimo != null && System.currentTimeMillis() - ultimo.optLong("t") < 30_000
        val g = grabacion
        grabacion = null
        actual = null
        // Al dejar `actual` en null, el Finalize del viejo no se toma como corte.
        val viejoId = f.optString("id")
        almacen.actualizar(viejoId) { c -> c.put("telemetria", f.optJSONArray("telemetria")); c.put("eventos", f.optJSONArray("eventos")); c.put("protegido", f.optBoolean("protegido")) }
        g?.stop()
        // Recorder admite una grabación pendiente mientras cierra la anterior: el hueco es mínimo.
        abrirFragmento(seguir)
    }

    /* ---------------- Proteger ---------------- */

    fun proteger(motivo: String, g: Double?, origen: String): Int {
        val t = System.currentTimeMillis()
        var n = 0
        val ev = JSONObject().put("t", t).put("motivo", motivo).apply { if (g != null) put("g", g) }
        actual?.let { f ->
            f.put("protegido", true)
            Almacen.agregarEvento(f, ev)
            almacen.actualizar(f.getString("id")) { c -> c.put("protegido", true).put("eventos", f.optJSONArray("eventos")) }
            n++
        }
        // El fragmento anterior guarda los segundos previos al evento.
        val previoId = ultimoIdCerrado
        if (previoId != null && t - ultimoCierre < 15_000) {
            val previo = almacen.actualizar(previoId) { c ->
                if (!c.optBoolean("protegido")) {
                    c.put("protegido", true)
                    Almacen.agregarEvento(c, JSONObject().put("t", t).put("motivo", "$motivo (segundos previos)"))
                }
            }
            if (previo != null) {
                n++
                trabajo.execute {
                    if (previo.isNull("hash")) runCatching {
                        val v = almacen.video(previoId)
                        val h = Almacen.sha256(v)
                        almacen.actualizar(previoId) { c -> c.put("hash", h).put("hashBytes", v.length()).put("hashEn", System.currentTimeMillis()) }
                    }
                    Bus.emitir("cerrado", almacen.leer(previoId) ?: previo)
                }
            }
        }
        if (n > 0) {
            vibrar(longArrayOf(0, 60, 40, 60))
            anotar("proteccion", origen, motivo)
            Bus.emitir("protegido", JSONObject().put("motivo", motivo).put("n", n).put("origen", origen).apply { if (g != null) put("g", g) })
            superposicionSucia = true
            actualizarNotificacion()
        }
        return n
    }

    private fun impacto(g: Double) {
        if (!activa) return
        vibrar(longArrayOf(0, 200, 80, 200))
        anotar("impacto", "sistema", String.format(Locale.US, "%.2f G", g).replace('.', ','))
        proteger("Impacto detectado", g, "sistema")
        Bus.emitir("impacto", JSONObject().put("g", g))
    }

    /* ---------------- Pulso de cada segundo ---------------- */

    private var segundos = 0
    private val pulso = object : Runnable {
        override fun run() {
            if (!activa) return
            segundos++
            actual?.let { f ->
                f.optJSONArray("telemetria")?.put(tel.muestra())
                // Cada 10 s la telemetría queda en disco, por si el teléfono se apaga.
                if (segundos % 10 == 0) { val id = f.getString("id"); val copia = f.optJSONArray("telemetria"); trabajo.execute { almacen.actualizar(id) { c -> c.put("telemetria", copia) } } }
                if (System.currentTimeMillis() - f.optLong("inicio") >= ajustes.minutos * 60_000L) rotar()
            }
            revisarLlamada()
            superposicionSucia = true
            Bus.emitir("telemetria", tel.estado().put("grabando", true).put("inicio", inicioSesion).put("protegido", actual?.optBoolean("protegido") == true).put("llamada", enLlamada))
            if (segundos % 15 == 0) actualizarNotificacion()
            main.postDelayed(this, 1000)
        }
    }

    /**
     * Durante una llamada Android entrega el micrófono a la llamada: el video
     * sigue y el audio queda en silencio hasta colgar. Aquí solo se detecta para
     * avisarlo en la notificación, en el video y en el registro.
     */
    private fun revisarLlamada() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ahora = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE
        if (ahora == enLlamada) return
        enLlamada = ahora
        anotar(if (ahora) "llamada" else "finLlamada", "sistema", null)
        actual?.let { f -> Almacen.agregarEvento(f, JSONObject().put("t", System.currentTimeMillis()).put("motivo", if (ahora) "Llamada: el video sigue, audio en pausa" else "Llamada terminada")) }
        Bus.emitir("llamada", JSONObject().put("activa", ahora))
        actualizarNotificacion()
    }

    /* ---------------- Telemetría dentro del video ---------------- */

    @Volatile private var superposicionSucia = true
    private var ultimoTamano = ""
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun coordenadas(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return "GPS: buscando señal"
        return String.format(Locale.US, "%.5f°%s  %.5f°%s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "O")
    }

    /** Dibuja la franja de telemetría. Solo redibuja cuando cambia (una vez por segundo). */
    private fun dibujar(frame: Frame): Boolean {
        val rot = frame.rotationDegrees
        val crop = frame.cropRect
        val clave = "${crop.width()}x${crop.height()}@$rot"
        if (!superposicionSucia && clave == ultimoTamano) return true
        superposicionSucia = false
        ultimoTamano = clave
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        // Se dibuja «derecho» para quien vea el video: el cuadro se rota `rot` grados al reproducirse.
        val giro = rot % 180 != 0
        val w = (if (giro) crop.height() else crop.width()).toFloat()
        val h = (if (giro) crop.width() else crop.height()).toFloat()
        canvas.translate(crop.exactCenterX(), crop.exactCenterY())
        canvas.rotate(-rot.toFloat())
        canvas.translate(-w / 2f, -h / 2f)
        pintarFranja(canvas, w, h, pincel)
        canvas.restore()
        return true
    }

    /** Franja inferior con fecha, velocidad, fuerza G, coordenadas y estado (igual que la versión web). */
    private fun pintarFranja(canvas: Canvas, W: Float, H: Float, pincel: Paint) {
        val u = maxOf(14f, (minOf(W, H) / 28f).roundToInt().toFloat())
        val grande = u; val chica = (u * 0.78f).roundToInt().toFloat()
        val prot = actual?.optBoolean("protegido") == true
        val gp = tel.gPico
        val vel = tel.velKmh?.let { "${it.roundToInt()} km/h" } ?: "-- km/h"
        val placa = if (ajustes.placa.isNotBlank()) "  ${ajustes.placa.uppercase()}" else ""
        val estado = when { enLlamada -> "● REC · LLAMADA (SIN AUDIO)"; prot -> "● REC · BÓVEDA"; else -> "● REC" }
        val pares = listOf(
            Texto(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()), grande, Color.WHITE, true) to Texto(String.format(Locale.US, "%s  %.2f G", vel, gp), grande, if (gp >= tel.umbral) 0xFFFF5252.toInt() else 0xFFFFC107.toInt(), true),
            Texto("${coordenadas(tel.lat, tel.lon)}$placa", chica, 0xFFF4F1EA.toInt(), false) to Texto(estado, chica, if (prot || enLlamada) 0xFFFFC107.toInt() else 0xFFFF6B6B.toInt(), false),
        )
        fun preparar(e: Texto) { pincel.textSize = e.tam; pincel.color = e.color; pincel.typeface = Typeface.create(Typeface.MONOSPACE, if (e.negrita) Typeface.BOLD else Typeface.NORMAL) }
        fun ancho(e: Texto): Float { preparar(e); return pincel.measureText(e.t) }
        val x = (u * 0.9f).roundToInt().toFloat(); val hueco = u
        val filas = mutableListOf<Pair<Texto, Texto?>>()
        // Si izquierda y derecha no caben en una fila, la derecha baja a su propia fila.
        for ((izq, der) in pares) {
            if (ancho(izq) + hueco + ancho(der) <= W - 2 * x) filas.add(izq to der) else { filas.add(izq to null); filas.add(der to null) }
        }
        val interlinea = 1.3f
        val altoFranja = (filas.sumOf { (it.first.tam * interlinea).toDouble() } + u * 0.7f).roundToInt().toFloat()
        pincel.style = Paint.Style.FILL
        pincel.color = Color.argb(158, 15, 15, 15)
        canvas.drawRect(0f, H - altoFranja, W, H, pincel)
        pincel.color = if (prot) 0xFFFFC107.toInt() else 0xFF8C877E.toInt()
        canvas.drawRect(0f, H - altoFranja, (u * 0.3f).roundToInt().toFloat(), H, pincel)
        var y = H - altoFranja + (u * 0.35f).roundToInt()
        for ((izq, der) in filas) {
            y += izq.tam * interlinea
            val base = y - izq.tam * 0.28f
            preparar(izq); pincel.textAlign = Paint.Align.LEFT
            canvas.drawText(izq.t, x, base, pincel)
            if (der != null) { preparar(der); pincel.textAlign = Paint.Align.RIGHT; canvas.drawText(der.t, W - x, base, pincel) }
        }
        pincel.textAlign = Paint.Align.LEFT
    }

    private data class Texto(val t: String, val tam: Float, val color: Int, val negrita: Boolean)

    /** Guarda en la bóveda una foto del cuadro actual, con la franja de telemetría. */
    fun guardarFoto(cuadro: Bitmap, origen: String): JSONObject {
        val foto = if (cuadro.isMutable) cuadro else cuadro.copy(Bitmap.Config.ARGB_8888, true)
        pintarFranja(Canvas(foto), foto.width.toFloat(), foto.height.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
        val t = System.currentTimeMillis()
        val id = "f$t"
        val archivo = almacen.foto(id)
        archivo.outputStream().use { foto.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val miniatura = Bitmap.createScaledBitmap(foto, 240, (240f * foto.height / foto.width).roundToInt().coerceAtLeast(1), true)
        val mini = java.io.ByteArrayOutputStream().also { miniatura.compress(Bitmap.CompressFormat.JPEG, 62, it) }.toByteArray()
        val ficha = JSONObject()
            .put("id", id).put("tipo", "foto").put("inicio", t).put("fin", t).put("bytes", archivo.length()).put("partes", 1)
            .put("mime", "image/jpeg").put("ancho", foto.width).put("alto", foto.height).put("protegido", true).put("estado", "listo")
            .put("eventos", JSONArray().put(JSONObject().put("t", t).put("motivo", "Foto")))
            .put("telemetria", JSONArray().put(tel.muestra())).put("placa", ajustes.placa)
            .put("hash", Almacen.sha256(archivo)).put("hashBytes", archivo.length()).put("hashEn", System.currentTimeMillis())
            .put("miniatura", "data:image/jpeg;base64," + android.util.Base64.encodeToString(mini, android.util.Base64.NO_WRAP))
            .put("nativo", true).put("ruta", archivo.absolutePath)
        almacen.guardar(ficha)
        anotar("foto", origen, null)
        Bus.emitir("cerrado", ficha)
        return ficha
    }

    /* ---------------- Orientación del video ---------------- */

    private fun vigilarOrientacion(si: Boolean) {
        if (!si) { orientacion?.disable(); orientacion = null; return }
        orientacion = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                // Rotación de la pantalla que corresponde a cómo está sostenido el teléfono.
                rotacion = when (o) { in 45 until 135 -> Surface.ROTATION_270; in 135 until 225 -> Surface.ROTATION_180; in 225 until 315 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    /* ---------------- Notificación ---------------- */

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Grabación en curso", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Aviso fijo mientras PV Car graba, con los botones Proteger y Detener."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, "Avisos de PV Car", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Memoria llena o cámara detenida."
        })
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificacion(): Notification {
        val prot = actual?.optBoolean("protegido") == true
        val titulo = when { prot -> "PV Car protegiendo en la bóveda"; else -> "PV Car está grabando" }
        val partes = mutableListOf<String>()
        if (enLlamada) partes.add("Llamada: el video sigue, audio en pausa")
        tel.velKmh?.let { partes.add("${it.roundToInt()} km/h") }
        if (tel.gps == "sin permiso") partes.add("sin GPS")
        partes.add("Graba aunque salgas de la app")
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat_pvcar)
            .setContentTitle(titulo)
            .setContentText(partes.joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(0xFFFFC107.toInt())
            .setUsesChronometer(inicioSesion > 0)
            .setWhen(if (inicioSesion > 0) inicioSesion else System.currentTimeMillis())
            .setContentIntent(abrirApp())
            .addAction(R.drawable.ic_accion_proteger, "Proteger", pendiente(this, ACC_PROTEGER, "notificacion", 1))
            .addAction(R.drawable.ic_accion_detener, "Detener", pendiente(this, ACC_DETENER, "notificacion", 2))
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun actualizarNotificacion() {
        if (!activa) return
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF, notificacion()) }
    }

    private fun avisoSistema(titulo: String, texto: String) {
        val n = NotificationCompat.Builder(this, CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_stat_pvcar).setContentTitle(titulo).setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setAutoCancel(true).setContentIntent(abrirApp()).build()
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_AVISO, n) }
    }

    /* ---------------- Utilidades ---------------- */

    private fun vibrar(patron: LongArray) {
        if (!ajustes.vibrar) return
        runCatching { vibrarAhora(patron) }
    }

    @Suppress("DEPRECATION")
    private fun vibrarAhora(patron: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(patron, -1))
        else v.vibrate(patron, -1)
    }

    /** Eventos para el registro de la interfaz; se guardan aunque la interfaz esté cerrada. */
    private fun anotar(tipo: String, origen: String, detalle: String?) {
        Registro.agregar(this, tipo, origen, detalle)
        Bus.emitir("registro", JSONObject().put("tipo", tipo))
    }

    fun estado(): JSONObject = JSONObject()
        .put("grabando", activa).put("sesion", sesion).put("inicio", inicioSesion)
        .put("protegido", actual?.optBoolean("protegido") == true)
        .put("clipActual", actual?.optString("id") ?: JSONObject.NULL)
        .put("llamada", enLlamada)
        .put("telemetria", tel.estado())

    private fun emitirEstado() = Bus.emitir("estado", estado())
}
