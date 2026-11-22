package lat.pascalia.pvcar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * Carpeta privada de la app (Android/data/lat.pascalia.pvcar/files/Movies/pvcar):
 * cada fragmento es un .mp4 con su ficha .json, con los mismos campos que
 * los clips de la versión web, para que la interfaz los muestre igual.
 * No necesita permiso de almacenamiento y no toca la galería.
 */
class Almacen(ctx: Context) {
    val dir: File = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "pvcar").apply { mkdirs() }

    fun video(id: String) = File(dir, "$id.mp4")
    fun foto(id: String) = File(dir, "$id.jpg")

    /** Archivo de una ficha: video .mp4 o foto .jpg. */
    fun archivo(m: JSONObject): File = m.optString("ruta").takeIf { it.isNotBlank() }?.let { File(it) }
        ?: if (m.optString("tipo") == "foto") foto(m.getString("id")) else video(m.getString("id"))
    fun archivo(id: String): File = leer(id)?.let { archivo(it) } ?: video(id)
    private fun ficha(id: String) = File(dir, "$id.json")

    @Synchronized fun leer(id: String): JSONObject? =
        ficha(id).takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    @Synchronized fun guardar(m: JSONObject) {
        val id = m.getString("id")
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(m.toString())
        if (!tmp.renameTo(ficha(id))) { ficha(id).writeText(m.toString()); tmp.delete() }
    }

    /** Modifica una ficha de forma atómica. */
    @Synchronized fun actualizar(id: String, fn: (JSONObject) -> Unit): JSONObject? {
        val m = leer(id) ?: return null
        fn(m)
        guardar(m)
        return m
    }

    /** Todas las fichas, de la más reciente a la más antigua, con el tamaño real del archivo. */
    @Synchronized fun listar(): List<JSONObject> {
        val fichas = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return fichas.mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
            .onEach { m -> val v = archivo(m); if (v.exists()) m.put("bytes", v.length()) }
            .sortedByDescending { it.optLong("inicio") }
    }

    @Synchronized fun borrar(id: String) {
        video(id).delete(); foto(id).delete(); ficha(id).delete()
    }

    fun usados(): Long = listar().sumOf { it.optLong("bytes") }

    /**
     * Recicla lo más antiguo no protegido hasta que quepa un fragmento nuevo
     * dentro de la cuota y del espacio libre real del teléfono.
     * Devuelve cuántos borró, o -1 si los protegidos llenaron la cuota.
     */
    @Synchronized fun reciclar(cuota: Long, necesarios: Long, enCurso: String?): Int {
        val clips = listar()
        var total = clips.sumOf { it.optLong("bytes") }
        val libres = clips.filter { !it.optBoolean("protegido") && it.optString("id") != enCurso && it.optString("estado") != "grabando" }
            .sortedBy { it.optLong("inicio") }
        val reserva = 300L * 1024 * 1024 // margen para que el teléfono no quede lleno
        fun cabe() = total + necesarios <= cuota && dir.usableSpace > necesarios + reserva
        var n = 0
        for (c in libres) {
            if (cabe()) break
            borrar(c.getString("id"))
            total -= c.optLong("bytes")
            n++
        }
        return if (cabe()) n else -1
    }

    /** Fichas que quedaron «grabando» tras un cierre inesperado. */
    @Synchronized fun recuperarHuerfanos(enCurso: String?): Int {
        var n = 0
        for (c in listar()) {
            val id = c.getString("id")
            if (c.optString("estado") != "grabando" || id == enCurso) continue
            n++
            val v = archivo(c)
            if (!v.exists() || v.length() == 0L) { borrar(id); continue }
            c.put("estado", "recuperado").put("fin", max(c.optLong("inicio"), v.lastModified()))
            guardar(c)
        }
        return n
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Miniatura 240×135 en JPEG como data URL, para las tarjetas de Videos. */
        fun miniatura(f: File, segundo: Long = 1): String? = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val cuadro = r.getFrameAtTime(segundo * 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                val salida = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888)
                val esc = max(240f / cuadro.width, 135f / cuadro.height) // recorte centrado
                val w = (cuadro.width * esc).toInt(); val h = (cuadro.height * esc).toInt()
                Canvas(salida).drawBitmap(cuadro, null, Rect((240 - w) / 2, (135 - h) / 2, (240 - w) / 2 + w, (135 - h) / 2 + h), null)
                cuadro.recycle()
                val bytes = ByteArrayOutputStream().also { salida.compress(Bitmap.CompressFormat.JPEG, 62, it) }.toByteArray()
                salida.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally { r.release() }
        }.getOrNull()

        fun agregarEvento(m: JSONObject, ev: JSONObject) {
            val lista = m.optJSONArray("eventos") ?: JSONArray().also { m.put("eventos", it) }
            lista.put(ev)
        }
    }
}
