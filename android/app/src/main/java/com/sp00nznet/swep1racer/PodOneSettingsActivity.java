package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

import io.github.frostfrontend.FrostButtonMapper;
import io.github.frostfrontend.FrostFrontendView;
import io.github.frostfrontend.FrostSpeakerTest;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Launcher settings: same six-section FrostFrontend before starting the native runtime. */
public final class PodOneSettingsActivity extends Activity {
    private SharedPreferences prefs;
    private FrostFrontendView frontend;
    private final FrostSpeakerTest speaker = new FrostSpeakerTest();
    private boolean mapping;

    private static final String[] ASPECTS = {
        "Original (4:3)", "Expand (16:9)", "Expand (16:10)", "Expand (3:2)", "Expand (21:9)"
    };
    private static final String[] FILTERS = {"Sharp", "Smooth"};
    private static final String[] SCALES = {"1x", "2x", "3x", "Fit"};
    private static final String[] MSAAS = {"Off", "2x", "4x"};
    private static final String[] DOWNSAMPLES = {"Off", "2x", "3x"};
    private static final String[] REFRESH = {"Original", "Display refresh", "Manual (60 Hz)"};
    private static final String[] SIZES = {"Small", "Medium", "Large"};
    private static final int[] OPACITIES = {100,75,50,30};
    private static final String[] IDS = {
        "accelerate", "brake", "slide", "repair", "camera_up", "camera_down", "camera_left", "camera_right"
    };
    private final FrostButtonMapper mapper = new FrostButtonMapper(Arrays.asList(
        new FrostButtonMapper.Action("accelerate", "Accelerate (N64 A)", KeyEvent.KEYCODE_BUTTON_A),
        new FrostButtonMapper.Action("brake", "Brake (N64 B)", KeyEvent.KEYCODE_BUTTON_B),
        new FrostButtonMapper.Action("slide", "Slide (N64 Z)", KeyEvent.KEYCODE_BUTTON_L2),
        new FrostButtonMapper.Action("repair", "Repair (N64 R)", KeyEvent.KEYCODE_BUTTON_R1),
        new FrostButtonMapper.Action("camera_up", "C-Up", KeyEvent.KEYCODE_BUTTON_Y),
        new FrostButtonMapper.Action("camera_down", "C-Down", KeyEvent.KEYCODE_BUTTON_THUMBL),
        new FrostButtonMapper.Action("camera_left", "C-Left", KeyEvent.KEYCODE_BUTTON_X),
        new FrostButtonMapper.Action("camera_right", "C-Right", KeyEvent.KEYCODE_BUTTON_THUMBR)
    ));

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        prefs = getSharedPreferences(PodOneFrontend.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("podone_aspect_migrated", false)) {
            SharedPreferences.Editor e = prefs.edit();
            if (prefs.getInt(PodOneFrontend.ASPECT, 0) == 2) e.putInt(PodOneFrontend.ASPECT, 4);
            e.putBoolean("podone_aspect_migrated", true).apply();
        }
        mapper.load(prefs, "podone_bind_");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1425);
        frontend = new FrostFrontendView(this);
        root.addView(frontend, new FrameLayout.LayoutParams(-1,-1));
        frontend.setTabs(Arrays.asList(
            new FrostFrontendView.Tab("graphics", "Graphics"),
            new FrostFrontendView.Tab("sound", "Sound"),
            new FrostFrontendView.Tab("controls", "Controls"),
            new FrostFrontendView.Tab("touch", "Touch Settings"),
            new FrostFrontendView.Tab("saves", "Save Management"),
            new FrostFrontendView.Tab("updates", "Updates")
        ));
        frontend.setUpdateInfo("Pod One (development)", "Not checked",
                "Checks published GitHub releases, not ROM-derived APK downloads.");
        frontend.setAdapter(new FrostFrontendView.Adapter() {
            @Override public List<FrostFrontendView.Item> itemsForTab(String tab) {
                return items(tab);
            }
            @Override public void onItemSelected(String tab, String id) { adjust(tab,id,1); }
            @Override public void onItemAdjusted(String tab, String id, int direction) {
                if ("graphics".equals(tab) || "touch".equals(tab)) adjust(tab,id,direction);
            }
            @Override public void onVisibilityChanged(boolean visible) {
                if (!visible) finish();
            }
        });
        setContentView(root);
        frontend.open();
    }

    private static FrostFrontendView.Item item(String id, String title, String val, String hint) {
        return new FrostFrontendView.Item(id,title,val,hint);
    }
    private int ix(String key, int def, int count) {
        return Math.max(0,Math.min(count-1,prefs.getInt(key,def)));
    }
    private int cy(String key,int def,int count,int d) {
        return ((ix(key,def,count)+d)%count+count)%count;
    }
    private String onOff(String key, boolean d) { return prefs.getBoolean(key,d) ? "On" : "Off"; }

    private List<FrostFrontendView.Item> items(String tab) {
        switch (tab) {
            case "graphics": return Arrays.asList(
                item("renderer","Renderer","RT64 Vulkan","Android renderer"),
                item("aspect","Game Aspect Ratio",ASPECTS[ix("aspect",0,5)],"True 3D expansion; HUD alignment pending testing"),
                item("resolution","Render Resolution",SCALES[ix("resolution",3,4)],"3D resolution or Fit"),
                item("downsample","Downsampling Quality",DOWNSAMPLES[ix("downsample",0,3)],"RT64 downsampling"),
                item("filter","Filtering",FILTERS[ix("filter",1,2)],"Sharp or smooth picture"),
                item("msaa","MS Anti-Aliasing",MSAAS[ix("msaa",0,3)],"2x/4x experimental on Odin"),
                item("window","Window Mode","Fullscreen","Android immersive fullscreen"),
                item("refresh","Framerate / Refresh",REFRESH[ix("refresh",0,3)],"Refresh output, not game speed"),
                item("fps","FPS Counter",onOff("fps",false),"In-game overlay")
            );
            case "sound": return Arrays.asList(
                item("speaker","Stereo Speaker Test","","Left, right, then both"),
                item("latency","Audio Buffer","Low-latency test","512-sample SDL buffer")
            );
            case "controls":
                if (!mapping) return Arrays.asList(
                    item("mapping","Physical Button Mapping","","Remap controller actions"),
                    item("left_stick","Steering","Left analogue stick","Preserved"),
                    item("right_stick","Camera","Right analogue stick","Preserved"),
                    item("menu_key","Menu Shortcut","Select button","Start remains in-game pause")
                );
                List<FrostFrontendView.Item> rows = new ArrayList<>();
                rows.add(item("map_back","Back to Controls","",""));
                for (FrostButtonMapper.Action a : mapper.actions()) {
                    int code=mapper.buttonFor(a.id);
                    rows.add(new FrostFrontendView.Item("map:"+a.id,a.name,
                        FrostButtonMapper.buttonName(code),"",code == FrostButtonMapper.UNMAPPED));
                }
                rows.add(item("reset_mapping","Restore Default Bindings","",""));
                return rows;
            case "touch": return Arrays.asList(
                item("pad","Show Touch Controls",onOff("pad",true),"Menu icon remains visible"),
                item("boost","Touch BOOST",onOff("boost",true),""),
                item("size","Control Size",SIZES[ix("size",1,3)],""),
                item("opacity","Touch Opacity",OPACITIES[ix("opacity",0,4)]+"%",""),
                item("layout","Move Touch Layout","In game","Drag touch controls and the menu icon during gameplay")
            );
            case "saves": return Collections.singletonList(
                item("manage_saves","Save Management","Launcher","Back out to launcher to import, export or delete safely"));
            case "updates": return Collections.singletonList(
                item("check","Check GitHub Releases","","No game assets are downloaded"));
            default: return Collections.emptyList();
        }
    }

    private void adjust(String tab,String id,int d) {
        if ("sound".equals(tab)) {
            if ("speaker".equals(id)) speaker.start();
            return;
        }
        if ("updates".equals(tab)) { if ("check".equals(id)) PodOneUpdates.check(this,frontend); return; }
        if ("saves".equals(tab)) {
            Toast.makeText(this,"Close settings and choose Save Management in the launcher",Toast.LENGTH_LONG).show();
            return;
        }
        if ("controls".equals(tab)) {
            if ("mapping".equals(id)) {
                mapping=true;
                frontend.enterButtonMapping(mapper,()->mapping=false);
            } else if ("map_back".equals(id)) {
                frontend.requestMappingBack();
            } else if ("reset_mapping".equals(id)) {
                mapper.resetDefaults(); mapper.save(prefs,"podone_bind_");
            } else if (id.startsWith("map:")) {
                String action=id.substring(4);
                frontend.beginButtonCapture(action,new FrostFrontendView.ButtonCaptureListener() {
                    @Override public void onCaptured(int key) {
                        FrostButtonMapper.Change change=mapper.assign(action,key);
                        mapper.save(prefs,"podone_bind_");
                        if(change.unmappedAction!=null)
                            frontend.showMappingNotice(change.unmappedAction+" was unassigned");
                    }
                    @Override public void onCancelled() {}
                });
            }
            return;
        }
        SharedPreferences.Editor e=prefs.edit();
        switch(id) {
            case "aspect": e.putInt("aspect",cy("aspect",0,5,d)); break;
            case "resolution": e.putInt("resolution",cy("resolution",3,4,d)); break;
            case "downsample": e.putInt("downsample",cy("downsample",0,3,d)); break;
            case "filter": e.putInt("filter",cy("filter",1,2,d)); break;
            case "msaa": e.putInt("msaa",cy("msaa",0,3,d)); break;
            case "refresh": e.putInt("refresh",cy("refresh",0,3,d)); break;
            case "fps": e.putBoolean("fps",!prefs.getBoolean("fps",false)); break;
            case "pad": e.putBoolean("pad",!prefs.getBoolean("pad",true)); break;
            case "boost": e.putBoolean("boost",!prefs.getBoolean("boost",true)); break;
            case "size": e.putInt("size",cy("size",1,3,d)); break;
            case "opacity": e.putInt("opacity",cy("opacity",0,4,d)); break;
            default: return;
        }
        e.apply();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if(frontend!=null&&frontend.isOpen()) return frontend.handleKeyEvent(e);
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if(frontend!=null&&frontend.handleGenericMotionEvent(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }
    @Override public void onBackPressed() {
        if(frontend!=null&&frontend.isOpen()) frontend.back();
        else finish();
    }
    @Override protected void onDestroy() {
        speaker.stop();
        super.onDestroy();
    }
}
