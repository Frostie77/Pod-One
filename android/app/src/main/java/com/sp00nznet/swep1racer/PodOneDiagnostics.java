package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A no-root diagnostic share action. Android permits apps to read logcat records
 * for their own UID; on restrictive devices only the device/build summary is
 * available. Never requests READ_LOGS or collects other apps' logs.
 */
final class PodOneDiagnostics {
    private PodOneDiagnostics() {}
    private static final int MAX_LOG_CHARS = 90000;

    static void shareStartupLog(Activity activity) {
        Toast.makeText(activity, "Preparing Pod One startup log…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String report = collect(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, "Pod One startup diagnostics");
                share.putExtra(Intent.EXTRA_TEXT, report);
                try {
                    activity.startActivity(Intent.createChooser(share, "Share Pod One log"));
                } catch (Exception e) {
                    Toast.makeText(activity, "No available app to share the log", Toast.LENGTH_LONG).show();
                }
            });
        }, "PodOne-DiagnosticShare").start();
    }

    static void openDriverPreferences(Activity activity) {
        // RT64 uses Android's Vulkan loader. System graphics-driver selection is
        // configured outside our process, in Android Developer Options.
        // Per-app custom Turnip drivers require separately integrated AdrenoTools
        // before Vulkan is initialized, and are NOT installed by this shortcut.
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                Toast.makeText(activity, "Open Developer options → Graphics driver preferences",
                        Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
                Toast.makeText(activity, "Android settings unavailable", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String collect(Activity activity) {
        StringBuilder out = new StringBuilder();
        out.append("POD ONE — ANDROID STARTUP DIAGNOSTICS\n")
           .append("Captured: ")
           .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
           .append("\n");
        try {
            out.append("Version: ")
               .append(activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName)
               .append("\n");
        } catch (Exception ignored) {}
        out.append("Package: ").append(activity.getPackageName()).append("\n")
           .append("Android: ").append(Build.VERSION.RELEASE)
           .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
           .append("Device: ").append(Build.MANUFACTURER).append(' ')
           .append(Build.MODEL).append("\n")
           .append("ABI: ").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS))
           .append("\n")
           .append("Renderer: RT64 / Vulkan (system selected driver)\n")
           .append("Logcat below contains only records accessible to this app.\n")
           .append("Review before sharing: logs can contain file paths or device identifiers.\n\n");
        Process proc = null;
        try {
            proc = new ProcessBuilder("logcat", "-d", "-t", "1000", "-v", "threadtime")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < MAX_LOG_CHARS) {
                    out.append(line).append('\n');
                }
            }
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception e) {
            out.append("Logcat unavailable: ").append(e.getClass().getSimpleName()).append('\n')
               .append("For a full native log, connect ADB and capture logcat on a PC.\n");
        } finally {
            if (proc != null) proc.destroy();
        }
        return out.toString();
    }
}
