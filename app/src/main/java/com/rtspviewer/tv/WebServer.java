package com.rtspviewer.tv;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * API HTTP mínima que usa el panel central del PC para gestionar el respaldo. Solo acepta
 * clientes de la red local (direcciones privadas, link-local o loopback).
 *
 * <pre>
 * GET  /api/status   estado en JSON
 * POST /api/upload?name=archivo.ext[&slot=esquela|imagen]
 *                    (cuerpo = archivo, con Content-Length; .zip = pase de diapositivas
 *                     con config.json opcional; por defecto .zip → esquela, el resto → imagen)
 * POST /api/text     texto de respaldo (UTF-8)
 * POST /api/mode     camara | esquela | imagen
 * POST /api/delete[?slot=esquela|imagen]   borra un hueco o los dos
 * </pre>
 */
final class WebServer {

    private static final String TAG = "WebServer";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_SMALL_BODY = 64 * 1024;
    private static final int MAX_TEXT_CHARS = 500;
    /** Margen de espacio libre que se deja siempre en el dispositivo. */
    private static final long SPACE_MARGIN = 50L * 1024 * 1024;

    private final App app;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ReentrantLock uploadLock = new ReentrantLock();

    WebServer(App app, int port) {
        this.app = app;
        this.port = port;
    }

    void start() {
        Thread t = new Thread(this::acceptLoop, "http-server");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (true) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                Log.i(TAG, "Escuchando en el puerto " + port);
                while (true) {
                    Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                Log.w(TAG, "Servidor detenido, reintentando en 5 s", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- petición

    private static final class Request {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        InputStream body;

        long contentLength() {
            String v = headers.get("content-length");
            if (v == null) return -1;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(30_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            if (!isLocal(socket.getInetAddress())) {
                sendJson(out, 403, error("Solo se admiten conexiones desde la red local"));
                return;
            }

            Request req = parse(in);
            if (req == null) {
                sendJson(out, 400, error("Petición no válida"));
                return;
            }
            if ("100-continue".equalsIgnoreCase(req.headers.get("expect"))) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            route(req, out);
            drain(socket, in);
        } catch (Exception e) {
            Log.w(TAG, "Error atendiendo petición", e);
        }
    }

    /**
     * Cierra la escritura y descarta lo que quede del cuerpo de la petición (por ejemplo, si se
     * rechazó sin leerlo). Si se cerrase con datos sin leer, el sistema enviaría un RST y el
     * cliente perdería la respuesta.
     */
    private static void drain(Socket socket, InputStream in) {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(2000);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0 && (total += n) < 16L * 1024 * 1024) {
                // descartar
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        // IPv6 de red local única (fc00::/7)
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static Request parse(InputStream in) throws IOException {
        // Leer cabeceras hasta \r\n\r\n
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEADER_BYTES) return null;
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))
                    ? state + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;

        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                r.query.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
        }
        r.body = in;
        return r;
    }

    // ---------------------------------------------------------------- rutas

    private void route(Request req, OutputStream out) throws IOException, JSONException {
        String key = req.method + " " + req.path;
        switch (key) {
            case "GET /api/status":
                sendJson(out, 200, status());
                break;
            case "POST /api/upload":
                upload(req, out);
                break;
            case "POST /api/text":
                setText(req, out);
                break;
            case "POST /api/mode":
                setMode(req, out);
                break;
            case "POST /api/delete":
                delete(req, out);
                break;
            default:
                sendJson(out, 404, error("No encontrado: " + req.method + " " + req.path
                        + ". Este equipo se gestiona desde el panel central."));
        }
    }

    private JSONObject status() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("app", "RTSPViewer");
        j.put("version", BuildConfig.VERSION_NAME);
        j.put("camera", app.cameraState);
        j.put("showing", app.showing);
        j.put("mode", Prefs.mode(app));
        j.put("text", Prefs.backupText(app));
        JSONObject files = new JSONObject();
        for (String slot : Backup.SLOTS) {
            File f = Backup.current(app, slot);
            if (f == null) {
                files.put(slot, JSONObject.NULL);
                continue;
            }
            JSONObject file = new JSONObject();
            file.put("name", Backup.originalName(app, slot));
            String type = Backup.type(f);
            file.put("type", type);
            file.put("size", Backup.size(f));
            if (Backup.TYPE_SLIDES.equals(type)) {
                file.put("slides", Backup.slides(f).size());
                file.put("seconds", Backup.slideSeconds(f));
            }
            files.put(slot, file);
        }
        j.put("files", files);
        j.put("freeBytes", Backup.freeSpace(app));
        return j;
    }

