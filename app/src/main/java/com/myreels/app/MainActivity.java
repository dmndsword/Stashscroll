package com.myreels.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
    private TextView infoText;
    private final List<Long> queue = new ArrayList<>();
    private long currentId = -1;
    private SharedPreferences prefs;

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

        setContentView(root);

        root.setOnClickListener(v -> playNext(true));
        videoView.setOnCompletionListener(mp -> playNext(true));
        videoView.setOnErrorListener((MediaPlayer mp, int what, int extra) -> {
            playNext(true);
            return true;
        });

        if (hasPermission()) {
            start();
        } else {
            requestPermission();
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
                if (!queue.isEmpty()) playNext(false);
            }
            return;
        }
        infoText.setText("");
        playNext(false);
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
                    if (!watched.contains(String.valueOf(id))) {
                        queue.add(id);
                    }
                }
            }
        }
        Collections.shuffle(queue);
    }

    private void playNext(boolean markCurrentWatched) {
        if (markCurrentWatched && currentId != -1) {
            Set<String> watched = new HashSet<>(prefs.getStringSet(KEY_WATCHED, new HashSet<>()));
            watched.add(String.valueOf(currentId));
            prefs.edit().putStringSet(KEY_WATCHED, watched).apply();
        }

        if (queue.isEmpty()) {
            currentId = -1;
            start();
            return;
        }

        currentId = queue.remove(0);
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, currentId);
        videoView.setVideoURI(uri);
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            videoView.start();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null) videoView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null && currentId != -1) videoView.start();
    }
}
