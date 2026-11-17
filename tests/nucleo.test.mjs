import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { Sha256, sha256Hex } from "../js/sha256.js";
import { Copiloto, interpretar, separarActivacion, ACCIONES as A } from "../js/comandos.js";
import { planReciclaje, horasDeGrabacion, bytesPorFragmento, formatoHoras, GB } from "../js/fifo.js";
import { Pdf, winAnsi, ajustar } from "../js/pdf.js";

const ref = (b) => createHash("sha256").update(b).digest("hex");

test("SHA-256 coincide con node:crypto en tamaños límite", () => {
  for (const n of [0, 1, 55, 56, 63, 64, 65, 119, 120, 1000, 100_003]) {
    const b = randomBytes(n);
    assert.equal(sha256Hex(b), ref(b), `n=${n}`);
  }
  assert.equal(sha256Hex(new TextEncoder().encode("abc")), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});

test("SHA-256 incremental por partes irregulares = de una vez", () => {
  const b = randomBytes(300_001);
  const h = new Sha256();
  let i = 0;
  for (const paso of [1, 63, 64, 65, 7, 4096, 99_999]) { h.update(b.subarray(i, i + paso)); i += paso; }
  h.update(b.subarray(i));
  assert.equal(h.hex(), ref(b));
});

test("palabra de activación", () => {
  assert.equal(separarActivacion("Hola Pascal, activa la grabación"), "activa la grabacion");
  assert.equal(separarActivacion("Okay Pascual toma una foto"), "toma una foto");
  assert.equal(separarActivacion("OK, Pascal."), "");
  assert.equal(separarActivacion("activa la grabación"), null);
  assert.equal(separarActivacion("Pascal es mi perro"), null);
});

test("comandos del manual", () => {
  const casos = {
    "activa la grabación": A.iniciar, "inicia la grabación": A.iniciar, "empieza a grabar": A.iniciar,
    "detén la grabación": A.detener, "para la grabación": A.detener, "deja de grabar": A.detener,
    "guarda este video": A.proteger, "acaba de pasar un accidente": A.proteger, "me chocaron": A.proteger,
    "toma una foto": A.foto, "modo ahorro térmico": A.ahorroOn, "sal del modo ahorro": A.ahorroOff,
    "desactiva el ahorro": A.ahorroOff, "cuánto espacio queda": A.estado,
  };
  for (const [t, acc] of Object.entries(casos)) assert.equal(interpretar(t), acc, t);
  assert.equal(interpretar("qué lindo día"), null);
});

test("copiloto: activación sola abre ventana para el comando", () => {
  let t = 0;
  const c = new Copiloto({ ventanaMs: 5000, ahora: () => t });
  assert.equal(c.oir("toma una foto"), null, "sin activación se ignora");
  assert.deepEqual(c.oir("Hola Pascal"), { tipo: "atento" });
  t = 3000;
  assert.deepEqual(c.oir("toma una foto"), { tipo: "accion", accion: A.foto });
  assert.equal(c.oir("toma una foto"), null, "la ventana se cierra tras usarla");
  c.oir("okay pascal"); t = 9000;
  assert.equal(c.oir("guarda este video"), null, "ventana vencida");
  assert.deepEqual(c.oir("Okay Pascal, acaba de pasar un accidente"), { tipo: "accion", accion: A.proteger });
});

test("FIFO: borra los más antiguos no protegidos hasta que quepa", () => {
  const clips = [
    { id: "a", inicio: 1, bytes: 100, protegido: false },
    { id: "b", inicio: 2, bytes: 100, protegido: true },
    { id: "c", inicio: 3, bytes: 100, protegido: false },
    { id: "d", inicio: 4, bytes: 100, protegido: false, grabando: true },
  ];
  assert.deepEqual(planReciclaje(clips, 400, 100), { borrar: ["a"], cabe: true, usados: 300, protegidos: 100 });
  assert.deepEqual(planReciclaje(clips, 1000, 100).borrar, []);
  const lleno = planReciclaje(clips, 250, 100);
  assert.deepEqual(lleno.borrar, ["a", "c"]);
  assert.equal(lleno.cabe, false, "protegido + en curso ya no dejan espacio");
});

test("autonomía según cuota y resolución", () => {
  assert.equal(bytesPorFragmento("1080p", 1), 60_000_000);
  assert.ok(Math.abs(horasDeGrabacion(32, "1080p") - 32 * GB / (8e6 / 8 * 3600)) < 1e-9);
  assert.equal(formatoHoras(horasDeGrabacion(32, "1080p")), "8 h 53 min");
  assert.equal(formatoHoras(0.5), "30 min");
});

test("PDF: estructura válida y acentos WinAnsi", () => {
  assert.equal(winAnsi("Fuerza G (máx) — ñ\\"), "Fuerza G \\(m\xE1x\\) \x97 \xF1\\\\");
  assert.ok(ajustar("uno dos tres cuatro cinco seis", 40, 10).length > 1);
  const doc = new Pdf({ titulo: "Acta" });
  const jpg = doc.jpeg(new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), 1, 1);
  doc.pagina().texto(50, 50, "Bóveda forense").imagen(jpg, 50, 60, 10, 10);
  doc.pagina().rect(10, 10, 20, 20, { relleno: "#FFC107" });
  const b = doc.bytes();
  const s = Buffer.from(b).toString("latin1");
  assert.ok(s.startsWith("%PDF-1.4") && s.trimEnd().endsWith("%%EOF"));
  const xref = Number(/startxref\n(\d+)/.exec(s)[1]);
  assert.equal(s.slice(xref, xref + 4), "xref");
  // Cada entrada del xref apunta al inicio de su objeto.
  const entradas = s.slice(xref).split("\n").slice(3).filter((l) => / n $/.test(l));
  entradas.forEach((l, i) => assert.ok(s.startsWith(`${i + 1} 0 obj`, Number(l.slice(0, 10))), `obj ${i + 1}`));
  assert.match(s, /\/Count 2/);
});
