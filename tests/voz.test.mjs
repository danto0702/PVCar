import { test } from "node:test";
import assert from "node:assert/strict";

// Reconocedor simulado al estilo de Chrome.
const creados = [];
let disponible = "unavailable";
let fallaLocal = false;
class FalsoSR {
  static async available({ langs }) { return typeof disponible === "function" ? disponible(langs[0]) : disponible; }
  constructor() { this.processLocally = false; creados.push(this); }
  start() {
    queueMicrotask(() => {
      if (this.processLocally && fallaLocal) { this.onerror?.({ error: "language-not-supported" }); this.onend?.(); return; }
      this.onstart?.();
    });
  }
  abort() {}
  oir(texto) { this.onresult?.({ resultIndex: 0, results: [Object.assign([{ transcript: texto }], { isFinal: true })] }); }
}
globalThis.webkitSpeechRecognition = FalsoSR;
const { Voz } = await import("../js/voz.js");
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

function nuevaVoz() {
  const v = new Voz();
  const estados = [], ordenes = [];
  v.addEventListener("estado", (e) => estados.push(e.detail));
  v.addEventListener("orden", (e) => ordenes.push(e.detail));
  return { v, estados, ordenes };
}

test("sin paquete local usa el servicio del navegador y arranca", async () => {
  creados.length = 0; disponible = "unavailable"; fallaLocal = false;
  const { v, estados, ordenes } = nuevaVoz();
  v.iniciar(); await espera(20);
  const rec = creados.at(-1);
  assert.equal(rec.processLocally, false, "no debe forzar el modo local");
  assert.equal(rec.lang, "es-CO");
  assert.deepEqual(estados, ["escuchando"]);
  rec.oir("Hola Pascal, toma una foto");
  assert.deepEqual(ordenes.map((o) => o.accion), ["foto"]);
  v.detener();
});

test("con paquete local disponible lo usa", async () => {
  creados.length = 0; disponible = (lang) => (lang === "es-US" ? "available" : "unavailable"); fallaLocal = false;
  const { v, estados } = nuevaVoz();
  v.iniciar(); await espera(20);
  assert.equal(creados.at(-1).processLocally, true);
  assert.equal(creados.at(-1).lang, "es-US");
  assert.deepEqual(estados, ["escuchando"]);
  v.detener();
});

test("si el modo local falla, se pasa al servicio del navegador", async () => {
  creados.length = 0; disponible = "available"; fallaLocal = true;
  const { v, estados } = nuevaVoz();
  v.iniciar(); await espera(400);
  assert.ok(creados.length >= 2, "debe reintentar");
  assert.equal(creados.at(-1).processLocally, false);
  assert.ok(estados.includes("escuchando"), `estados: ${estados}`);
  v.detener();
});