    private void upload(Request req, OutputStream out) throws IOException, JSONException {
        String name = sanitizeName(req.query.get("name"));
        // Hueco donde se guarda: por defecto, un ZIP de diapositivas va a "esquela" y lo demás a "imagen".
        String slot = req.query.get("slot");
        if (slot == null || slot.isEmpty()) {
            slot = Backup.SLIDES_EXT.equals(Backup.ext(name)) ? Backup.SLOT_ESQUELA : Backup.SLOT_IMAGEN;
        }
        if (!Backup.isSlot(slot)) {
            sendJson(out, 400, error("Hueco no válido. Usa slot=esquela o slot=imagen"));
            return;
        }
        if (name.isEmpty()) {
            sendJson(out, 400, error("Falta el parámetro name (ejemplo: /api/upload?name=foto.jpg)"));
            return;
        }
        if (!Backup.isSupported(name)) {
            sendJson(out, 415, error("Formato no admitido. Usa JPG, PNG, WEBP, MP4, MKV, WEBM o ZIP de diapositivas"));
            return;
        }
        long len = req.contentLength();
        if (len <= 0) {
            sendJson(out, 411, error("Falta Content-Length o el archivo está vacío"));
            return;
        }
        long free = Backup.freeSpace(app);
        if (len + SPACE_MARGIN > free) {
            sendJson(out, 507, error("No hay espacio suficiente: el archivo ocupa " + mb(len)
                    + " y quedan " + mb(free) + " libres (se reservan " + mb(SPACE_MARGIN) + ")"));
            return;
        }
        if (!uploadLock.tryLock()) {
            sendJson(out, 409, error("Ya hay otra subida en curso"));
            return;
        }
        File temp = Backup.tempFile(app);
        try {
            long received = 0;
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                while (received < len) {
                    int n = req.body.read(buf, 0, (int) Math.min(buf.length, len - received));
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    received += n;
                }
                fos.getFD().sync();
            }
            if (received != len) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                sendJson(out, 400, error("Subida incompleta: llegaron " + received + " de " + len + " bytes"));
                return;
            }
            try {
                Backup.replace(app, temp, name, slot);
            } catch (IOException e) {
                sendJson(out, 400, error(e.getMessage() != null ? e.getMessage() : "No se pudo guardar el archivo"));
                return;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        } finally {
            uploadLock.unlock();
        }
        app.notifyConfigChanged();
        sendJson(out, 200, status());
    }

    private void setText(Request req, OutputStream out) throws IOException, JSONException {
        String text = readSmallBody(req);
        if (text == null) {
            sendJson(out, 413, error("Texto demasiado largo"));
            return;
        }
        text = text.replace("\r\n", "\n").trim();
        if (text.length() > MAX_TEXT_CHARS) {
            sendJson(out, 413, error("Texto demasiado largo (máximo " + MAX_TEXT_CHARS + " caracteres)"));
            return;
        }
        Prefs.get(app).edit().putString(Prefs.BACKUP_TEXT, text).commit();
        app.notifyConfigChanged();
        sendJson(out, 200, status());
    }

    private void setMode(Request req, OutputStream out) throws IOException, JSONException {
        String body = readSmallBody(req);
        String mode = body == null ? "" : body.trim().toLowerCase(Locale.ROOT).replace("á", "a");
        if (!mode.equals(Prefs.MODE_CAMERA) && !Backup.isSlot(mode)) {
            sendJson(out, 400, error("Modo no válido. Usa: camara, esquela o imagen"));
            return;
        }
        Prefs.get(app).edit().putString(Prefs.MODE, mode).commit();
        app.notifyConfigChanged();
        sendJson(out, 200, status());
    }

    /** Borra un hueco (?slot=esquela|imagen) o los dos. Si se estaba viendo, vuelve a la cámara. */
    private void delete(Request req, OutputStream out) throws IOException, JSONException {
        String slot = req.query.get("slot");
        if (slot != null && !slot.isEmpty() && !Backup.isSlot(slot)) {
            sendJson(out, 400, error("Hueco no válido. Usa slot=esquela o slot=imagen"));
            return;
        }
        String mode = Prefs.mode(app);
        for (String s : Backup.SLOTS) {
            if (slot == null || slot.isEmpty() || slot.equals(s)) {
                Backup.delete(app, s);
                if (s.equals(mode)) Prefs.get(app).edit().putString(Prefs.MODE, Prefs.MODE_CAMERA).commit();
            }
        }
        app.notifyConfigChanged();
        sendJson(out, 200, status());
    }

    // ---------------------------------------------------------------- utilidades

    /** Cuerpo pequeño como UTF-8, o null si es demasiado grande. */
    private static String readSmallBody(Request req) throws IOException {
        long len = req.contentLength();
        if (len > MAX_SMALL_BODY) return null;
        if (len <= 0) return "";
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = req.body.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    private static String sanitizeName(String name) {
        if (name == null) return "";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "").trim();
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static JSONObject error(String msg) {
        JSONObject j = new JSONObject();
        try {
            j.put("error", msg);
        } catch (JSONException ignored) {
        }
        return j;
    }

    private static void sendJson(OutputStream out, int code, JSONObject json) throws IOException {
        send(out, code, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }
}
