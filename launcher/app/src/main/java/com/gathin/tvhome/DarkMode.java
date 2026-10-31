package com.gathin.tvhome;

import android.content.Context;

import java.time.LocalTime;

/**
 * Dark mode, per profile: Home without colour light (no ambient glow, no app-colour focus glow, no artwork behind the
 * shelf), pure black behind everything and any wallpaper dimmed. Auto turns it on from 20:00 to 07:00.
 */
final class DarkMode {
    static final String OFF = "off";
    static final String ON = "on";
    static final String AUTO = "auto";

    private DarkMode() {
    }

    static String mode(Context ctx) {
        String m = Widgets.prefs(ctx).getString("dark_mode", OFF);
        return ON.equals(m) || AUTO.equals(m) ? m : OFF;
    }

    /** Off, On, Auto, Off... */
    static void cycle(Context ctx) {
        String m = mode(ctx);
        String next = OFF.equals(m) ? ON : ON.equals(m) ? AUTO : OFF;
        Widgets.prefs(ctx).edit().putString("dark_mode", next).apply();
        Profiles.bumpRevision(ctx);
    }

    static boolean active(Context ctx) {
        String m = mode(ctx);
        return ON.equals(m) || (AUTO.equals(m) && night(LocalTime.now()));
    }

    static boolean night(LocalTime t) {
        return t.getHour() >= 20 || t.getHour() < 7;
    }

    static int label(Context ctx) {
        switch (mode(ctx)) {
            case ON: return R.string.dark_on;
            case AUTO: return R.string.dark_auto;
            default: return R.string.hub_off;
        }
    }
}
