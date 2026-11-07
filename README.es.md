# Liquid Recorder

[English](README.md) | **Español**

Se instala en el teléfono como **Grabadora** (*Recorder* en inglés).

Grabadora de voz libre y sin rastreadores, con la misma interfaz de cristal líquido que el resto de
la suite ([Liquid Clock](https://github.com/mrsalvaa/liquid-clock), Calendar, Contacts, Phone,
Gallery y Calc). En Ajustes está también el estilo Sólido.

**Privacidad en tres líneas:** la app no tiene permiso de internet. Las grabaciones viven en su
carpeta privada y solo salen del teléfono si las compartes o exportas. Sin publicidad, telemetría,
informes de errores ni Google Play Services.

Licencia GPL-3.0-or-later.

## Funciones

- **Grabación con la mejor calidad posible**: captura en coma flotante a 44,1 o 48 kHz, mono o
  estéreo, en FLAC sin pérdida (predeterminado), WAV de 16 o 24 bits, AAC 128–320 kbps u Opus
  64–256 kbps. Ganancia, micrófono (teléfono, cable, USB, Bluetooth), procesamiento (estándar, sin
  procesar, voz, ambiente).
- **Mientras se graba**: onda o espectrograma (FFT, escala logarítmica), medidor en dBFS con pico
  retenido y aviso de saturación, aviso si una llamada toma el micrófono, pantalla apagada con
  notificación (pausar, marcador, detener).
- **Marcadores**: se crean al instante y se nombran sin pausar; se renombran o borran mientras se
  sigue grabando. Al reproducir: saltar entre marcadores, agregar, renombrar y compartirlos como texto.
- **Reproducción**: onda con marcadores, velocidad 0,5×–2×, saltear silencios, controles en la
  pantalla de bloqueo. Abre audio y **video** del teléfono (solo el sonido) y puede guardar ese
  audio en las grabaciones.
- **Edición**: recortar, borrar un tramo y dividir (los marcadores se corren con el audio), como
  copia o reemplazando el original (que va a la papelera).
- **Quitar ruido de ambiente**: crea una copia limpia (el original no se toca), sin IA y todo en el
  teléfono: pasa-altos de 80 Hz, perfil de ruido automático, sustracción espectral en dos pasadas,
  pulido extra opcional (medias no locales) y volumen EBU R128 a −16 LUFS. Notificación con el avance.
- **Organización**: selección múltiple, carpetas, etiquetas, favoritos, búsqueda sin tildes (también en marcadores) y
  papelera de 30 días.
- **Exportar** a otro formato con el selector de archivos del sistema; **compartir** el original.
- **Atajos**: ajuste rápido «Grabar», widget (con marcador y detener sin abrir la app) y acceso
  directo en el ícono.
- **Recuperación**: si el teléfono se apaga grabando en FLAC o WAV, al abrir la app se rescata hasta
  los últimos 5 segundos.

## Permisos

| Permiso | Para qué |
|---|---|
| `RECORD_AUDIO` | Grabar. Se pide la primera vez que se toca Grabar. |
| `POST_NOTIFICATIONS` | La notificación de grabación y de reproducción. Se pide junto con el micrófono; si se niega, se graba igual. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Seguir grabando con la pantalla apagada o la app en segundo plano. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Seguir reproduciendo en segundo plano, con controles en la pantalla de bloqueo. |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC` | Que una tarea larga (limpiar ruido, exportar) siga con su notificación de avance aunque salgas de la app. Uno u otro según la versión de Android. |
| `WAKE_LOCK` | Que el procesador no se duerma a mitad de una grabación o reproducción. |
| `MODIFY_AUDIO_SETTINGS` | Activar un micrófono Bluetooth elegido en Ajustes. |

No pide internet, almacenamiento ni ubicación. `INTERNET` y `ACCESS_NETWORK_STATE` se quitan
explícitamente del manifest final. Los archivos de afuera se abren con el selector del sistema o con
«Abrir con», con permiso temporal sobre ese archivo solamente.

## Dependencias

| Biblioteca | Licencia | Uso |
|---|---|---|
| Jetpack Compose, AndroidX (core, activity, lifecycle) | Apache-2.0 | Interfaz |
| kotlinx.serialization | Apache-2.0 | Ajustes y biblioteca en JSON |
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Efecto de cristal |
| Media3 ExoPlayer y Session | Apache-2.0 | Reproducción y controles en la notificación |

FLAC y WAV los escribe la propia app (`core/audio`); AAC y Opus usan los codificadores del sistema
(`MediaCodec`). Sin la app no hay red, así que el código HTTP que trae Media3 no puede conectarse.

## Descarga

En el [registro de cambios](CHANGELOG.es.md) está lo nuevo de cada versión.

Baja el APK de [Releases](../../releases) e instálalo (Android 12 o posterior).

Comprueba que el archivo no fue alterado:

```bash
sha256sum -c liquid-recorder-v1.2.1.apk.sha256
```

SHA-256 de `liquid-recorder-v1.2.1.apk`:

```
f30c156e4ab2c94ed36187fffa773aad9ec3b239f2302dbda724d50f156af1b8
```

Y que está firmado con la clave del proyecto (huella SHA-256 del certificado):

```
22:90:B6:A3:5D:46:53:AD:7E:54:3B:3D:DE:B7:01:66:1F:A9:80:7B:E8:EC:CF:22:57:D2:D6:66:5A:42:AA:59
```

```bash
apksigner verify --print-certs liquid-recorder-v1.2.1.apk
```

## Compilar

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test assembleDebug
```

Para probar la fluidez real en el teléfono, usar `assembleProfile` (optimizada como la release, se
instala como la de prueba): la debug de Compose es varias veces más lenta.

`:core:test` usa `flac`, `metaflac` y `ffprobe` del sistema, si están, para validar los archivos
generados (incluido el MD5 de FLAC).

Debug y profile se instalan como `dev.liquidrecorder.debug`, «Grabadora (prueba)».

## Estructura

- `core/`: JVM puro y con pruebas: codificadores WAV y FLAC, conversión de muestras, cambio de
  frecuencia, FFT y espectrograma, medidor, picos de la onda, cortes y marcadores, textos.
- `app/.../audio`: motor de grabación (`Recorder`), codificadores del sistema, decodificador de
  cualquier audio o video, transcodificador y reproductor.
- `app/.../service`: servicios de grabación y reproducción, y ajuste rápido. `widget/`: el widget.
- `app/.../ui/glass`: copia de Liquid Contacts (ver [DESIGN.md](DESIGN.md)).
