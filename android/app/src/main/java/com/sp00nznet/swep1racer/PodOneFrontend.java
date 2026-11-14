package com.sp00nznet.swep1racer;

import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import io.github.frostfrontend.FrostButtonMapper;
import io.github.frostfrontend.FrostFrontendView;
import io.github.frostfrontend.FrostSpeakerTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Game-facing settings adapter. The entire visible menu uses the FrostFrontend library. */
final class PodOneFrontend {
    static final String PREFS = "racer";
    static final String ASPECT = "aspect", FILTER = "filter", RESOLUTION = "resolution", FPS = "fps";
    static final String MSAA = "msaa", DOWNSAMPLE = "downsample", REFRESH = "refresh";
    static final String PAD = "pad", BOOST = "boost", OPACITY = "opacity", SIZE = "size";
    static final int DEF_ASPECT = 0, DEF_FILTER = 1, DEF_RESOLUTION = 3, DEF_OPACITY = 0, DEF_SIZE = 1;
    static final int[] OPACITIES = {100, 75, 50, 30};
    static final float[] SIZES = {0.85f, 1.0f, 1.2f};

    private static final String[] ASPECT_NAMES = {
        "Original (4:3)", "Expand (16:9)", "Expand (16:10)", "Expand (3:2)", "Expand (21:9)", "Expand to Screen"
    };
    private static final String[] FILTER_NAMES = {"Sharp", "Smooth"};
    private static final String[] SCALE_NAMES = {"1x", "2x", "3x", "Fit"};
    private static final String[] MSAA_NAMES = {"Off", "2x", "4x"};
    private static final String[] DOWNSAMPLE_NAMES = {"Off", "2x", "3x"};
    private static final String[] REFRESH_NAMES = {"Original", "Display refresh", "Manual (60 Hz)"};
    private static final String[] SIZE_NAMES = {"Small", "Medium", "Large"};

    private static final String[] ACTION_IDS = {
        "accelerate", "brake", "slide", "repair", "camera_up", "camera_down", "camera_left", "camera_right"
    };
    private static final List<FrostButtonMapper.Action> ACTIONS = Arrays.asList(
        new FrostButtonMapper.Action("accelerate", "Accelerate (N64 A)", KeyEvent.KEYCODE_BUTTON_A),
        new FrostButtonMapper.Action("brake", "Brake (N64 B)", KeyEvent.KEYCODE_BUTTON_B),
        new FrostButtonMapper.Action("slide", "Slide (N64 Z)", KeyEvent.KEYCODE_BUTTON_L2),
        new FrostButtonMapper.Action("repair", "Repair (N64 R)", KeyEvent.KEYCODE_BUTTON_R1),
        new FrostButtonMapper.Action("camera_up", "Camera / C-Up", KeyEvent.KEYCODE_BUTTON_Y),
        new FrostButtonMapper.Action("camera_down", "Camera / C-Down", KeyEvent.KEYCODE_BUTTON_THUMBL),
        new FrostButtonMapper.Action("camera_left", "Camera / C-Left", KeyEvent.KEYCODE_BUTTON_X),
        new FrostButtonMapper.Action("camera_right", "Camera / C-Right", KeyEvent.KEYCODE_BUTTON_THUMBR)
    );

    private final SWE1RacerActivity activity;
    private final TouchPadView pad;
    private final SharedPreferences prefs;
    private final FrostFrontendView frontend;
    private final FrostSpeakerTest speaker = new FrostSpeakerTest();
    private final FrostButtonMapper mapper = new FrostButtonMapper(ACTIONS);
    private boolean editingMappings;

