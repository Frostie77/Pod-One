package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Save tools used from standalone settings, when the game is closed. */
final class PodOneSaveManager {
    static final int EXPORT_SAVE = 2;
    static final int IMPORT_SAVE = 3;
    private final Activity activity;

    PodOneSaveManager(Activity activity) {
        this.activity = activity;
    }

    private File save() {
        return new File(activity.getFilesDir(), "swe1racer/saves/swe1racer.bin");
    }

    void show() {
        final String[] options = {"Export / Backup Save", "Import / Restore Save", "Delete Save"};
        new AlertDialog.Builder(activity).setTitle("Pod One · Save Management")
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    if (!save().isFile()) {
                        message("No Racer save exists yet.");
                        return;
                    }
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/octet-stream");
                    i.putExtra(Intent.EXTRA_TITLE, "pod-one-save.bin");
                    activity.startActivityForResult(i, EXPORT_SAVE);
                } else if (which == 1) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    activity.startActivityForResult(i, IMPORT_SAVE);
                } else {
                    new AlertDialog.Builder(activity).setTitle("Delete Racer Save?")
                        .setMessage("This cannot be undone. Export a backup first.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (dialog, button) -> {
                            message(save().delete() ? "Save deleted." : "No save deleted.");
                        }).show();
                }
            }).setNegativeButton("Close", null).show();
    }

    void transfer(int request, Uri uri) {
        try {
            if (request == EXPORT_SAVE) {
                if (!save().isFile()) throw new IOException("No save found");
                try (InputStream in = new FileInputStream(save());
                     OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("Cannot open destination");
                    copy(in, out, 8 * 1024 * 1024);
                }
                message("Save exported successfully.");
            } else {
                File target = save();
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                    throw new IOException("Cannot create save folder");
                File temp = File.createTempFile("racer-save-", ".import", target.getParentFile());
                try {
                    try (InputStream in = activity.getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(temp)) {
                        if (in == null) throw new IOException("Cannot open imported save");
                        copy(in, out, 8 * 1024 * 1024);
                    }
                    if (temp.length() == 0) throw new IOException("Imported file is empty");
                } catch (IOException e) {
                    temp.delete();
                    throw e;
                }
                new AlertDialog.Builder(activity).setTitle("Replace Racer Save?")
                    .setMessage("The previous save will be kept as a local .bak file.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Restore", (dialog, button) -> restore(temp, target))
                    .setOnDismissListener(dialog -> temp.delete()).show();
            }
        } catch (IOException e) {
            message("Save operation failed: " + e.getMessage());
        }
    }

    private void restore(File temporary, File target) {
        try {
            if (target.isFile()) {
                File backup = new File(target.getAbsolutePath() + "." + System.currentTimeMillis() + ".bak");
                Files.copy(target.toPath(), backup.toPath());
            }
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            message("Save imported.");
        } catch (IOException error) {
            message("Could not restore the save: " + error.getMessage());
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
        new AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK", null).show();
    }
}
