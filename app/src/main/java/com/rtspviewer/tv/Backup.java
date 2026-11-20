package com.rtspviewer.tv;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Archivo de respaldo (imagen o vídeo) guardado en el almacenamiento privado de la app. */
final class Backup {

    static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp"));
    static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList("mp4", "mkv", "webm"));

    static final String TYPE_IMAGE = "imagen", TYPE_VIDEO = "video";

    private static final String TEMP_NAME = "subida.tmp";

    private Backup() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "respaldo");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File tempFile(Context c) {
        return new File(dir(c), TEMP_NAME);
    }

    static void cleanTemp(Context c) {
        //noinspection ResultOfMethodCallIgnored
        tempFile(c).delete();
    }

    /** Extensión en minúsculas, sin punto ("" si no tiene). */
    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isSupported(String name) {
        String e = ext(name);
        return IMAGE_EXT.contains(e) || VIDEO_EXT.contains(e);
    }

    static String type(File f) {
        return VIDEO_EXT.contains(ext(f.getName())) ? TYPE_VIDEO : TYPE_IMAGE;
    }

    /** Archivo actual o null si no hay. */
    static synchronized File current(Context c) {
        String stored = Prefs.get(c).getString(Prefs.BACKUP_FILE, "");
        if (stored.isEmpty()) return null;
        File f = new File(dir(c), stored);
        return f.isFile() ? f : null;
    }

    /** Nombre original con el que se subió. */
    static String originalName(Context c) {
        return Prefs.get(c).getString(Prefs.BACKUP_NAME, "");
    }

    static long freeSpace(Context c) {
        return dir(c).getUsableSpace();
    }

    /**
     * Sustituye el archivo actual por {@code temp} (ya escrito completo). Cada archivo recibe
     * un nombre nuevo para que la pantalla detecte el cambio aunque se llame igual.
     */
    static synchronized void replace(Context c, File temp, String originalName) throws IOException {
        File old = current(c);
        File dst = new File(dir(c), "respaldo_" + System.currentTimeMillis() + "." + ext(originalName));
        if (!temp.renameTo(dst)) throw new IOException("No se pudo renombrar el archivo temporal");
        Prefs.get(c).edit()
                .putString(Prefs.BACKUP_FILE, dst.getName())
                .putString(Prefs.BACKUP_NAME, originalName)
                .commit();
        if (old != null && !old.equals(dst)) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
    }

    /** Borra el archivo de respaldo. Devuelve true si había uno. */
    static synchronized boolean delete(Context c) {
        File f = current(c);
        Prefs.get(c).edit().remove(Prefs.BACKUP_FILE).remove(Prefs.BACKUP_NAME).commit();
        return f != null && f.delete();
    }
}
