package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports emulator-style Adreno packages into Pod One's internal files directory. */
final class PodOneDrivers {
    private static final int PICK_DRIVER = 41;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_METADATA = 128 * 1024;
    private final Activity activity;
    private final boolean inGame;
    private final Runnable refresh;
    private final File directory;
    private final File selection;
    private boolean importing;

    PodOneDrivers(Activity activity, boolean inGame, Runnable refresh) {
        this.activity = activity;
        this.inGame = inGame;
        this.refresh = refresh;
        directory = new File(activity.getFilesDir(), "swe1racer/drivers");
        selection = new File(directory, "selected");
    }

    String selectedName() {
        try {
            String[] lines = new String(Files.readAllBytes(selection.toPath()), StandardCharsets.UTF_8).split("\\n", 3);
            if (lines.length == 3 && !lines[2].trim().isEmpty()) return lines[2].trim();
        } catch (IOException ignored) {}
        return "System default";
    }

    void show() {
        if (importing) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            message("Pod One uses the system Vulkan driver on this device. Custom Adreno drivers require Android 9 or later.");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Graphics Driver")
                .setMessage("Selected: " + selectedName() + "\n\nA driver change takes effect the next time you launch the game.")
                .setPositiveButton("Import Driver ZIP", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                    activity.startActivityForResult(intent, PICK_DRIVER);
                })
                .setNeutralButton("Use System Driver", (dialog, which) -> {
                    try {
                        Files.deleteIfExists(selection.toPath());
                        changed("System driver selected.");
                    } catch (IOException error) {
                        message("Could not select the system driver. " + error.getMessage());
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_DRIVER) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || importing) return true;
        importing = true;
        Uri uri = data.getData();
        Toast.makeText(activity, "Checking driver package…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String name = install(uri);
                activity.runOnUiThread(() -> {
                    importing = false;
                    if (!activity.isFinishing() && !activity.isDestroyed()) changed("Selected " + name + ".");
                });
            } catch (IOException | JSONException | SecurityException error) {
                activity.runOnUiThread(() -> {
                    importing = false;
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        message("Could not import the driver ZIP.\n" + error.getMessage());
                });
            }
        }, "PodOne-DriverImport").start();
        return true;
    }

    private String install(Uri uri) throws IOException, JSONException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Cannot create the driver folder.");
        File packageDir = Files.createTempDirectory(directory.toPath(), "driver-").toFile();
        boolean installed = false;
        try {
            extract(uri, packageDir);
            File metadataFile = new File(packageDir, "meta.json");
            if (!metadataFile.isFile()) throw new IOException("meta.json is missing from the ZIP root.");
            JSONObject meta = new JSONObject(
                    new String(Files.readAllBytes(metadataFile.toPath()), StandardCharsets.UTF_8));
            String library = meta.getString("libraryName");
            if (!library.matches("[A-Za-z0-9._+-]+\\.so") || !isArm64Library(new File(packageDir, library)))
                throw new IOException("The named driver must be an Arm64 Vulkan .so at the ZIP root.");
            int minApi = meta.optInt("minApi", Build.VERSION_CODES.P);
            if (minApi > Build.VERSION.SDK_INT) throw new IOException("The driver needs Android API " + minApi + ".");
            String name = meta.optString("name", library).replaceAll("[\\p{Cc}]+", " ").trim();
            if (name.isEmpty()) name = library;
            if (name.length() > 120) name = name.substring(0, 120);
            File pending = File.createTempFile("driver-selection-", ".tmp", directory);
            try {
                Files.write(pending.toPath(),
                        (packageDir.getName() + "\n" + library + "\n" + name).getBytes(StandardCharsets.UTF_8));
                Files.move(pending.toPath(), selection.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                pending.delete();
            }
            installed = true;
            return name;
        } finally {
            if (!installed) {
                File[] files = packageDir.listFiles();
                if (files != null) for (File file : files) file.delete();
                packageDir.delete();
            }
        }
    }

    private void extract(Uri uri, File packageDir) throws IOException {
        InputStream content = activity.getContentResolver().openInputStream(uri);
        if (content == null) throw new IOException("Cannot open the selected file.");
        try (ZipInputStream zip = new ZipInputStream(content)) {
            Set<String> names = new HashSet<>();
            byte[] buffer = new byte[32768];
            long total = 0;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (!names.add(name) || names.size() > 64)
                    throw new IOException("The ZIP contains duplicate entries or too many files.");
                boolean metadata = name.equals("meta.json");
                // Only flat, known resource names are written to disk.
                boolean binary = name.matches("[A-Za-z0-9._+-]{1,120}\\.so");
                try (FileOutputStream out = !entry.isDirectory() && (metadata || binary)
                        ? new FileOutputStream(new File(packageDir, name)) : null) {
                    int count, metadataBytes = 0;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("The extracted driver is too large.");
                        if (metadata && (metadataBytes += count) > MAX_METADATA)
                            throw new IOException("Driver metadata is too large.");
                        if (out != null) out.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static boolean isArm64Library(File library) throws IOException {
        if (!library.isFile()) return false;
        byte[] bytes = new byte[20];
        try (InputStream in = new FileInputStream(library)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) return false;
                offset += count;
            }
        }
        ByteBuffer elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F'
                && bytes[4] == 2 && bytes[5] == 1 && elf.getShort(16) == 3 && elf.getShort(18) == 183;
    }

    private void changed(String text) {
        refresh.run();
        if (!inGame) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Graphics Driver")
                .setMessage(text + " The driver will change after restarting the game.")
                .setPositiveButton("Back to Launcher", (dialog, which) -> activity.finish())
                .setNegativeButton("Keep Playing", null).show();
    }

    private void message(String text) {
        new AlertDialog.Builder(activity).setTitle("Graphics Driver").setMessage(text)
                .setPositiveButton("OK", null).show();
    }
}
