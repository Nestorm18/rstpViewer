package com.rtspviewer.panel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Acceso a la API HTTP de la app RTSP Viewer de cada equipo (puerto 8080). */
final class DeviceClient {

    static final String APP_ID = "RTSPViewer";
    static final int DEVICE_PORT = 8080;
    static final int DEVICE_TIMEOUT = 4000;
    private static final int CHUNK = 256 * 1024;

    /** Error con un mensaje listo para mostrar. */
    static final class DeviceError extends Exception {
        DeviceError(String msg) {
            super(msg);
        }
    }

    interface Progress {
        void update(long sent, long total);
    }

    private DeviceClient() {
    }

    static String friendly(Exception e) {
        if (e instanceof DeviceError) return e.getMessage();
        if (e instanceof SocketTimeoutException) return "No responde (tiempo agotado)";
        String msg = String.valueOf(e.getMessage());
        if (e instanceof ConnectException) {
            if (msg.contains("ECONNREFUSED") || msg.toLowerCase(Locale.ROOT).contains("refused")) {
                return "Rechaza la conexión: ¿está abierta la app?";
            }
            if (msg.contains("ETIMEDOUT")) return "No responde (tiempo agotado)";
            return "No se puede conectar (" + msg + ")";
        }
        if (e instanceof SocketException && (msg.contains("reset") || msg.contains("EPIPE") || msg.contains("Broken pipe"))) {
            return "Se cortó la conexión";
        }
        if (e instanceof IOException) return "No se puede conectar (" + msg + ")";
        return "Error inesperado: " + e;
    }

    private static HttpURLConnection open(String ip, String path, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http", ip, DEVICE_PORT, path).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        return c;
    }

    private static JSONObject readJson(HttpURLConnection c) throws IOException, DeviceError {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream s = in) {
                byte[] b = new byte[16 * 1024];
                int n;
                while ((n = s.read(b)) > 0) buf.write(b, 0, n);
            }
        }
        JSONObject data;
        try {
            data = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new DeviceError("Respuesta no válida (HTTP " + code + ")");
        }
        if (code != 200) {
            String err = data.optString("error");
            throw new DeviceError(err.isEmpty() ? "Error HTTP " + code : err);
        }
        return data;
    }

    static JSONObject get(String ip, String path, int timeout) throws IOException, DeviceError {
        HttpURLConnection c = open(ip, path, timeout);
        try {
            return readJson(c);
        } finally {
            c.disconnect();
        }
    }

    static JSONObject get(String ip, String path) throws IOException, DeviceError {
        return get(ip, path, DEVICE_TIMEOUT);
    }

    static JSONObject post(String ip, String path, String body) throws IOException, DeviceError {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = open(ip, path, DEVICE_TIMEOUT + 4000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            return readJson(c);
        } finally {
            c.disconnect();
        }
    }

    static JSONObject upload(String ip, File file, String name, String slot, Progress progress)
            throws IOException, DeviceError {
        long size = file.length();
        HttpURLConnection c = open(ip, "/api/upload?name=" + enc(name) + "&slot=" + slot, 60_000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(size);
            IOException writeError = null;
            try (InputStream in = new FileInputStream(file); OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[CHUNK];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    progress.update(sent, size);
                }
            } catch (IOException e) {
                writeError = e;  // el equipo puede cortar al rechazar el archivo: leer su mensaje
            }
            try {
                return readJson(c);
            } catch (IOException e) {
                throw writeError != null ? writeError : e;
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
