package com.rtspviewer.panel;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Panel central dentro de la tablet: la misma API que pc/panel_central.py, servida en el puerto
 * 8000 para el WebView de la app (y para cualquier otro navegador de la red local).
 */
final class PanelServer {

    static final int PORT = 8000;

    private static final String TAG = "PanelServer";
    private static final int POLL_MS = 4000;
    private static final int SCAN_TIMEOUT = 800;
    private static final long SPACE_MARGIN = 50L * 1048576;
    private static final int MAX_JSON = 1048576;
    private static final int MAX_SLIDESHOW_BYTES = 200 * 1048576;
    private static final int MAX_PINNED_BYTES = 30 * 1048576;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private static final Map<String, String> MODES = new LinkedHashMap<>();

    static {
        MODES.put("camara", "Cámara");
        MODES.put("esquela", "Esquelas");
        MODES.put("imagen", "Imagen");
    }

    private static PanelServer instance;

    private final Context app;
    private final Config cfg;
    private final PanelState state = new PanelState();
    private final ExecutorService requests = Executors.newCachedThreadPool();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private byte[] page;

    /** Arranca el servidor la primera vez (abre el puerto); las siguientes no hace nada. */
    static synchronized void start(Context context) throws IOException {
        if (instance != null) return;
        PanelServer s = new PanelServer(context.getApplicationContext());
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(PORT));
        s.listen(ss);
        instance = s;
    }

    private PanelServer(Context app) {
        this.app = app;
        this.cfg = new Config(app.getFilesDir());
        cleanTemp();
    }

    // ---------------------------------------------------------------- imagen fija

    /**
     * Imagen fija del editor de esquelas: va siempre al final y no se borra con el editor. Se guarda
     * en la tablet (no en el navegador) para verla desde cualquier navegador, igual que en el PC.
     */
    private File pinnedFile() {
        return new File(app.getFilesDir(), "imagen_fija.json");
    }

    private synchronized JSONObject loadPinned() {
        File f = pinnedFile();
        if (!f.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return cleanPinned(new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException | BadRequest e) {
            return null;
        }
    }

    /** null o {"type": "imagen", "image": data URL, "fit": contain|cover}. */
    private static JSONObject cleanPinned(JSONObject p) throws BadRequest, JSONException {
        if (p == null) return null;
        String image = p.optString("image", "");
        if (!image.startsWith("data:image/")) throw new BadRequest("La imagen fija no es válida");
        return new JSONObject()
                .put("type", "imagen")
                .put("image", image)
                .put("fit", "cover".equals(p.optString("fit")) ? "cover" : "contain");
    }

    private synchronized void savePinned(JSONObject p) throws BadRequest, JSONException, IOException {
        p = cleanPinned(p);
        File f = pinnedFile();
        if (p == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(p.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar la imagen fija");
    }

    private void listen(final ServerSocket ss) {
        Thread accept = new Thread(() -> {
            while (true) {
                try {
                    final Socket s = ss.accept();
                    requests.execute(() -> handle(s));
                } catch (IOException e) {
                    Log.w(TAG, "Error aceptando conexión", e);
                }
            }
        }, "panel-http");
        accept.setDaemon(true);
        accept.start();
        Thread poll = new Thread(this::poller, "panel-poller");
        poll.setDaemon(true);
        poll.start();
    }

    // =========================================================================== estado de los equipos

    private void pollOne(String ip) {
        try {
            JSONObject data = DeviceClient.get(ip, "/api/status");
            if (!DeviceClient.APP_ID.equals(data.optString("app"))) {
                throw new DeviceClient.DeviceError("En esa IP responde algo que no es RTSP Viewer");
            }
            state.setStatus(ip, data, null);
        } catch (Exception e) {
            state.setStatus(ip, null, DeviceClient.friendly(e));
        }
    }

    private void poller() {
        ExecutorService ex = Executors.newFixedThreadPool(16);
        while (true) {
            List<Callable<Object>> jobs = new ArrayList<>();
            for (Config.Device d : cfg.devices()) {
                final String ip = d.ip;
                jobs.add(Executors.callable(() -> pollOne(ip)));
            }
            try {
                ex.invokeAll(jobs);
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // =========================================================================== acciones

    private interface DevFn {
        JSONObject run(String ip) throws Exception;
    }

    private interface DoneText {
        String text(JSONObject data);
    }

    /** Ejecuta fn(ip) en paralelo en cada equipo, anotando el resultado en su tarea. */
    private void runOn(final List<String> ips, final String label, final DevFn fn, final DoneText done) {
        workers.execute(() -> {
            ExecutorService ex = Executors.newFixedThreadPool(Math.min(16, ips.size()));
            List<Callable<Object>> jobs = new ArrayList<>();
            for (final String ip : ips) {
                jobs.add(Executors.callable(() -> {
                    state.setTask(ip, label + "…");
                    try {
                        JSONObject data = fn.run(ip);
                        state.setStatus(ip, data, null);
                        state.setTask(ip, done != null ? done.text(data) : label + ": hecho", "ok", null);
                    } catch (Exception e) {
                        state.setTask(ip, DeviceClient.friendly(e), "error", null);
                    }
                }));
            }
            try {
                ex.invokeAll(jobs);
            } catch (InterruptedException ignored) {
            } finally {
                ex.shutdown();
            }
        });
    }

    /** Envía el archivo al hueco «slot» (esquela o imagen) de cada equipo y lo muestra al momento. */
    private void uploadJob(final File src, final String name, final List<String> ips, final File workdir, final String slot) {
        workers.execute(() -> {
            try {
                state.setNotice("Enviando «" + name + "»…");
                final long size = src.length();
                ExecutorService ex = Executors.newFixedThreadPool(Math.min(8, ips.size()));
                List<Future<Boolean>> results = new ArrayList<>();
                for (final String ip : ips) {
                    results.add(ex.submit(() -> {
                        try {
                            JSONObject st = DeviceClient.get(ip, "/api/status");
                            long free = st.optLong("freeBytes", 0);
                            if (size + SPACE_MARGIN > free) {
                                throw new DeviceClient.DeviceError("No cabe: ocupa " + mb(size) + " y quedan " + mb(free) + " libres");
                            }
                            final double[] last = {0};
                            state.setTask(ip, "Enviando 0 %", "running", 0.0);
                            DeviceClient.upload(ip, src, name, slot, (sent, total) -> {
                                double p = total > 0 ? (double) sent / total : 1;
                                if (p - last[0] >= 0.01 || sent == total) {
                                    last[0] = p;
                                    state.setTask(ip, "Enviando " + (int) (p * 100) + " %", "running", p);
                                }
                            });
                            JSONObject data = DeviceClient.post(ip, "/api/mode", slot);  // enviar = mostrar al momento
                            state.setStatus(ip, data, null);
                            state.setTask(ip, "«" + name + "» en pantalla", "ok", 1.0);
                            return true;
                        } catch (Exception e) {
                            state.setTask(ip, DeviceClient.friendly(e), "error", null);
                            return false;
                        }
                    }));
                }
                int ok = 0;
                for (Future<Boolean> f : results) {
                    try {
                        if (f.get()) ok++;
                    } catch (Exception ignored) {
                    }
                }
                ex.shutdown();
                int fails = ips.size() - ok;
                state.setNotice("«" + name + "» en pantalla en " + ok + " de " + ips.size() + " equipos"
                        + (fails > 0 ? " (" + fails + " con error)" : ""), fails == 0 ? "ok" : "error");
            } finally {
                state.busy.set(false);
                deleteTree(workdir);
            }
        });
    }

    // =========================================================================== búsqueda

    /** Redes /24 privadas de la tablet, como "192.168.1." */
    private static List<String> localNetworks() {
        List<String> nets = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        byte[] b = a.getAddress();
                        String net = (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                        if (!nets.contains(net)) nets.add(net);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "No se pudieron leer las interfaces de red", e);
        }
        return nets;
    }

    /** IP de la tablet en la red local (o null). */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void scanJob() {
        List<String> nets = localNetworks();
        if (nets.isEmpty()) {
            state.setNotice("No se encontró ninguna red local en esta tablet. ¿Está conectada al Wi-Fi?", "error");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String n : nets) names.append(names.length() > 0 ? ", " : "").append(n).append("0/24");
        state.setNotice("Buscando equipos en " + names + "…");
        ExecutorService ex = Executors.newFixedThreadPool(64);
        List<Future<String>> probes = new ArrayList<>();
        for (String net : nets) {
            for (int i = 1; i < 255; i++) {
                final String ip = net + i;
                probes.add(ex.submit(() -> {
                    try {
                        JSONObject j = DeviceClient.get(ip, "/api/status", SCAN_TIMEOUT);
                        return DeviceClient.APP_ID.equals(j.optString("app")) ? ip : null;
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }
        }
        List<String> found = new ArrayList<>();
        for (Future<String> f : probes) {
            try {
                String ip = f.get();
                if (ip != null) found.add(ip);
            } catch (Exception ignored) {
            }
        }
        ex.shutdown();
        int added = 0;
        for (String ip : found) {
            if (cfg.add(ip, "")) {
                added++;
                pollOne(ip);
            }
        }
        if (found.isEmpty()) {
            state.setNotice("No se encontró ningún equipo con RTSP Viewer abierto.", "error");
        } else {
            state.setNotice("Encontrados " + found.size() + " equipos; " + added + " nuevos añadidos a la lista.", "ok");
        }
    }

    // =========================================================================== HTTP

    private static final class BadRequest extends Exception {
        BadRequest(String msg) {
            super(msg);
        }
    }

    private static final class Request {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        InputStream body;

        long contentLength() {
            try {
                return Long.parseLong(String.valueOf(headers.get("content-length")).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(60_000);
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
            try {
                route(req, out);
            } catch (BadRequest e) {
                sendJson(out, 400, error(e.getMessage()));
            }
            drain(socket, in);
        } catch (Exception e) {
            Log.w(TAG, "Error atendiendo petición", e);
        }
    }

    private void route(Request req, OutputStream out) throws Exception {
        if ("GET".equals(req.method)) {
            if ("/".equals(req.path) || "/index.html".equals(req.path)) {
                send(out, 200, "text/html; charset=utf-8", page());
            } else if ("/api/state".equals(req.path)) {
                sendJson(out, 200, state.snapshot(cfg));
            } else if ("/api/pinned".equals(req.path)) {
                JSONObject p = loadPinned();
                sendJson(out, 200, new JSONObject().put("pinned", p == null ? JSONObject.NULL : p));
            } else {
                sendJson(out, 404, error("No encontrado"));
            }
            return;
        }
        if (!"POST".equals(req.method)) {
            sendJson(out, 404, error("No encontrado"));
            return;
        }
        if ("/api/upload".equals(req.path)) {
            upload(req, out);
            return;
        }
        JSONObject body = bodyJson(req, "/api/slideshow".equals(req.path) ? MAX_SLIDESHOW_BYTES
                : "/api/pinned".equals(req.path) ? MAX_PINNED_BYTES : MAX_JSON);
        switch (req.path) {
            case "/api/devices/add": addDevice(body); break;
            case "/api/devices/remove": cfg.remove(body.optString("ip")); break;
            case "/api/devices/rename": cfg.rename(body.optString("ip"), clip(body.optString("name").trim(), 60)); break;
            case "/api/scan": workers.execute(this::scanJob); break;
            case "/api/mode": mode(body); break;
            case "/api/text": text(body); break;
            case "/api/delete": delete(body); break;
            case "/api/cleanup": cleanup(body); break;
            case "/api/settings": cfg.setSecondsPerSlide(seconds(body.opt("seconds_per_slide"))); break;
            case "/api/slideshow": slideshow(body); break;
            case "/api/pinned": savePinned(body.optJSONObject("pinned")); break;
            default:
                sendJson(out, 404, error("No encontrado"));
                return;
        }
        sendJson(out, 200, new JSONObject().put("ok", true));
    }

    private List<String> targetIps(JSONObject body) throws BadRequest {
        List<String> ips = new ArrayList<>();
        JSONArray arr = body.optJSONArray("ips");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String ip = arr.optString(i);
                if (cfg.has(ip) && !ips.contains(ip)) ips.add(ip);
            }
        }
        if (ips.isEmpty()) throw new BadRequest("Marca al menos un equipo");
        return ips;
    }

    private void addDevice(JSONObject body) throws BadRequest {
        final String ip = validIp(body.optString("ip"));
        if (ip == null) throw new BadRequest("IP no válida");
        if (!cfg.add(ip, clip(body.optString("name").trim(), 60))) throw new BadRequest(ip + " ya está en la lista");
        workers.execute(() -> pollOne(ip));
    }

    private void mode(JSONObject body) throws BadRequest {
        final String mode = body.optString("mode");
        if (!MODES.containsKey(mode)) throw new BadRequest("Modo no válido");
        String label = "Mostrar " + MODES.get(mode).toLowerCase(Locale.ROOT);
        runOn(targetIps(body), label, ip -> DeviceClient.post(ip, "/api/mode", mode), null);
    }

    private void text(JSONObject body) throws BadRequest {
        final String text = body.optString("text").trim();
        if (text.length() > 500) throw new BadRequest("Texto demasiado largo (máximo 500 caracteres)");
        runOn(targetIps(body), "Guardar texto", ip -> DeviceClient.post(ip, "/api/text", text), null);
    }

    private void cleanup(JSONObject body) throws BadRequest {
        runOn(targetIps(body), "Liberar espacio", ip -> {
            try {
                return DeviceClient.post(ip, "/api/cleanup", "");
            } catch (DeviceClient.DeviceError e) {
                if (String.valueOf(e.getMessage()).contains("No encontrado")) {  // app antigua sin /api/cleanup
                    return DeviceClient.get(ip, "/api/status");
                }
                throw e;
            }
        }, data -> {
            if (!data.has("freedBytes")) return "Esta versión de la app no admite liberar espacio (actualízala)";
            long freed = data.optLong("freedBytes");
            return freed > 0 ? "Liberados " + mb(freed) : "No había nada sin usar";
        });
    }

    private void delete(JSONObject body) throws BadRequest {
        final String slot = body.optString("slot");
        if (!"esquela".equals(slot) && !"imagen".equals(slot)) throw new BadRequest("Indica qué borrar: esquela o imagen");
        // Si se estaba viendo, el equipo vuelve solo a la cámara.
        String label = "Borrar " + ("esquela".equals(slot) ? "esquelas" : "imagen");
        runOn(targetIps(body), label, ip -> DeviceClient.post(ip, "/api/delete?slot=" + slot, ""), null);
    }

    /** Diapositivas dibujadas en la página (JPEG en base64) → ZIP → equipos marcados. */
    private void slideshow(JSONObject body) throws BadRequest, IOException, JSONException {
        List<String> ips = targetIps(body);
        JSONArray slides = body.optJSONArray("slides");
        if (slides == null || slides.length() == 0) throw new BadRequest("Añade al menos una diapositiva");
        if (slides.length() > 100) throw new BadRequest("Demasiadas diapositivas (máximo 100)");
        Object rawSecs = body.opt("seconds");
        int secs = seconds(rawSecs == null || "".equals(rawSecs) ? cfg.secondsPerSlide() : rawSecs);
        StringBuilder clean = new StringBuilder();
        for (char ch : body.optString("name").toCharArray()) {
            if (Character.isLetterOrDigit(ch) || " -_.".indexOf(ch) >= 0) clean.append(ch);
        }
        String name = clip(clean.toString().replaceAll("^[ .]+|[ .]+$", ""), 60);
        name = (name.isEmpty() ? "Diapositivas" : name) + ".zip";
        if (!state.busy.compareAndSet(false, true)) throw new BadRequest("Ya hay un envío en curso; espera a que termine");

        File workdir = newWorkdir();
        File zip = new File(workdir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int i = 0; i < slides.length(); i++) {
                String dataUrl = slides.optString(i);
                int comma = dataUrl.indexOf(',');
                if (comma < 0 || !"data:image/jpeg;base64".equals(dataUrl.substring(0, comma))) {
                    throw new BadRequest("La diapositiva " + (i + 1) + " no es una imagen JPEG válida");
                }
                byte[] jpg;
                try {
                    jpg = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    throw new BadRequest("La diapositiva " + (i + 1) + " no es una imagen JPEG válida");
                }
                putStored(z, String.format(Locale.ROOT, "%03d.jpg", i + 1), jpg);
            }
            putStored(z, "config.json", new JSONObject().put("seconds", secs).toString().getBytes(StandardCharsets.UTF_8));
        } catch (BadRequest | IOException | JSONException e) {
            deleteTree(workdir);
            state.busy.set(false);
            throw e;
        }
        cfg.setSecondsPerSlide(secs);
        uploadJob(zip, name, ips, workdir, "esquela");
    }

    private void upload(Request req, OutputStream out) throws IOException, JSONException {
        String name = req.query.containsKey("name") ? req.query.get("name").replace('\\', '/') : "";
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        String ext = ext(name);
        long length = req.contentLength();
        List<String> ips = new ArrayList<>();
        String q = req.query.containsKey("ips") ? req.query.get("ips") : "";
        for (String ip : q.split(",")) if (cfg.has(ip) && !ips.contains(ip)) ips.add(ip);

        String error = null;
        if (ext.matches("\\.(pptx|ppt|ppsx|pps)")) {
            error = "Los PowerPoint solo se pueden convertir en el panel central del PC (hace falta PowerPoint). "
                    + "Envíalo desde el PC o guárdalo antes como imagen o vídeo.";
        } else if (name.isEmpty() || !ext.matches("\\.(jpg|jpeg|png|webp|mp4|mkv|webm)")) {
            error = "Formato no admitido. Usa JPG, PNG, WEBP, MP4, MKV o WEBM";
        } else if (length <= 0) {
            error = "El archivo está vacío";
        } else if (ips.isEmpty()) {
            error = "Marca al menos un equipo";
        } else if (!state.busy.compareAndSet(false, true)) {
            error = "Ya hay un envío en curso; espera a que termine";
        }
        if (error != null) {
            // Leer y descartar el cuerpo: si se cierra con datos sin leer, el navegador pierde la respuesta.
            skip(req.body, length);
            sendJson(out, 400, error(error));
            return;
        }

        File workdir = newWorkdir();
        File src = new File(workdir, name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_"));
        long received = 0;
        try (FileOutputStream fos = new FileOutputStream(src)) {
            byte[] buf = new byte[256 * 1024];
            while (received < length) {
                int n = req.body.read(buf, 0, (int) Math.min(buf.length, length - received));
                if (n < 0) break;
                fos.write(buf, 0, n);
                received += n;
            }
        } catch (IOException e) {
            received = -1;
        }
        if (received != length) {
            state.busy.set(false);
            deleteTree(workdir);
            sendJson(out, 400, error("No se recibió el archivo completo"));
            return;
        }
        uploadJob(src, name, ips, workdir, "imagen");
        sendJson(out, 200, new JSONObject().put("ok", true));
    }

    // =========================================================================== utilidades

    private synchronized byte[] page() throws IOException {
        if (page == null) {
            try (InputStream in = app.getAssets().open("panel.html")) {
                page = readAll(in, Integer.MAX_VALUE);
            }
        }
        return page;
    }

    private static JSONObject bodyJson(Request req, int limit) throws IOException, BadRequest {
        long n = req.contentLength();
        if (n > limit) throw new BadRequest("Petición demasiado grande");
        byte[] buf = new byte[(int) Math.max(0, n)];
        int off = 0;
        while (off < buf.length) {
            int r = req.body.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        String s = new String(buf, 0, off, StandardCharsets.UTF_8).trim();
        try {
            return new JSONObject(s.isEmpty() ? "{}" : s);
        } catch (JSONException e) {
            throw new BadRequest("JSON no válido");
        }
    }

    private static int seconds(Object v) throws BadRequest {
        int secs;
        try {
            secs = v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BadRequest("Los segundos por diapositiva deben ser un número");
        }
        if (secs < 1 || secs > 600) throw new BadRequest("Los segundos por diapositiva deben estar entre 1 y 600");
        return secs;
    }

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    static String validIp(String s) {
        Matcher m = IPV4.matcher(s.trim());
        if (!m.matches()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            int n = Integer.parseInt(m.group(i));
            if (n > 255) return null;
            sb.append(i > 1 ? "." : "").append(n);
        }
        return sb.toString();
    }

    private static void putStored(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    private File tempRoot() {
        return new File(app.getCacheDir(), "envios");
    }

    private File newWorkdir() throws IOException {
        File d = new File(tempRoot(), "e" + System.nanoTime());
        if (!d.mkdirs()) throw new IOException("No se pudo crear la carpeta temporal");
        return d;
    }

    /** Restos de envíos que se cortaron (por ejemplo, si se cerró la app). */
    private void cleanTemp() {
        deleteTree(tempRoot());
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String mb(long n) {
        return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
    }

    private static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static Request parse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int st = 0;
        while (st < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEADER_BYTES) return null;
            st = (b == '\r' && (st == 0 || st == 2)) || (b == '\n' && (st == 1 || st == 3)) ? st + 1 : (b == '\r' ? 1 : 0);
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
                try {
                    r.query.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        r.body = in;
        return r;
    }

    private static void skip(InputStream in, long n) {
        byte[] buf = new byte[64 * 1024];
        try {
            while (n > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r < 0) break;
                n -= r;
            }
        } catch (IOException ignored) {
        }
    }

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

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > limit) throw new IOException("demasiado grande");
        }
        return b.toByteArray();
    }

    private static JSONObject error(String msg) {
        try {
            return new JSONObject().put("error", msg);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void sendJson(OutputStream out, int code, JSONObject json) throws IOException {
        send(out, code, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
