package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The start screen (2026-10-21): bring in your own Racer ROM, then play. The ROM is
 * copied into the app's own storage (files/swe1racer/roms/selected.z64, the path src/main.cpp loads); the original is
 * only read. .v64 and .n64 dumps are converted to .z64 byte order while copying, then checked by SHA-1.
 */
public final class LauncherActivity extends Activity {
    private static final int PICK_ROM = 1;
    private static final int EXPORT_SAVE = 2, IMPORT_SAVE = 3;
    // The dump the game was recompiled for (SHA-1 of the .z64): Star Wars Episode I: Racer (USA).
    private static final String KNOWN_SHA1 = "3542d5597c8a56ea8f5c63bceae97a24c4c08d58";

    private TextView status;
    private Button play;
    private LauncherReferenceView referenceView;

    private File rom() { return new File(getFilesDir(), "swe1racer/roms/selected.z64"); }

    private File save() { return new File(getFilesDir(), "swe1racer/saves/swe1racer.bin"); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        // Approved, user-supplied launcher mockup is a local-only resource.
        // Display it directly with accessible interactive hit areas: this avoids
        // reinventing the typography, logo, artwork and menu proportions.
        int referenceId = getResources().getIdentifier("pod_one_launcher_reference",
                "drawable", getPackageName());
        if (referenceId != 0) {
            Bitmap reference = BitmapFactory.decodeResource(getResources(), referenceId);
            if (reference != null) {
                referenceView = new LauncherReferenceView(reference);
                setContentView(referenceView);
                referenceView.requestFocus();
                return;
            }
        }
        final boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050B16);
        ImageView cover = new ImageView(this);
        // Optional personal artwork, excluded from public source due to game-art rights.
        int art = getResources().getIdentifier("pod_one_launcher_art", "drawable", getPackageName());
        cover.setImageResource(art != 0 ? art : R.drawable.launcher_background);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        GradientDrawable shade = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF608172B, 0xDD08172B, 0x4808172B, 0x0008172B});
        View shadeView = new View(this);
        shadeView.setBackground(shade);
        root.addView(shadeView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(17), dp(11), dp(8), dp(9));
        final int panelWidth = Math.min(dp(306), Math.round(getResources().getDisplayMetrics().widthPixels * 0.34f));

        TextView heading = new TextView(this);
        heading.setText("POD ONE");
        heading.setTextColor(0xFFFFD787);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 32 : 29);
        heading.setGravity(Gravity.LEFT);
        layout.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("STAR WARS EPISODE I: RACER");
        subtitle.setTextColor(0xFFDAE6F9);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPadding(0, 0, 0, dp(9));
        layout.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        play = button(buttons, "▶  Play Game", () ->
                startActivity(new Intent(this, SWE1RacerActivity.class)));
        button(buttons, "▣  Choose ROM", this::pickRom);
        button(buttons, "⚙  Settings", () ->
                startActivity(new Intent(this, PodOneSettingsActivity.class)));
        button(buttons, "▤  Save Management", this::showSaveManager);
        button(buttons, "↻  Updates", () -> PodOneUpdates.check(this));
        button(buttons, "ⓘ  Diagnostics", this::showDiagnostics);
        button(buttons, "➜  Quit", this::finish);
        scroll.addView(buttons);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = new TextView(this);
        status.setTextColor(0xFFC0D3EA);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(0, dp(8), 0, 0);
        layout.addView(status);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        root.addView(layout, menuParams);

        TextView footer = new TextView(this);
        footer.setText("Pod One · Development       A Select   ·   B Back");
        footer.setTextColor(0xFFE1E9F7);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setPadding(dp(8), dp(4), dp(12), dp(8));
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.RIGHT);
        root.addView(footer, footerParams);

        setContentView(root);
        refresh();
        (rom().isFile() ? play : buttons.getChildAt(1)).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final boolean haveRom = rom().isFile();
        final StringBuilder s = new StringBuilder();
        s.append(haveRom ? "Game ROM: ready" : "Choose your own Star Wars Episode I: Racer (USA) ROM to start.");
        s.append('\n');
        s.append(save().isFile() ? "Save: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(save().lastModified())) : "Save: none yet (the game makes one when you race)");
        if (status != null) status.setText(s);
        if (play != null) {
            play.setEnabled(haveRom);
            play.setAlpha(haveRom ? 1f : 0.5f);
        }
        if (referenceView != null) referenceView.invalidate();
    }

    private Button button(LinearLayout box, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(dp(12), 0, dp(6), 0);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    focused ? new int[]{0xF5B47A16, 0xC054330D}
                            : new int[]{0xE80C2035, 0xCC142A43});
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(2), focused ? 0xFFFFD269 : 0xFF3977AB);
            b.setBackground(bg);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(47));
        params.bottomMargin = dp(5);
        box.addView(b, params);
        GradientDrawable idle = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE80C2035, 0xCC142A43});
        idle.setCornerRadius(dp(12));
        idle.setStroke(dp(2), 0xFF3977AB);
        b.setBackground(idle);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void pickRom() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_ROM);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT_SAVE || request == IMPORT_SAVE) {
            transferSave(request, data.getData());
            return;
        }
        if (request != PICK_ROM) return;
        final Uri uri = data.getData();
        if (status != null) status.setText("Copying the ROM...");
        // Large copy off the UI thread.
        new Thread(() -> {
            String message;
            try {
                message = importRom(uri);
            } catch (IOException e) {
                message = "That did not work: " + e.getMessage();
            }
            final String m = message;
            runOnUiThread(() -> {
                refresh();
                new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show();
            });
        }).start();
    }

    private void showSaveManager() {
        final String[] options = {"Export / Backup Save", "Import / Restore Save", "Delete Save"};
        new AlertDialog.Builder(this).setTitle("Pod One · Save Management")
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    if (!save().isFile()) {
                        message("No Racer save exists yet."); return;
                    }
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/octet-stream");
                    i.putExtra(Intent.EXTRA_TITLE, "pod-one-save.bin");
                    startActivityForResult(i, EXPORT_SAVE);
                } else if (which == 1) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, IMPORT_SAVE);
                } else {
                    new AlertDialog.Builder(this).setTitle("Delete Racer Save?")
                        .setMessage("This cannot be undone. Export a backup first.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (dialog, button) -> {
                            message(save().delete() ? "Save deleted." : "No save deleted.");
                            refresh();
                        }).show();
                }
            }).setNegativeButton("Close", null).show();
    }

    private void transferSave(int request, Uri uri) {
        try {
            if (request == EXPORT_SAVE) {
                if (!save().isFile()) throw new IOException("No save found");
                try (InputStream in = new java.io.FileInputStream(save());
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("Cannot open destination");
                    copy(in, out, 8 * 1024 * 1024);
                }
                message("Save exported successfully.");
            } else {
                File target = save();
                File temp = new File(target.getAbsolutePath() + ".import");
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                    throw new IOException("Cannot create save folder");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("Cannot open imported save");
                    copy(in, out, 8 * 1024 * 1024);
                }
                if (temp.length() == 0) throw new IOException("Imported file is empty");
                new AlertDialog.Builder(this).setTitle("Replace Racer Save?")
                    .setMessage("This will replace the current Racer save. The previous save will be kept as a local .bak file.")
                    .setNegativeButton("Cancel", (d, w) -> temp.delete())
                    .setPositiveButton("Restore", (d, w) -> {
                        File backup = new File(target.getAbsolutePath() + "." + System.currentTimeMillis() + ".bak");
                        if (target.exists() && !target.renameTo(backup)) {
                            message("Could not back up existing save."); return;
                        }
                        if (temp.renameTo(target)) message("Save imported.");
                        else message("Could not restore; backup retained.");
                        refresh();
                    }).show();
            }
        } catch (IOException e) {
            message("Save operation failed: " + e.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out, int limit) throws IOException {
        byte[] buf = new byte[8192];
        int count = 0, n;
        while ((n = in.read(buf)) != -1) {
            count += n;
            if (count > limit) throw new IOException("File is too large");
            out.write(buf, 0, n);
        }
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
    }

    private void showDiagnostics() {
        new AlertDialog.Builder(this)
                .setTitle("Pod One Diagnostics")
                .setMessage("Share startup logs, including accessible game output and device details. Android limits logcat to app-readable records.")
                .setPositiveButton("Share Startup Log", (d, w) -> PodOneDiagnostics.shareStartupLog(this))
                .setNeutralButton("Driver Preferences", (d, w) -> PodOneDiagnostics.openDriverPreferences(this))
                .setNegativeButton("Close", null)
                .show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (referenceView != null) return referenceView.handleKey(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                View focused = getCurrentFocus();
                if (focused instanceof Button) focused.performClick();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Full-quality reference launcher. All art, lettering, icons and button
     * silhouettes come from the user-approved image; no generated replacement
     * elements are inserted here. The overlay supplies the actual interactions.
     *
     * Reference coordinate system: 1672 by 941 (16:9). We fit it inside the
     * screen instead of stretching it, then map touch coordinates back.
     */
    private final class LauncherReferenceView extends View {
        private static final float ART_W = 1672f, ART_H = 941f;
        private final Bitmap reference;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF[] hitAreas = new RectF[] {
            new RectF(35, 257, 465, 345), // Play
            new RectF(41, 357, 464, 432), // Settings
            new RectF(41, 439, 464, 516), // Save Management
            new RectF(41, 526, 464, 601), // Updates
            new RectF(41, 610, 464, 685), // Diagnostics
            new RectF(41, 693, 464, 769)  // Quit
        };
        private int choice = 0;
        private float scale = 1f, left, top;

        LauncherReferenceView(Bitmap art) {
            super(LauncherActivity.this);
            reference = art;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setContentDescription("Pod One launcher. Use up and down to choose an option, A to select. "
                    + "Long press Play to choose a different ROM.");
        }

        private void transform() {
            scale = Math.min(getWidth() / ART_W, getHeight() / ART_H);
            left = (getWidth() - ART_W * scale) * 0.5f;
            top = (getHeight() - ART_H * scale) * 0.5f;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            transform();
            canvas.drawColor(0xFF061323);
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            canvas.drawBitmap(reference, null, new RectF(0, 0, ART_W, ART_H), imagePaint);

            if (choice != 0) {
                // The reference deliberately highlights Play. Dim it when
                // another control is focused so only one selection is active.
                RectF playBounds = hitAreas[0];
                tint.setStyle(Paint.Style.FILL);
                tint.setColor(0xC7081B31);
                canvas.drawRoundRect(playBounds, 20, 20, tint);
                tint.setColor(0xFFE1EFFA);
                tint.setTextSize(28);
                tint.setTypeface(Typeface.DEFAULT_BOLD);
                canvas.drawText("▶   Play Game", 77, 298, tint);
                tint.setTextSize(16);
                tint.setTypeface(Typeface.DEFAULT);
                canvas.drawText("Start Star Wars Racer", 159, 324, tint);
                tint.setStyle(Paint.Style.STROKE);
                tint.setStrokeWidth(2.5f);
                tint.setColor(0xFF278DCD);
                canvas.drawRoundRect(playBounds, 20, 20, tint);
            }
            if (choice > 0) {
                RectF current = hitAreas[choice];
                tint.setStyle(Paint.Style.FILL);
                tint.setColor(0x553C2906);
                canvas.drawRoundRect(current, 18, 18, tint);
                tint.setStyle(Paint.Style.STROKE);
                tint.setStrokeWidth(4f);
                tint.setColor(0xFFFFD36D);
                canvas.drawRoundRect(current, 18, 18, tint);
            }
            // The reference image uses an illustrative release number.
            // Always show the installed build's actual version instead.
            tint.setStyle(Paint.Style.FILL);
            tint.setColor(0xFA071A2D);
            RectF badge = new RectF(48, 837, 339, 895);
            canvas.drawRoundRect(badge, 30, 30, tint);
            tint.setStyle(Paint.Style.STROKE);
            tint.setStrokeWidth(2);
            tint.setColor(0xFF4D6980);
            canvas.drawRoundRect(badge, 30, 30, tint);
            tint.setStyle(Paint.Style.FILL);
            tint.setTypeface(Typeface.DEFAULT);
            tint.setTextSize(20);
            tint.setColor(0xFFEBF3FA);
            String version = "Development";
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            canvas.drawText("Pod One v" + version + " (dev)", 79, 873, tint);
            canvas.restore();
        }

        private void runChoice(int index) {
            switch (index) {
                case 0:
                    if (rom().isFile()) startActivity(new Intent(LauncherActivity.this, SWE1RacerActivity.class));
                    else pickRom();
                    break;
                case 1: startActivity(new Intent(LauncherActivity.this, PodOneSettingsActivity.class)); break;
                case 2: showSaveManager(); break;
                case 3: PodOneUpdates.check(LauncherActivity.this); break;
                case 4: showDiagnostics(); break;
                case 5: finish(); break;
                default: break;
            }
        }

        boolean handleKey(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0)
                return true;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    choice = (choice + hitAreas.length - 1) % hitAreas.length;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    choice = (choice + 1) % hitAreas.length;
                    break;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    runChoice(choice);
                    break;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    pickRom(); // Optional controller shortcut to replace the game ROM.
                    break;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    break;
                default: return false;
            }
            invalidate();
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            transform();
            float x = (event.getX() - left) / scale;
            float y = (event.getY() - top) / scale;
            for (int i = 0; i < hitAreas.length; i++) {
                if (!hitAreas[i].contains(x, y)) continue;
                choice = i;
                invalidate();
                runChoice(i);
                return true;
            }
            return true;
        }
    }

    private String importRom(Uri uri) throws IOException {
        final File dest = rom(), tmp = new File(dest.getPath() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        dest.getParentFile().mkdirs();
        final String sha1 = copyAsZ64(uri, tmp);
        if (sha1 == null) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return "That file is not a Nintendo 64 ROM (.z64, .v64 or .n64).";
        }
        if (!KNOWN_SHA1.equals(sha1)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return "That ROM is not the supported Star Wars Episode I: Racer (USA) dump.\nSHA-1: " + sha1;
        }
        if (dest.exists() && !dest.delete()) throw new IOException("cannot replace the old ROM");
        if (!tmp.renameTo(dest)) throw new IOException("cannot store the ROM");
        return "Game ROM ready. Press Play.";
    }

    /**
     * Copies a picked N64 ROM to `to` in .z64 (big-endian) byte order and returns the SHA-1 of the result (lowercase
     * hex), or null when the file does not start like an N64 ROM. .v64 swaps every 2 bytes, .n64 reverses every 4.
     */
    private String copyAsZ64(Uri uri, File to) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("cannot open the file");
            final MessageDigest sha = MessageDigest.getInstance("SHA-1");
            final byte[] buf = new byte[1 << 16];
            int order = -1; // 0 = z64, 1 = v64, 2 = n64
            int filled;
            while ((filled = readBlock(in, buf)) > 0) {
                if (order < 0) {
                    if (filled < 4) return null;
                    final int magic = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16) | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
                    if (magic == 0x80371240) order = 0;
                    else if (magic == 0x37804012) order = 1;
                    else if (magic == 0x40123780) order = 2;
                    else return null;
                }
                if (order == 1) {
                    for (int i = 0; i + 1 < filled; i += 2) { byte t = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = t; }
                } else if (order == 2) {
                    for (int i = 0; i + 3 < filled; i += 4) {
                        byte t0 = buf[i], t1 = buf[i + 1];
                        buf[i] = buf[i + 3]; buf[i + 1] = buf[i + 2]; buf[i + 2] = t1; buf[i + 3] = t0;
                    }
                }
                out.write(buf, 0, filled);
                sha.update(buf, 0, filled);
            }
            if (order < 0) return null;
            final StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Fills `buf` as far as the stream allows (so byte swapping always sees whole 4-byte groups). */
    private static int readBlock(InputStream in, byte[] buf) throws IOException {
        int filled = 0;
        while (filled < buf.length) {
            final int n = in.read(buf, filled, buf.length - filled);
            if (n < 0) break;
            filled += n;
        }
        return filled;
    }
}
