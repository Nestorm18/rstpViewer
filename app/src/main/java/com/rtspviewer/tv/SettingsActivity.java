package com.rtspviewer.tv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
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
                : getString(R.string.remote_url_fmt, localIp, App.HTTP_PORT));

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
