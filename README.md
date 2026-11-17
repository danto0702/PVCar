# PV Car · Pascal Video Car

Cámara vehicular en bucle, copiloto por voz y bóveda forense para Android e iOS.
Un desarrollo del ecosistema [PascalIA](https://pascalia.lat).

Esta primera versión es una **PWA** (aplicación web instalable): se abre desde el navegador del
celular, se instala en la pantalla de inicio y funciona sin conexión. Es la base para empaquetarla
después como app nativa (ver «Siguientes pasos»).

## Qué hace hoy

| Especificación | Implementación |
|---|---|
| **3.1 Grabación en bucle (FIFO)** | Cámara trasera → `<canvas>` → `MediaRecorder`. Fragmentos de 1, 3 o 5 min (`.mp4` H.264 donde el navegador lo permite, si no `.webm` VP9/VP8) a 30 FPS. El fragmento nuevo arranca antes de cerrar el anterior: no hay hueco entre clips. |
| Cuota configurable 4–64 GB | Deslizador con cálculo de horas según 720p (4 Mbps), 1080p (8 Mbps) o 4K (25 Mbps). Si el navegador concede menos espacio, el bucle se ajusta a lo real. |
| Grabación unida al detener | Cada viaje (de Grabar a Detener) queda como **una grabación** en Videos › Grabaciones: duración total, reproducción seguida de todas las partes y Guardar/compartir de una vez (`js/sesiones.js`, con pruebas). Las partes siguen existiendo por dentro para el bucle y la protección por impacto. |
| Reciclaje automático | Antes de cada fragmento se borra el clip más antiguo **no protegido** hasta que quepa el siguiente (`js/fifo.js`, con pruebas). |
| Sandbox `/pbcar_loop/` | Base IndexedDB `pbcar_loop`, privada del navegador para este sitio: nunca toca la galería ni los archivos del teléfono. |
| Resistencia a golpes y apagones | El video se guarda en trozos de 2 s según llega. Si el teléfono se apaga a mitad de un fragmento, lo grabado queda en disco y se marca «Recuperado» al volver a abrir. |
| **Telemetría incrustada** | Cada fotograma lleva fecha, hora, coordenadas, velocidad (GPS), fuerza G y placa. Se registra además una muestra por segundo en los metadatos del clip. |
| **3.2 Copiloto «Hola Pascal» / «Okay Pascal»** | Escucha continua con el reconocedor de voz del sistema. Comandos: activar/detener grabación, guardar video / «acaba de pasar un accidente», tomar foto, modo ahorro térmico y salir de él, cuánto espacio queda. Si solo dices «Hola Pascal», espera el comando 7 s. Respuesta hablada (TTS). |
| Ahorro térmico | Pantalla al 5 % de brillo (capa negra al 95 %) mientras la grabación sigue. |
| **3.3 Sensor de impacto (G-Sensor)** | Por encima del umbral (1,8 / 2,5 / 3,5 G) protege el clip en curso y el anterior (segundos previos). El siguiente también nace protegido si el evento tiene menos de 30 s. |
| **Bóveda forense** | Los clips protegidos no se reciclan. Al cerrarse se sellan con **SHA-256** (calculado por partes en un worker, igual al de `sha256sum`). |
| **Acta PDF + Pascal Sign** | PDF generado en el teléfono, sin librerías ni servidores: datos del archivo, huella SHA-256, telemetría, eventos, fotograma de referencia, declarante y firma en pantalla, más instrucciones para verificar la huella. |
| Guía de bienvenida | 4 pantallas (instalar el teléfono, grabar sin tocar, telemetría, evidencia) con la aceptación de términos al final. Imágenes en `img/guia-1-soporte.webp`, `guia-2-voz.webp`, `guia-3-telemetria.webp` y `guia-4-evidencia.webp` (las dos primeras son provisionales). |
| Qué hacer tras un choque | Guía de 9 pasos con botones para proteger el video, tomar foto e ir a la bóveda. Contenido orientativo, para revisión de abogado. |
| Inicio automático | Empieza a grabar al superar 10/15/20/30 km/h sostenidos 5 s y detiene tras 2/5/10 min quieto (solo si la grabación empezó sola). `js/autoarranque.js`, con pruebas. |
| Registro de eventos | Inicios, detenciones, impactos, protecciones, fotos y actas, con origen (botón, voz, automático, sistema); se exporta en CSV. `js/registro.js`, con pruebas. |
| Offline-First | Service worker con caché de toda la app. |

La especificación que recibimos llega hasta el título de la sección 3.3; lo del sensor de impacto y la
bóveda se implementó según los objetivos 2 y 4 del documento. Ajustar cuando esté el texto completo.

## Probar en local

```
npm test            # pruebas de la lógica (Node 20+)
npm start           # sirve en http://localhost:8080
```

La cámara y los sensores exigen HTTPS (o `localhost`). Para probar en el celular, publícala
(GitHub Pages con el flujo `.github/workflows/pages.yml`) o usa un túnel HTTPS.

## Estructura

```
index.html, app.css     interfaz (oscura, para la cabina; paleta PascalIA)
js/app.js               orquestación de la interfaz
js/grabador.js          cámara, dibujo de telemetría, fragmentos, protección, fotos
js/fifo.js              cuota, autonomía y plan de reciclaje (puro)
js/db.js                IndexedDB: clips y trozos de video
js/telemetria.js        GPS, velocidad, fuerza G y detección de impacto
js/comandos.js          palabra de activación e intérprete de comandos (puro)
js/voz.js               reconocimiento continuo y respuesta hablada
js/sha256.js            SHA-256 incremental (puro)
js/hash-worker.js       sellado de evidencias fuera del hilo principal
js/pdf.js, js/acta.js   escritor de PDF mínimo y acta de custodia
sw.js, manifest         instalación y funcionamiento sin conexión
tests/                  pruebas con node:test
```

## Límites conocidos de la versión web

- **Pantalla encendida y app al frente.** Los navegadores detienen la cámara si se bloquea el teléfono
  o se cambia de app. PV Car pide *Wake Lock* para mantener la pantalla encendida y reanuda la cámara al
  volver, pero no graba en segundo plano.
- **Voz sin datos.** El reconocimiento lo hace el sistema. En Chrome para Android funciona sin datos si
  está el paquete de voz en español; en iPhone depende de la versión de iOS. No es un detector de
  palabra de activación dedicado.
- **iPhone:** el acelerómetro pide permiso con un toque; Safari guarda las grabaciones en MP4.
- **4K** depende del teléfono: dibujar 4K a 30 FPS en el canvas exige un equipo de gama alta.
- **Valor probatorio:** el acta deja constancia técnica de integridad (huella SHA-256) desde que el
  teléfono selló el archivo. No es una firma digital certificada; su valor como prueba lo decide la
  autoridad. Antes de prometer «validez probatoria» en publicidad, revisarlo con un abogado
  (ver `CUMPLIMIENTO.md` del sitio de PascalIA: «cifras y promesas solo con respaldo»).

## Siguientes pasos sugeridos

1. Empaquetar con Capacitor para Play Store / App Store: grabación con la pantalla apagada en Android
   (servicio en primer plano), detector de palabra de activación local (p. ej. Porcupine o Vosk) y
   reloj con hora de red/GNSS para el sello de tiempo.
2. Firma digital del acta con certificado (Pascal Sign) y sello de tiempo de una TSA.
3. Página de producto en `pascalia.lat/pv-car/`, cumpliendo `CUMPLIMIENTO.md`.

## Privacidad

Videos, fotos, ubicación y telemetría quedan solo en el teléfono. La app no tiene servidor, analítica ni
publicidad. Política de datos: https://pascalia.lat/politica-de-datos/
