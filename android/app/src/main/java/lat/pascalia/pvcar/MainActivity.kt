package lat.pascalia.pvcar

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import com.getcapacitor.BridgeActivity
import java.lang.ref.WeakReference

/**
 * La interfaz de PV Car (la misma de la web) va encima de la vista de cámara.
 * Al salir de la app mientras graba, queda una ventana flotante (imagen en imagen)
 * con los botones Proteger y Detener; si se cierra, la grabación sigue.
 */
class MainActivity : BridgeActivity() {

    companion object {
        @Volatile var vista: WeakReference<PreviewView>? = null
            private set
    }

    private lateinit var camara: PreviewView
    private lateinit var insignia: TextView
    private var enPip = false

    private val oyente = Bus.Oyente { tipo, datos ->
        if (tipo == "estado") runOnUiThread {
            actualizarPip()
            // Si se detuvo desde la ventana flotante, la ventana se cierra.
            if (!datos.optBoolean("grabando") && enPip) moveTaskToBack(true)
        }
        if (tipo == "protegido" || tipo == "llamada") runOnUiThread { pintarInsignia() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(PVNativoPlugin::class.java)
        super.onCreate(savedInstanceState)
        val web = bridge.webView
        web.setBackgroundColor(Color.TRANSPARENT)
        val raiz = web.parent as ViewGroup
        camara = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // se compone bajo la interfaz
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        raiz.addView(camara, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        insignia = TextView(this).apply {
            text = "● REC"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xCC141413.toInt()) }
            visibility = View.GONE
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START; setMargins(dp(6f), dp(6f), 0, 0)
        }
        val contenedor = FrameLayout(this).apply { addView(insignia, lp) }
        raiz.addView(contenedor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contenedor.isClickable = false
        Bus.agregar(oyente)
    }

    override fun onStart() {
        super.onStart()
        vista = WeakReference(camara)
        GrabacionService.instancia?.conectarVista(camara)
        actualizarPip()
    }

    override fun onStop() {
        super.onStop()
        vista = null
        GrabacionService.instancia?.conectarVista(null)
    }

    override fun onDestroy() {
        Bus.quitar(oyente)
        super.onDestroy()
    }

    /* ---------------- Ventana flotante ---------------- */

    private fun parametrosPip(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val horizontal = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val acciones = listOf(
            RemoteAction(Icon.createWithResource(this, R.drawable.ic_accion_proteger), "Proteger", "Proteger el video en la bóveda",
                GrabacionService.pendiente(this, GrabacionService.ACC_PROTEGER, "ventana", 11)),
            RemoteAction(Icon.createWithResource(this, R.drawable.ic_accion_detener), "Detener", "Detener la grabación",
                GrabacionService.pendiente(this, GrabacionService.ACC_DETENER, "ventana", 12)),
        )
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(if (horizontal) Rational(16, 9) else Rational(9, 16))
            .setActions(acciones)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(GrabacionService.grabando).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun actualizarPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature("android.software.picture_in_picture")) return
        runCatching { parametrosPip()?.let { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Desde Android 12 entra sola (setAutoEnterEnabled); antes, se pide aquí.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && GrabacionService.grabando) {
            runCatching { parametrosPip()?.let { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enPip = isInPictureInPictureMode
        // En la ventana solo se ve la cámara con la insignia de grabación.
        bridge.webView.visibility = if (enPip) View.INVISIBLE else View.VISIBLE
        pintarInsignia()
    }

    private fun pintarInsignia() {
        if (!::insignia.isInitialized) return
        val s = GrabacionService.instancia
        insignia.text = when { s?.enLlamada == true -> "● REC · LLAMADA"; s?.estado()?.optBoolean("protegido") == true -> "● REC · BÓVEDA"; else -> "● REC" }
        insignia.visibility = if (enPip && GrabacionService.grabando) View.VISIBLE else View.GONE
    }
}
