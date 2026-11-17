import { test } from "node:test";
import assert from "node:assert/strict";
import { agruparSesiones, duracionTexto } from "../js/sesiones.js";

test("agrupa las partes de un viaje en una grabación ordenada", () => {
  const clips = [
    { id: "v3", tipo: "video", sesion: "s1", inicio: 360_000, fin: 420_000, bytes: 30, protegido: true, estado: "listo" },
    { id: "v1", tipo: "video", sesion: "s1", inicio: 0, fin: 180_000, bytes: 100, protegido: false, estado: "listo" },
    { id: "v2", tipo: "video", sesion: "s1", inicio: 180_000, fin: 360_000, bytes: 100, protegido: false, estado: "listo" },
    { id: "v9", tipo: "video", sesion: "s2", inicio: 900_000, fin: 960_000, bytes: 20, protegido: false, estado: "listo" },
    { id: "f1", tipo: "foto", inicio: 500_000, fin: 500_000, bytes: 5, protegido: true, estado: "listo" },
    { id: "v0", tipo: "video", inicio: -100_000, fin: -40_000, bytes: 7, protegido: false, estado: "listo" },
  ];
  const g = agruparSesiones(clips);
  assert.deepEqual(g.map((x) => x.id), ["s2", "s1", "v0"], "más reciente primero; la foto no es una grabación");
  const s1 = g[1];
  assert.deepEqual(s1.partes.map((p) => p.id), ["v1", "v2", "v3"]);
  assert.equal(s1.inicio, 0); assert.equal(s1.fin, 420_000);
  assert.equal(s1.bytes, 230); assert.equal(s1.segundos, 420); assert.equal(s1.protegidas, 1);
  assert.equal(s1.grabando, false);
});

test("una grabación en curso no tiene fin", () => {
  const ahora = Date.now();
  const [g] = agruparSesiones([{ id: "v1", tipo: "video", sesion: "s", inicio: ahora - 5000, fin: null, bytes: 1, protegido: false, estado: "grabando" }]);
  assert.equal(g.grabando, true); assert.equal(g.fin, null);
  assert.ok(g.segundos >= 4 && g.segundos <= 6);
});

test("duración legible", () => {
  assert.equal(duracionTexto(45), "45 s");
  assert.equal(duracionTexto(480), "8 min");
  assert.equal(duracionTexto(500), "8 min 20 s");
  assert.equal(duracionTexto(3 * 3600 + 120), "3 h 2 min");
});
