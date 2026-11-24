package com.rtspviewer.panel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Muestra el panel central (la misma página que en el PC) servido por {@link PanelServer}
 * dentro de la propia tablet.
 */
public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;
    private static final String URL = "http://127.0.0.1:" + PanelServer.PORT + "/";

    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // el editor de esquelas se guarda en localStorage
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);         // archivos elegidos con el selector de Android
        s.setTextZoom(100);                    // la página ya tiene su propio tamaño de letra
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Solo la página del panel; cualquier otro enlace no se abre aquí.
                return !"127.0.0.1".equals(request.getUrl().getHost());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes(params.getAcceptTypes()));
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegir archivo"), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingFiles = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        }
        // Abrir el puerto fuera del hilo principal y cargar la página cuando esté listo.
        new Thread(() -> {
            String error = null;
            try {
                PanelServer.start(getApplicationContext());
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (err == null) {
                    if (web.getUrl() == null) web.loadUrl(URL);
                } else {
                    web.loadDataWithBaseURL(null, "<html><body style='font:18px sans-serif;padding:24px'>"
                            + "<h2>No se pudo abrir el panel</h2><p>El puerto " + PanelServer.PORT
                            + " está ocupado por otra app (" + TextUtils.htmlEncode(String.valueOf(err)) + ").</p>"
                            + "<p>Cierra esa app o reinicia la tablet y vuelve a abrir el Panel Central.</p></body></html>",
                            "text/html", "utf-8", null);
                }
            });
        }, "panel-start").start();
    }

    /** Tipos MIME para el selector de Android a partir del accept de la página (".jpg,.mp4" o "image/*"). */
    private static String[] mimeTypes(String[] accept) {
        String all = accept == null ? "" : TextUtils.join(",", accept).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (all.contains("image/") || all.matches(".*\\.(jpe?g|png|webp)\\b.*")) out.add("image/*");
        if (all.contains("video/") || all.matches(".*\\.(mp4|mkv|webm)\\b.*")) out.add("video/*");
        if (out.isEmpty()) out.add("*/*");
        return out.toArray(new String[0]);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFiles = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
