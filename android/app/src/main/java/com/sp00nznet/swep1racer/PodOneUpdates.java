package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import io.github.frostfrontend.FrostFrontendView;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** Public GitHub release checker. Does not download ROMs or recompiled game code. */
final class PodOneUpdates {
    private static final String TAG = "PodOneUpdates";
    private static final String ENDPOINT =
            "https://api.github.com/repos/Frostie77/Pod-One/releases/latest";

    private PodOneUpdates() {}

    /** Launcher shortcut: show result as a toast when no settings panel is visible. */
    static void check(Activity activity) {
        check(activity, null);
    }

    static void check(Activity activity, FrostFrontendView frontend) {
        if (frontend != null) frontend.setUpdateInfo(installed(activity), "Checking", "Contacting GitHub...");
        new Thread(() -> {
            String latest = "Unavailable", status;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Pod-One-Android");
                int code = connection.getResponseCode();
                if (code == 404) {
                    latest = "No release yet";
                    status = "No public Pod One release has been published.";
                } else if (code != 200) {
                    status = "GitHub returned HTTP " + code + ". Try again later.";
                } else {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream stream = connection.getInputStream()) {
                        byte[] buffer = new byte[4096]; int n;
                        while ((n = stream.read(buffer)) > 0) {
                            if (bytes.size() + n > 65536) throw new IllegalStateException("Response too large");
                            bytes.write(buffer, 0, n);
                        }
                    }
                    JSONObject release = new JSONObject(bytes.toString("UTF-8"));
                    latest = release.optString("tag_name", "Unknown");
                    status = "Latest published source release: " + latest
                            + ". Game code is built locally from your ROM.";
                }
            } catch (Exception e) {
                Log.w(TAG, "Release check failed", e);
                status = "Could not check updates: " + e.getClass().getSimpleName();
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String result = latest, info = status;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (frontend != null) frontend.setUpdateInfo(installed(activity), result, info);
                Toast.makeText(activity, info, Toast.LENGTH_LONG).show();
            });
        }, "PodOne-UpdateCheck").start();
    }

    private static String installed(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "Development";
        }
    }
}
