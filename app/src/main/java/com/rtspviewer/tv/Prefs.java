package com.rtspviewer.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class Prefs {
    static final String IP = "ip", PORT = "port", USER = "user", PASS = "pass", PATH = "path", TCP = "tcp",
            AUTOSTART = "autostart",
            MODE = "mode", BACKUP_TEXT = "backup_text", BACKUP_FILE = "backup_file", BACKUP_NAME = "backup_name";

    /** Modos de pantalla: solo se cambia de uno a otro manualmente (desde el PC o el panel web). */
    static final String MODE_BACKUP = "respaldo", MODE_CAMERA = "camara";

    static final String DEFAULT_BACKUP_TEXT = "Cámara no disponible";

    private Prefs() {}

    static String mode(Context c) {
        return MODE_BACKUP.equals(get(c).getString(MODE, MODE_CAMERA)) ? MODE_BACKUP : MODE_CAMERA;
    }

    static String backupText(Context c) {
        String t = get(c).getString(BACKUP_TEXT, "");
        return t.trim().isEmpty() ? DEFAULT_BACKUP_TEXT : t;
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("camara", Context.MODE_PRIVATE);
    }

    static boolean isConfigured(Context c) {
        return !get(c).getString(IP, "").trim().isEmpty();
    }

    static boolean useTcp(Context c) {
        return get(c).getBoolean(TCP, true);
    }

    static boolean autoStart(Context c) {
        return get(c).getBoolean(AUTOSTART, true);
    }

    /** rtsp://usuario:clave@ip:puerto/ruta */
    static String buildUrl(Context c) {
        SharedPreferences p = get(c);
        String ip = p.getString(IP, "").trim();
        String port = p.getString(PORT, "554").trim();
        String user = p.getString(USER, "");
        String pass = p.getString(PASS, "");
        String path = p.getString(PATH, "").trim();

        StringBuilder sb = new StringBuilder("rtsp://");
        if (!user.isEmpty()) {
            sb.append(Uri.encode(user));
            if (!pass.isEmpty()) sb.append(':').append(Uri.encode(pass));
            sb.append('@');
        }
        sb.append(ip);
        if (!port.isEmpty()) sb.append(':').append(port);
        if (!path.startsWith("/")) sb.append('/');
        sb.append(path);
        return sb.toString();
    }
}
