// Puente con la app Android de PV Car (Capacitor).
// En la app, quien graba es un servicio nativo que sigue funcionando con
// PV Car en segundo plano, con la pantalla apagada o durante una llamada.
// Esta interfaz es la misma de la web: aquí solo se traduce a ese servicio.
// En el navegador `esNativo` es false y nada de esto se usa.

import * as db from "./db.js";
import { Telemetria } from "./telemetria.js";

const cap = globalThis.Capacitor;
export const esNativo = !!(cap && typeof cap.nativePromise === "function" && cap.isNativePlatform?.() && cap.getPlatform?.() === "android");

const llamar = (metodo, opciones = {}) => cap.nativePromise("PVNativo", metodo, opciones);

export const nativo = esNativo ? {
  disponible: () => llamar("disponible"),
  permisos: () => llamar("permisos"),
  pedirPermisos: (audio) => llamar("pedirPermisos", { audio: !!audio }),
  pedirSinRestriccionBateria: () => llamar("pedirSinRestriccionBateria"),
  iniciar: (ajustes, origen) => llamar("iniciar", { ajustes, origen }),
  detener: (origen) => llamar("detener", { origen }),
  proteger: (motivo, origen) => llamar("proteger", { motivo, origen }),
  configurar: (ajustes) => llamar("configurar", { ajustes }),
  estado: () => llamar("estado"),
  listar: () => llamar("listar"),
  actualizar: (id, cambios) => llamar("actualizar", { id, ...cambios }),
  huella: (id) => llamar("huella", { id }),
  borrar: (ids) => llamar("borrar", { ids }),
  foto: (origen) => llamar("foto", { origen }),
  registro: () => llamar("registro"),
  compartir: (o) => llamar("compartir", o),
  on: (evento, fn) => cap.addListener("PVNativo", evento, (d) => fn(d || {})),
} : null;

/** URL que la vista web puede reproducir para un archivo de la carpeta de la app. */
export const urlArchivo = (ruta) => cap.convertFileSrc(ruta);

export async function base64DeBlob(blob) {
  const datos = await new Promise((ok, mal) => {
    const r = new FileReader();
    r.onload = () => ok(r.result);
    r.onerror = () => mal(r.error);
    r.readAsDataURL(blob);
  });
  return String(datos).slice(String(datos).indexOf(",") + 1);
}

/* ---------- Clips nativos ⇄ IndexedDB ---------- */

// Los videos viven en archivos de la app; IndexedDB guarda solo sus fichas para
// que Videos, la bóveda y el acta funcionen igual que en la web.
if (esNativo) db.ganchoNativo({
  actualizar: (c) => nativo.actualizar(c.id, { protegido: !!c.protegido, eventos: c.eventos || [] }),
  borrar: (id) => nativo.borrar([id]),
});

let sincronizando = null;
/** Trae las fichas del servicio. Devuelve la lista de clips. */
export function sincronizar() {
  if (sincronizando) return sincronizando;
  sincronizando = (async () => {
    const { clips } = await nativo.listar();
    const vivos = new Set();
    for (const c of clips) {
      vivos.add(c.id);
      await db.guardarClip({ ...c, nativo: true, url: urlArchivo(c.ruta) });
    }
    for (const c of await db.listarClips()) if (c.nativo && !vivos.has(c.id)) await db.borrarClip(c.id, { soloLocal: true });
    return clips;
  })().finally(() => { sincronizando = null; });
  return sincronizando;
}

/* ---------- Telemetría: la del servicio mientras graba ---------- */

export class TelemetriaNativa extends Telemetria {
  constructor() {
    super();
    this._nativa = false;
  }
  /** Sin grabar, el GPS de la vista web sirve para el inicio automático. */
  iniciar() { if (!this._nativa) super.iniciar(); }
  usarNativa(si) {
    if (si === this._nativa) return;
    this._nativa = si;
    if (si) super.detener();
  }
  recibir(d) {
    if (!this._nativa) return;
    Object.assign(this.estado, {
      lat: d.lat ?? null, lon: d.lon ?? null, precision: d.precision ?? null, velKmh: d.velKmh ?? null,
      g: d.g || 0, gPico: d.gPico || 0, gps: d.gps || "apagado", sensor: d.sensor || "apagado",
    });
    this._emitir();
  }
  // El impacto lo detecta el servicio (también con la app en segundo plano).
  _movimiento() {}
}

/* ---------- Grabador: misma interfaz que js/grabador.js ---------- */

const ORIGENES_EXTERNOS = new Set(["notificacion", "ventana", "sistema"]);

