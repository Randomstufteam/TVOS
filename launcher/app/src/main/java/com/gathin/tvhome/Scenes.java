package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import java.util.Arrays;
import java.util.List;

/**
 * The screensaver scenes: the existing clock (ClockDream) and the drawn scenes in SceneView (SceneDream). The choice
 * is the active profile's; "Use as the TV's screensaver" writes Android's screensaver setting, which needs
 * WRITE_SECURE_SETTINGS (granted once over adb: pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS).
 */
final class Scenes {
    static final String CLOCK = "clock";
    static final String AURORA = "aurora";
    static final String WARP = "warp";
    static final String NEBULA = "nebula";
    static final List<String> ALL = Arrays.asList(AURORA, WARP, NEBULA, CLOCK);

    private Scenes() {
    }

    static String chosen(Context ctx) {
        String s = Widgets.prefs(ctx).getString("scene", AURORA);
        return ALL.contains(s) ? s : AURORA;
    }

    static void choose(Context ctx, String scene) {
        Widgets.prefs(ctx).edit().putString("scene", scene).apply();
        // Already Android's screensaver: switch between the clock and the drawn scenes to match.
        if (isSystemScreensaver(ctx)) makeSystemScreensaver(ctx);
    }

    static int nameOf(String scene) {
        switch (scene) {
            case WARP: return R.string.scene_warp;
            case NEBULA: return R.string.scene_nebula;
            case CLOCK: return R.string.scene_clock;
            default: return R.string.scene_aurora;
        }
    }

    private static ComponentName component(Context ctx) {
        return new ComponentName(ctx, CLOCK.equals(chosen(ctx)) ? ClockDream.class : SceneDream.class);
    }

    static boolean isSystemScreensaver(Context ctx) {
        String v = Settings.Secure.getString(ctx.getContentResolver(), "screensaver_components");
        return v != null && v.startsWith(ctx.getPackageName() + "/");
    }

    /** Makes the chosen scene Android's screensaver. False when the permission was never granted. */
    static boolean makeSystemScreensaver(Context ctx) {
        try {
            Settings.Secure.putString(ctx.getContentResolver(), "screensaver_components", component(ctx).flattenToShortString());
            Settings.Secure.putInt(ctx.getContentResolver(), "screensaver_enabled", 1);
            return true;
        } catch (SecurityException notGranted) {
            return false;
        }
    }
}
