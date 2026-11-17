import { test } from "node:test";
import assert from "node:assert/strict";
import { Autoarranque } from "../js/autoarranque.js";
import { Registro } from "../js/registro.js";

const paso = (a, v, t, grabando = false, iniciadaPorAuto = false) => a.actualizar({ velKmh: v, ahora: t, grabando, iniciadaPorAuto });

test("inicia solo si la velocidad se sostiene 5 s sobre el umbral", () => {
  const a = new Autoarranque({ umbralKmh: 15, minutosParada: 5 });
  assert.equal(paso(a, 20, 0), null);
  assert.equal(paso(a, 5, 2000), null, "un salto del GPS reinicia la cuenta");
  assert.equal(paso(a, 18, 3000), null);
  assert.equal(paso(a, 22, 7000), null);
  assert.equal(paso(a, 25, 8000), "iniciar");
  assert.equal(paso(a, null, 9000), null, "sin GPS no decide");
});

test("detiene tras N minutos quieto solo si la inició el modo automático", () => {
  const a = new Autoarranque({ umbralKmh: 15, minutosParada: 2 });
  assert.equal(paso(a, 0, 0, true, false), null);
  assert.equal(paso(a, 0, 10 * 60_000, true, false), null, "una grabación manual no se detiene sola");
  assert.equal(paso(a, 1, 0, true, true), null);
  assert.equal(paso(a, 30, 60_000, true, true), null, "moverse reinicia la parada");
  assert.equal(paso(a, 0, 70_000, true, true), null);
  assert.equal(paso(a, 2, 70_000 + 119_000, true, true), null);
  assert.equal(paso(a, 0, 70_000 + 120_000, true, true), "detener");
  const nunca = new Autoarranque({ umbralKmh: 15, minutosParada: 0 });
  assert.equal(paso(nunca, 0, 0, true, true), null);
  assert.equal(paso(nunca, 0, 60 * 60_000, true, true), null, "0 = no detener nunca");
});

test("registro guarda, recorta y exporta CSV", () => {
  const datos = new Map();
  const almacen = { getItem: (k) => datos.get(k) ?? null, setItem: (k, v) => datos.set(k, v), removeItem: (k) => datos.delete(k) };
  const r = new Registro(almacen, { max: 3 });
  r.agregar("inicio", { origen: "auto", t: 1 });
  r.agregar("impacto", { origen: "sistema", detalle: '2,8 G "fuerte"', t: 2 });
  r.agregar("proteccion", { origen: "voz", t: 3 });
  r.agregar("detencion", { origen: "manual", t: 4 });
  assert.deepEqual(r.listar().map((e) => e.tipo), ["impacto", "proteccion", "detencion"], "conserva los últimos 3");
  const csv = r.csv((t) => `T${t}`);
  assert.ok(csv.startsWith("﻿Fecha y hora;Evento;Origen;Detalle"));
  assert.match(csv, /"T2";"Impacto detectado";"sistema";"2,8 G ""fuerte"""/);
  assert.match(csv, /"T3";"Evidencia protegida";"voz";""/);
  r.limpiar();
  assert.deepEqual(r.listar(), []);
});
