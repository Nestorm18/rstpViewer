package com.rtspviewer.tv;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Arranca el servidor HTTP de control al iniciar el proceso (también al encender el
 * dispositivo, vía BootReceiver) y guarda el estado en vivo que consulta /api/status.
 */
public class App extends Application {

    static final int HTTP_PORT = 8080;

    // Estado de la cámara
    static final String CAM_OK = "ok", CAM_FAIL = "fallo", CAM_CONNECTING = "conectando", CAM_STOPPED = "parada";
    // Qué se ve en pantalla
    static final String SHOW_CAMERA = "camara", SHOW_NONE = "ninguno"; // o el hueco que se ve: esquela / imagen

    volatile String cameraState = CAM_STOPPED;
    volatile String showing = SHOW_NONE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable configListener;

    static App get(Context c) {
        return (App) c.getApplicationContext();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Al arrancar no hay subidas en curso: borrar todo lo que no se usa (restos de envíos cortados).
        Backup.cleanUnused(this, true);
        new WebServer(this, HTTP_PORT).start();
    }

    /** La pantalla visible se registra aquí para aplicar al momento los cambios que llegan por red. */
    void setConfigListener(Runnable listener) {
        configListener = listener;
    }

    /**
     * Llamable desde cualquier hilo. Espera (como mucho 2 s) a que la pantalla aplique el
     * cambio, para que el estado que se devuelva justo después ya lo refleje.
     */
    void notifyConfigChanged() {
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                if (configListener != null) configListener.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** IPv4 de la red local de este dispositivo, o null si no hay red. */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
