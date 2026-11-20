# RTSP Viewer

App mínima para **Android TV** (Xiaomi TV Stick, Chromecast con Google TV, Fire TV…) que muestra una cámara IP por **RTSP** a pantalla completa. Sin anuncios, sin cuentas, sin nube: se conecta directamente a la cámara en tu red local.

## Características
- Vídeo a pantalla completa y la pantalla no se apaga mientras se ve.
- Configuración con el mando: IP, puerto, usuario, contraseña y ruta del stream.
- Reconexión automática cada 5 s si se cae la conexión.
- Transporte RTP sobre TCP (opcional, más estable en Wi-Fi).
- **Modo respaldo:** cuando lo eliges, muestra una imagen, un vídeo o un texto en lugar de la cámara.
- **Control desde el PC por la red local**, con un programa para Windows o desde el navegador.
- Basada en [AndroidX Media3 / ExoPlayer](https://developer.android.com/media/media3).

## Uso
| Tecla del mando | Acción |
|---|---|
| **OK** o **Menú** | Abrir la configuración |
| **Atrás** | Salir |

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

## Modo respaldo
Para cuando la cámara no funciona. El cambio entre cámara y respaldo es **solo manual**: la app nunca cambia sola.
El contenido de respaldo puede ser:
- **Un vídeo** (MP4, MKV o WEBM), que se reproduce en bucle y sin sonido.
- **Una imagen** (JPG, PNG o WEBP), que se reduce a 1920×1080 como máximo.
- **Un texto** grande y centrado, si no hay archivo. Por defecto es «Cámara no disponible».

| Modo | Qué se ve |
|---|---|
| **Cámara** (por defecto) | La cámara. Si falla, se muestra un aviso y se reintenta cada 5 s. |
| **Respaldo** | El contenido de respaldo. La cámara se desconecta. |

Todo se gestiona por la red y los cambios se aplican al momento, sin reiniciar la app. El stick guarda el archivo, el texto y el modo, así que el PC puede apagarse después.
En la pantalla de ajustes se ve la dirección de control (`http://IP_DEL_STICK:8080`) y también se puede escribir el texto de respaldo.

### Programa de control para Windows
Está en [`pc/rtspviewer_control.pyw`](pc/rtspviewer_control.pyw). Es un solo archivo que solo usa la biblioteca estándar de Python 3.
Se abre con doble clic si tienes Python instalado. Para tener un `.exe` que no necesite Python, ejecuta [`pc/crear_exe.bat`](pc/crear_exe.bat), que usa PyInstaller.

- **IP del stick:** se escribe a mano o se encuentra con **Buscar**, que recorre la red local /24. **Comprobar** confirma la conexión.
- **Estado:** cámara, qué se ve, modo, archivo actual y espacio libre. Se actualiza solo cada 4 s.
- **Elegir archivo y enviar:** sirve para imágenes, vídeos y **PowerPoint** (`.pptx`, `.ppt`, `.ppsx`). El PowerPoint se convierte con el PowerPoint instalado en el PC antes de enviarlo:
  - Si tiene 1 diapositiva, se convierte en una imagen JPG de 1920 px de ancho.
  - Si tiene varias, se convierte en un vídeo MP4 de 1080p con *N* segundos por diapositiva (8 por defecto, configurable).
- **Qué mostrar en la tele** (cámara o respaldo), **texto de respaldo** y **borrar archivo**.

La configuración (IP y segundos por diapositiva) se guarda en `rtspviewer_control.json`, junto al programa.

### Desde el navegador
Abre `http://IP_DEL_STICK:8080` en cualquier navegador de la red local. Ahí puedes ver el estado, cambiar el modo, subir un archivo (sin la conversión de PowerPoint) y cambiar el texto.

### API HTTP
El servidor escucha en el puerto 8080 y solo atiende a direcciones de red local (privadas, link-local o loopback). No tiene contraseña, así que cualquiera en tu red local puede cambiar el respaldo.

| Petición | Cuerpo | Descripción |
|---|---|---|
| `GET /api/status` | – | Estado en JSON (ver abajo) |
| `POST /api/upload?name=archivo.ext` | el archivo, con `Content-Length` | Sustituye el archivo de respaldo. Primero se guarda en un temporal y luego se renombra. Se rechaza si no cabe (se dejan 50 MB libres). |
| `POST /api/text` | texto UTF-8 (máx. 500) | Texto de respaldo. Si va vacío, se usa el texto por defecto. |
| `POST /api/mode` | `camara` o `respaldo` | Cambia lo que se muestra |
| `POST /api/delete` | – | Borra el archivo de respaldo |

```json
{"app":"RTSPViewer","version":"1.1","camera":"ok","showing":"camara","mode":"camara",
 "text":"Cámara no disponible","file":{"name":"aviso.mp4","type":"video","size":5242880},"freeBytes":3400000000}
```
- `camera` puede ser `ok`, `fallo`, `conectando` o `parada` (en modo respaldo).
- `showing` puede ser `camara`, `respaldo` o `ninguno` (la app no está en pantalla).
- Los errores devuelven `{"error":"mensaje"}` con un código HTTP 4xx o 5xx.

Ejemplos con `curl`:
```bash
curl http://IP_DEL_STICK:8080/api/status
curl -X POST --data-binary @aviso.mp4 "http://IP_DEL_STICK:8080/api/upload?name=aviso.mp4"
curl -X POST --data-binary "respaldo" http://IP_DEL_STICK:8080/api/mode
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
*Mostrar sobre otras apps*. En Android TV ese ajuste no suele aparecer en el menú, así que se concede una vez con adb:

```bash
adb shell appops set com.rtspviewer.tv SYSTEM_ALERT_WINDOW allow
adb shell appops write-settings
```

Si el stick se alimenta desde un USB de la tele, se reinicia cada vez que enciendes la TV y la app se abre sola.
Si está enchufado a la corriente, solo entra en reposo y al despertar vuelve a la pantalla de inicio.

## Privacidad
La configuración (incluida la contraseña de la cámara) se guarda solo en el dispositivo, en las preferencias privadas de la app. La API de control nunca devuelve la contraseña de la cámara. La app no envía datos a ningún servidor.
