package com.rtspviewer.panel;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lista de equipos y ajustes, guardados en panel_central.json (mismo formato que el panel del PC).
 */
final class Config {

    static final class Device {
        final String ip;
        String name;

        Device(String ip, String name) {
            this.ip = ip;
            this.name = name;
        }
    }

    private static final String TAG = "Config";

    private final File file;
    private final List<Device> devices = new ArrayList<>();
    private int secondsPerSlide = 8;

    Config(File dir) {
        file = new File(dir, "panel_central.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            JSONObject j = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            secondsPerSlide = j.optInt("seconds_per_slide", 8);
            JSONArray arr = j.optJSONArray("devices");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    if (d != null && !d.optString("ip").isEmpty()) {
                        devices.add(new Device(d.optString("ip"), d.optString("name")));
                    }
                }
            }
        } catch (IOException | JSONException e) {
            // No perder la lista: guardar una copia del archivo dañado antes de empezar de cero.
            Log.w(TAG, "No se pudo leer la configuración", e);
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(new File(file.getPath() + ".dañado"));
        }
    }

    private void save() {
        try {
            JSONObject j = new JSONObject();
            j.put("seconds_per_slide", secondsPerSlide);
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                arr.put(new JSONObject().put("ip", d.ip).put("name", d.name));
            }
            j.put("devices", arr);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("no se pudo renombrar " + tmp);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "No se pudo guardar la configuración", e);
        }
    }

    synchronized List<Device> devices() {
        List<Device> copy = new ArrayList<>();
        for (Device d : devices) copy.add(new Device(d.ip, d.name));
        return copy;
    }

    synchronized boolean has(String ip) {
        for (Device d : devices) if (d.ip.equals(ip)) return true;
        return false;
    }

    synchronized boolean add(String ip, String name) {
        if (has(ip)) return false;
        devices.add(new Device(ip, name));
        save();
        return true;
    }

    synchronized void remove(String ip) {
        for (int i = devices.size() - 1; i >= 0; i--) {
            if (devices.get(i).ip.equals(ip)) devices.remove(i);
        }
        save();
    }

    synchronized void rename(String ip, String name) {
        for (Device d : devices) if (d.ip.equals(ip)) d.name = name;
        save();
    }

    synchronized int secondsPerSlide() {
        return secondsPerSlide;
    }

    synchronized void setSecondsPerSlide(int s) {
        secondsPerSlide = s;
        save();
    }
}
