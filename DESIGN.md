# Diseño: Liquid Recorder dentro de la suite

SPDX-License-Identifier: GPL-3.0-or-later

## Origen de `ui/glass`

`app/src/main/java/dev/liquidrecorder/ui/glass` es una copia **sin cambios** de
`app/src/main/java/dev/liquidcontacts/ui/glass` de Liquid Contacts, commit `eb1fec9`
(`Components.kt`, `Controls.kt`, `Dialog.kt`, `Theme.kt`, `UiIcons.kt`), que a su vez es la de
Calendar con `Dialog.kt`. Solo cambia el paquete. Del mismo commit salen `ui/screens/AppearanceCard.kt`,
`ui/screens/Common.kt`, `util/AppIcons.kt`, `util/Strings.kt`, `util/Display.kt`, `data/Stores.kt`
(más la biblioteca) y `res/values*/strings_glass.xml`.

Valores de referencia del cristal: los de la tabla del `DESIGN.md` de Contacts.

**Diferencia con el resto de la suite: español neutro.** Los textos en español (también los de
`strings_glass.xml`) usan tú en lugar de vos: «Toca», «Puedes», «Desactívalo». El resto de las apps
sigue con voseo.

`ui/components/Dropdown.kt` es el de Contacts (sin el desplegable de cuentas): flecha hacia abajo y
ventana con la opción marcada. Se usa para formato, calidad, frecuencia, ganancia, saltos y velocidad.

## Ajustes

`data/AppSettings.kt` guarda en `files/settings.json` los 8 campos de personalización de la suite,
con los mismos nombres, valores y valores por defecto: `themeMode`, `backgroundMode`, `colorSet`,
`accentColor`, `animatedBackground`, `uiStyle`, `solidAccent`, `appIcon`. Los de la grabadora van
aparte: `format`, `sampleRate`, `channels`, `bitsPerSample`, `aacBitrate`, `opusBitrate`,
`audioSource`, `inputDevice`, `inputDeviceName`, `noiseSuppression`, `gainDb`, `keepScreenOn`,
`visualizer`, `skipSeconds`, `skipSilence`, `speed`.

Íconos: los 4 estilos en el orden de siempre (Liquid por defecto, Material, Samsung, iPhone) más
monocromo. Fondos de Contacts; el dibujo es un micrófono (Liquid con el punto rojo de grabación,
Samsung gris pizarra con detalle rojo, iPhone como barras de onda con la central roja).

## Componentes propios

| Componente | Archivo | Notas |
|---|---|---|
| `RecordDock` | `ui/screens/RecordScreen.kt` | Controles de grabación en una barra de cristal en la capa flotante de `GlassRoot`, sobre las pestañas: lo que se desplaza por debajo se ve refractado, también con fondo liso (donde las tarjetas del contenido se dibujan sin efectos). |
| `SkipButton` | `ui/screens/PlayerScreen.kt` | Retroceder/avanzar: la flecha gira hacia su lado y vuelve con rebote, y sube un «−10 s» que suma si se toca varias veces. Con «quitar animaciones», solo el texto. |
| `NewFolderChip` | `ui/screens/LibraryScreen.kt` | «+» y carpeta con las medidas de un `GlassChip`, primero en la fila de carpetas. |
| `EditIcons` | `ui/components/EditIcons.kt` | «[▶» y «◀]» para marcar el comienzo y el final de la selección. |

| Componente | Archivo | Notas |
|---|---|---|
| `LiveWaveform`, `LiveSpectrogram`, `LevelMeter` | `ui/screens/Visualizers.kt` | Se dibujan en cada cuadro (reloj de cuadros leído solo al dibujar, sin recomponer) e interpolan la posición entre bloques de audio (`LiveMeter.nowMs`), así se deslizan de forma continua a 120 Hz. Onda: barras de 100 ms, unos 8 s a lo ancho. Espectrograma: una columna cada 40 ms, 240 columnas (unos 10 s); suelto y fundido con el fondo (`fade`). El medidor sube al instante y baja a 24 dB/s. Para TalkBack son una sola descripción fija. |
| `FileWaveform` | idem | Onda del archivo ajustada al ancho, con marcadores, posición y selección. |
| `RenameField`, `RenameWindow` | `ui/components/Components.kt` | Campo de cristal que arranca con el texto seleccionado: el `GlassTextField` de `ui/glass` no puede. |
| `SwitchRow`, `DangerButton`, `DangerFill`, `dangerText` | idem | Copiados de Contacts. |
| `RecordRed` | idem | Rojo del botón de grabar (`#D92D3A`), ícono blanco de 38 dp. |

## Audio

