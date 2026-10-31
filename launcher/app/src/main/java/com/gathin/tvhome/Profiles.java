package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Who is using the TV: a short list of profiles, each with a name, a colour and an optional 4-digit PIN, and the one
 * that is active. Ported from Remindr's profile model.
 *
 * Each profile has its own Home: a JSON overlay (profile_&lt;id&gt;.json in the app's private files) laid over the
 * theme's config.json with the same keys (dock_apps, hidden_apps, wallpaper and the rest, see Config), plus its own
 * tile order and "last opened" history. Profile 1 keeps the plain file and preference names, so the Home that was
 * there before profiles existed becomes the first profile unchanged.
 *
 * The PIN is a privacy screen, not security: hidden apps are still reachable through Android's own Settings, and
 * the apps keep their own accounts. It is stored as a salted PBKDF2-SHA256 hash, never as the digits.
 */
final class Profiles {
    private static final String TAG = "TvHome.Profiles";
    private static final String PREFS = "profiles";
    private static final String KEY_LIST = "list";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_LAST = "last";
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_UNLOCKED_BOOT = "unlocked_boot";
    static final int MAX_PROFILES = 6;
    static final int PIN_LENGTH = 4;
    private static final int ITERATIONS = 20_000;   // ~0.1 s on a 1.4 GB MediaTek TV: a privacy screen, not a vault
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    /** Avatar colours offered when a profile is made or edited, in order. */
    static final int[] COLORS = {
            0xFF3B82F6, 0xFFEF4444, 0xFF10B981, 0xFFF59E0B, 0xFF8B5CF6, 0xFFEC4899, 0xFF14B8A6, 0xFF64748B,
    };

    /** Pictures a profile can use instead of its initial (the TV's emoji font draws them). */
    static final String[] AVATARS = {"🦊", "🐼", "🦁", "🐙", "🦄", "🚀", "🎮", "⚽", "🎬"};

    static final class Profile {
        final int id;
        String name;
        int color;
        /** The avatar's picture; null shows the initial. */
        String emoji;
        String pinHash;
        String pinSalt;

        Profile(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        boolean hasPin() {
            return pinHash != null && !pinHash.isEmpty();
        }

        /** What the avatar shows: its picture, or the name's first letter. */
        String initial() {
            if (emoji != null && !emoji.isEmpty()) return emoji;
            String n = name.trim();
            return n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.getDefault());
        }
    }

    private Profiles() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The last parsed list and the text it came from: nearly every per-profile file name asks for the current
     *  profile, so the list is parsed again only when the stored text changes. */
    private static String cachedRaw;
    private static List<Profile> cached;

    /** Every profile, the first made first. There is always at least one: the Home from before profiles existed. */
    static List<Profile> list(Context ctx) {
        String raw = prefs(ctx).getString(KEY_LIST, null);
        synchronized (Profiles.class) {
            if (raw != null && raw.equals(cachedRaw)) return copies(cached);
        }
        List<Profile> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Profile p = new Profile(o.getInt("id"), o.optString("name", "?"), o.optInt("color", COLORS[0]));
                    p.emoji = o.optString("emoji", null);
                    p.pinHash = o.optString("pin_hash", null);
                    p.pinSalt = o.optString("pin_salt", null);
                    out.add(p);
                }
            } catch (JSONException e) {
                Log.w(TAG, "profile list unreadable, starting over: " + e.getMessage());
                out.clear();
            }
        }
        if (out.isEmpty()) {
            out.add(new Profile(1, ctx.getString(R.string.profile_default_name), COLORS[0]));
            save(ctx, out);
            return out;
        }
        synchronized (Profiles.class) {
            cachedRaw = raw;
            cached = copies(out);
        }
        return out;
    }

    /** Callers edit what they get (rename, PIN) before saving it, so the cache hands out copies. */
    private static List<Profile> copies(List<Profile> from) {
        List<Profile> out = new ArrayList<>(from.size());
        for (Profile p : from) {
            Profile c = new Profile(p.id, p.name, p.color);
            c.emoji = p.emoji;
            c.pinHash = p.pinHash;
            c.pinSalt = p.pinSalt;
            out.add(c);
        }
        return out;
    }

    static void save(Context ctx, List<Profile> profiles) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : profiles) {
                JSONObject o = new JSONObject().put("id", p.id).put("name", p.name).put("color", p.color);
                if (p.emoji != null && !p.emoji.isEmpty()) o.put("emoji", p.emoji);
                if (p.hasPin()) o.put("pin_hash", p.pinHash).put("pin_salt", p.pinSalt);
                a.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(ctx).edit().putString(KEY_LIST, a.toString()).apply();
    }

    static Profile find(Context ctx, int id) {
        for (Profile p : list(ctx)) if (p.id == id) return p;
        return null;
    }

    /** The active profile's id; the first profile until someone picks. */
    static int currentId(Context ctx) {
        int id = prefs(ctx).getInt(KEY_CURRENT, 0);
        if (id != 0 && find(ctx, id) != null) return id;
        return list(ctx).get(0).id;
    }

    static Profile current(Context ctx) {
        return find(ctx, currentId(ctx));
    }

    static int lastId(Context ctx) {
        return prefs(ctx).getInt(KEY_LAST, currentId(ctx));
    }

    /** Makes a profile active; for a different one, bumps Launcher settings' change count, so Home rebuilds with its
     *  overlay. */
    static void enter(Context ctx, int id) {
        boolean changed = currentId(ctx) != id;
        prefs(ctx).edit().putInt(KEY_CURRENT, id).putInt(KEY_LAST, id)
                .putInt(KEY_UNLOCKED_BOOT, bootCount(ctx)).apply();
        // Home rebuilds for another profile's Home; picking the one already in use (unlocking at power-on, or
        // choosing yourself on the profiles screen) keeps it as it is, without reloading every tile.
        if (changed) bumpRevision(ctx);
    }

    static void bumpRevision(Context ctx) {
        SharedPreferences settings = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        settings.edit().putLong(Config.SETTINGS_REVISION_KEY, settings.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    /** Whether the picker has anything to ask: more than one profile, or a PIN on the only one. */
    static boolean pickerNeeded(Context ctx) {
        List<Profile> all = list(ctx);
        return all.size() > 1 || all.get(0).hasPin();
    }

    /** Whether someone has picked a profile since this boot (and the TV has not been in standby since). */
    static boolean pickedThisBoot(Context ctx) {
        return prefs(ctx).getInt(KEY_UNLOCKED_BOOT, -2) == bootCount(ctx);
    }

    /** Forgets this boot's pick, so the next Home asks again (after standby). */
    static void lock(Context ctx) {
        prefs(ctx).edit().putInt(KEY_UNLOCKED_BOOT, -2).apply();
    }

    private static int bootCount(Context ctx) {
        return android.provider.Settings.Global.getInt(ctx.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    static Profile add(Context ctx, String name, int color) {
        List<Profile> all = list(ctx);
        if (all.size() >= MAX_PROFILES) return null;
        int next = Math.max(prefs(ctx).getInt(KEY_NEXT_ID, 2), maxId(all) + 1);
        Profile p = new Profile(next, name, color);
        all.add(p);
        save(ctx, all);
        prefs(ctx).edit().putInt(KEY_NEXT_ID, next + 1).apply();
        return p;
    }

    private static int maxId(List<Profile> all) {
        int max = 0;
        for (Profile p : all) max = Math.max(max, p.id);
        return max;
    }

    static void update(Context ctx, Profile changed) {
        List<Profile> all = list(ctx);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id == changed.id) all.set(i, changed);
        save(ctx, all);
        bumpRevision(ctx);
    }

    /** Removes a profile and its Home. The last profile cannot be removed. */
    static boolean delete(Context ctx, int id) {
        List<Profile> all = list(ctx);
        if (all.size() <= 1) return false;
        boolean removed = false;
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == id) { all.remove(i); removed = true; }
        if (!removed) return false;
        save(ctx, all);
        overlayFile(ctx, id).delete();
        Order.file(ctx, id).delete();
        ctx.deleteSharedPreferences(LastOpened.PREFS + suffix(id));
        ctx.deleteSharedPreferences(Widgets.PREFS + suffix(id));
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .remove(Config.WALLPAPER_CHOICE_KEY + suffix(id)).apply();
        SharedPreferences.Editor edit = prefs(ctx).edit();
        // Deleting the active profile falls back to the first one, which may have a PIN of its own: ask again.
        if (prefs(ctx).getInt(KEY_CURRENT, 0) == id) edit.remove(KEY_CURRENT).putInt(KEY_UNLOCKED_BOOT, -2);
        if (prefs(ctx).getInt(KEY_LAST, 0) == id) edit.remove(KEY_LAST);
        edit.apply();
        bumpRevision(ctx);
        return true;
    }

    /** The active profile's name for the greeting; none for the single default profile, which has no one's name. */
    static String greetingName(Context ctx) {
        Profile p = current(ctx);
        if (p == null || (list(ctx).size() == 1 && p.name.equals(ctx.getString(R.string.profile_default_name)))) return "";
        return p.name;
    }

    /** Black with a trace of the profile's colour: Home's background says whose Home it is without shouting. */
    static int tintedBlack(Context ctx) {
        Profile p = current(ctx);
        if (p == null) return 0xFF000000;
        int c = p.color;
        return 0xFF000000 | (Math.round(((c >> 16) & 0xFF) * 0.07f) << 16)
                | (Math.round(((c >> 8) & 0xFF) * 0.07f) << 8) | Math.round((c & 0xFF) * 0.07f);
    }

    // --- per-profile storage ---------------------------------------------------------------------------------------

    /** "" for the first profile, "_p&lt;id&gt;" for the others: the name suffix of each profile's own files. */
    static String suffix(int id) {
        return id == 1 ? "" : "_p" + id;
    }

    static String suffix(Context ctx) {
        return suffix(currentId(ctx));
    }

    static File overlayFile(Context ctx, int id) {
        return new File(ctx.getFilesDir(), "profile_" + id + ".json");
    }

    /** The active profile's Home overlay, or null when it has none. */
    static JSONObject overlay(Context ctx) {
        return readJson(overlayFile(ctx, currentId(ctx)));
    }

    /** The active profile's overlay to edit; an empty object when it has none. */
    static JSONObject editableOverlay(Context ctx) {
        JSONObject o = overlay(ctx);
        return o != null ? o : new JSONObject();
    }

    static void saveOverlay(Context ctx, JSONObject overlay) {
        File f = overlayFile(ctx, currentId(ctx));
        // Atomically: power off in the middle of a plain write leaves half a file, and with it no Home for this profile.
        android.util.AtomicFile file = new android.util.AtomicFile(f);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(overlay.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "could not save " + f + ": " + e.getMessage());
        }
        bumpRevision(ctx);
    }

    /** Lays the profile's keys over the theme's: a key the profile sets wins, the rest stay the theme's. */
    static JSONObject merge(JSONObject theme, JSONObject profile) {
        if (profile == null) return theme;
        JSONObject out = new JSONObject();
        try {
            if (theme != null) {
                for (java.util.Iterator<String> it = theme.keys(); it.hasNext(); ) {
                    String k = it.next();
                    out.put(k, theme.get(k));
                }
            }
            for (java.util.Iterator<String> it = profile.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, profile.get(k));
            }
        } catch (JSONException e) {
            return theme;
        }
        return out;
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + f + ": " + e.getMessage());
            return null;
        }
    }

    /** A string list from the active profile's overlay, or the fallback when it does not set one. */
    static List<String> overlayList(Context ctx, String key, List<String> fallback) {
        JSONObject o = overlay(ctx);
        JSONArray a = o != null ? o.optJSONArray(key) : null;
        if (a == null) return fallback;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** The apps hidden on the active profile's Home. Top-bar apps leave the grid through Config, not this list. */
    static List<String> hiddenApps(Context ctx) {
        Config cfg = Config.load(ctx);
        List<String> out = new ArrayList<>(cfg.hiddenApps);
        out.removeAll(cfg.topBarApps);
        return out;
    }

    /** Hides an app on the active profile's Home only, or shows it there again. */
    static void setHidden(Context ctx, String pkg, boolean hide) {
        List<String> hidden = hiddenApps(ctx);
        hidden.remove(pkg);
        if (hide) hidden.add(pkg);
        putOverlayList(ctx, "hidden_apps", hidden);
    }

    static void putOverlayList(Context ctx, String key, List<String> values) {
        JSONObject o = editableOverlay(ctx);
        try {
            o.put(key, new JSONArray(values));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        saveOverlay(ctx, o);
    }

    // --- PIN -------------------------------------------------------------------------------------------------------

    static boolean validPin(String pin) {
        if (pin == null || pin.length() != PIN_LENGTH) return false;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    /** Sets (or with null, clears) a profile's PIN. */
    static void setPin(Profile p, String pin) {
        if (pin == null) {
            p.pinHash = null;
            p.pinSalt = null;
            return;
        }
        if (!validPin(pin)) throw new IllegalArgumentException("PIN must be 4 digits");
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        p.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP);
        p.pinHash = Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP);
    }

    static boolean checkPin(Profile p, String pin) {
        if (!p.hasPin()) return true;
        if (!validPin(pin) || p.pinSalt == null) return false;
        try {
            byte[] expected = Base64.decode(p.pinHash, Base64.NO_WRAP);
            byte[] actual = derive(pin, Base64.decode(p.pinSalt, Base64.NO_WRAP));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Unused colours first, so a new profile looks different from the others. */
    static int nextColor(List<Profile> all) {
        List<Integer> used = new ArrayList<>();
        for (Profile p : all) used.add(p.color);
        for (int c : COLORS) if (!used.contains(c)) return c;
        return COLORS[all.size() % COLORS.length];
    }

    static List<Profile> pickerOrder(Context ctx) {
        List<Profile> all = list(ctx);
        int last = lastId(ctx);
        List<Profile> out = new ArrayList<>(all);
        Collections.sort(out, (a, b) -> Integer.compare(a.id == last ? 0 : 1, b.id == last ? 0 : 1));
        return out;
    }
}