    PodOneFrontend(SWE1RacerActivity activity, TouchPadView pad, SharedPreferences prefs) {
        this.activity = activity;
        this.pad = pad;
        this.prefs = prefs;
        // Previous Racer used aspect index 2 for 21:9. Convert once to new five-item ordering.
        if (!prefs.getBoolean("podone_aspect_migrated", false)) {
            SharedPreferences.Editor e = prefs.edit();
            if (prefs.getInt(ASPECT, 0) == 2) e.putInt(ASPECT, 4);
            e.putBoolean("podone_aspect_migrated", true).apply();
        }
        mapper.load(prefs, "podone_bind_");
        applyMappings();

        frontend = new FrostFrontendView(activity);
        frontend.setTabs(Arrays.asList(
            new FrostFrontendView.Tab("graphics", "Graphics"),
            new FrostFrontendView.Tab("sound", "Sound"),
            new FrostFrontendView.Tab("controls", "Controls"),
            new FrostFrontendView.Tab("touch", "Touch Settings"),
            new FrostFrontendView.Tab("saves", "Save Management"),
            new FrostFrontendView.Tab("updates", "Updates"),
            new FrostFrontendView.Tab("diagnostics", "Diagnostics"),
            new FrostFrontendView.Tab("about", "About")
        ));
        frontend.setUpdateInfo("Pod One (development)", "Not checked",
                "Checks official Pod One GitHub releases; game files are never downloaded.");
        frontend.setAdapter(new FrostFrontendView.Adapter() {
            @Override public List<FrostFrontendView.Item> itemsForTab(String tab) { return items(tab); }
            @Override public void onItemSelected(String tab, String id) { change(tab, id, 1); }
            @Override public void onItemAdjusted(String tab, String id, int direction) {
                if (("graphics".equals(tab) && !id.equals("driver") && !id.equals("share_log")) || ("touch".equals(tab) && !"edit".equals(id))) {
                    change(tab, id, direction);
                }
            }
            @Override public void onVisibilityChanged(boolean visible) {
                pad.releaseAll();
                try { SWE1RacerActivity.nativeSetFrontendOpen(visible); }
                catch (UnsatisfiedLinkError ignored) { }
                if (!visible) speaker.stop();
            }
        });
    }

    FrostFrontendView view() { return frontend; }
    boolean isOpen() { return frontend.isOpen(); }
    void open() { pad.releaseAll(); frontend.open(); }
    void back() { frontend.back(); }
    boolean handleKeyEvent(KeyEvent e) { return frontend.handleKeyEvent(e); }
    boolean handleGenericMotionEvent(MotionEvent e) { return frontend.handleGenericMotionEvent(e); }
    void destroy() { speaker.stop(); }
    void reapplyMappings() { applyMappings(); }

    private void applyMappings() {
        int[] keys = new int[ACTION_IDS.length];
        for (int i = 0; i < keys.length; i++) keys[i] = mapper.buttonFor(ACTION_IDS[i]);
        try { SWE1RacerActivity.nativeSetGamepadKeys(keys); }
        catch (UnsatisfiedLinkError ignored) { }
    }

    private int index(String key, int fallback, int count) {
        return Math.max(0, Math.min(count - 1, prefs.getInt(key, fallback)));
    }
    private int cycle(String key, int fallback, int count, int delta) {
        return ((index(key, fallback, count) + delta) % count + count) % count;
    }
    private String enabled(String key, boolean fallback) { return prefs.getBoolean(key, fallback) ? "On" : "Off"; }
    private static FrostFrontendView.Item item(String id, String title, String value, String hint) {
        return new FrostFrontendView.Item(id, title, value, hint);
    }

