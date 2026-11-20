package com.rtspviewer.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muestra la cámara a pantalla completa o, si se elige manualmente el modo respaldo,
 * un contenido de respaldo (vídeo en bucle, imagen o texto). OK / Menú abre la configuración.
 */
public class MainActivity extends Activity {

    private static final long RETRY_MS = 5000;
    /** Si la cámara no está lista en este tiempo se considera caída y se reintenta. */
    private static final long CONNECT_TIMEOUT_MS = 10_000;

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

    /** Tamaño máximo al que se decodifican las imágenes de respaldo. */
    private static final int MAX_IMAGE_W = 1920, MAX_IMAGE_H = 1080;

    private App app;
    private PlayerView playerView;
    private TextView status;
    private View backupLayer;
    private PlayerView backupVideo;
    private ImageView backupImage;
    private TextView backupText;

    private ExoPlayer player;
    private ExoPlayer backupPlayer;
    private boolean catchingUp;
    private boolean backupShown;
    /** Identifica el contenido de respaldo cargado, para no recargarlo si no cambia. */
    private String loadedBackupKey;

    private static final long SLIDE_FADE_MS = 400;
    private List<File> slideFiles;
    private int slideIndex;
    private long slideMs;
    private final Runnable nextSlide = this::showNextSlide;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Runnable retry = this::startPlayer;
    private final Runnable latencyCheck = this::checkLatency;
    private final Runnable connectTimeout = this::onConnectTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        app = App.get(this);
        playerView = findViewById(R.id.player_view);
        status = findViewById(R.id.status);
        backupLayer = findViewById(R.id.backup_layer);
        backupVideo = findViewById(R.id.backup_video);
        backupImage = findViewById(R.id.backup_image);
        backupText = findViewById(R.id.backup_text);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        if (!Prefs.isConfigured(this) && !Prefs.MODE_BACKUP.equals(Prefs.mode(this))) {
            openSettings();
            return;
        }
        app.setConfigListener(this::applyConfig);
        applyConfig();
    }

    @Override
    protected void onStop() {
        super.onStop();
        app.setConfigListener(null);
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        hideBackup();
        app.cameraState = App.CAM_STOPPED;
        app.showing = App.SHOW_NONE;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        decoder.shutdownNow();
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

    /** En tablets (sin mando), tocar la pantalla abre la configuración. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) openSettings();
        return true;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ---------------------------------------------------------------- modo

    /** Aplica el modo y el contenido de respaldo actuales (al iniciar y cuando cambian por red). */
    private void applyConfig() {
        String mode = Prefs.mode(this);
        if (Prefs.MODE_BACKUP.equals(mode)) {
            handler.removeCallbacks(retry);
            handler.removeCallbacks(connectTimeout);
            releasePlayer();
            app.cameraState = App.CAM_STOPPED;
            status.setVisibility(View.GONE);
            showBackup();
            return;
        }

        // Modo cámara
        hideBackup();
        if (player == null) {
            if (Prefs.isConfigured(this)) {
                startPlayer();
            } else {
                app.cameraState = App.CAM_FAIL;
            }
        } else if (App.CAM_FAIL.equals(app.cameraState)) {
            showStatus(getString(R.string.reconnecting));
        }
    }

    // ---------------------------------------------------------------- cámara

    private void startPlayer() {
        handler.removeCallbacks(retry);
        releasePlayer();
        // Durante los reintentos tras un fallo se sigue informando "fallo" hasta que vuelva.
        if (!App.CAM_FAIL.equals(app.cameraState)) app.cameraState = App.CAM_CONNECTING;
        if (!backupShown) {
            app.showing = App.SHOW_CAMERA;
            showStatus(getString(R.string.connecting));
        }

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
                    onCameraReady();
                } else if (state == Player.STATE_ENDED) {
                    onCameraFailed(getString(R.string.reconnecting));
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                onCameraFailed(getString(R.string.error_fmt, error.getErrorCodeName()));
            }
        });

        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();

        handler.removeCallbacks(connectTimeout);
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void onCameraReady() {
        handler.removeCallbacks(connectTimeout);
        app.cameraState = App.CAM_OK;
        status.setVisibility(View.GONE);
        handler.removeCallbacks(latencyCheck);
        handler.postDelayed(latencyCheck, LATENCY_CHECK_MS);
    }

    private void onCameraFailed(String message) {
        handler.removeCallbacks(connectTimeout);
        app.cameraState = App.CAM_FAIL;
        showStatus(message);
        scheduleRetry();
    }

    /** La cámara no ha llegado a estar lista a tiempo: tratarlo como fallo y reintentar. */
    private void onConnectTimeout() {
        if (player != null && player.getPlaybackState() == Player.STATE_READY) return;
        onCameraFailed(getString(R.string.reconnecting));
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

    // ---------------------------------------------------------------- respaldo

    private void showBackup() {
        app.showing = App.SHOW_BACKUP;
        status.setVisibility(View.GONE);
        if (!backupShown) {
            backupShown = true;
            backupLayer.setVisibility(View.VISIBLE);
            // La superficie de la cámara no se usa en modo respaldo: quitarla libera la GPU.
            playerView.setVisibility(View.INVISIBLE);
        }
        loadBackupContent();
    }

    private void hideBackup() {
        app.showing = App.SHOW_CAMERA;
        if (!backupShown) return;
        backupShown = false;
        backupLayer.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        releaseBackupPlayer();
        stopSlides();
        backupImage.setImageDrawable(null);
        loadedBackupKey = null;
    }

    /** Carga el archivo o el texto de respaldo si ha cambiado desde la última vez. */
    private void loadBackupContent() {
        File file = Backup.current(this);
        String text = Prefs.backupText(this);
        String key = file == null ? "text:" + text : "file:" + file.getAbsolutePath();
        if (key.equals(loadedBackupKey)) return;
        loadedBackupKey = key;

        releaseBackupPlayer();
        stopSlides();
        backupImage.setImageDrawable(null);

        String type = file == null ? null : Backup.type(file);
        if (file == null) {
            showBackupText(text);
        } else if (Backup.TYPE_VIDEO.equals(type)) {
            showBackupVideo(file);
        } else if (Backup.TYPE_SLIDES.equals(type)) {
            showBackupSlides(file);
        } else {
            showBackupImage(file, key);
        }
    }

    // ---- pase de diapositivas

    private void showBackupSlides(File dir) {
        slideFiles = Backup.slides(dir);
        slideMs = Backup.slideSeconds(dir) * 1000L;
        slideIndex = -1;
        if (slideFiles.isEmpty()) {
            showBackupText(Prefs.backupText(this));
            return;
        }
        backupVideo.setVisibility(View.GONE);
        backupText.setVisibility(View.GONE);
        backupImage.setVisibility(View.VISIBLE);
        backupImage.setAlpha(1f);
        showNextSlide();
    }

    /** Decodifica la siguiente diapositiva en segundo plano y la muestra con un fundido. */
    private void showNextSlide() {
        if (!backupShown || slideFiles == null || slideFiles.isEmpty()) return;
        slideIndex = (slideIndex + 1) % slideFiles.size();
        File f = slideFiles.get(slideIndex);
        String key = loadedBackupKey;
        boolean first = backupImage.getDrawable() == null;
        decoder.execute(() -> {
            Bitmap bmp = decodeScaled(f);
            handler.post(() -> {
                if (key == null || !key.equals(loadedBackupKey) || !backupShown) return;
                if (bmp != null) {
                    if (first) {
                        backupImage.setImageBitmap(bmp);
                    } else {
                        backupImage.animate().alpha(0f).setDuration(SLIDE_FADE_MS).withEndAction(() -> {
                            backupImage.setImageBitmap(bmp);
                            backupImage.animate().alpha(1f).setDuration(SLIDE_FADE_MS).start();
                        }).start();
                    }
                }
                if (slideFiles.size() > 1) handler.postDelayed(nextSlide, slideMs);
            });
        });
    }

    private void stopSlides() {
        handler.removeCallbacks(nextSlide);
        backupImage.animate().cancel();
        backupImage.setAlpha(1f);
        slideFiles = null;
    }

    private void showBackupText(String text) {
        backupVideo.setVisibility(View.GONE);
        backupImage.setVisibility(View.GONE);
        backupText.setText(text);
        backupText.setVisibility(View.VISIBLE);
    }

    private void showBackupVideo(File file) {
        backupImage.setVisibility(View.GONE);
        backupText.setVisibility(View.GONE);
        backupVideo.setVisibility(View.VISIBLE);

        backupPlayer = new ExoPlayer.Builder(this).build();
        backupPlayer.setTrackSelectionParameters(backupPlayer.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build());
        backupPlayer.setVolume(0f);
        backupPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        backupPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // Vídeo ilegible: mostrar el texto en su lugar.
                releaseBackupPlayer();
                showBackupText(Prefs.backupText(MainActivity.this));
            }
        });
        backupVideo.setPlayer(backupPlayer);
        backupPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        backupPlayer.setPlayWhenReady(true);
        backupPlayer.prepare();
    }

    private void showBackupImage(File file, String key) {
        backupVideo.setVisibility(View.GONE);
        backupText.setVisibility(View.GONE);
        backupImage.setVisibility(View.VISIBLE);
        decoder.execute(() -> {
            Bitmap bmp = decodeScaled(file);
            handler.post(() -> {
                if (!key.equals(loadedBackupKey) || !backupShown) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    showBackupText(Prefs.backupText(this));
                } else {
                    backupImage.setImageBitmap(bmp);
                }
            });
        });
    }

    /** Decodifica la imagen reducida para que quepa en 1920x1080 sin agotar la memoria. */
    private static Bitmap decodeScaled(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > MAX_IMAGE_W || bounds.outHeight / sample > MAX_IMAGE_H) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try {
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return null;
            // Ajuste fino: inSampleSize solo reduce en potencias de 2.
            float scale = Math.min((float) MAX_IMAGE_W / bmp.getWidth(), (float) MAX_IMAGE_H / bmp.getHeight());
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void releaseBackupPlayer() {
        if (backupPlayer != null) {
            backupPlayer.release();
            backupPlayer = null;
        }
        backupVideo.setPlayer(null);
    }

    // ---------------------------------------------------------------- UI

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
