package com.myreels.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

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
                // Everything before this page counts as watched
                for (int i = 0; i < position; i++) markWatched(reels.get(i));
                playPosition(position);
            }
        });

        if (hasPermission()) start(); else requestPermission();
    }

    // ---------------- permissions ----------------

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
    public void onRequestPermissionsResult(int code, String[] p, int[] results) {
        super.onRequestPermissionsResult(code, p, results);
        if (code == PERM_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            infoText.setText("My Reels needs access to your videos.\n\nAllow it in Settings > Apps > My Reels > Permissions, then reopen the app.");
        }
    }

    // ---------------- feed ----------------

    private void start() {
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
        pager.post(() -> playPosition(0));
    }

    private void buildList() {
        reels.clear();
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

    private void playPosition(int position) {
        pager.post(() -> {
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
            if (activeHolder != null && activeHolder != vh) activeHolder.stop();
            if (vh instanceof ReelHolder) {
                activeHolder = (ReelHolder) vh;
                activeHolder.play();
            }
        });
    }

    private void advanceFrom(int position) {
        if (position < reels.size() - 1) {
            pager.setCurrentItem(position + 1, true); // smooth animated scroll
        } else {
            markWatched(reels.get(position));
            start(); // exhausted -> reset round
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (activeHolder != null) activeHolder.pauseVideo(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeHolder != null && !activeHolder.pausedByUser) activeHolder.resumeVideo();
    }

    // ---------------- adapter ----------------

    private class ReelAdapter extends RecyclerView.Adapter<ReelHolder> {
        @NonNull @Override
        public ReelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ReelHolder(buildPage(parent));
        }
        @Override public void onBindViewHolder(@NonNull ReelHolder h, int pos) { h.bind(reels.get(pos)); }
        @Override public int getItemCount() { return reels.size(); }
        @Override public void onViewRecycled(@NonNull ReelHolder h) { h.stop(); }
    }

    private FrameLayout buildPage(ViewGroup parent) {
        FrameLayout page = new FrameLayout(parent.getContext());
        page.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.setBackgroundColor(Color.BLACK);
        return page;
    }

    private GradientDrawable pill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ---------------- one reel page ----------------

    private class ReelHolder extends RecyclerView.ViewHolder {
        final VideoView video;
        final SeekBar seek;
        final View scrim;
        final TextView playIcon, muteIcon;
        MediaPlayer mp;
        boolean pausedByUser = false;
        boolean userSeeking = false;
        long id = -1;

        final Handler tick = new Handler(Looper.getMainLooper());
        final Runnable tickRun = new Runnable() {
            @Override public void run() {
                if (video.getDuration() > 0 && !userSeeking) {
                    seek.setMax(video.getDuration());
                    seek.setProgress(video.getCurrentPosition());
                }
                tick.postDelayed(this, 100);
            }
        };

        ReelHolder(FrameLayout page) {
            super(page);

            video = new VideoView(page.getContext());
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            vp.gravity = Gravity.CENTER;
            page.addView(video, vp);

            // Soft dark scrim shown while paused
            scrim = new View(page.getContext());
            scrim.setBackgroundColor(0x59000000);
            scrim.setAlpha(0f);
            scrim.setVisibility(View.GONE);
            page.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Center play button: frosted circle
            playIcon = new TextView(page.getContext());
            playIcon.setText("\u25B6");
            playIcon.setTextColor(Color.WHITE);
            playIcon.setTextSize(30);
            playIcon.setGravity(Gravity.CENTER);
            playIcon.setBackground(pill(0x4DFFFFFF, 60));
            int ps = (int) dp(84);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ps, ps);
            pp.gravity = Gravity.CENTER;
            playIcon.setPadding((int) dp(6), 0, 0, 0);
            playIcon.setAlpha(0f);
            playIcon.setVisibility(View.GONE);
            page.addView(playIcon, pp);

            // Mute pill, bottom-right above the bar
            muteIcon = new TextView(page.getContext());
            muteIcon.setTextColor(Color.WHITE);
            muteIcon.setTextSize(20);
            muteIcon.setGravity(Gravity.CENTER);
            muteIcon.setBackground(pill(0x4D000000, 40));
            int ms = (int) dp(52);
            FrameLayout.LayoutParams mpar = new FrameLayout.LayoutParams(ms, ms);
            mpar.gravity = Gravity.BOTTOM | Gravity.END;
            mpar.bottomMargin = (int) dp(56);
            mpar.rightMargin = (int) dp(16);
            muteIcon.setAlpha(0f);
            muteIcon.setVisibility(View.GONE);
            page.addView(muteIcon, mpar);

            // Minimal progress bar: hairline white, thumb only while touched
            seek = new SeekBar(page.getContext());
            seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            seek.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0x40FFFFFF));
            seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            seek.setPadding((int) dp(4), 0, (int) dp(4), 0);
            seek.getThumb().mutate().setAlpha(0);
            seek.setSplitTrack(false);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.gravity = Gravity.BOTTOM;
            sp.bottomMargin = (int) dp(6);
            page.addView(seek, sp);

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                    if (fromUser) video.seekTo(pr);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {
                    userSeeking = true;
                    sb.getThumb().mutate().setAlpha(255);
                }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    userSeeking = false;
                    sb.getThumb().mutate().setAlpha(0);
                }
            });

            page.setOnClickListener(v -> {
                if (video.isPlaying()) pauseVideo(true); else resumeVideo();
            });

            playIcon.setOnClickListener(v -> resumeVideo());
            muteIcon.setOnClickListener(v -> {
                muted = !muted;
                applyVolume();
                updateMuteIcon();
            });

            video.setOnCompletionListener(m -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) advanceFrom(pos);
            });
            video.setOnErrorListener((m, w, e) -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) advanceFrom(pos);
                return true;
            });
        }

        void bind(long reelId) {
            id = reelId;
            pausedByUser = false;
            hideOverlay(false);
            seek.setProgress(0);
            Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, reelId);
            video.setVideoURI(uri);
            video.setOnPreparedListener(m -> {
                mp = m;
                m.setLooping(false);
                applyVolume();
                seek.setMax(video.getDuration());
            });
        }

        void play() {
            pausedByUser = false;
            hideOverlay(true);
            video.start();
            tick.post(tickRun);
        }

        void pauseVideo(boolean byUser) {
            video.pause();
            pausedByUser = byUser;
            if (byUser) showOverlay();
        }

        void resumeVideo() {
            pausedByUser = false;
            hideOverlay(true);
            video.start();
        }

        void stop() {
            tick.removeCallbacks(tickRun);
            video.stopPlayback();
            mp = null;
        }

        void showOverlay() {
            updateMuteIcon();
            for (View v : new View[]{scrim, playIcon, muteIcon}) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(160).start();
            }
        }

        void hideOverlay(boolean animate) {
            for (View v : new View[]{scrim, playIcon, muteIcon}) {
                if (animate) {
                    v.animate().alpha(0f).setDuration(160)
                            .withEndAction(() -> v.setVisibility(View.GONE)).start();
                } else {
                    v.setAlpha(0f);
                    v.setVisibility(View.GONE);
                }
            }
        }

        void updateMuteIcon() {
            muteIcon.setText(muted ? "\uD83D\uDD07" : "\uD83D\uDD0A");
        }

        void applyVolume() {
            if (mp != null) {
                float vol = muted ? 0f : 1f;
                try { mp.setVolume(vol, vol); } catch (Exception ignored) {}
            }
        }
    }
}