    private List<FrostFrontendView.Item> items(String tab) {
        switch (tab) {
            case "graphics":
                return Arrays.asList(
                    item("resolution", "Resolution Scale", SCALE_NAMES[index(RESOLUTION, DEF_RESOLUTION, 4)],
                         "RT64 internal resolution; Fit uses integer scaling"),
                    item("downsample", "Downsampling", DOWNSAMPLE_NAMES[index(DOWNSAMPLE, 0, 3)],
                         "RT64 downsample quality"),
                    item("aspect", "Aspect Ratio", ASPECT_NAMES[index(ASPECT, DEF_ASPECT, 6)],
                         "Original, fixed ratios, or Expand to Screen without stretching"),
                    item("window", "Display Mode", "Fullscreen",
                         "The Android Vulkan build uses immersive fullscreen"),
                    item("refresh", "Framerate / Refresh", REFRESH_NAMES[index(REFRESH, 0, 3)],
                         "Select display refresh; the game's simulation speed is unchanged"),
                    item("msaa", "MSAA", MSAA_NAMES[index(MSAA, 0, 3)],
                         "Off, 2x or 4x anti-aliasing; requires Odin compatibility testing"),
                    item("filter", "Texture Filtering", FILTER_NAMES[index(FILTER, DEF_FILTER, 2)],
                         "Sharp or smooth output filtering"),
                    item("driver", "Graphics Driver", "System default",
                         "Opens Android's per-app driver settings; custom drivers are not installed"),
                    item("share_log", "Share Startup Log", "",
                         "Share available app logs and Android device information"),
                    item("fps", "Show FPS Counter", enabled(FPS, false),
                         "Show gameplay frames per second")
                );
            case "sound":
                return Arrays.asList(
                    item("speaker", "Speaker Test", "", "Play left, right and both channels"),
                    item("latency", "Audio Buffer", "Low-latency experimental",
                         "512-sample SDL buffer; listen for crackle during tests")
                );
            case "controls":
                if (!editingMappings) return Arrays.asList(
                    item("mapping", "Physical Button Mapping", "", "Remap gamepad buttons and triggers"),
                    item("analog", "Steering", "Left analogue stick", "Original analogue steering is unchanged"),
                    item("camera", "Camera", "Right analogue stick", "C-direction input is retained"),
                    item("menu_key", "Open Menu", "Select or touchscreen icon", "Start remains the game's pause button")
                );
                List<FrostFrontendView.Item> rows = new ArrayList<>();
                rows.add(item("map_back", "Back to Controls", "", ""));
                for (FrostButtonMapper.Action a : mapper.actions()) {
                    final int code = mapper.buttonFor(a.id);
                    rows.add(new FrostFrontendView.Item("map:" + a.id, a.name,
                            FrostButtonMapper.buttonName(code), "",
                            code == FrostButtonMapper.UNMAPPED));
                }
                rows.add(item("reset_mapping", "Restore Default Bindings", "", ""));
                return rows;
            case "touch":
                return Arrays.asList(
                    item("pad", "Show Touch Controls", enabled(PAD, true), "Menu icon remains visible"),
                    item("boost", "BOOST Button", enabled(BOOST, true), ""),
                    item("size", "Control Size", SIZE_NAMES[index(SIZE, DEF_SIZE, 3)], ""),
                    item("opacity", "Control Opacity", OPACITIES[index(OPACITY, DEF_OPACITY, 4)] + "%", ""),
                    item("edit", "Move and Resize Controls", "", "Includes the menu icon; save to persist")
                );
            case "saves":
                return Collections.singletonList(item("save_manager", "Manage Racer Saves", "",
                       "Export, import or delete with confirmation (return to launcher for changes)"));
            case "updates":
                return Collections.singletonList(item("check", "Check for Updates", "",
                       "Query Pod One's published GitHub releases, if available"));
            case "diagnostics":
                return Arrays.asList(
                    item("share_log", "Share Startup Log", "",
                         "Share available app logcat records and device summary"),
                    item("driver", "Android Driver Preferences", "",
                         "Open supported Android per-app driver settings")
                );
            case "about":
                return Collections.singletonList(item("credits", "About Pod One", "",
                       "Android fan-made port; source attribution and licence information"));
            default: return Collections.emptyList();
        }
    }

