package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The top-shelf widgets' settings and data, per profile (preferences "widgets" + the profile's suffix): which
 * widgets show and in what order, the notes, the weather place and its last reading, and the Continue watching
 * history. Weather is the only part that touches the network: Open-Meteo (no account, no key), read at most every
 * 30 minutes and only while Home or the Weather app is on screen.
 */
final class Widgets {
    private static final String TAG = "TvHome.Widgets";
    static final String PREFS = "widgets";
    static final String WEATHER = "weather";
    static final String CALENDAR = "calendar";
    static final String NOTES = "notes";
    static final String WATCHING = "watching";
    static final String RECENT = "recent";
    /** Every widget, in the order Settings lists them. */
    static final List<String> ALL = Arrays.asList(WEATHER, CALENDAR, WATCHING, NOTES, RECENT);
    /** The shelf holds a 2 x 2 grid. */
    static final int MAX_SHOWN = 4;
    private static final List<String> DEFAULT = Arrays.asList(WEATHER, CALENDAR, WATCHING, NOTES);
    static final long WEATHER_TTL_MS = 30 * 60 * 1000L;
    static final int MAX_NOTES = 12;
    static final int MAX_WATCHING = 8;

    private Widgets() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS + Profiles.suffix(ctx), Context.MODE_PRIVATE);
    }

    /** Another profile's preferences, for the profile picker. */
    private static SharedPreferences prefsOf(Context ctx, int profileId) {
        return ctx.getSharedPreferences(PREFS + Profiles.suffix(profileId), Context.MODE_PRIVATE);
    }

    // --- which widgets -----------------------------------------------------------------------------------------------

    static List<String> shown(Context ctx) {
        String raw = prefs(ctx).getString("shown", null);
        if (raw == null) return new ArrayList<>(DEFAULT);
        List<String> out = new ArrayList<>();
        if (!raw.isEmpty()) for (String k : raw.split(",")) if (ALL.contains(k) && !out.contains(k)) out.add(k);
        return out;
    }

    static void setShown(Context ctx, List<String> keys) {
        prefs(ctx).edit().putString("shown", String.join(",", keys)).apply();
    }

    /** Turns a widget on (at the end, if there is room) or off. Returns false when the shelf is full. */
    static boolean toggle(Context ctx, String key) {
        List<String> s = shown(ctx);
        if (s.contains(key)) {
            s.remove(key);
        } else {
            if (s.size() >= MAX_SHOWN) return false;
            s.add(key);
        }
        setShown(ctx, s);
        return true;
    }

    /** Moves a shown widget one place earlier. */
    static void moveEarlier(Context ctx, String key) {
        List<String> s = shown(ctx);
        int i = s.indexOf(key);
        if (i > 0) {
            s.remove(i);
            s.add(i - 1, key);
            setShown(ctx, s);
        }
    }

    // --- notes -------------------------------------------------------------------------------------------------------

    static final class Note {
        String text;
        boolean done;

        Note(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    static List<Note> notes(Context ctx) {
        return notes(prefs(ctx));
    }

    /** How many notes a profile has not ticked off yet. */
    static int openNotesOf(Context ctx, int profileId) {
        int n = 0;
        for (Note note : notes(prefsOf(ctx, profileId))) if (!note.done) n++;
        return n;
    }

    private static List<Note> notes(SharedPreferences prefs) {
        List<Note> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("notes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Note(o.optString("t"), o.optBoolean("d")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "notes unreadable: " + e.getMessage());
        }
        return out;
    }

    static void saveNotes(Context ctx, List<Note> notes) {
        JSONArray a = new JSONArray();
        try {
            for (Note n : notes) a.put(new JSONObject().put("t", n.text).put("d", n.done));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(ctx).edit().putString("notes", a.toString()).apply();
    }

    // --- weather -----------------------------------------------------------------------------------------------------

    static final class Weather {
        final double temp;
        final double feels;
        final double high;
        final double low;
        final int code;
        final boolean day;

        Weather(double temp, double feels, double high, double low, int code, boolean day) {
            this.temp = temp;
            this.feels = feels;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
        }
    }

    static String city(Context ctx) {
        return prefs(ctx).getString("city", "");
    }

    static String cityOf(Context ctx, int profileId) {
        return prefsOf(ctx, profileId).getString("city", "");
    }

    /** A profile's last reading, for the profile picker, or null. */
    static Weather cachedWeatherOf(Context ctx, int profileId) {
        String raw = prefsOf(ctx, profileId).getString("weather", null);
        try {
            return raw == null ? null : parseWeather(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean fahrenheit(Context ctx) {
        return prefs(ctx).getBoolean("fahrenheit", Locale.getDefault().getCountry().equals("US"));
    }

    static void setFahrenheit(Context ctx, boolean f) {
        prefs(ctx).edit().putBoolean("fahrenheit", f).remove("weather").remove("weather_at").apply();
    }

    static void setCity(Context ctx, String name, double lat, double lon) {
        prefs(ctx).edit().putBoolean("use_location", false).putString("city", name)
                .putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                .remove("weather").remove("weather_at").apply();
    }

    /** The last reading, or null when there is none yet. */
    static Weather cachedWeather(Context ctx) {
        JSONObject json = cachedForecast(ctx);
        if (json == null) return null;
        try {
            return parseWeather(json);
        } catch (JSONException e) {
            return null;
        }
    }

    /** The full last reading (hourly and daily too) for the Weather app, or null. */
    static JSONObject cachedForecast(Context ctx) {
        String raw = prefs(ctx).getString("weather", null);
        try {
            return raw == null ? null : new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean weatherStale(Context ctx, long now) {
        return now - prefs(ctx).getLong("weather_at", 0) > WEATHER_TTL_MS;
    }

    /** When the last reading was taken, or 0. */
    static long weatherAt(Context ctx) {
        return prefs(ctx).getLong("weather_at", 0);
    }

    static void forceRefresh(Context ctx) {
        prefs(ctx).edit().remove("weather_at").apply();
    }

    /** Reads the weather from Open-Meteo. Network: call off the main thread. */
    static Weather fetchWeather(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        if (usesLocation(ctx)) refreshLocation(ctx);
        if (city(ctx).isEmpty()) return null;
        // One reading serves the widget and the Weather app: now, the next 24 hours and the week.
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                        + "&hourly=temperature_2m,weather_code,is_day&forecast_hours=24"
                        + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,sunrise,sunset"
                        + "&forecast_days=7&timezone=auto%s",
                p.getFloat("lat", 0), p.getFloat("lon", 0),
                fahrenheit(ctx) ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
        JSONObject json = new JSONObject(get(url));
        Weather w = parseWeather(json);
        p.edit().putString("weather", json.toString()).putLong("weather_at", System.currentTimeMillis()).apply();
        return w;
    }

    static Weather parseWeather(JSONObject json) throws JSONException {
        JSONObject cur = json.getJSONObject("current");
        JSONObject daily = json.getJSONObject("daily");
        return new Weather(cur.getDouble("temperature_2m"), cur.optDouble("apparent_temperature",
                cur.getDouble("temperature_2m")),
                daily.getJSONArray("temperature_2m_max").getDouble(0),
                        daily.getJSONArray("temperature_2m_min").getDouble(0),
                cur.optInt("weather_code", 0), cur.optInt("is_day", 1) == 1);
    }

    // --- location
    // ------------------------------------------------------------------------------------------------------

    static boolean usesLocation(Context ctx) {
        return prefs(ctx).getBoolean("use_location", false);
    }

    /** Follows the TV's own location (Wi-Fi based, from Android's location service) instead of a chosen city.
     *  Returns false when Android has no location to give. Calls the geocoder: off the main thread. */
    static boolean useLocation(Context ctx) {
        prefs(ctx).edit().putBoolean("use_location", true).apply();
        boolean ok = refreshLocation(ctx);
        if (!ok) prefs(ctx).edit().putBoolean("use_location", false).apply();
        else forceRefresh(ctx);
        return ok;
    }

    /** The newest fix Android already has, rounded to about a kilometre: nothing more precise is kept or sent. */
    static boolean refreshLocation(Context ctx) {
        android.location.LocationManager lm = ctx.getSystemService(android.location.LocationManager.class);
        android.location.Location best = null;
        for (String provider : new String[]{"fused", android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER, android.location.LocationManager.GPS_PROVIDER}) {
            try {
                android.location.Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException unavailable) {
                // permission not granted, or this provider does not exist here
            }
        }
        if (best == null) return false;
        double lat = Math.round(best.getLatitude() * 100) / 100.0, lon = Math.round(best.getLongitude() * 100) / 100.0;
        String name = placeName(ctx, lat, lon);
        prefs(ctx).edit().putString("city", name).putFloat("lat", (float) lat).putFloat("lon", (float) lon).apply();
        return true;
    }

    /** The town for a position through Android's geocoder, or "My location" when it has no answer. */
    private static String placeName(Context ctx, double lat, double lon) {
        try {
            if (android.location.Geocoder.isPresent()) {
                List<android.location.Address> a = new android.location.Geocoder(ctx,
                        Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (a != null && !a.isEmpty()) {
                    String n = a.get(0).getLocality();
                    if (n == null) n = a.get(0).getSubAdminArea();
                    if (n == null) n = a.get(0).getAdminArea();
                    if (n != null) return n;
                }
            }
        } catch (Exception noGeocoder) {
            // offline or no backend
        }
        return ctx.getString(R.string.weather_my_location);
    }

    // --- city search -----------------------------------------------------------------------------------------------

    static final class Place {
        final String name;
        final String detail;
        final double lat;
        final double lon;

        Place(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** City search through Open-Meteo's geocoder. Network: call off the main thread. */
    static List<Place> searchCity(String query) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&name="
                + URLEncoder.encode(query, "UTF-8") + "&language=" + Locale.getDefault().getLanguage();
        JSONObject json = new JSONObject(get(url));
        List<Place> out = new ArrayList<>();
        JSONArray a = json.optJSONArray("results");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            StringBuilder detail = new StringBuilder();
            for (String k : new String[]{"admin1", "country"}) {
                String v = o.optString(k, "");
                if (!v.isEmpty()) detail.append(detail.length() > 0 ? ", " : "").append(v);
            }
            out.add(new Place(o.getString("name"), detail.toString(), o.getDouble("latitude"),
                    o.getDouble("longitude")));
        }
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    // --- how conditions look
    // ---------------------------------------------------------------------------------------------

    /** WMO weather code to a word. */
    static int describe(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code <= 2) return R.string.weather_partly;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 67) return R.string.weather_rain;
        if (code >= 71 && code <= 77) return R.string.weather_snow;
        if (code >= 80 && code <= 82) return R.string.weather_showers;
        if (code == 85 || code == 86) return R.string.weather_snow;
        if (code >= 95) return R.string.weather_storm;
        return R.string.weather_cloudy;
    }

    /** A symbol for the condition (the TV's emoji font draws it). */
    static String glyph(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        if (code >= 51) return "🌧️";
        return "☁️";
    }

    /** The Weather app's sky: top and bottom colours for the condition and the time of day. */
    static int[] sky(int code, boolean day) {
        if (!day) return new int[]{0xFF0B1026, 0xFF1E1B4B};
        if (code == 0) return new int[]{0xFF1D4ED8, 0xFF60A5FA};
        if (code <= 2) return new int[]{0xFF1E40AF, 0xFF7DA2D6};
        if (code >= 95) return new int[]{0xFF1E1B2E, 0xFF4C1D95};
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new int[]{0xFF475569, 0xFF94A3B8};
        if (code >= 51) return new int[]{0xFF1F2937, 0xFF475569};
        return new int[]{0xFF334155, 0xFF64748B};
    }

    /** A colour for the card's accent, by condition. */
    static int tint(int code, boolean day) {
        if (!day) return 0xFF4F46E5;
        if (code == 0) return 0xFFF59E0B;
        if (code <= 3) return 0xFF60A5FA;
        if (code >= 95) return 0xFF7C3AED;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return 0xFFBAE6FD;
        return 0xFF3B82F6;
    }

    // --- continue watching
    // ---------------------------------------------------------------------------------------------

    static final class Watched {
        final String title;
        final String subtitle;
        final String pkg;
        final long at;

        Watched(String title, String subtitle, String pkg, long at) {
            this.title = title;
            this.subtitle = subtitle;
            this.pkg = pkg;
            this.at = at;
        }
    }

    static List<Watched> watching(Context ctx) {
        return watching(prefs(ctx));
    }

    static List<Watched> watchingOf(Context ctx, int profileId) {
        return watching(prefsOf(ctx, profileId));
    }

    private static List<Watched> watching(SharedPreferences prefs) {
        List<Watched> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("watching", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Watched(o.optString("t"), o.optString("s"), o.optString("p"), o.optLong("at")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "history unreadable: " + e.getMessage());
        }
        return out;
    }

    /** Puts a title at the front of the active profile's history (the same title moves up instead of repeating). */
    static void recordWatching(Context ctx, String title, String subtitle, String pkg) {
        if (title == null || pkg == null) return;
        String t = title.trim();
        if (t.isEmpty()) return;
        List<Watched> list = watching(ctx);
        // Apps report playback state many times a minute: the title already in front needs no write.
        if (!list.isEmpty() && list.get(0).title.equals(t) && list.get(0).pkg.equals(pkg)) return;
        list.removeIf(w -> w.title.equals(t) && w.pkg.equals(pkg));
        list.add(0, new Watched(t, subtitle == null ? "" : subtitle.trim(), pkg, System.currentTimeMillis()));
        while (list.size() > MAX_WATCHING) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        try {
            for (Watched w : list) a.put(new JSONObject().put("t", w.title).put("s", w.subtitle).put("p",
                    w.pkg).put("at", w.at));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(ctx).edit().putString("watching", a.toString()).apply();
    }

    static void clearWatching(Context ctx) {
        prefs(ctx).edit().remove("watching").apply();
    }
}
