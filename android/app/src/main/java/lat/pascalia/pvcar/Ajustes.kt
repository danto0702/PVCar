package lat.pascalia.pvcar

import android.content.Context
import org.json.JSONObject

/** Ajustes de grabación que envía la interfaz web; se guardan para los botones de la notificación. */
data class Ajustes(
    val cuotaGB: Double = 16.0,
    val resolucion: String = "1080p",
    val minutos: Int = 3,
    val umbralG: Double = 2.5,
    val audio: Boolean = false,
    val placa: String = "",
    val vibrar: Boolean = true,
) {
    /** Bits por segundo del video: los mismos de la versión web (js/fifo.js). */
    val bps: Int get() = when (resolucion) { "720p" -> 4_000_000; "4k" -> 25_000_000; else -> 8_000_000 }

    /** Bytes estimados de un fragmento, para reciclar antes de abrirlo. */
    val bytesFragmento: Long get() = ((bps + if (audio) 96_000 else 0).toLong() * minutos * 60L) / 8L

    val cuotaBytes: Long get() = (cuotaGB * 1e9).toLong()

    fun json(): JSONObject = JSONObject()
        .put("cuotaGB", cuotaGB).put("resolucion", resolucion).put("minutos", minutos)
        .put("umbralG", umbralG).put("audio", audio).put("placa", placa).put("vibrar", vibrar)

    companion object {
        private const val PREFS = "pvcar"
        private const val CLAVE = "ajustes"

        fun de(j: JSONObject?): Ajustes {
            if (j == null) return Ajustes()
            val d = Ajustes()
            return Ajustes(
                cuotaGB = j.optDouble("cuotaGB", d.cuotaGB).coerceIn(4.0, 64.0),
                resolucion = j.optString("resolucion", d.resolucion).ifBlank { d.resolucion },
                minutos = j.optInt("minutos", d.minutos).coerceIn(1, 5),
                umbralG = j.optDouble("umbralG", d.umbralG),
                audio = j.optBoolean("audio", d.audio),
                placa = j.optString("placa", d.placa),
                vibrar = j.optBoolean("vibrar", d.vibrar),
            )
        }

        fun leer(ctx: Context): Ajustes {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE, null)
            return de(s?.let { runCatching { JSONObject(it) }.getOrNull() })
        }

        fun guardar(ctx: Context, a: Ajustes) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLAVE, a.json().toString()).apply()
        }
    }
}
