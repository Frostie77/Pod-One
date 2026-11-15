package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import io.github.frostfrontend.FrostSpeakerTest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/** Select and verify a Racer ROM, then start the game in its own process. */
public final class LauncherActivity extends Activity {
    private static final int PICK_ROM = 1;
    private static final String KNOWN_SHA1 = "3542d5597c8a56ea8f5c63bceae97a24c4c08d58";
    private final FrostSpeakerTest speaker = new FrostSpeakerTest();
    private PodOneLauncherView launcher;
    private boolean importingRom;

    private File rom() { return new File(getFilesDir(), "swe1racer/roms/selected.z64"); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PodOneLauncherView.enterFullscreen(this);
        launcher = new PodOneLauncherView(this, new Runnable[] {
            this::playOrPickRom,
            () -> startActivity(new Intent(this, PodOneSettingsActivity.class)),
            () -> PodOneUpdates.check(this),
            this::showDiagnostics,
            this::finish
        }, this::pickRom);
        setContentView(launcher);
        launcher.focusFirstAction();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) PodOneLauncherView.enterFullscreen(this);
    }

    private void refresh() {
        launcher.setRomState(rom().isFile(), importingRom);
    }

    private void playOrPickRom() {
        if (importingRom) return;
        if (rom().isFile()) startActivity(new Intent(this, SWE1RacerActivity.class));
        else pickRom();
    }

    private void pickRom() {
        if (importingRom) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_ROM);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROM || result != RESULT_OK || importingRom
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        importingRom = true;
        refresh();
        new Thread(() -> {
            String error = null;
            try {
                importRom(uri);
            } catch (IOException e) {
                error = e.getMessage();
            }
            String failure = error;
            runOnUiThread(() -> {
                importingRom = false;
                if (isFinishing() || isDestroyed()) return;
                refresh();
                launcher.focusFirstAction();
                if (failure != null) {
                    new AlertDialog.Builder(this).setTitle("ROM selection")
                            .setMessage(failure).setPositiveButton("OK", null).show();
                }
            });
        }, "PodOne-RomImport").start();
    }

    private void importRom(Uri uri) throws IOException {
        File destination = rom();
        File directory = destination.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Cannot create the ROM folder.");
        File temporary = File.createTempFile("racer-rom-", ".tmp", directory);
        try {
            String sha1 = copyAsZ64(uri, temporary);
            if (sha1 == null) throw new IOException("Select an N64 ROM in .z64, .v64 or .n64 format.");
            if (!KNOWN_SHA1.equals(sha1))
                throw new IOException("Select a supported Star Wars Episode I: Racer (USA) ROM.\nSHA-1: " + sha1);
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
    }

    private void showDiagnostics() {
        new AlertDialog.Builder(this)
                .setTitle("Pod One Diagnostics")
                .setItems(new String[]{"Share Startup Log", "Stereo Speaker Test"}, (dialog, option) -> {
                    if (option == 0) PodOneDiagnostics.shareStartupLog(this);
                    else PodOneDiagnostics.playSpeakerTest(this, speaker);
                })
                .setNegativeButton("Close", null).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_B || PodOneControls.isControllerBack(event)) return true;
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_BUTTON_Y) pickRom();
                else {
                    View focused = getCurrentFocus();
                    if (focused != null && focused.isClickable()) focused.performClick();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        speaker.stop();
        super.onStop();
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
