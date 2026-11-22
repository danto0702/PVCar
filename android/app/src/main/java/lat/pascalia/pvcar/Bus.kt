package lat.pascalia.pvcar

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Avisos del servicio de grabación hacia la interfaz (plugin y actividad). */
object Bus {
    fun interface Oyente { fun evento(tipo: String, datos: JSONObject) }

    private val oyentes = CopyOnWriteArraySet<Oyente>()

    fun agregar(o: Oyente) { oyentes.add(o) }
    fun quitar(o: Oyente) { oyentes.remove(o) }
    fun emitir(tipo: String, datos: JSONObject = JSONObject()) {
        for (o in oyentes) runCatching { o.evento(tipo, datos) }
    }
}