    private void change(String tab, String id, int delta) {
        if (("graphics".equals(tab) || "diagnostics".equals(tab)) && "driver".equals(id)) {
            PodOneDiagnostics.openDriverPreferences(activity);
            return;
        }
        if (("graphics".equals(tab) || "diagnostics".equals(tab)) && "share_log".equals(id)) {
            PodOneDiagnostics.shareStartupLog(activity);
            return;
        }
        if ("about".equals(tab) && "credits".equals(id)) {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("About Pod One")
                    .setMessage("Native Android port of Star Wars Episode I: Racer. Built on sp00nznet/racer and dguillot-gh/star-wars-racer-recomp; frontend by Frostie77. Game and artwork rights remain with their owners.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if ("sound".equals(tab)) {
            if ("speaker".equals(id) && speaker.start())
                Toast.makeText(activity, "Speaker test: left, right, both", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("saves".equals(tab)) {
            if ("save_manager".equals(id)) activity.showSaveManagerInfo();
            return;
        }
        if ("updates".equals(tab)) {
            if ("check".equals(id))
                PodOneUpdates.check(activity, frontend);
            return;
        }
        if ("controls".equals(tab)) {
            if ("mapping".equals(id)) {
                editingMappings = true;
                frontend.enterButtonMapping(mapper, () -> editingMappings = false);
            } else if ("map_back".equals(id)) {
                frontend.requestMappingBack();
            } else if ("reset_mapping".equals(id)) {
                mapper.resetDefaults();
                mapper.save(prefs, "podone_bind_");
                applyMappings();
            } else if (id.startsWith("map:")) {
                final String action = id.substring(4);
                frontend.beginButtonCapture(action, new FrostFrontendView.ButtonCaptureListener() {
                    @Override public void onCaptured(int code) {
                        FrostButtonMapper.Change result = mapper.assign(action, code);
                        mapper.save(prefs, "podone_bind_");
                        applyMappings();
                        if (result.unmappedAction != null)
                            frontend.showMappingNotice(result.unmappedAction + " was unassigned");
                    }
                    @Override public void onCancelled() {}
                });
            }
            return;
        }
        if ("touch".equals(tab) && "edit".equals(id)) {
            frontend.close();
            pad.startEditing();
            return;
        }
        final SharedPreferences.Editor e = prefs.edit();
        switch (id) {
            case "aspect": e.putInt(ASPECT, cycle(ASPECT, DEF_ASPECT, 6, delta)); break;
            case "resolution": e.putInt(RESOLUTION, cycle(RESOLUTION, DEF_RESOLUTION, 4, delta)); break;
            case "filter": e.putInt(FILTER, cycle(FILTER, DEF_FILTER, 2, delta)); break;
            case "msaa": e.putInt(MSAA, cycle(MSAA, 0, 3, delta)); break;
            case "downsample": e.putInt(DOWNSAMPLE, cycle(DOWNSAMPLE, 0, 3, delta)); break;
            case "refresh": e.putInt(REFRESH, cycle(REFRESH, 0, 3, delta)); break;
            case "fps":
                boolean shown = !prefs.getBoolean(FPS, false);
                e.putBoolean(FPS, shown).apply();
                activity.setFpsShown(shown);
                return;
            case "pad":
                boolean visible = !prefs.getBoolean(PAD, true);
                e.putBoolean(PAD, visible).apply();
                pad.setPadVisible(visible);
                return;
            case "boost":
                boolean boosted = !prefs.getBoolean(BOOST, true);
                e.putBoolean(BOOST, boosted).apply();
                pad.setBoostEnabled(boosted);
                return;
            case "size":
                int size = cycle(SIZE, DEF_SIZE, 3, delta);
                e.putInt(SIZE, size).apply();
                pad.setSizeScale(SIZES[size]);
                return;
            case "opacity":
                int opacity = cycle(OPACITY, DEF_OPACITY, 4, delta);
                e.putInt(OPACITY, opacity).apply();
                pad.setOpacity(OPACITIES[opacity] / 100f);
                return;
            default: return;
        }
        e.apply();
        activity.applyVideo();
    }
}
