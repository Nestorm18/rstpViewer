# RTSP Viewer

App mínima para **Android TV** (Xiaomi TV Stick, Chromecast con Google TV, Fire TV…) y **tablets Android** que muestra una cámara IP por **RTSP** a pantalla completa. Sin anuncios, sin cuentas, sin nube: se conecta directamente a la cámara en tu red local.

## Características
- Vídeo a pantalla completa y la pantalla no se apaga mientras se ve.
- Configuración con el mando: IP, puerto, usuario, contraseña y ruta del stream.
- Reconexión automática cada 5 s si se cae la conexión.
- Transporte RTP sobre TCP (opcional, más estable en Wi-Fi).
- **Mostrar cámara, esquelas o imagen**, a elegir desde el panel central.
- **Control por la red local:** un panel central en Windows para todos los equipos a la vez, con editor de esquelas.
- Funciona en **TV sticks y en tablets** Android.
- Basada en [AndroidX Media3 / ExoPlayer](https://developer.android.com/media/media3).

## Uso
| Tecla del mando | Acción |
|---|---|
| **OK** o **Menú** | Abrir la configuración |
| **Atrás** | Salir |

En una **tablet**, toca la pantalla para abrir la configuración.

La primera vez se abre directamente la pantalla de configuración. La URL resultante es:

```
rtsp://usuario:contraseña@IP:puerto/ruta
```

### Ruta del stream según la marca
| Marca | Ruta |
|---|---|
| Hikvision / HiLook | `/Streaming/Channels/101` (`102` = calidad baja) |
| Dahua / Imou | `/cam/realmonitor?channel=1&subtype=0` |
| TP-Link Tapo | `/stream1` (`/stream2` = calidad baja). La cuenta de cámara se crea en la app Tapo → Ajustes avanzados → Cuenta de la cámara |
| Ezviz | `/h264/ch1/main/av_stream` (usuario `admin`, contraseña = código de verificación de la etiqueta) |

## Qué se muestra: cámara, esquelas o imagen
Cada equipo puede mostrar una de tres cosas. Se elige **solo a mano** desde el panel central: la app nunca cambia sola.

| Mostrar | Qué se ve |
|---|---|
| **Cámara** (por defecto) | La cámara. Si falla, se muestra un aviso y se reintenta cada 5 s. |
| **Esquelas** | El pase de esquelas creado en el editor del panel: en bucle, con un fundido y el mismo tiempo cada una. |
| **Imagen** | Una imagen (JPG, PNG, WEBP; se reduce a 1920×1080 como máximo), un vídeo (MP4, MKV, WEBM; en bucle y sin sonido) o un PowerPoint convertido. |

Cada equipo guarda **por separado** sus esquelas y su imagen, así que se puede cambiar entre las tres sin volver a enviarlas.
Al enviar esquelas o una imagen, se muestran al momento. Si se elige «Esquelas» o «Imagen» en un equipo que no tiene ese contenido, se ve un texto grande (por defecto «Cámara no disponible»).

Los cambios se aplican al momento, sin reiniciar la app. El equipo lo guarda todo, así que el PC puede apagarse después.
En la pantalla de ajustes se ve la IP del equipo (para añadirlo al panel central) y también se puede escribir ese texto.

### Panel central (varios equipos a la vez)
[`pc/panel_central.py`](pc/panel_central.py) es un servidor web para Windows que gestiona **todos los equipos a la vez**, sean TV sticks o tablets. Es un solo archivo que solo usa la biblioteca estándar de Python 3.

- **Abrirlo:** doble clic en [`pc/iniciar_panel.bat`](pc/iniciar_panel.bat). Se abre una ventana negra con las direcciones y el navegador en `http://localhost:8000`. Para tenerlo sin Python, [`pc/crear_exe.bat`](pc/crear_exe.bat) crea `PanelCentral.exe` con PyInstaller.
- **Desde otros PCs o el móvil:** abre `http://IP_DEL_PC:8000`. La dirección exacta aparece en la ventana negra. La primera vez, Windows pregunta si permite a Python usar la red: acepta para **redes privadas**.
- **Equipos:** **Buscar en la red** añade todos los que encuentre en la red local /24. También se pueden añadir a mano con su IP y un nombre. Si haces clic en el nombre, lo puedes cambiar.
- **Tabla de estado** de cada equipo: conexión, cámara, qué está mostrando, qué esquelas e imagen tiene guardadas y espacio libre. Se actualiza sola.
- **Mostrar: Cámara · Esquelas · Imagen** cambia lo que se ve en los equipos marcados. **Esquelas** envía directamente lo que hay en el editor y lo muestra; si alguna esquela no tiene nombre o sigue con el de ejemplo, pide confirmación.
- **Esquelas**, con un editor que no necesita PowerPoint:
  - **+ Esquela:** encabezado («La Señora», «El Señor»…), nombre, edad (si pones «87», sale «87 años»), detalles (las líneas que terminan en «:» salen en negrita), cruz y D.E.P. Siempre con fondo blanco. Admite una imagen opcional, que puede ir **debajo de la esquela, alargada** (por ejemplo, la foto de un servicio o un lugar) o **a la izquierda, en vertical** (la foto de la persona).
  - **+ Imagen:** una foto o un cartel a pantalla completa, entera o recortada para llenar la pantalla.
  - **Rellenar con un ejemplo aleatorio**, dentro de cada esquela, la llena con datos inventados al azar (nombre, edad, fecha de los últimos días, hora, iglesia) y una **foto dibujada al azar**: un paisaje con iglesia si la imagen va debajo, o un retrato genérico si va a la izquierda. Sirve para probar o como plantilla.
  - Las confirmaciones (quitar una esquela, borrar, enviar con el nombre de ejemplo…) salen en una ventana de la propia página, que el navegador no puede bloquear.
  - Las diapositivas se ordenan con ↑ ↓, se ven en una vista previa y todas duran el mismo tiempo (segundos configurables).
  - Al pulsar **Mostrar → Esquelas**, se dibujan a 1920×1080, se reparten a los equipos marcados y se muestran al momento. **Borrar esquelas de los equipos** las elimina; si se estaban viendo, el equipo vuelve a la cámara.
  - Lo que hay en el editor se guarda en el navegador, así que no se pierde al cerrar la página. La primera vez aparece una **esquela de ejemplo** con datos inventados; **Cargar ejemplo** la recupera para usarla de plantilla.
- **Imagen:** **Enviar y mostrar** reparte una imagen, un vídeo o un PowerPoint a los equipos marcados (con el progreso de cada uno) y la muestra al momento. **Borrar imagen** la elimina; si se estaba viendo, el equipo vuelve a la cámara.
- Si un equipo está apagado, se marca en rojo y el resto se actualiza igual.
- **PowerPoint en «Imagen»** (`.pptx`, `.ppt`, `.ppsx`): se convierte **una sola vez** con el PowerPoint instalado en el PC donde corre el panel, y se reparte a todos los equipos marcados:
  - Si tiene 1 diapositiva, se convierte en una imagen JPG de 1920 px de ancho.
  - Si tiene varias, se convierte en un vídeo MP4 de 1080p con *N* segundos por diapositiva (8 por defecto, configurable en la web).

La lista de equipos y los ajustes se guardan en `pc/panel_central.json`. Ahí se puede cambiar también el puerto (`port`) y si se abre el navegador al iniciar (`open_browser`).
Al cerrar la ventana negra se detiene el panel, pero los equipos siguen mostrando lo último que se les envió.

### API HTTP
El servidor escucha en el puerto 8080 y solo atiende a direcciones de red local (privadas, link-local o loopback). No tiene contraseña, así que cualquiera en tu red local puede cambiar lo que se muestra.

| Petición | Cuerpo | Descripción |
|---|---|---|
| `GET /api/status` | – | Estado en JSON (ver abajo) |
| `POST /api/upload?name=archivo.ext&slot=esquela\|imagen` | el archivo, con `Content-Length` | Sustituye el contenido de ese hueco (por defecto, un `.zip` va a `esquela` y lo demás a `imagen`). No cambia lo que se muestra. Primero se guarda en un temporal y luego se renombra. Se rechaza si no cabe (se dejan 50 MB libres). Un `.zip` es un pase de diapositivas: imágenes JPG, PNG o WEBP (en orden por nombre) y un `config.json` opcional con `{"seconds": 8}`. |
| `POST /api/text` | texto UTF-8 (máx. 500) | Texto que se ve si el hueco elegido está vacío. Si va vacío, se usa el texto por defecto. |
| `POST /api/mode` | `camara`, `esquela` o `imagen` | Cambia lo que se muestra |
| `POST /api/delete?slot=esquela\|imagen` | – | Borra ese hueco (sin `slot`, los dos). Si se estaba viendo, vuelve a la cámara. |

```json
{"app":"RTSPViewer","version":"1.3","camera":"ok","showing":"camara","mode":"camara","text":"Cámara no disponible",
 "files":{"esquela":{"name":"Esquela.zip","type":"diapositivas","size":254748,"slides":3,"seconds":8},
          "imagen":{"name":"aviso.mp4","type":"video","size":5242880}},"freeBytes":3400000000}
```
- `camera` puede ser `ok`, `fallo`, `conectando` o `parada` (cuando se muestran esquelas o imagen).
- `showing` puede ser `camara`, `esquela`, `imagen` o `ninguno` (la app no está en pantalla).
- Los errores devuelven `{"error":"mensaje"}` con un código HTTP 4xx o 5xx.

Ejemplos con `curl`:
```bash
curl http://IP_DEL_STICK:8080/api/status
curl -X POST --data-binary @aviso.mp4 "http://IP_DEL_STICK:8080/api/upload?name=aviso.mp4&slot=imagen"
curl -X POST --data-binary "imagen" http://IP_DEL_STICK:8080/api/mode
```

## Compilar
Requisitos: JDK 17 o 21 y el Android SDK (plataforma 34).

- **Android Studio:** abre la carpeta del proyecto → *Build → Build APK(s)*.
- **Línea de comandos:**
  ```bash
  ./gradlew assembleDebug        # Linux / macOS
  gradlew.bat assembleDebug      # Windows
  ```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar en una tablet
Copia el APK a la tablet (por USB, Drive o correo), ábrelo y permite instalar apps de **fuentes desconocidas** cuando lo pida.
Para que se abra sola al encender, activa **Iniciar al encender** en los ajustes de la app y pulsa **Permitir el inicio automático**. Se abrirá la pantalla de Android donde se activa *Mostrar sobre otras apps*.
Conviene tenerla siempre enchufada: si Android cierra la app para ahorrar batería, también se para el control remoto.

## Instalar en el TV stick
1. En el stick: *Ajustes → Preferencias del dispositivo → Información* → pulsa 7 veces **Compilación** para activar las opciones de desarrollador.
2. En *Opciones de desarrollador* activa **Depuración USB / ADB**.
3. Desde el PC, en la misma red:
   ```bash
   adb connect IP_DEL_STICK:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Acepta en la TV el aviso de depuración la primera vez.

Sin PC: sube el APK a la nube y ábrelo en el stick con *Downloader* o *Send Files to TV*.

### Iniciar al encender
La opción **Iniciar al encender** (activada por defecto) abre la app automáticamente al arrancar el stick.
En Android 10 o superior, Android solo deja abrir una app desde segundo plano si tiene el permiso
*Mostrar sobre otras apps*. En tablets se activa desde los ajustes de la app (ver arriba). En Android TV ese ajuste no suele aparecer en el menú, así que se concede una vez con adb:

```bash
adb shell appops set com.rtspviewer.tv SYSTEM_ALERT_WINDOW allow
adb shell appops write-settings
```

Si el stick se alimenta desde un USB de la tele, se reinicia cada vez que enciendes la TV y la app se abre sola.
Si está enchufado a la corriente, solo entra en reposo y al despertar vuelve a la pantalla de inicio.

## Privacidad
La configuración (incluida la contraseña de la cámara) se guarda solo en el dispositivo, en las preferencias privadas de la app. La API de control nunca devuelve la contraseña de la cámara. La app no envía datos a ningún servidor.
