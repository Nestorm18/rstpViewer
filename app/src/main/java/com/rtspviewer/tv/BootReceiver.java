package com.rtspviewer.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Abre la app al encender el dispositivo si está activado «Iniciar al encender».
 * En Android 10+ necesita el permiso «Mostrar sobre otras apps» para poder abrirse
 * desde segundo plano (en Android TV se concede con adb, ver README).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        if (!Prefs.autoStart(context)) return;

        Intent start = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(start);
    }
}
