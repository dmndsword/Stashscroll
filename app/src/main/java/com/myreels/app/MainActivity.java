package com.myreels.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int PERM_REQUEST = 1;
    private static final String PREFS = "reels";
    private static final String KEY_WATCHED = "watched_ids";

    private VideoView videoView;
    private TextView infoText, playBtn, muteBtn;
    private SeekBar seekBar;
    private MediaPlayer currentMp;

    private final List<Long> queue = new ArrayList<>();
    private final List<Long> history = new ArrayList<>();
    private int historyPos = -1;
    private long currentId = -1;
    private boolean muted = false;
    private boolean userSeeking = false;
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && videoView.getDuration() > 0 && !userSeeking) {
                seekBar.setMax(videoView.getDuration());
                seekBar.setProgress(videoView.getCurrentPosition());
            }
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoView = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        vp.gravity = Gravity.CENTER;
        root.addView(videoView, vp);

        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(16);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(60, 60, 60, 60);
        root.addView(infoText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Center play button (visible only while paused)
        playBtn = new TextView(this);
        playBtn.setText("\u25B6");
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTextSize(64);
        playBtn.setShadowLayer(12, 0, 0, Color.BLACK);
        playBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER;
        root.addView(playBtn, pp);
        playBtn.setOnClickListener(v -> resume());

        // Mute button top-right (visible only while paused)
        muteBtn = new TextView(this);
        muteBtn.setTextColor(Color.WHITE);
        muteBtn.setTextSize(30);
        muteBtn.setShadowLayer(10, 0, 0, Color.BLACK);
        muteBtn.setPadding(40, 40, 40, 40);
        muteBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp2 = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        mp2.gravity = Gravity.TOP | Gravity.END;
        mp2.topMargin = 60;
        root.addView(muteBtn, mp2);
        muteBtn.setOnClickListener(v -> {
            muted = !muted;
            applyVolume();
            updateMuteIcon();
        });
        updateMuteIcon();

        // Red seek bar at the bottom, draggable
        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(ColorStateList.valueOf(Color.RED));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.RED));
        seekBar.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.BOTTOM;
        sp.bottomMargin = 20;
        sp.leftMargin = 10;
        sp.rightMargin = 10;
        root.addView(seekBar, sp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) videoView.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { userSeeking = false; }
        });

        setContentView(root);

        // Tap = pause/resume. Swipe up = next. Swipe down = previous.
        GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (videoView.isPlaying()) pause(); else resume();
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                             float vX, float vY) {
                if (e1 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 150 && Math.abs(vY) > 400) {
                    if (dy < 0) nextReel();      // swipe up
                    else previousReel();          // swipe down
                    return true;
                }
                return false;
            }
        });
        root.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));

        videoView.setOnCompletionListener(mp -> nextReel());
        videoView.setOnErrorListener((mp, what, extra) -> { nextReel(); return true; });

        if (hasPermission()) start(); else requestPermission();
    }

    private void pause() {
        videoView.pause();
        playBtn.setVisibility(View.VISIBLE);
        muteBtn.setVisibility(View.VISIBLE);
    }

    private void resume() {
        videoView.start();
        playBtn.setVisibility(View.GONE);
        muteBtn.setVisibility(View.GONE);
    }

    private void updateMuteIcon() {
        muteBtn.setText(muted ? "\uD83D\uDD07" : "\uD83D\uDD0A");
    }

    private void applyVolume() {
        if (currentMp != null) {
            float v = muted ? 0f : 1f;
            try { currentMp.setVolume(v, v); } catch (Exception ignored) {}
        }
    }

    private boolean hasPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{perm}, PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == PERM_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            infoText.setText("My Reels needs access to your videos.\n\nAllow it in Settings > Apps > My Reels > Permissions, then reopen the app.");
        }
    }

    private void start() {
        buildQueue();
        if (queue.isEmpty()) {
            Set<String> watched = prefs.getStringSet(KEY_WATCHED, new HashSet<>());
            if (watched.isEmpty()) {
                infoText.setText("No videos found in\nMovies > Instagram");
            } else {
                prefs.edit().remove(KEY_WATCHED).apply();
                Toast.makeText(this, "You watched them all! Starting over.", Toast.LENGTH_LONG).show();
                buildQueue();
                if (!queue.isEmpty()) nextReel();
            }
            return;
        }
        infoText.setText("");
        handler.post(progressTick);
        nextReel();
    }

    private void buildQueue() {
        queue.clear();
        Set<String> watched = prefs.getStringSet(KEY_WATCHED, new HashSet<>());

        String[] projection = {MediaStore.Video.Media._ID};
        String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Movies/Instagram%"};

        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    if (!watched.contains(String.valueOf(id))) queue.add(id);
                }
            }
        }
        Collections.shuffle(queue);
    }

    private void markWatched(long id) {
        if (id == -1) return;
        Set<String> watched = new HashSet<>(prefs.getStringSet(KEY_WATCHED, new HashSet<>()));
        watched.add(String.valueOf(id));
        prefs.edit().putStringSet(KEY_WATCHED, watched).apply();
    }

    /** Swipe up / video ended: mark watched, go forward. */
    private void nextReel() {
        markWatched(currentId);

        // If we had gone back in history, move forward through it first
        if (historyPos >= 0 && historyPos < history.size() - 1) {
            historyPos++;
            playById(history.get(historyPos));
            return;
        }

        if (queue.isEmpty()) {
            currentId = -1;
            start(); // triggers the reset flow
            return;
        }

        long id = queue.remove(0);
        history.add(id);
        historyPos = history.size() - 1;
        playById(id);
    }

    /** Swipe down: go back to the reel before this one. */
    private void previousReel() {
        if (historyPos > 0) {
            historyPos--;
            playById(history.get(historyPos));
        } else {
            Toast.makeText(this, "This was the first one", Toast.LENGTH_SHORT).show();
        }
    }

    private void playById(long id) {
        currentId = id;
        playBtn.setVisibility(View.GONE);
        muteBtn.setVisibility(View.GONE);
        seekBar.setProgress(0);
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        videoView.setVideoURI(uri);
        videoView.setOnPreparedListener(mp -> {
            currentMp = mp;
            mp.setLooping(false);
            applyVolume();
            seekBar.setMax(videoView.getDuration());
            videoView.start();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(progressTick);
        if (videoView != null) videoView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(progressTick);
        if (videoView != null && currentId != -1 && playBtn.getVisibility() != View.VISIBLE) {
            videoView.start();
        }
    }
}
