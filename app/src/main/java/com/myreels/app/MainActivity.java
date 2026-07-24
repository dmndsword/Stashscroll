package com.myreels.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 1;
    private static final String PREFS = "reels";
    private static final String KEY_WATCHED = "watched_ids";

    private ViewPager2 pager;
    private TextView infoText;
    private ReelAdapter adapter;
    private SharedPreferences prefs;

    private final List<Long> reels = new ArrayList<>();
    private boolean muted = false;
    private ReelHolder activeHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Keep screen on, but keep status bar and nav buttons visible
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(16);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(80, 80, 80, 80);
        root.addView(infoText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        adapter = new ReelAdapter();
        pager.setAdapter(adapter);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                for (int i = 0; i < position; i++) markWatched(reels.get(i));
                activatePosition(position);
            }
        });

        if (hasPermission()) start(); else requestPermission();
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
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, p, results);
        if (code == PERM_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            infoText.setText("This app needs access to your videos.\n\nAllow it in Settings > Apps > Permissions, then reopen.");
        }
    }

    private void start() {
        reels.clear();
        buildList();
        if (reels.isEmpty()) {
            Set<String> watched = prefs.getStringSet(KEY_WATCHED, new HashSet<>());
            if (watched.isEmpty()) {
                infoText.setText("No videos found in\nMovies > Instagram");
                return;
            }
            prefs.edit().remove(KEY_WATCHED).apply();
            Toast.makeText(this, "You watched them all — starting over", Toast.LENGTH_LONG).show();
            buildList();
            if (reels.isEmpty()) return;
        }
        infoText.setText("");
        adapter.notifyDataSetChanged();
        pager.setCurrentItem(0, false);
        pager.post(() -> activatePosition(0));
    }

    private void buildList() {
        Set<String> watched = prefs.getStringSet(KEY_WATCHED, new HashSet<>());
        String[] projection = {MediaStore.Video.Media._ID};
        String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Movies/Instagram%"};
        try (android.database.Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    if (!watched.contains(String.valueOf(id))) reels.add(id);
                }
            }
        }
        Collections.shuffle(reels);
    }

    private void markWatched(long id) {
        Set<String> watched = new HashSet<>(prefs.getStringSet(KEY_WATCHED, new HashSet<>()));
        if (watched.add(String.valueOf(id))) {
            prefs.edit().putStringSet(KEY_WATCHED, watched).apply();
        }
    }

    private void activatePosition(int position) {
        pager.post(() -> {
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
            if (activeHolder != null && activeHolder != vh) activeHolder.deactivate();
            if (vh instanceof ReelHolder) {
                activeHolder = (ReelHolder) vh;
                activeHolder.activate();
            }
        });
    }

    private void advanceFrom(int position) {
        if (position < reels.size() - 1) {
            pager.setCurrentItem(position + 1, true);
        } else {
            markWatched(reels.get(position));
            start();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (activeHolder != null) activeHolder.pausePlayback(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (activeHolder != null && !activeHolder.pausedByUser) activeHolder.resumePlayback();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ---------- vector icon view (play / sound / sound-off), drawn crisp ----------

    private static class IconView extends View {
        enum Kind { PLAY, SOUND_ON, SOUND_OFF }
        Kind kind = Kind.PLAY;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(Kind k) { kind = k; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            stroke.setStrokeWidth(w * 0.06f);
            if (kind == Kind.PLAY) {
                Path p = new Path();
                p.moveTo(w * 0.38f, h * 0.28f);
                p.lineTo(w * 0.38f, h * 0.72f);
                p.lineTo(w * 0.74f, cy);
                p.close();
                canvas.drawPath(p, fill);
            } else {
                // speaker body
                Path p = new Path();
                p.moveTo(w * 0.26f, h * 0.42f);
                p.lineTo(w * 0.38f, h * 0.42f);
                p.lineTo(w * 0.52f, h * 0.28f);
                p.lineTo(w * 0.52f, h * 0.72f);
                p.lineTo(w * 0.38f, h * 0.58f);
                p.lineTo(w * 0.26f, h * 0.58f);
                p.close();
                canvas.drawPath(p, fill);
                if (kind == Kind.SOUND_ON) {
                    canvas.drawArc(w * 0.50f, h * 0.34f, w *
