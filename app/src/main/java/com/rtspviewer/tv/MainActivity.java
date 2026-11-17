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
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

/** Muestra la cámara a pantalla completa. OK / Menú en el control abre la configuración. */
public class MainActivity extends Activity {

    private static final long RETRY_MS = 5000;

    private PlayerView playerView;
    private TextView status;
    private ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::startPlayer;

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

        player = new ExoPlayer.Builder(this).build();
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

    private void releasePlayer() {
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
