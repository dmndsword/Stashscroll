package com.myreels.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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
import android.text.InputType;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 1;
    private static final int DELETE_REQUEST = 42;
    private static final int WRITE_REQUEST = 43;
    private static final String PREFS = "reels";
    private static final String KEY_WATCHED = "watched_ids";

    static class Reel {
        final long id;
        final long dateAddedSec;
        final long sizeBytes;
        String name;
        Reel(long id, long dateAddedSec, long sizeBytes, String name) {
            this.id = id; this.dateAddedSec = dateAddedSec;
            this.sizeBytes = sizeBytes; this.name = name;
        }
    }

    private ViewPager2 pager;
    private TextView infoText;
    private ReelAdapter adapter;
    private SharedPreferences prefs;
    private Gallery gallery;

    private final List<Reel> reels = new ArrayList<>();
    private final List<Reel> allReels = new ArrayList<>();
    private boolean muted = false;
    private ReelHolder activeHolder;
    private final List<Long> pendingDeleteIds = new ArrayList<>();
    private long pendingRenameId = -1;
    private String pendingRenameName = null;

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

        // Gallery button, top-left, always visible
        FrameLayout galleryBtn = new FrameLayout(this);
        GradientDrawable gb = new GradientDrawable();
        gb.setShape(GradientDrawable.OVAL);
        gb.setColor(0x40000000);
        galleryBtn.setBackground(gb);
        IconView gridIcon = new IconView(this);
        gridIcon.set(IconView.Kind.GRID);
        int gpad = (int) dp(11);
        galleryBtn.setPadding(gpad, gpad, gpad, gpad);
        galleryBtn.addView(gridIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams((int) dp(44), (int) dp(44));
        gl.gravity = Gravity.TOP | Gravity.START;
        gl.topMargin = (int) dp(12);
        gl.leftMargin = (int) dp(12);
        root.addView(galleryBtn, gl);

        gallery = new Gallery(this, root);
        galleryBtn.setOnClickListener(v -> {
            if (activeHolder != null) activeHolder.pausePlayback(false);
            gallery.open();
        });

        setContentView(root);

        adapter = new ReelAdapter();
        pager.setAdapter(adapter);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                for (int i = 0; i < position; i++) markWatched(reels.get(i).id);
                activatePosition(position);
            }
        });

        if (hasPermission()) start(); else requestPermission();
    }

    @Override
    public void onBackPressed() {
        if (gallery != null && gallery.handleBack()) return;
        super.onBackPressed();
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
        buildLists();
        if (reels.isEmpty()) {
            Set<String> watched = prefs.getStringSet(KEY_WATCHED, new HashSet<>());
            if (watched.isEmpty()) {
                infoText.setText("No videos found in\nMovies > Instagram");
                return;
            }
            prefs.edit().remove(KEY_WATCHED).apply();
            Toast.makeText(this, "You watched them all — starting over", Toast.LENGTH_LONG).show();
            buildLists();
            if (reels.isEmpty()) return;
        }
        infoText.setText("");
        adapter.notifyDataSetChanged();
        pager.setCurrentItem(0, false);
        pager.post(() -> activatePosition(0));
    }

    private void buildLists() {
        allReels.clear();
        reels.clear();
        Set<String> watched = prefs.getStringSet(KEY_WATCHED, new HashSet<>());
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DISPLAY_NAME};
        String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Movies/Instagram%"};
        try (android.database.Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                int sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                while (c.moveToNext()) {
                    Reel r = new Reel(c.getLong(idCol), c.getLong(dateCol),
                            c.getLong(sizeCol), c.getString(nameCol));
                    allReels.add(r);
                    if (!watched.contains(String.valueOf(r.id))) reels.add(r);
                }
            }
        }
        Collections.shuffle(reels);
        if (gallery != null) gallery.onDataChanged();
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
            markWatched(reels.get(position).id);
            start();
        }
    }

    /** Jump the feed to a specific reel picked from the gallery. */
    private void jumpToReel(Reel target) {
        int idx = -1;
        for (int i = 0; i < reels.size(); i++) {
            if (reels.get(i).id == target.id) { idx = i; break; }
        }
        if (idx == -1) {
            int insertAt = Math.min(pager.getCurrentItem() + 1, reels.size());
            reels.add(insertAt, target);
            adapter.notifyDataSetChanged();
            idx = insertAt;
        }
        pager.setCurrentItem(idx, false);
        final int fidx = idx;
        pager.post(() -> activatePosition(fidx));
    }

    // ---------- delete / rename plumbing ----------

    private void requestDelete(List<Long> ids) {
        pendingDeleteIds.clear();
        pendingDeleteIds.addAll(ids);
        List<Uri> uris = new ArrayList<>();
        for (long id : ids) {
            uris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id));
        }
        try {
            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
            startIntentSenderForResult(pi.getIntentSender(), DELETE_REQUEST, null, 0, 0, 0);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestRename(long id, String newName) {
        pendingRenameId = id;
        pendingRenameName = newName;
        List<Uri> uris = new ArrayList<>();
        uris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id));
        try {
            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
            startIntentSenderForResult(pi.getIntentSender(), WRITE_REQUEST, null, 0, 0, 0);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't rename", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DELETE_REQUEST) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, pendingDeleteIds.size() > 1
                        ? "Deleted " + pendingDeleteIds.size() + " reels"
                        : "Deleted", Toast.LENGTH_SHORT).show();
                refreshAfterFileChange();
            }
            pendingDeleteIds.clear();
        } else if (requestCode == WRITE_REQUEST) {
            if (resultCode == RESULT_OK && pendingRenameId != -1 && pendingRenameName != null) {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, pendingRenameName);
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pendingRenameId);
                    getContentResolver().update(uri, cv, null, null);
                    Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
                }
                refreshAfterFileChange();
            }
            pendingRenameId = -1;
            pendingRenameName = null;
        }
    }

    private void refreshAfterFileChange() {
        long currentId = -1;
        int cur = pager.getCurrentItem();
        if (cur >= 0 && cur < reels.size()) currentId = reels.get(cur).id;
        buildLists();
        adapter.notifyDataSetChanged();
        if (reels.isEmpty()) {
            start();
            return;
        }
        int target = 0;
        for (int i = 0; i < reels.size(); i++) {
            if (reels.get(i).id == currentId) { target = i; break; }
        }
        pager.setCurrentItem(target, false);
        final int ft = target;
        pager.post(() -> activatePosition(ft));
    }

    @Override protected void onPause() {
        super.onPause();
        if (activeHolder != null) activeHolder.pausePlayback(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (activeHolder != null && !activeHolder.pausedByUser
                && (gallery == null || !gallery.isOpen())) {
            activeHolder.resumePlayback();
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private static String formatMb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
    }

    private static String formatDate(long sec) {
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(new Date(sec * 1000L));
    }

    // ---------- vector icons ----------

    private static class IconView extends View {
        enum Kind { PLAY, SOUND_ON, SOUND_OFF, SHARE, TRASH, GRID, CLOSE, SORT, CHECK, EDIT }
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
            stroke.setStrokeWidth(w * 0.07f);
            if (kind == Kind.PLAY) {
                Path p = new Path();
                p.moveTo(w * 0.38f, h * 0.28f);
                p.lineTo(w * 0.38f, h * 0.72f);
                p.lineTo(w * 0.74f, cy);
                p.close();
                canvas.drawPath(p, fill);
            } else if (kind == Kind.SHARE) {
                float r = w * 0.09f;
                canvas.drawLine(w * 0.30f, cy, w * 0.68f, h * 0.28f, stroke);
                canvas.drawLine(w * 0.30f, cy, w * 0.68f, h * 0.72f, stroke);
                canvas.drawCircle(w * 0.30f, cy, r, fill);
                canvas.drawCircle(w * 0.68f, h * 0.28f, r, fill);
                canvas.drawCircle(w * 0.68f, h * 0.72f, r, fill);
            } else if (kind == Kind.TRASH) {
                canvas.drawLine(w * 0.26f, h * 0.32f, w * 0.74f, h * 0.32f, stroke);
                canvas.drawLine(w * 0.42f, h * 0.32f, w * 0.42f, h * 0.24f, stroke);
                canvas.drawLine(w * 0.58f, h * 0.32f, w * 0.58f, h * 0.24f, stroke);
                canvas.drawLine(w * 0.42f, h * 0.24f, w * 0.58f, h * 0.24f, stroke);
                Path body = new Path();
                body.moveTo(w * 0.32f, h * 0.40f);
                body.lineTo(w * 0.36f, h * 0.76f);
                body.lineTo(w * 0.64f, h * 0.76f);
                body.lineTo(w * 0.68f, h * 0.40f);
                canvas.drawPath(body, stroke);
                canvas.drawLine(cx, h * 0.46f, cx, h * 0.68f, stroke);
            } else if (kind == Kind.GRID) {
                float s = w * 0.30f, gpx = w * 0.10f;
                float x0 = (w - 2 * s - gpx) / 2f, y0 = (h - 2 * s - gpx) / 2f;
                float r = w * 0.06f;
                canvas.drawRoundRect(x0, y0, x0 + s, y0 + s, r, r, fill);
                canvas.drawRoundRect(x0 + s + gpx, y0, x0 + 2 * s + gpx, y0 + s, r, r, fill);
                canvas.drawRoundRect(x0, y0 + s + gpx, x0 + s, y0 + 2 * s + gpx, r, r, fill);
                canvas.drawRoundRect(x0 + s + gpx, y0 + s + gpx, x0 + 2 * s + gpx, y0 + 2 * s + gpx, r, r, fill);
            } else if (kind == Kind.CLOSE) {
                canvas.drawLine(w * 0.30f, h * 0.30f, w * 0.70f, h * 0.70f, stroke);
                canvas.drawLine(w * 0.70f, h * 0.30f, w * 0.30f, h * 0.70f, stroke);
            } else if (kind == Kind.SORT) {
                canvas.drawLine(w * 0.26f, h * 0.32f, w * 0.74f, h * 0.32f, stroke);
                canvas.drawLine(w * 0.26f, h * 0.50f, w * 0.60f, h * 0.50f, stroke);
                canvas.drawLine(w * 0.26f, h * 0.68f, w * 0.46f, h * 0.68f, stroke);
            } else if (kind == Kind.CHECK) {
                canvas.drawLine(w * 0.26f, h * 0.52f, w * 0.44f, h * 0.70f, stroke);
                canvas.drawLine(w * 0.44f, h * 0.70f, w * 0.76f, h * 0.32f, stroke);
            } else if (kind == Kind.EDIT) {
                canvas.drawLine(w * 0.30f, h * 0.70f, w * 0.62f, h * 0.38f, stroke);
                canvas.drawLine(w * 0.62f, h * 0.38f, w * 0.70f, h * 0.46f, stroke);
                canvas.drawLine(w * 0.70f, h * 0.46f, w * 0.38f, h * 0.78f, stroke);
                canvas.drawLine(w * 0.38f, h * 0.78f, w * 0.28f, h * 0.80f, stroke);
                canvas.drawLine(w * 0.28f, h * 0.80f, w * 0.30f, h * 0.70f, stroke);
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

    // ---------- gallery / file manager ----------

    private class Gallery {
        final FrameLayout overlay;
        final RecyclerView grid;
        final GalleryAdapter gAdapter;
        final TextView title, subtitle;
        final FrameLayout sortBtn, closeBtn, shareSel, trashSel, renameSel, selectAllBtn;
        final LinearLayout normalActions, selectionActions;

        final List<Reel> items = new ArrayList<>();
        final Set<Long> selected = new HashSet<>();
        int sortMode = 0; // 0 date↓ 1 date↑ 2 size↓ 3 size↑ 4 name A-Z 5 name Z-A
        final String[] sortNames = {"Newest first", "Oldest first", "Largest first",
                "Smallest first", "Name A–Z", "Name Z–A"};

        final ExecutorService thumbPool = Executors.newFixedThreadPool(3);
        final LruCache<Long, Bitmap> thumbs = new LruCache<>(80);
        final Handler main = new Handler(Looper.getMainLooper());

        Gallery(Context c, FrameLayout root) {
            overlay = new FrameLayout(c);
            overlay.setBackgroundColor(0xFF16131F);
            overlay.setVisibility(View.GONE);
            overlay.setClickable(true);
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            overlay.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // header
            FrameLayout header = new FrameLayout(c);
            header.setPadding((int) dp(16), (int) dp(14), (int) dp(10), (int) dp(10));
            column.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout titles = new LinearLayout(c);
            titles.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(22);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setText("Your stash");
            subtitle = new TextView(c);
            subtitle.setTextColor(0xFF9A93B8);
            subtitle.setTextSize(12.5f);
            titles.addView(title);
            titles.addView(subtitle);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            header.addView(titles, tl);

            normalActions = new LinearLayout(c);
            normalActions.setOrientation(LinearLayout.HORIZONTAL);
            sortBtn = headerButton(c, IconView.Kind.SORT);
            closeBtn = headerButton(c, IconView.Kind.CLOSE);
            normalActions.addView(sortBtn);
            normalActions.addView(space(c));
            normalActions.addView(closeBtn);
            FrameLayout.LayoutParams na = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            na.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            header.addView(normalActions, na);

            selectionActions = new LinearLayout(c);
            selectionActions.setOrientation(LinearLayout.HORIZONTAL);
            selectAllBtn = headerButton(c, IconView.Kind.CHECK);
            renameSel = headerButton(c, IconView.Kind.EDIT);
            shareSel = headerButton(c, IconView.Kind.SHARE);
            trashSel = headerButton(c, IconView.Kind.TRASH);
            FrameLayout cancelSel = headerButton(c, IconView.Kind.CLOSE);
            selectionActions.addView(selectAllBtn);
            selectionActions.addView(space(c));
            selectionActions.addView(renameSel);
            selectionActions.addView(space(c));
            selectionActions.addView(shareSel);
            selectionActions.addView(space(c));
            selectionActions.addView(trashSel);
            selectionActions.addView(space(c));
            selectionActions.addView(cancelSel);
            selectionActions.setVisibility(View.GONE);
            FrameLayout.LayoutParams sa = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sa.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            header.addView(selectionActions, sa);

            grid = new RecyclerView(c);
            grid.setLayoutManager(new GridLayoutManager(c, 3));
            grid.setPadding((int) dp(3), 0, (int) dp(3), (int) dp(24));
            grid.setClipToPadding(false);
            gAdapter = new GalleryAdapter();
            grid.setAdapter(gAdapter);
            column.addView(grid, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            closeBtn.setOnClickListener(v -> close());
            sortBtn.setOnClickListener(v -> showSortMenu(v));
            cancelSel.setOnClickListener(v -> clearSelection());
            selectAllBtn.setOnClickListener(v -> {
                if (selected.size() == items.size()) selected.clear();
                else for (Reel r : items) selected.add(r.id);
                onSelectionChanged();
                gAdapter.notifyDataSetChanged();
            });
            shareSel.setOnClickListener(v -> shareSelected());
            trashSel.setOnClickListener(v -> deleteSelected());
            renameSel.setOnClickListener(v -> renameSelected());
        }

        View space(Context c) {
            View v = new View(c);
            v.setLayoutParams(new LinearLayout.LayoutParams((int) dp(6), 1));
            return v;
        }

        FrameLayout headerButton(Context c, IconView.Kind kind) {
            FrameLayout f = new FrameLayout(c);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0x1AFFFFFF);
            f.setBackground(d);
            IconView icon = new IconView(c);
            icon.set(kind);
            int pad = (int) dp(10);
            f.setPadding(pad, pad, pad, pad);
            f.addView(icon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            f.setLayoutParams(new LinearLayout.LayoutParams((int) dp(42), (int) dp(42)));
            return f;
        }

        boolean isOpen() { return overlay.getVisibility() == View.VISIBLE; }

        boolean handleBack() {
            if (!isOpen()) return false;
            if (!selected.isEmpty()) { clearSelection(); return true; }
            close();
            return true;
        }

        void open() {
            onDataChanged();
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(180).start();
        }

        void close() {
            clearSelection();
            overlay.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
            if (activeHolder != null && !activeHolder.pausedByUser) activeHolder.resumePlayback();
        }

        void onDataChanged() {
            items.clear();
            items.addAll(allReels);
            applySort();
            long total = 0;
            for (Reel r : items) total += r.sizeBytes;
            subtitle.setText(items.size() + " reels · " + formatMb(total));
            gAdapter.notifyDataSetChanged();
        }

        void applySort() {
            Comparator<Reel> cmp;
            switch (sortMode) {
                case 1: cmp = Comparator.comparingLong(r -> r.dateAddedSec); break;
                case 2: cmp = (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes); break;
                case 3: cmp = Comparator.comparingLong(r -> r.sizeBytes); break;
                case 4: cmp = (a, b) -> a.name.compareToIgnoreCase(b.name); break;
                case 5: cmp = (a, b) -> b.name.compareToIgnoreCase(a.name); break;
                default: cmp = (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec);
            }
            Collections.sort(items, cmp);
        }

        void showSortMenu(View anchor) {
            PopupMenu menu = new PopupMenu(MainActivity.this, anchor);
            for (int i = 0; i < sortNames.length; i++) {
                menu.getMenu().add(0, i, i, (i == sortMode ? "•  " : "    ") + sortNames[i]);
            }
            menu.setOnMenuItemClickListener(item -> {
                sortMode = item.getItemId();
                applySort();
                gAdapter.notifyDataSetChanged();
                return true;
            });
            menu.show();
        }

        void clearSelection() {
            selected.clear();
            onSelectionChanged();
            gAdapter.notifyDataSetChanged();
        }

        void onSelectionChanged() {
            boolean selecting = !selected.isEmpty();
            normalActions.setVisibility(selecting ? View.GONE : View.VISIBLE);
            selectionActions.setVisibility(selecting ? View.VISIBLE : View.GONE);
            renameSel.setVisibility(selected.size() == 1 ? View.VISIBLE : View.GONE);
            if (selecting) {
                title.setText(selected.size() + " selected");
                subtitle.setText("Tap items to select or deselect");
            } else {
                title.setText("Your stash");
                long total = 0;
                for (Reel r : items) total += r.sizeBytes;
                subtitle.setText(items.size() + " reels · " + formatMb(total));
            }
        }

        void shareSelected() {
            ArrayList<Uri> uris = new ArrayList<>();
            for (long id : selected) {
                uris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id));
            }
            Intent share;
            if (uris.size() == 1) {
                share = new Intent(Intent.ACTION_SEND);
                share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                share = new Intent(Intent.ACTION_SEND_MULTIPLE);
                share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            share.setType("video/*");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share reels"));
        }

        void deleteSelected() {
            requestDelete(new ArrayList<>(selected));
        }

        void renameSelected() {
            if (selected.size() != 1) return;
            long id = selected.iterator().next();
            Reel target = null;
            for (Reel r : items) if (r.id == id) { target = r; break; }
            if (target == null) return;

            EditText input = new EditText(MainActivity.this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(target.name);
            input.setSelectAllOnFocus(true);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Rename")
                    .setView(input)
                    .setPositiveButton("Rename", (d, w) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) requestRename(id, newName);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        void loadThumb(long id, ImageView into) {
            Bitmap cached = thumbs.get(id);
            if (cached != null) { into.setImageBitmap(cached); return; }
            into.setImageDrawable(null);
            into.setTag(id);
            thumbPool.execute(() -> {
                try {
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    Bitmap bmp = getContentResolver().loadThumbnail(
                            uri, new Size(320, 320), null);
                    thumbs.put(id, bmp);
                    main.post(() -> {
                        Object tag = into.getTag();
                        if (tag instanceof Long && (Long) tag == id) into.setImageBitmap(bmp);
                    });
                } catch (Exception ignored) {}
            });
        }

        class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.Cell> {

            class Cell extends RecyclerView.ViewHolder {
                final ImageView image;
                final TextView meta;
                final View selTint;
                final FrameLayout checkBadge;
                long boundId = -1;

                Cell(FrameLayout cell, ImageView image, TextView meta,
                     View selTint, FrameLayout checkBadge) {
                    super(cell);
                    this.image = image;
                    this.meta = meta;
                    this.selTint = selTint;
                    this.checkBadge = checkBadge;
                }
            }

            @NonNull @Override
            public Cell onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                Context c = parent.getContext();
                FrameLayout cell = new FrameLayout(c);
                int size = Math.max((parent.getWidth() - (int) dp(6)) / 3, (int) dp(110));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
                cell.setLayoutParams(lp);
                cell.setPadding((int) dp(2), (int) dp(2), (int) dp(2), (int) dp(2));

                FrameLayout inner = new FrameLayout(c);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF241F33);
                bg.setCornerRadius(dp(10));
                inner.setBackground(bg);
                inner.setClipToOutline(true);
                cell.addView(inner, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                ImageView image = new ImageView(c);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                inner.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                View grad = new View(c);
                GradientDrawable gg = new GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        new int[]{0xB3000000, 0x00000000});
                grad.setBackground(gg);
                FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(44));
                gl.gravity = Gravity.BOTTOM;
                inner.addView(grad, gl);

                TextView meta = new TextView(c);
                meta.setTextColor(Color.WHITE);
                meta.setTextSize(9.5f);
                meta.setAlpha(0.95f);
                meta.setMaxLines(2);
                FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ml.gravity = Gravity.BOTTOM;
                ml.leftMargin = (int) dp(7);
                ml.rightMargin = (int) dp(7);
                ml.bottomMargin = (int) dp(5);
                inner.addView(meta, ml);

                View selTint = new View(c);
                GradientDrawable st = new GradientDrawable();
                st.setColor(0x66A78BFA);
                st.setCornerRadius(dp(10));
                st.setStroke((int) dp(2), 0xFFA78BFA);
                selTint.setBackground(st);
                selTint.setVisibility(View.GONE);
                inner.addView(selTint, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                FrameLayout checkBadge = new FrameLayout(c);
                GradientDrawable cb = new GradientDrawable();
                cb.setShape(GradientDrawable.OVAL);
                cb.setColor(0xFFA78BFA);
                checkBadge.setBackground(cb);
                IconView check = new IconView(c);
                check.set(IconView.Kind.CHECK);
                int cp = (int) dp(4);
                checkBadge.setPadding(cp, cp, cp, cp);
                checkBadge.addView(check, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams((int) dp(22), (int) dp(22));
                cl.gravity = Gravity.TOP | Gravity.END;
                cl.topMargin = (int) dp(6);
                cl.rightMargin = (int) dp(6);
                checkBadge.setVisibility(View.GONE);
                inner.addView(checkBadge, cl);

                return new Cell(cell, image, meta, selTint, checkBadge);
            }

            @Override
            public void onBindViewHolder(@NonNull Cell cell, int pos) {
                Reel r = items.get(pos);
                cell.boundId = r.id;
                cell.meta.setText(formatDate(r.dateAddedSec) + " · " + formatMb(r.sizeBytes));
                loadThumb(r.id, cell.image);

                boolean isSel = selected.contains(r.id);
                cell.selTint.setVisibility(isSel ? View.VISIBLE : View.GONE);
                cell.checkBadge.setVisibility(isSel ? View.VISIBLE : View.GONE);

                cell.itemView.setOnClickListener(v -> {
                    if (!selected.isEmpty()) {
                        toggle(r.id);
                    } else {
                        close();
                        jumpToReel(r);
                    }
                });
                cell.itemView.setOnLongClickListener(v -> {
                    toggle(r.id);
                    return true;
                });
            }

            void toggle(long id) {
                if (!selected.remove(id)) selected.add(id);
                onSelectionChanged();
                notifyDataSetChanged();
            }

            @Override public int getItemCount() { return items.size(); }
        }
    }

    // ---------- feed adapter ----------

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
        final IconView playIcon, muteIcon, shareIcon, trashIcon;
        final FrameLayout playCircle, muteCircle, shareCircle, trashCircle;
        final TextView dateText, sizeText;
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

            LinearLayout infoColumn = new LinearLayout(page.getContext());
            infoColumn.setOrientation(LinearLayout.VERTICAL);
            infoColumn.setGravity(Gravity.END);
            dateText = pillText(page.getContext());
            sizeText = pillText(page.getContext());
            infoColumn.addView(dateText);
            View gapV = new View(page.getContext());
            gapV.setLayoutParams(new LinearLayout.LayoutParams(1, (int) dp(5)));
            infoColumn.addView(gapV);
            infoColumn.addView(sizeText);
            FrameLayout.LayoutParams dt = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dt.gravity = Gravity.TOP | Gravity.END;
            dt.topMargin = (int) dp(12);
            dt.rightMargin = (int) dp(12);
            page.addView(infoColumn, dt);

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
            addSideButton(page, muteCircle, 48);

            shareCircle = circle(page.getContext(), 0x40000000);
            shareIcon = new IconView(page.getContext());
            shareIcon.set(IconView.Kind.SHARE);
            shareCircle.addView(shareIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addSideButton(page, shareCircle, 112);

            trashCircle = circle(page.getContext(), 0x40000000);
            trashIcon = new IconView(page.getContext());
            trashIcon.set(IconView.Kind.TRASH);
            trashCircle.addView(trashIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addSideButton(page, trashCircle, 176);

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
            trashCircle.setOnClickListener(v -> {
                List<Long> one = new ArrayList<>();
                one.add(reelId);
                requestDelete(one);
            });
        }

        TextView pillText(Context c) {
            TextView t = new TextView(c);
            t.setTextColor(Color.WHITE);
            t.setTextSize(11);
            t.setAlpha(0.9f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x40000000);
            bg.setCornerRadius(dp(20));
            t.setBackground(bg);
            t.setPadding((int) dp(10), (int) dp(5), (int) dp(10), (int) dp(5));
            return t;
        }

        void addSideButton(FrameLayout page, FrameLayout btn, float bottomDp) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) dp(52), (int) dp(52));
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.bottomMargin = (int) dp(bottomDp);
            lp.rightMargin = (int) dp(16);
            btn.setAlpha(0f);
            btn.setVisibility(View.GONE);
            page.addView(btn, lp);
        }

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

        void bind(Reel reel) {
            reelId = reel.id;
            pausedByUser = false;
            prepared = false;
            hideOverlay(false);
            seek.setProgress(0);
            dateText.setText(formatDate(reel.dateAddedSec));
            sizeText.setText(formatMb(reel.sizeBytes));
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
                    if (pos != RecyclerView.NO_POSITION
                            && activeHolder == ReelHolder.this
                            && !gallery.isOpen()
                            && pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE) {
                        advanceFrom(pos);
                    }
                });
                mp.setOnErrorListener((m, w, e) -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION
                            && activeHolder == ReelHolder.this
                            && !gallery.isOpen()
                            && pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE) {
                        advanceFrom(pos);
                    }
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception ignored) {}
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
            for (View v : new View[]{scrim, playCircle, muteCircle, shareCircle, trashCircle}) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(150).start();
            }
        }

        void hideOverlay(boolean animate) {
            for (View v : new View[]{scrim, playCircle, muteCircle, shareCircle, trashCircle}) {
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
