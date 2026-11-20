package com.rtspviewer.tv;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Contenido guardado en el almacenamiento privado de la app, en dos huecos independientes:
 * "esquela" (normalmente un pase de diapositivas, subido como ZIP con imágenes y un
 * config.json opcional) e "imagen" (una imagen, un vídeo o también un pase).
 */
final class Backup {

    static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp"));
    static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList("mp4", "mkv", "webm"));
    static final String SLIDES_EXT = "zip";

    static final String TYPE_IMAGE = "imagen", TYPE_VIDEO = "video", TYPE_SLIDES = "diapositivas";

    static final String SLOT_ESQUELA = Prefs.MODE_ESQUELA, SLOT_IMAGEN = Prefs.MODE_IMAGEN;
    static final String[] SLOTS = {SLOT_ESQUELA, SLOT_IMAGEN};

    static boolean isSlot(String s) {
        return SLOT_ESQUELA.equals(s) || SLOT_IMAGEN.equals(s);
    }

    private static String fileKey(String slot) {
        return Prefs.BACKUP_FILE + "_" + slot;
    }

    private static String nameKey(String slot) {
        return Prefs.BACKUP_NAME + "_" + slot;
    }

    static final int DEFAULT_SLIDE_SECONDS = 8;
    private static final int MAX_SLIDES = 100;
    private static final String TEMP_NAME = "subida.tmp";
    private static final String EXTRACT_NAME = "descomprimiendo.tmp";
    private static final String SLIDES_CONFIG = "config.json";

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

    /**
     * Archivos de la carpeta que no son el contenido actual de ningún hueco: restos de envíos
     * cortados (por ejemplo, por un corte de luz). Con {@code includeTemp} incluye también el
     * temporal de la subida, que solo debe borrarse si no hay ninguna subida en curso.
     */
    static synchronized List<File> unused(Context c, boolean includeTemp) {
        Set<String> keep = new HashSet<>();
        for (String slot : SLOTS) {
            File f = current(c, slot);
            if (f != null) keep.add(f.getName());
        }
        if (!includeTemp) {
            keep.add(TEMP_NAME);
            keep.add(EXTRACT_NAME);
        }
        List<File> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) out.add(f);
        return out;
    }

    static long unusedBytes(Context c) {
        long total = 0;
        for (File f : unused(c, false)) total += size(f);
        return total;
    }

    /** Borra todo lo que no se está usando. Devuelve los bytes liberados. */
    static synchronized long cleanUnused(Context c, boolean includeTemp) {
        long freed = 0;
        for (File f : unused(c, includeTemp)) {
            long s = size(f);
            if (deleteRecursive(f)) freed += s;
        }
        return freed;
    }

    /** Extensión en minúsculas, sin punto ("" si no tiene). */
    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isSupported(String name) {
        String e = ext(name);
        return IMAGE_EXT.contains(e) || VIDEO_EXT.contains(e) || SLIDES_EXT.equals(e);
    }

    static String type(File f) {
        if (f.isDirectory()) return TYPE_SLIDES;
        return VIDEO_EXT.contains(ext(f.getName())) ? TYPE_VIDEO : TYPE_IMAGE;
    }

    /** Tamaño en bytes (la suma del contenido si es una carpeta). */
    static long size(File f) {
        if (!f.isDirectory()) return f.length();
        long total = 0;
        File[] files = f.listFiles();
        if (files != null) for (File x : files) total += size(x);
        return total;
    }

    /** Imágenes de un pase de diapositivas, en orden por nombre. */
    static List<File> slides(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && IMAGE_EXT.contains(ext(f.getName()))) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** Segundos por diapositiva (config.json del pase, o 8). */
    static int slideSeconds(File dir) {
        try (InputStream in = new FileInputStream(new File(dir, SLIDES_CONFIG))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            int s = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).optInt("seconds", DEFAULT_SLIDE_SECONDS);
            return Math.max(1, Math.min(600, s));
        } catch (Exception e) {
            return DEFAULT_SLIDE_SECONDS;
        }
    }

    /** Archivo o carpeta guardado en ese hueco, o null si no hay. */
    static synchronized File current(Context c, String slot) {
        String stored = Prefs.get(c).getString(fileKey(slot), "");
        if (stored.isEmpty()) return null;
        File f = new File(dir(c), stored);
        return f.exists() ? f : null;
    }

    /** Nombre original con el que se subió. */
    static String originalName(Context c, String slot) {
        return Prefs.get(c).getString(nameKey(slot), "");
    }

    static long freeSpace(Context c) {
        return dir(c).getUsableSpace();
    }

    /**
     * Sustituye el contenido actual por {@code temp} (ya escrito completo). Un ZIP se descomprime
     * en una carpeta. Cada contenido recibe un nombre nuevo para que la pantalla detecte el cambio.
     *
     * @throws IOException con un mensaje para el usuario si el archivo no es válido
     */
    static synchronized void replace(Context c, File temp, String originalName, String slot) throws IOException {
        File old = current(c, slot);
        String ext = ext(originalName);
        File dst = new File(dir(c), slot + "_" + System.currentTimeMillis() + (SLIDES_EXT.equals(ext) ? "" : "." + ext));
        try {
            if (SLIDES_EXT.equals(ext)) {
                File work = new File(dir(c), EXTRACT_NAME);
                deleteRecursive(work);
                extractSlides(temp, work);
                if (!work.renameTo(dst)) throw new IOException("No se pudo guardar el pase de diapositivas");
            } else if (!temp.renameTo(dst)) {
                throw new IOException("No se pudo renombrar el archivo temporal");
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        Prefs.get(c).edit()
                .putString(fileKey(slot), dst.getName())
                .putString(nameKey(slot), originalName)
                .commit();
        if (old != null && !old.equals(dst)) deleteRecursive(old);
    }

    /** Descomprime solo imágenes y config.json, sin subcarpetas (evita rutas maliciosas). */
    private static void extractSlides(File zip, File out) throws IOException {
        if (!out.mkdirs()) throw new IOException("No se pudo crear la carpeta del pase");
        int images = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName();
                boolean image = IMAGE_EXT.contains(ext(name));
                if (!image && !SLIDES_CONFIG.equals(name)) continue;
                if (image && ++images > MAX_SLIDES) {
                    throw new IOException("Demasiadas diapositivas (máximo " + MAX_SLIDES + ")");
                }
                try (FileOutputStream fos = new FileOutputStream(new File(out, name))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
        } catch (IOException ex) {
            deleteRecursive(out);
            throw ex.getMessage() != null && ex.getMessage().startsWith("Demasiadas")
                    ? ex : new IOException("El ZIP de diapositivas no es válido");
        }
        if (images == 0) {
            deleteRecursive(out);
            throw new IOException("El ZIP no contiene imágenes JPG, PNG o WEBP");
        }
    }

    /** Borra el contenido de ese hueco. Devuelve true si había uno. */
    static synchronized boolean delete(Context c, String slot) {
        File f = current(c, slot);
        Prefs.get(c).edit().remove(fileKey(slot)).remove(nameKey(slot)).commit();
        return f != null && deleteRecursive(f);
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File x : files) deleteRecursive(x);
        }
        return f.delete();
    }
}
