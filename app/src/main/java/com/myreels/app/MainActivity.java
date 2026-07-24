package com.myreels.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
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

    // ---------- vector icons ----------

    private static class IconView extends View {
        enum Kind { PLAY, SOUND_ON, SOUND_OFF, SHARE }
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
            } else if (kind == Kind.SHARE) {
                float r = w * 0.09f;
                float lx = w * 0.30f, ly = cy;
                float rxT = w * 0.68f, ryT = h * 0.28f;
                float rxB = w * 0.68f, ryB = h * 0.72f;
                canvas.drawLine(lx, ly, rxT, ryT, stroke);
                canvas.drawLine(lx, ly, rxB, ryB, stroke);
                canvas.drawCircle(lx, ly, r, fill);
                canvas.drawCircle(rxT, ryT, r, fill);
                canvas.drawCircle(rxB, ryB, r, fill);
            } else {
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
                    canvas.drawArc(w * 0.50f, h * 0.34f, w * 0.78f, h * 0.66f, -60, 120, false, stroke);
                } else {
                    canvas.drawLine(w * 0.60f, h * 0.38f, w * 0.78f, h * 0.62f, stroke);
                    canvas.drawLine(w * 0.78f, h * 0.38f, w * 0.60f, h * 0.62f, stroke);
                }
            }
        }
    }

    // ---------- adapter ----------

    private class ReelAdapter extends RecyclerView.Adapter<ReelHolder> {
        @NonNull @Override
        public ReelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout page = new FrameLayout(parent.getContext());
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            page.setBackgroundColor(Color.BLACK);
            return new ReelHolder(page);
        }
        @Override public void onBindViewHolder(@NonNull ReelHolder h, int pos) { h.bind(reels.get(pos)); }
        @Override public int getItemCount() { return reels.size(); }
        @Override public void onViewRecycled(@NonNull ReelHolder h) { h.release(); }
    }

    // ---------- one reel page ----------

    private class ReelHolder extends RecyclerView.ViewHolder
            implements TextureView.SurfaceTextureListener {

        final TextureView texture;
        final View bottomGradient, scrim;
        final IconView playIcon, muteIcon, shareIcon;
        final FrameLayout playCircle, muteCircle, shareCircle;
        final SeekBar seek;

        MediaPlayer mp;
        Surface surface;
        boolean prepared = false;
        boolean isActive = false;
        boolean pausedByUser = false;
        boolean userSeeking = false;
        long reelId = -1;

        final Handler tick = new Handler(Looper.getMainLooper());
        final Runnable tickRun = new Runnable() {
            @Override public void run() {
                if (mp != null && prepared && !userSeeking) {
                    try {
                        seek.setMax(mp.getDuration());
                        seek.setProgress(mp.getCurrentPosition());
                    } catch (Exception ignored) {}
                }
                tick.postDelayed(this, 100);
            }
        };

        ReelHolder(FrameLayout page) {
            super(page);

            texture = new TextureView(page.getContext());
            texture.setSurfaceTextureListener(this);
            page.addView(texture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            bottomGradient = new View(page.getContext());
            GradientDrawable g = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, 0x00000000});
            bottomGradient.setBackground(g);
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(96));
            gp.gravity = Gravity.BOTTOM;
            page.addView(bottomGradient, gp);

            scrim = new View(page.getContext());
            scrim.setBackgroundColor(0x66000000);
            scrim.setAlpha(0f);
            scrim.setVisibility(View.GONE);
            page.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            playCircle = circle(page.getContext(), 0x33FFFFFF);
            playIcon = new IconView(page.getContext());
            playIcon.set(IconView.Kind.PLAY);
            playCircle.addView(playIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams pc = new FrameLayout.LayoutParams((int) dp(88), (int) dp(88));
            pc.gravity = Gravity.CENTER;
            playCircle.setAlpha(0f);
            playCircle.setVisibility(View.GONE);
            page.addView(playCircle, pc);

            muteCircle = circle(page.getContext(), 0x40000000);
            muteIcon = new IconView(page.getContext());
            muteCircle.addView(muteIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams mc = new FrameLayout.LayoutParams((int) dp(52), (int) dp(52));
            mc.gravity = Gravity.BOTTOM | Gravity.END;
            mc.bottomMargin = (int) dp(48);
            mc.rightMargin = (int) dp(16);
            muteCircle.setAlpha(0f);
            muteCircle.setVisibility(View.GONE);
            page.addView(muteCircle, mc);

            shareCircle = circle(page.getContext(), 0x40000000);
            shareIcon = new IconView(page.getContext());
            shareIcon.set(IconView.Kind.SHARE);
            shareCircle.addView(shareIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams sc = new FrameLayout.LayoutParams((int) dp(52), (int) dp(52));
            sc.gravity = Gravity.BOTTOM | Gravity.END;
            sc.bottomMargin = (int) dp(112);
            sc.rightMargin = (int) dp(16);
            shareCircle.setAlpha(0f);
            shareCircle.setVisibility(View.GONE);
            page.addView(shareCircle, sc);

            seek = new SeekBar(page.getContext());
            seek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x4DFFFFFF));
            seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            seek.getThumb().mutate().setAlpha(0);
            seek.setSplitTrack(false);
            seek.setPadding((int) dp(8), 0, (int) dp(8), 0);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.gravity = Gravity.BOTTOM;
            sp.bottomMargin = (int) dp(4);
            page.addView(seek, sp);

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                    if (fromUser && mp != null && prepared) mp.seekTo(pr);
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
                if (mp == null || !prepared) return;
                if (mp.isPlaying()) pausePlayback(true); else resumePlayback();
            });
            playCircle.setOnClickListener(v -> resumePlayback());
            muteCircle.setOnClickListener(v -> {
                muted = !muted;
                applyVolume();
                updateMuteIcon();
            });
            shareCircle.setOnClickListener(v -> {
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, reelId);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("video/*");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                itemView.getContext().startActivity(
                        Intent.createChooser(share, "Share reel"));
            });

            video_listeners_placeholder();
        }

        void video_listeners_placeholder() { /* listeners are set in setupPlayer */ }

        FrameLayout circle(Context c, int color) {
            FrameLayout f = new FrameLayout(c);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            f.setBackground(d);
            int pad = (int) dp(10);
            f.setPadding(pad, pad, pad, pad);
            return f;
        }

        void bind(long id) {
            reelId = id;
            pausedByUser = false;
            prepared = false;
            hideOverlay(false);
            seek.setProgress(0);
            setupPlayer();
        }

        void setupPlayer() {
            releasePlayer();
            mp = new MediaPlayer();
            try {
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, reelId);
                mp.setDataSource(itemView.getContext(), uri);
                if (surface != null) mp.setSurface(surface);
                mp.setOnPreparedListener(m -> {
                    prepared = true;
                    applyVolume();
                    seek.setMax(m.getDuration());
                    fitVideo(m.getVideoWidth(), m.getVideoHeight());
                    if (isActive && !pausedByUser) m.start();
                });
                mp.setOnVideoSizeChangedListener((m, w, h) -> fitVideo(w, h));
                mp.setOnCompletionListener(m -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) advanceFrom(pos);
                });
                mp.setOnErrorListener((m, w, e) -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) advanceFrom(pos);
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) advanceFrom(pos);
            }
        }

        void fitVideo(int videoW, int videoH) {
            if (videoW == 0 || videoH == 0) return;
            float viewW = texture.getWidth(), viewH = texture.getHeight();
            if (viewW == 0 || viewH == 0) return;
            float viewRatio = viewW / viewH;
            float videoRatio = (float) videoW / videoH;
            Matrix m = new Matrix();
            if (videoRatio > viewRatio) {
                m.setScale(1f, viewRatio / videoRatio, viewW / 2f, viewH / 2f);
            } else {
                m.setScale(videoRatio / viewRatio, 1f, viewW / 2f, viewH / 2f);
            }
            texture.setTransform(m);
        }

        void activate() {
            isActive = true;
            pausedByUser = false;
            hideOverlay(false);
            if (mp != null && prepared) {
                mp.seekTo(0);
                mp.start();
            }
            tick.post(tickRun);
        }

        void deactivate() {
            isActive = false;
            tick.removeCallbacks(tickRun);
            if (mp != null && prepared && mp.isPlaying()) mp.pause();
        }

        void pausePlayback(boolean byUser) {
            if (mp != null && prepared && mp.isPlaying()) mp.pause();
            pausedByUser = byUser;
            if (byUser) showOverlay();
        }

        void resumePlayback() {
            pausedByUser = false;
            hideOverlay(true);
            if (mp != null && prepared) mp.start();
        }

        void release() {
            deactivate();
            releasePlayer();
        }

        void releasePlayer() {
            prepared = false;
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) {}
                mp = null;
            }
        }

        void applyVolume() {
            if (mp != null) {
                float v = muted ? 0f : 1f;
                try { mp.setVolume(v, v); } catch (Exception ignored) {}
            }
        }

        void updateMuteIcon() {
            muteIcon.set(muted ? IconView.Kind.SOUND_OFF : IconView.Kind.SOUND_ON);
        }

        void showOverlay() {
            updateMuteIcon();
            for (View v : new View[]{scrim, playCircle, muteCircle, shareCircle}) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(150).start();
            }
        }

        void hideOverlay(boolean animate) {
            for (View v : new View[]{scrim, playCircle, muteCircle, shareCircle}) {
                if (animate) {
                    v.animate().alpha(0f).setDuration(150)
                            .withEndAction(() -> v.setVisibility(View.GONE)).start();
                } else {
                    v.setAlpha(0f);
                    v.setVisibility(View.GONE);
                }
            }
        }

        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
            surface = new Surface(st);
            if (mp != null) mp.setSurface(surface);
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
            if (mp != null && prepared) fitVideo(mp.getVideoWidth(), mp.getVideoHeight());
        }
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
            if (surface != null) { surface.release(); surface = null; }
            return true;
        }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
    }
}