- **Limpieza de ruido** (`core/dsp/Cleaning.kt` y `CleanPipeline.kt`), sobre una copia y nunca al
  grabar: el original no se toca. Solo procesamiento de señal clásico. Es el pipeline pedido con
  FFmpeg + libspecbleach, llevado a Kotlin para no sumar bibliotecas nativas (FFmpeg, libspecbleach
  y FFTW: unos 10–20 MB y builds que F-Droid tendría que reproducir):
  1. Pasa-altos de 80 Hz (`HighPass`, como `highpass`).
  2. Perfil automático: espectro promedio del 7,5 % de bloques más silenciosos, sin contar el
     silencio digital del arranque (`NoiseProfile`).
  3. Sustracción espectral ×2 con ese perfil (`SpectralCleaner`, como libspecbleach en modo manual):
     STFT de 2048 al 75 %, sobre-resta de Berouti, SNR a priori dirigida por decisión, suavizado en
     tiempo y frecuencia, piso de 5/8/12 dB por pasada. **Híbrido:** además del perfil sigue el ruido
     del momento (mínimo en ~2,7 s) y usa el mayor. Con un perfil fijo solo, en una grabación real del
     S25 FE (piso que varía entre −57 y −47 dB) la mejora era de +2,5 dB; híbrido, +6,6 / +9,8 / +13 dB.
  4. Opcional, «pulido extra»: medias no locales en el tiempo (`NlMeans`, como `anlmdn`, parche 2 ms,
     búsqueda ±6 ms). Con él, +10 / +12 / +14 dB.
  5. Volumen EBU R128 a −16 LUFS (`LoudnessMeter`, ponderación K de libebur128, igual que la ITU a
     48 kHz) y limitador con anticipación a −1,5 dBFS (`Limiter`).
  Cuatro pasadas por el archivo (análisis, perfil, limpieza a un FLAC temporal de 24 bits, volumen).
  Resultado alineado muestra a muestra: los marcadores no se corren. En el S25 FE: 30 s de audio en
  6,2 s (9,1 s con pulido extra). Herramienta manual: `./gradlew :core:test
  -Pliquid.clean=archivo.f32 [-Pliquid.extra=1]` escribe los tres niveles.
- Las tareas largas (`Jobs`) muestran una notificación con avance, tiempo restante y Cancelar
  (`JobService`, servicio en primer plano de tipo procesamiento de medios, o sincronización en
  Android 14).
- Filtro de graves: Butterworth de 2.º orden a 80 Hz (`HighPass`).

- Captura `AudioRecord` en `ENCODING_PCM_FLOAT`; la ganancia se aplica antes de pasar a enteros.
  16 bits llevan dither triangular.
- **FLAC propio** (`core/audio/FlacWriter.kt`): predictores fijos de orden 0 a 4, Rice particionado
  y decorrelación estéreo por bloque. Android no trae contenedor FLAC para su codificador. Las
  pruebas comparan bit a bit con `flac -d` y verifican el MD5.
- Cada 5 s, punto de control: FLAC deja el total de muestras al día y anota el largo seguro en
  `<id>.state`; WAV reescribe el encabezado. Al abrir la app se recorta y se rescata.
- AAC y Opus: `MediaCodec` + `MediaMuxer` (MPEG-4 y Ogg). No se pueden rescatar si se corta.
- Editar y exportar decodifican con `MediaExtractor`/`MediaCodec` y vuelven a escribir; el cambio de
  frecuencia (solo al exportar o importar a Opus) es Hermite de 4 puntos.

## Lecciones para las próximas apps

- **Probar el DSP con audio real, no solo con señales sintéticas.** El primer reductor pasaba todos
  los tests y no hacía nada en el teléfono: las grabaciones empiezan con unos ms de ceros y el
  estimador de ruido quedaba en cero. Y un perfil fijo rinde mucho menos con ruido que varía.
- **Medir la fluidez con `assembleProfile`, nunca con debug.** El reproductor y la pantalla de grabar
  daban 16–70 % de cuadros lentos en debug y 0–0,2 % en profile (121 cuadros/s grabando).
- **ExoPlayer solo desde el hilo principal:** llamarlo desde `Jobs` (Dispatchers.Default) daba
  `IllegalStateException` al reemplazar un original. Usar `withContext(Dispatchers.Main)`.
- **Animar un elemento que crece sin `AnimatedContent`:** recorta el contenido durante el cambio de
  tamaño. La barra de grabar es una sola pieza: el cristal se estira con `lerp` y los botones salen
  con `offset`, sin recortes.

- **Fluidez:** un texto que cambia 10 veces por segundo (un reloj con décimas) hace que Android
  recalcule el árbol de accesibilidad cada vez si hay algún servicio de accesibilidad activo (unos
  16 ms en el S25 FE). Se ve con `atrace` como `checkForSemanticsChanges`. Solución: el texto
  visible con décimas y, para TalkBack, `clearAndSetSemantics` con un texto que cambie por segundo.
- **Recomponer por cuadro:** lo que se mueve en cada cuadro se lee al dibujar (lambda o
  `State` leído en el `Canvas`), nunca en la composición de la pantalla. El reproductor solo
  actualiza la posición cuando el cabezal se movió un píxel.
- **Costo del cristal en el S25 FE (GPU Xclipse, Vulkan):** con fondo en movimiento, cada
  superficie con desenfoque reserva memoria de GPU en cada cuadro que se redibuja la pantalla. Menos
  superficies de cristal en pantallas que se redibujan seguido (por eso la velocidad es un
  desplegable y no 7 chips).

- **Servicio de micrófono**: Android 14+ no deja arrancarlo desde segundo plano. El ajuste rápido y
  el widget abren la actividad con `ACTION_RECORD` y es ella la que arranca el servicio.
- **Acceso directo con sufijo `.debug`**: los `shortcuts.xml` estáticos no admiten
  `${applicationId}`; se crean dinámicos en `RecorderApp`.
