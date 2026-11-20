package com.rtspviewer.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private EditText ip, port, user, pass, path, backupText;
    private CheckBox tcp, autostart;
    private TextView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ip = findViewById(R.id.ip);
        port = findViewById(R.id.port);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        path = findViewById(R.id.path);
        tcp = findViewById(R.id.tcp);
        autostart = findViewById(R.id.autostart);
        backupText = findViewById(R.id.backup_text);
        preview = findViewById(R.id.preview);

        String localIp = App.localIp();
        ((TextView) findViewById(R.id.remote_url)).setText(localIp == null
                ? getString(R.string.remote_url_none)
                : getString(R.string.remote_url_fmt, localIp));

        SharedPreferences p = Prefs.get(this);
        ip.setText(p.getString(Prefs.IP, ""));
        port.setText(p.getString(Prefs.PORT, "554"));
        user.setText(p.getString(Prefs.USER, ""));
        pass.setText(p.getString(Prefs.PASS, ""));
        path.setText(p.getString(Prefs.PATH, ""));
        tcp.setChecked(p.getBoolean(Prefs.TCP, true));
        autostart.setChecked(Prefs.autoStart(this));
        backupText.setText(p.getString(Prefs.BACKUP_TEXT, ""));
        updatePreview();

        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.grant_autostart).setOnClickListener(v -> requestOverlayPermission());
        autostart.setOnCheckedChangeListener((b, checked) -> updatePermissionHint());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionHint(); // por si vuelve de conceder el permiso
    }

    /** Muestra el aviso si el inicio automático está activado pero falta el permiso que lo hace posible. */
    private void updatePermissionHint() {
        boolean missing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && autostart.isChecked()
                && !Settings.canDrawOverlays(this);
        findViewById(R.id.autostart_permission).setVisibility(missing ? View.VISIBLE : View.GONE);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.grant_autostart_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void save() {
        if (ip.getText().toString().trim().isEmpty()) {
            ip.setError(getString(R.string.ip_required));
            ip.requestFocus();
            return;
        }
        String portText = port.getText().toString().trim();
        Prefs.get(this).edit()
                .putString(Prefs.IP, ip.getText().toString().trim())
                .putString(Prefs.PORT, portText.isEmpty() ? "554" : portText)
                .putString(Prefs.USER, user.getText().toString())
                .putString(Prefs.PASS, pass.getText().toString())
                .putString(Prefs.PATH, path.getText().toString().trim())
                .putBoolean(Prefs.TCP, tcp.isChecked())
                .putBoolean(Prefs.AUTOSTART, autostart.isChecked())
                .putString(Prefs.BACKUP_TEXT, backupText.getText().toString().trim())
                .apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void updatePreview() {
        if (Prefs.isConfigured(this)) {
            preview.setText(Prefs.buildUrl(this).replaceAll(":[^:@/]*@", ":****@"));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Sin configuración no hay nada que mostrar: salir de la app.
        if (!Prefs.isConfigured(this)) {
            finishAffinity();
        } else {
            super.onBackPressed();
        }
    }
}
