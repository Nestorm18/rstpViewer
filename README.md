# RTSP Viewer

App mínima para **Android TV** (Xiaomi TV Stick, Chromecast con Google TV, Fire TV…) que muestra una cámara IP por **RTSP** a pantalla completa. Sin anuncios, sin cuentas, sin nube: se conecta directamente a la cámara en tu red local.

## Características
- Vídeo a pantalla completa y la pantalla no se apaga mientras se ve.
- Configuración con el mando: IP, puerto, usuario, contraseña y ruta del stream.
- Reconexión automática cada 5 s si se cae la conexión.
- Transporte RTP sobre TCP (opcional, más estable en Wi-Fi).
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
La configuración (incluida la contraseña de la cámara) se guarda solo en el dispositivo, en las preferencias privadas de la app. La app no envía datos a ningún servidor.
