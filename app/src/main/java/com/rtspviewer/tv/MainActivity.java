package com.rtspviewer.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

/** Muestra la cámara a pantalla completa. OK / Menú en el control abre la configuración. */
public class MainActivity extends Activity {

    private static final long RETRY_MS = 5000;

    // Baja latencia: empezar a mostrar con muy poco vídeo en el buffer.
    private static final int MIN_BUFFER_MS = 500;
    private static final int MAX_BUFFER_MS = 2000;
    private static final int START_BUFFER_MS = 100;
    private static final int REBUFFER_MS = 300;

    // Si se acumula retraso, acelerar un poco hasta volver al directo.
    private static final long LATENCY_CHECK_MS = 1000;
    private static final long CATCH_UP_ABOVE_MS = 700;
    private static final long CATCH_UP_UNTIL_MS = 250;
    private static final float CATCH_UP_SPEED = 1.25f;

    private PlayerView playerView;
    private TextView status;
    private ExoPlayer player;
    private boolean catchingUp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::startPlayer;
    private final Runnable latencyCheck = this::checkLatency;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        playerView = findViewById(R.id.player_view);
        status = findViewById(R.id.status);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        if (!Prefs.isConfigured(this)) {
            openSettings();
            return;
        }
        startPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(retry);
        releasePlayer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                openSettings();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void startPlayer() {
        handler.removeCallbacks(retry);
        releasePlayer();
        showStatus(getString(R.string.connecting));

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        catchingUp = false;
        playerView.setPlayer(player);

        RtspMediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(Prefs.useTcp(this))
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(Prefs.buildUrl(this))));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.setVisibility(View.GONE);
                    handler.removeCallbacks(latencyCheck);
                    handler.postDelayed(latencyCheck, LATENCY_CHECK_MS);
                } else if (state == Player.STATE_ENDED) {
                    showStatus(getString(R.string.reconnecting));
                    scheduleRetry();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                showStatus(getString(R.string.error_fmt, error.getErrorCodeName()));
                scheduleRetry();
            }
        });

        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    /** Mantiene el vídeo cerca del directo: si el buffer crece, reproduce algo más rápido. */
    private void checkLatency() {
        if (player == null) return;
        long buffered = player.getTotalBufferedDuration();
        if (!catchingUp && buffered > CATCH_UP_ABOVE_MS) {
            catchingUp = true;
            player.setPlaybackParameters(new PlaybackParameters(CATCH_UP_SPEED));
        } else if (catchingUp && buffered < CATCH_UP_UNTIL_MS) {
            catchingUp = false;
            player.setPlaybackParameters(PlaybackParameters.DEFAULT);
        }
        handler.postDelayed(latencyCheck, LATENCY_CHECK_MS);
    }

    private void releasePlayer() {
        handler.removeCallbacks(latencyCheck);
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
