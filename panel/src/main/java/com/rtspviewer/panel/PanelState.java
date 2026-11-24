package com.rtspviewer.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Último estado conocido de cada equipo y la tarea en curso o terminada en cada uno. */
final class PanelState {

    private static final class Status {
        JSONObject data;
        String error;
        double time;
    }

    private static final class Task {
        String text, state;
        Double progress;
        double time;
    }

    private final Map<String, Status> status = new HashMap<>();
    private final Map<String, Task> tasks = new HashMap<>();
    private String noticeText = "", noticeState = "";
    private double noticeTime = 0;
    /** Hay un envío en curso. */
    final AtomicBoolean busy = new AtomicBoolean(false);

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    synchronized void setStatus(String ip, JSONObject data, String error) {
        Status s = new Status();
        s.data = data;
        s.error = error;
        s.time = now();
        status.put(ip, s);
    }

    synchronized void setTask(String ip, String text, String state, Double progress) {
        Task t = new Task();
        t.text = text;
        t.state = state;
        t.progress = progress;
        t.time = now();
        tasks.put(ip, t);
    }

    void setTask(String ip, String text) {
        setTask(ip, text, "running", null);
    }

    synchronized void setNotice(String text, String state) {
        noticeText = text;
        noticeState = state;
        noticeTime = now();
    }

    void setNotice(String text) {
        setNotice(text, "running");
    }

    synchronized JSONObject snapshot(Config cfg) throws JSONException {
        double now = now();
        JSONArray devices = new JSONArray();
        for (Config.Device d : cfg.devices()) {
            Status st = status.get(d.ip);
            Task t = tasks.get(d.ip);
            if (t != null && !"running".equals(t.state) && now - t.time > 30) t = null;  // los resultados se ven 30 s
            JSONObject task = null;
            if (t != null) {
                task = new JSONObject().put("text", t.text).put("state", t.state)
                        .put("progress", t.progress == null ? JSONObject.NULL : t.progress).put("time", t.time);
            }
            devices.put(new JSONObject()
                    .put("ip", d.ip)
                    .put("name", d.name)
                    .put("status", st == null || st.data == null ? JSONObject.NULL : st.data)
                    .put("error", st == null || st.error == null ? JSONObject.NULL : st.error)
                    .put("checked", st == null ? JSONObject.NULL : st.time)
                    .put("task", task == null ? JSONObject.NULL : task));
        }
        Object notice = JSONObject.NULL;
        if ("running".equals(noticeState) || now - noticeTime < 30) {
            notice = new JSONObject().put("text", noticeText).put("state", noticeState).put("time", noticeTime);
        }
        return new JSONObject()
                .put("devices", devices)
                .put("notice", notice)
                .put("busy", busy.get())
                .put("seconds_per_slide", cfg.secondsPerSlide());
    }
}
