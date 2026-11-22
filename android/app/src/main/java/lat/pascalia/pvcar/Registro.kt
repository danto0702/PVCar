package lat.pascalia.pvcar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Cola de eventos del servicio: la interfaz web la recoge y la pasa a su registro. */
object Registro {
    private fun archivo(ctx: Context) = File(ctx.filesDir, "registro-pendiente.json")

    @Synchronized fun agregar(ctx: Context, tipo: String, origen: String, detalle: String?) {
        val lista = leer(ctx)
        lista.put(JSONObject().put("t", System.currentTimeMillis()).put("tipo", tipo).put("origen", origen).apply { if (detalle != null) put("detalle", detalle) })
        while (lista.length() > 500) lista.remove(0)
        archivo(ctx).writeText(lista.toString())
    }

    /** Devuelve los eventos pendientes y vacía la cola. */
    @Synchronized fun recoger(ctx: Context): JSONArray {
        val lista = leer(ctx)
        archivo(ctx).delete()
        return lista
    }

    private fun leer(ctx: Context): JSONArray =
        runCatching { JSONArray(archivo(ctx).readText()) }.getOrDefault(JSONArray())
}
