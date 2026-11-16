package com.sp00nznet.swep1racer;

import android.view.InputDevice;
import android.view.KeyEvent;

import io.github.frostfrontend.FrostButtonMapper;
import io.github.frostfrontend.FrostFrontendView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PodOneControls {
    private static final int START_SCAN_CODE = 315;
    // Native get_input() consumes these keys in this order. The IDs also back saved mappings.
    private static final List<FrostButtonMapper.Action> ACTIONS = Arrays.asList(
        new FrostButtonMapper.Action("accelerate", "Accelerate (N64 A)", KeyEvent.KEYCODE_BUTTON_A),
        new FrostButtonMapper.Action("brake", "Brake (N64 B)", KeyEvent.KEYCODE_BUTTON_B),
        new FrostButtonMapper.Action("slide", "Drift (N64 Z)", KeyEvent.KEYCODE_BUTTON_L2),
        new FrostButtonMapper.Action("repair", "Repair (N64 R)", KeyEvent.KEYCODE_BUTTON_R1),
        new FrostButtonMapper.Action("camera_up", "C-Up (Change Camera)", KeyEvent.KEYCODE_BUTTON_Y),
        new FrostButtonMapper.Action("camera_down", "C-Down (Look Back)", KeyEvent.KEYCODE_BUTTON_THUMBL),
        new FrostButtonMapper.Action("camera_left", "C-Left (Roll Left)", KeyEvent.KEYCODE_BUTTON_X),
        new FrostButtonMapper.Action("camera_right", "C-Right (Roll Right)", KeyEvent.KEYCODE_BUTTON_THUMBR),
        new FrostButtonMapper.Action("cycle_map", "Cycle Map Display (N64 L)", KeyEvent.KEYCODE_BUTTON_L1)
    );
    private static final String[] DESCRIPTIONS = {
        "Apply thrust to accelerate the podracer.",
        "Slow the podracer.",
        "Hold while steering to slide through a turn.",
        "Hold to repair engine damage.",
        "Cycle through the race camera views.",
        "Look behind your podracer during a race.",
        "Roll the podracer to the left to fit through narrow gaps.",
        "Roll the podracer to the right to fit through narrow gaps.",
        "Cycle the race map and progress displays."
    };

    private PodOneControls() {}

    static FrostButtonMapper newMapper() {
        return new FrostButtonMapper(ACTIONS);
    }

    static int[] nativeKeys(FrostButtonMapper mapper) {
        int[] keys = new int[ACTIONS.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = mapper.buttonFor(ACTIONS.get(i).id);
        return keys;
    }

    static List<FrostFrontendView.Item> mappingRows(FrostButtonMapper mapper) {
        List<FrostFrontendView.Item> rows = new ArrayList<>();
        rows.add(new FrostFrontendView.Item("map_back", "Back to Controls", "", ""));
        for (int i = 0; i < ACTIONS.size(); i++) {
            FrostButtonMapper.Action action = ACTIONS.get(i);
            int code = mapper.buttonFor(action.id);
            rows.add(new FrostFrontendView.Item("map:" + action.id, action.name,
                    FrostButtonMapper.buttonName(code), DESCRIPTIONS[i],
                    code == FrostButtonMapper.UNMAPPED));
        }
        rows.add(new FrostFrontendView.Item("reset_mapping", "Restore Default Bindings", "",
                "Restore the original gamepad assignments."));
        return rows;
    }

    static boolean isControllerBack(KeyEvent event) {
        int keyCode = event.getKeyCode();
        return (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && isController(event);
    }

    static KeyEvent normalizeStart(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_START) return event;
        if (event.getScanCode() != START_SCAN_CODE && !(code == KeyEvent.KEYCODE_MENU && isController(event))) return event;
        return new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                KeyEvent.KEYCODE_BUTTON_START, event.getRepeatCount(), event.getMetaState(),
                event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource());
    }

    private static boolean isController(KeyEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK))
            return true;
        InputDevice device = event.getDevice();
        return device != null && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }
}
