package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The TV's own settings drawn inside the launcher's Settings, so Network, Sound, Display, Apps and About stay in one
 * calm look instead of jumping to Android's Settings app. Each page reads only what an app may read, and what an app
 * may not change (joining a Wi-Fi network, the picture mode) is one row away on Android's own page.
 *
 * Built when a page opens and dropped when it closes. Anything slow (app sizes, icons) is read on the host's one
 * background thread, so nothing here holds memory or a thread while Settings is closed.
 */
final class TvPages {
    interface Host {
        void push(String title, SettingsHubActivity.Page page);

        /** Rebuilds the page on screen in place, keeping focus where it is. */
        void refresh();

        void open(Intent intent);

        /** Runs work off the main thread, then done on it while the same page is still on screen; done gets null
         *  when the work failed. */
        <T> void load(Supplier<T> work, Consumer<T> done);
    }

    private final SettingsHubActivity ctx;
    private final Host host;

    TvPages(SettingsHubActivity ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    private String s(int id, Object... args) {
        return ctx.getString(id, args);
    }

    private View info(CharSequence title, CharSequence value) {
        return Ui.row(ctx, title, value, null);
    }

    private View link(int title, CharSequence detail, String action) {
        Intent i = SystemSettings.intent(ctx, action);
        if (i == null) return null;
        return Ui.navRow(ctx, s(title), detail, v -> host.open(i));
    }

    private static void add(LinearLayout r, View v) {
        if (v != null) r.addView(v);
    }

    /** A glass card at the top of a page: a large line, a smaller one under it, and an optional view at the end. */
    private View hero(int icon, int color, CharSequence big, CharSequence small, View end) {
        LinearLayout box = new LinearLayout(ctx);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.sheen(ctx, 24, Ui.withAlpha(color, 0x40), 0x14FFFFFF, 0x33FFFFFF, Math.max(1, Ui.dp(ctx,
                1))));
        int pad = Ui.dp(ctx, 20);
        box.setPadding(pad, pad, pad, pad);
        FrameLayout tile = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.lighten(color, 0.18f), Ui.darken(color, 0.15f)});
        bg.setCornerRadius(Ui.dp(ctx, 14));
        tile.setBackground(bg);
        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(icon);
        glyph.setColorFilter(0xFFFFFFFF);
        tile.addView(glyph, new FrameLayout.LayoutParams(Ui.dp(ctx, 28), Ui.dp(ctx, 28), Gravity.CENTER));
        box.addView(tile, new LinearLayout.LayoutParams(Ui.dp(ctx, 52), Ui.dp(ctx, 52)));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 8), 0);
        TextView a = Ui.text(ctx, big, 21, R.string.font_regular, Ui.TEXT);
        a.setMaxLines(2);
        text.addView(a);
        TextView b = Ui.text(ctx, small, 14, R.string.font_light, Ui.TEXT_DIM);
        b.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        text.addView(b);
        box.addView(text, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        if (end != null) box.addView(end);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(ctx, 6);
        box.setLayoutParams(lp);
        return box;
    }

    // --- network -------------------------------------------------------------------------------------------------

    void network(LinearLayout r) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network active = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        boolean vpn = false;
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
            }
        }
        // With a VPN up the active network is the VPN; the link under it is the one to describe.
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    active = n;
                    caps = c;
                    break;
                }
            }
        }
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean ethernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean internet = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        WifiInfo w = null;
        if (wifi) {
            WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
            w = wm != null ? wm.getConnectionInfo() : null;
        }
        String ssid = w != null && w.getSSID() != null ? w.getSSID().replace("\"", "").trim() : "";
        if (ssid.isEmpty() || ssid.contains("unknown")) ssid = "Wi-Fi";
        CharSequence big = wifi ? s(R.string.tv_network_wifi, ssid) : ethernet ? s(R.string.tv_network_ethernet)
                : s(R.string.tv_network_offline);
        CharSequence small = caps == null ? s(R.string.tv_network_internet_off)
                : s(internet ? R.string.tv_network_internet_ok : R.string.tv_network_internet_none);
        int level = w != null ? WifiManager.calculateSignalLevel(w.getRssi(), 5) : 0;
        r.addView(hero(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi,
                caps == null ? 0xFF64748B : internet ? 0xFF10B981 : 0xFFF59E0B, big, small,
                wifi ? signalBars(level) : null));
        add(r, link(R.string.tv_network_change, s(R.string.tv_network_change_hint), Settings.ACTION_WIFI_SETTINGS));

        if (caps == null) return;
        r.addView(Ui.header(ctx, s(R.string.tv_network_details)));
        LinkProperties lp = cm.getLinkProperties(active);
        String ip = "";
        if (lp != null) {
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) ip = a.getAddress().getHostAddress();
            }
        }
        if (!ip.isEmpty()) r.addView(info(s(R.string.tv_network_ip), ip));
        if (w != null) {
            int[] words = {R.string.tv_signal_weak, R.string.tv_signal_weak, R.string.tv_signal_fair,
                    R.string.tv_signal_good, R.string.tv_signal_excellent};
            r.addView(info(s(R.string.tv_network_signal), s(R.string.tv_network_signal_value, s(words[level]),
                    w.getRssi())));
            if (w.getLinkSpeed() > 0) r.addView(info(s(R.string.tv_network_speed), s(R.string.tv_network_speed_value,
                    w.getLinkSpeed())));
            int mhz = w.getFrequency();
            if (mhz > 0) r.addView(info(s(R.string.tv_network_band), mhz >= 5900 ? "6 GHz" : mhz >= 4900
                    ? "5 GHz" : "2.4 GHz"));
        }
        String dnsMode = Settings.Global.getString(ctx.getContentResolver(), "private_dns_mode");
        String dnsHost = Settings.Global.getString(ctx.getContentResolver(), "private_dns_specifier");
        r.addView(info(s(R.string.tv_network_dns), "hostname".equals(dnsMode) && dnsHost != null ? dnsHost
                : "off".equals(dnsMode) ? s(R.string.off) : s(R.string.tv_network_dns_auto)));
        r.addView(info(s(R.string.tv_network_vpn), s(vpn ? R.string.on : R.string.off)));
    }

    /** The link in use, for Settings' Overview: the Wi-Fi network's name, Ethernet, or offline. */
    String networkSummary() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network active = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        if (caps == null) return s(R.string.tv_network_offline);
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return s(R.string.tv_network_ethernet);
        WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo w = wm != null ? wm.getConnectionInfo() : null;
        String ssid = w != null && w.getSSID() != null ? w.getSSID().replace("\"", "").trim() : "";
        return ssid.isEmpty() || ssid.contains("unknown") ? "Wi-Fi" : ssid;
    }

    /** Four bars rising left to right, the first level of them lit. */
    private View signalBars(int level) {
        LinearLayout bars = new LinearLayout(ctx);
        bars.setGravity(Gravity.BOTTOM);
        int w = Ui.dp(ctx, 5);
        for (int i = 1; i <= 4; i++) {
            View b = new View(ctx);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(Ui.dp(ctx, 2));
            d.setColor(i <= level ? 0xFFFFFFFF : 0x40FFFFFF);
            b.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, Ui.dp(ctx, 6 + 5 * i));
            lp.setMarginStart(Ui.dp(ctx, 3));
            bars.addView(b, lp);
        }
        return bars;
    }

    // --- sound ---------------------------------------------------------------------------------------------------

    void sound(LinearLayout r) {
        AudioManager audio = ctx.getSystemService(AudioManager.class);
        if (audio == null) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean fixed = audio.isVolumeFixed() || max <= 0;
        if (fixed) {
            r.addView(info(s(R.string.tv_sound_volume), s(R.string.tv_sound_fixed)));
        } else {
            r.addView(volumeRow(audio, max));
            boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            r.addView(Ui.toggle(ctx, s(R.string.tv_sound_mute), muted, v -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                host.refresh();
            }));
        }
        if (Settings.System.canWrite(ctx)) {
            boolean clicks = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED,
                    1) == 1;
            r.addView(Ui.toggle(ctx, s(R.string.tv_sound_effects), clicks, v -> {
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, clicks ? 0 : 1);
                if (clicks) audio.unloadSoundEffects(); else audio.loadSoundEffects();
                host.refresh();
            }));
        }
        add(r, link(R.string.tv_sound_more, null, Settings.ACTION_SOUND_SETTINGS));
    }

    /** Volume as a bar in the row: Left and Right step it, the bar and the percentage follow at once. */
    private View volumeRow(AudioManager audio, int max) {
        LinearLayout row = Ui.row(ctx, s(R.string.tv_sound_volume), null, null);
        FrameLayout track = new FrameLayout(ctx);
        GradientDrawable t = new GradientDrawable();
        t.setColor(0x4D8A8F99);   // a grey that shows on the dark row and on the white focused one alike
        t.setCornerRadius(Ui.dp(ctx, 3));
        track.setBackground(t);
        View fill = new View(ctx);
        GradientDrawable f = new GradientDrawable();
        f.setColor(0xFFEC4899);
        f.setCornerRadius(Ui.dp(ctx, 3));
        fill.setBackground(f);
        int width = Ui.dp(ctx, 150);
        track.addView(fill, new FrameLayout.LayoutParams(0, Ui.MATCH));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(width, Ui.dp(ctx, 6));
        tl.setMarginEnd(Ui.dp(ctx, 14));
        row.addView(track, tl);
        TextView pct = Ui.text(ctx, "", 16, R.string.font_light, Ui.TEXT_DIM);
        Ui.textFocus(pct, Ui.TEXT_DIM);
        pct.setMinWidth(Ui.dp(ctx, 44));
        pct.setGravity(Gravity.END);
        row.addView(pct);
        boolean[] muted = {audio.isStreamMute(AudioManager.STREAM_MUSIC)};
        Runnable update = () -> {
            // Raising the volume unmutes the TV: rebuild the page then, so the Mute row says so too.
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) != muted[0]) {
                host.refresh();
                return;
            }
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            fill.getLayoutParams().width = Math.round(width * v / (float) max);
            fill.requestLayout();
            pct.setText(String.format(Locale.getDefault(), "%d%%", Math.round(100f * v / max)));
        };
        update.run();
        row.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        code == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                update.run();
            }
            return true;
        });
        return row;
    }

    // --- display -------------------------------------------------------------------------------------------------

    private static final int[] SCREENSAVER_MINUTES = {5, 15, 30, 60, 120, 0};

    void display(LinearLayout r) {
        android.view.Display d = ctx.getWindowManager().getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);
        int hz = Math.round(d.getRefreshRate());
        r.addView(hero(R.drawable.ic_picture, 0xFFF97316, dm.widthPixels + " × " + dm.heightPixels, hz + " Hz", null));
        r.addView(info(s(R.string.tv_display_hdr), hdrFormats(d)));
        r.addView(screensaverDelay());
        add(r, link(R.string.tv_display_picture, s(R.string.tv_display_picture_hint),
                Settings.ACTION_DISPLAY_SETTINGS));
    }

    private String hdrFormats(android.view.Display d) {
        android.view.Display.HdrCapabilities caps = d.getHdrCapabilities();
        List<String> names = new ArrayList<>();
        if (caps != null) {
            for (int t : caps.getSupportedHdrTypes()) {
                switch (t) {
                    case android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: names.add("Dolby Vision"); break;
                    case android.view.Display.HdrCapabilities.HDR_TYPE_HDR10: names.add("HDR10"); break;
                    case android.view.Display.HdrCapabilities.HDR_TYPE_HLG: names.add("HLG"); break;
                    default: break;
                }
            }
        }
        return names.isEmpty() ? s(R.string.tv_display_hdr_none) : String.join(" · ", names);
    }

    /** How long the TV waits before its screensaver; OK steps through the choices. Also on the Screensaver page,
     *  where Android's own timing page is missing on some firmware (this Hisense has none). */
    View screensaverDelay() {
        int ms = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60_000);
        return Ui.row(ctx, s(R.string.tv_display_screensaver_after), duration(ms), v -> {
            if (!Settings.System.canWrite(ctx)) {
                Toast.makeText(ctx, R.string.tv_display_no_permission, Toast.LENGTH_LONG).show();
                return;
            }
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, nextTimeout(ms));
            host.refresh();
        });
    }

    /** The next choice after the current one (an unlisted value starts the cycle); 0 minutes is Never. */
    static int nextTimeout(int ms) {
        int i = 0;
        while (i < SCREENSAVER_MINUTES.length && minutesMs(SCREENSAVER_MINUTES[i]) != ms) i++;
        return minutesMs(SCREENSAVER_MINUTES[(i + 1) % SCREENSAVER_MINUTES.length]);
    }

    private static int minutesMs(int minutes) {
        return minutes == 0 ? Integer.MAX_VALUE : minutes * 60_000;
    }

    private String duration(int ms) {
        if (ms <= 0 || ms == Integer.MAX_VALUE) return s(R.string.tv_display_never);
        int minutes = Math.max(1, ms / 60_000);
        return minutes >= 60 && minutes % 60 == 0 ? s(R.string.tv_display_hours, minutes / 60)
                : s(R.string.tv_display_minutes, minutes);
    }

    // --- apps ----------------------------------------------------------------------------------------------------

    private static final class App {
        final String pkg;
        final String label;
        final Drawable icon;
        final long bytes;

        App(String pkg, String label, Drawable icon, long bytes) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.bytes = bytes;
        }
    }

    /** The last reading, kept while the page is open so a rebuild (focus kept in place) doesn't measure again. */
    private List<App> apps;

    /** Opening the page measures afresh; call before pushing it. */
    void forgetApps() {
        apps = null;
    }

    void apps(LinearLayout r) {
        if (apps == null) {
            TextView wait = Ui.text(ctx, s(R.string.tv_apps_loading), 16, R.string.font_light, Ui.TEXT_DIM);
            wait.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 12), 0, 0);
            r.addView(wait);
            host.load(this::readApps, found -> {
                apps = found != null ? found : new ArrayList<>();   // a failed read shows an empty list, not a wait
                host.refresh();
            });
            return;
        }
        long total = 0;
        for (App a : apps) total += Math.max(0, a.bytes);
        r.addView(hero(R.drawable.ic_apps, 0xFFEF4444, s(R.string.tv_apps_summary, apps.size(),
                Formatter.formatShortFileSize(ctx, total)), s(R.string.tv_apps_note), null));
        for (App a : apps) {
            LinearLayout row = Ui.navRow(ctx, a.label, a.bytes >= 0 ? Formatter.formatShortFileSize(ctx, a.bytes)
                    : null,
                    v -> appMenu(a));
            if (a.icon != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(a.icon);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(ctx, 28), Ui.dp(ctx, 28));
                ilp.setMarginEnd(Ui.dp(ctx, 14));
                row.addView(iv, 0, ilp);
                row.setPadding(Ui.dp(ctx, 14), 0, row.getPaddingEnd(), 0);
            }
            r.addView(row);
        }
    }

    /** Every app with a TV or phone entry, biggest first. Off the main thread: each size is a binder call. */
    private List<App> readApps() {
        PackageManager pm = ctx.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> found = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                if (ri.activityInfo != null && seen.add(ri.activityInfo.packageName)) found.add(ri);
            }
        }
        StorageStatsManager stats = ctx.getSystemService(StorageStatsManager.class);
        UserHandle me = android.os.Process.myUserHandle();
        int iconPx = Ui.dp(ctx, 28);
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            long bytes = -1;
            try {
                StorageStats st = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, me);
                bytes = st.getAppBytes() + st.getDataBytes();   // getDataBytes() already counts the cache
            } catch (Exception noAccess) {
                // usage access not granted, or the app went away: no size
            }
            out.add(new App(pkg, Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), Ui.smallIcon(ctx,
                    ri.loadIcon(pm), iconPx), bytes));
        }
        out.sort((a, b) -> b.bytes != a.bytes ? Long.compare(b.bytes, a.bytes) : a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void appMenu(App a) {
        Ui.ListDialog d = Ui.listDialog(ctx, a.label);
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(a.pkg);
        Intent go = launch;
        if (go != null) d.add(s(R.string.tv_apps_open), null, () -> host.open(go));
        boolean hidden = Profiles.hiddenApps(ctx).contains(a.pkg);
        d.add(s(hidden ? R.string.tv_apps_show : R.string.tv_apps_hide), null, () -> Profiles.setHidden(ctx, a.pkg,
                !hidden));
        Intent info = SystemSettings.appInfo(ctx, a.pkg);
        if (info != null) d.add(s(R.string.tv_apps_info), null, () -> host.open(info));
        d.show();
    }

    // --- storage -----------------------------------------------------------------------------------------------

    void storage(LinearLayout r) {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes(), used = total - free;
        r.addView(hero(R.drawable.ic_apps, 0xFFEF4444, s(R.string.tv_storage_used, Formatter.formatShortFileSize(ctx,
                used),
                Formatter.formatShortFileSize(ctx, total)), s(R.string.hub_free, Formatter.formatShortFileSize(ctx,
                        free)), null));
        FrameLayout track = new FrameLayout(ctx);
        GradientDrawable t = new GradientDrawable();
        t.setColor(0x26FFFFFF);
        t.setCornerRadius(Ui.dp(ctx, 4));
        track.setBackground(t);
        View fill = new View(ctx);
        GradientDrawable f = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFF97316,
                0xFFEF4444});
        f.setCornerRadius(Ui.dp(ctx, 4));
        fill.setBackground(f);
        int width = Ui.dp(ctx, 500);
        track.addView(fill, new FrameLayout.LayoutParams((int) (width * Math.min(1.0, (double) used / Math.max(1,
                total))), Ui.dp(ctx, 8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, Ui.dp(ctx, 8));
        lp.setMargins(Ui.dp(ctx, 8), Ui.dp(ctx, 6), 0, Ui.dp(ctx, 16));
        track.setLayoutParams(lp);
        r.addView(track);
        r.addView(Ui.navRow(ctx, s(R.string.hub_all_apps), s(R.string.tv_storage_apps_hint), v -> {
            forgetApps();
            host.push(s(R.string.hub_all_apps), this::apps);
        }));
        add(r, link(R.string.tv_storage_more, null, Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
    }

    // --- date and time -------------------------------------------------------------------------------------------

    void dateTime(LinearLayout r) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        r.addView(hero(R.drawable.ic_hub_sun, 0xFFF59E0B,
                android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date()),
                android.text.format.DateFormat.getLongDateFormat(ctx).format(new java.util.Date()), null));
        r.addView(info(s(R.string.tv_time_zone), zone.getDisplayName(zone.inDaylightTime(new java.util.Date()),
                java.util.TimeZone.LONG)));
        boolean auto = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AUTO_TIME, 1) == 1;
        r.addView(info(s(R.string.tv_time_auto), s(auto ? R.string.on : R.string.off)));
        boolean h24 = android.text.format.DateFormat.is24HourFormat(ctx);
        if (Settings.System.canWrite(ctx)) {
            r.addView(Ui.toggle(ctx, s(R.string.tv_time_24), h24, v -> {
                Settings.System.putString(ctx.getContentResolver(), Settings.System.TIME_12_24, h24 ? "12" : "24");
                host.refresh();
            }));
        } else {
            r.addView(info(s(R.string.tv_time_24), s(h24 ? R.string.on : R.string.off)));
        }
        add(r, link(R.string.tv_time_more, null, Settings.ACTION_DATE_SETTINGS));
    }

    // --- keyboard ------------------------------------------------------------------------------------------------

    void keyboard(LinearLayout r) {
        android.view.inputmethod.InputMethodManager imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager.class);
        String current = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String name = s(R.string.tv_keyboard_unknown);
        boolean ours = false;
        boolean enabled = false;
        if (imm != null) {
            for (android.view.inputmethod.InputMethodInfo i : imm.getEnabledInputMethodList()) {
                if (i.getId().equals(current)) name = String.valueOf(i.loadLabel(ctx.getPackageManager()));
                if (i.getPackageName().equals(ctx.getPackageName())) {
                    enabled = true;
                    ours |= i.getId().equals(current);
                }
            }
        }
        r.addView(hero(R.drawable.ic_hub_layout, 0xFF8B5CF6, name, s(ours ? R.string.tv_keyboard_ours
                : R.string.tv_keyboard_other), null));
        if (imm != null) r.addView(Ui.row(ctx, s(R.string.tv_keyboard_switch), null, v -> imm.showInputMethodPicker()));
        if (!enabled) add(r, link(R.string.tv_keyboard_enable, null, Settings.ACTION_INPUT_METHOD_SETTINGS));
        r.addView(Ui.note(ctx, s(R.string.tv_keyboard_note)));
    }

    // --- Bluetooth -----------------------------------------------------------------------------------------------

    /** What is paired, read only: switching Bluetooth here could cut off the remote this page is driven with. */
    @android.annotation.SuppressLint("MissingPermission")   // Android 11 uses BLUETOOTH; newer ones throw, caught below
    void bluetooth(LinearLayout r) {
        android.bluetooth.BluetoothManager bm = ctx.getSystemService(android.bluetooth.BluetoothManager.class);
        android.bluetooth.BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        boolean on = adapter != null && adapter.isEnabled();
        Set<android.bluetooth.BluetoothDevice> paired = null;
        try {
            if (on) paired = adapter.getBondedDevices();
        } catch (SecurityException notAllowed) {
            // an empty list below
        }
        int count = paired != null ? paired.size() : 0;
        r.addView(hero(R.drawable.ic_hub_sound, 0xFF3B82F6, s(on ? R.string.tv_bluetooth_on
                : R.string.tv_bluetooth_off),
                s(R.string.tv_bluetooth_paired, count), null));
        if (count > 0) {
            r.addView(Ui.header(ctx, s(R.string.tv_bluetooth_devices)));
            for (android.bluetooth.BluetoothDevice d : paired) {
                String label = d.getName();
                r.addView(info(label != null ? label : s(R.string.tv_keyboard_unknown), null));
            }
        }
        add(r, link(R.string.tv_bluetooth_more, null, Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    // --- language ------------------------------------------------------------------------------------------------

    void language(LinearLayout r) {
        Locale l = Locale.getDefault();
        r.addView(hero(R.drawable.ic_hub_person, 0xFF14B8A6, l.getDisplayLanguage(l), l.getDisplayCountry(l), null));
        add(r, link(R.string.tv_language_more, null, Settings.ACTION_LOCALE_SETTINGS));
        add(r, link(R.string.tv_accessibility, null, Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    // --- about ---------------------------------------------------------------------------------------------------

    void about(LinearLayout r) {
        String model = Build.MANUFACTURER.isEmpty() ? Build.MODEL
                : Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1)
                        + " " + Build.MODEL;
        r.addView(hero(R.drawable.ic_hub_info, 0xFF3B82F6, model,
                s(R.string.tv_about_android_value, Build.VERSION.RELEASE), null));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        r.addView(info(s(R.string.tv_about_memory), s(R.string.tv_about_memory_value,
                Formatter.formatShortFileSize(ctx, mi.availMem), Formatter.formatShortFileSize(ctx, mi.totalMem))));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        r.addView(info(s(R.string.tv_about_storage), s(R.string.tv_about_memory_value,
                Formatter.formatShortFileSize(ctx, fs.getAvailableBytes()), Formatter.formatShortFileSize(ctx,
                        fs.getTotalBytes()))));
        r.addView(info(s(R.string.tv_about_chip), Build.HARDWARE.toUpperCase(Locale.ROOT)));
        DisplayMetrics dm = new DisplayMetrics();
        ctx.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        r.addView(info(s(R.string.tv_about_resolution), dm.widthPixels + " × " + dm.heightPixels));
        long up = SystemClock.elapsedRealtime() / 60_000;
        r.addView(info(s(R.string.tv_about_uptime), up >= 60 * 24 ? (up / (60 * 24)) + " d " + (up / 60 % 24) + " h"
                : up >= 60 ? (up / 60) + " h " + (up % 60) + " min" : up + " min"));
        r.addView(info(s(R.string.tv_about_build), Build.DISPLAY));
        try {
            PackageInfo me = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            r.addView(info(s(R.string.tv_about_launcher), me.versionName));
        } catch (PackageManager.NameNotFoundException impossible) {
            // our own package is always there
        }
        add(r, link(R.string.tv_about_more, null, Settings.ACTION_DEVICE_INFO_SETTINGS));
    }
}