export class GrabadorNativo extends EventTarget {
  constructor({ telemetria }) {
    super();
    this.tel = telemetria;
    this.ajustes = { resolucion: "1080p", minutos: 3, cuotaGB: 16, audio: false, placa: "" };
    this.vibrar = true;
    this.grabando = false;
    this.stream = null;
    this.clipActual = null;
    this.sesion = null;
    this.inicio = 0;
    this.llamada = false;
    this._t = null;
    nativo.on("estado", (d) => this._estado(d));
    nativo.on("telemetria", (d) => { this._estado(d); this.tel.recibir(d); });
    nativo.on("llamada", (d) => { this.llamada = !!d.activa; this.dispatchEvent(new CustomEvent("llamada", { detail: this.llamada })); });
    for (const ev of ["cerrado", "fragmento", "reciclado"]) nativo.on(ev, () => this._refrescar(ev));
    nativo.on("protegido", (d) => {
      if (this.clipActual) this.clipActual.protegido = true;
      this._refrescar("cerrado");
      // Lo protegido desde la notificación, la ventana flotante o por impacto se anuncia en pantalla.
      if (ORIGENES_EXTERNOS.has(d.origen)) this.dispatchEvent(new CustomEvent("protegido-externo", { detail: d }));
    });
    nativo.on("sin-espacio", () => this.dispatchEvent(new CustomEvent("sin-espacio")));
    nativo.on("error", (d) => this.dispatchEvent(new CustomEvent("error", { detail: new Error(d.mensaje || "Error del grabador") })));
    nativo.on("registro", () => this.dispatchEvent(new CustomEvent("registro")));
  }

  _estado(d) {
    const antes = this.grabando;
    this.grabando = !!d.grabando;
    this.stream = this.grabando ? {} : null;
    if (d.sesion) this.sesion = d.sesion;
    if (d.inicio) this.inicio = d.inicio;
    if (typeof d.llamada === "boolean") this.llamada = d.llamada;
    this.clipActual = this.grabando ? { id: d.clipActual || this.clipActual?.id || null, protegido: !!d.protegido } : null;
    this.tel.usarNativa(this.grabando);
    if (antes !== this.grabando) this.dispatchEvent(new Event("estado"));
  }

  /** Agrupa los avisos del servicio: una sola sincronización cada medio segundo. */
  _refrescar(tipo) {
    clearTimeout(this._t);
    this._t = setTimeout(async () => {
      try {
        const clips = await sincronizar();
        const ultimo = clips.find((c) => c.estado !== "grabando");
        this.dispatchEvent(new CustomEvent(tipo === "fragmento" ? "fragmento" : "cerrado", { detail: ultimo ? await db.obtenerClip(ultimo.id) : null }));
      } catch (err) { this.dispatchEvent(new CustomEvent("error", { detail: err })); }
    }, 500);
  }

  ajustesNativos() {
    return { ...this.ajustes, umbralG: this.tel.umbral, vibrar: this.vibrar };
  }

  configurar() {
    nativo.configurar(this.ajustesNativos()).catch(() => {});
  }

  async refrescarEstado() {
    this._estado(await nativo.estado());
  }

  async encenderCamara() {}
  apagarCamara() {}

  async iniciar(origen = "manual") {
    if (this.grabando) return;
    this.sesion = null;
    const p = await nativo.pedirPermisos(this.ajustes.audio);
    if (p.camara !== "granted") {
      const e = new Error("Sin permiso para la cámara");
      e.name = "NotAllowedError";
      throw e;
    }
    await nativo.iniciar(this.ajustesNativos(), origen);
    this.grabando = true;
    this.stream = {};
    this.inicio = Date.now();
    this.clipActual = { id: null, protegido: false };
    this.tel.usarNativa(true);
    // El servicio confirma la sesión enseguida.
    for (let i = 0; i < 20 && !this.sesion?.startsWith("s"); i++) await new Promise((ok) => setTimeout(ok, 100));
    await this.refrescarEstado().catch(() => {});
  }

  async detener(origen = "manual") {
    if (!this.grabando) return;
    await nativo.detener(origen);
    this.grabando = false;
    this.stream = null;
    this.clipActual = null;
    this.tel.usarNativa(false);
    // El último archivo tarda un instante en cerrarse.
    await new Promise((ok) => setTimeout(ok, 1800));
    await sincronizar().catch(() => {});
  }

  async proteger(motivo, extra = {}, origen = "manual") {
    if (!this.grabando) return [];
    const { n } = await nativo.proteger(motivo, origen);
    if (this.clipActual) this.clipActual.protegido = n > 0;
    await sincronizar().catch(() => {});
    return n ? Array.from({ length: n }, (_, i) => `${this.clipActual?.id || "nativo"}-${i}`) : [];
  }

  async foto(origen = "manual") {
    const meta = await nativo.foto(origen);
    await sincronizar().catch(() => {});
    return { meta: { ...meta, url: urlArchivo(meta.ruta) }, blob: null };
  }
}
