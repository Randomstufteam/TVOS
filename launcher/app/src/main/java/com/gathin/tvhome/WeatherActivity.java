package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * The built-in Weather app: the profile's place (a chosen city, or the TV's own location), now in large type over a
 * sky that follows the conditions, the day's facts, a drawn graph of the next 12 hours and the week. Opened from the
 * Weather widget, from Settings and as
 * a tile. Reads Open-Meteo only while it is on screen and the last reading is over 30 minutes old.
 */
public final class WeatherActivity extends Activity {
    private FrameLayout root;
    private LinearLayout content;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.worker();
    private boolean loading;
    /** A forced refresh asked for while one was running (a new unit or city): the running one read the old settings. */
    private boolean again;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        refresh(false);
    }

    private void refresh(boolean force) {
        if (loading) {
            again |= force;
            return;
        }
        if (Widgets.city(this).isEmpty()) return;
        if (!force && !Widgets.weatherStale(this, System.currentTimeMillis())) return;
        loading = true;
        io.execute(() -> {
            boolean ok;
            try {
                ok = Widgets.fetchWeather(getApplicationContext()) != null;
            } catch (Exception e) {
                ok = false;
            }
            boolean done = ok;
            main.post(() -> {
                loading = false;
                if (isDestroyed()) return;
                if (again) {
                    again = false;
                    refresh(true);
                    return;
                }
                if (!done) Toast.makeText(this, R.string.widget_offline, Toast.LENGTH_SHORT).show();
                render();
            });
        });
    }

    // --- screen ------------------------------------------------------------------------------------------------------

    private void render() {
        View focused = getCurrentFocus();
        Object focusTag = focused != null ? focused.getTag() : null;
        root.removeAllViews();
        JSONObject f = Widgets.cachedForecast(this);
        Widgets.Weather now = Widgets.cachedWeather(this);
        int[] sky = now != null ? Widgets.sky(now.code, now.day) : new int[]{0xFF0F172A, 0xFF1E293B};
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{sky[0], sky[1],
                0xFF05060A});
        root.setBackground(bg);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 64);
        content.setPadding(side, Ui.dp(this, 34), side, Ui.dp(this, 26));
        root.addView(content, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // Top row: the place, and the pills.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String city = Widgets.city(this);
        LinearLayout place = new LinearLayout(this);
        place.setOrientation(LinearLayout.VERTICAL);
        TextView cityText = Ui.text(this, city.isEmpty() ? getString(R.string.widget_weather) : city, 30,
                R.string.font_light, Ui.TEXT);
        place.addView(cityText);
        String sub = Widgets.usesLocation(this) ? getString(R.string.weather_following_location)
                : LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()));
        long at = Widgets.weatherAt(this);
        if (at > 0) sub += "  ·  " + getString(R.string.weather_updated,
                android.text.format.DateUtils.getRelativeTimeSpanString(
                at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        TextView subText = Ui.text(this, sub, 14, R.string.font_regular, 0xB3FFFFFF);
        subText.setPadding(0, Ui.dp(this, 6), 0, 0);
        place.addView(subText);
        top.addView(place, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        top.addView(pill(getString(R.string.weather_use_location), "loc", v -> useLocation()));
        top.addView(pill(getString(R.string.weather_change_city), "city", v -> chooseCity()));
        boolean fahr = Widgets.fahrenheit(this);
        top.addView(pill(fahr ? "°F" : "°C", "unit", v -> {
            Widgets.setFahrenheit(this, !fahr);
            render();
            refresh(true);
        }));
        content.addView(top);

        boolean hasData = false;
        if (city.isEmpty()) {
            setupPrompt();
        } else if (now == null || f == null) {
            loadingText();
        } else {
            try {
                body(f, now, fahr);
                hasData = true;
            } catch (RuntimeException unexpected) {
                // A reading in a shape this screen doesn't know (a bad date, say): drop it and read again.
                while (content.getChildCount() > 1) content.removeViewAt(1);
                Widgets.forceRefresh(this);
                loadingText();
            }
        }
        // The screen's parts glide in one after another when it opens, and again when the forecast first arrives;
        // a later refresh (a new reading, the unit) just changes the numbers in place.
        if (!opened || (hasData && !dataShown)) {
            for (int i = 0; i < content.getChildCount(); i++) Motion.enter(content.getChildAt(i), i, 0, 14);
        }
        opened = true;
        dataShown |= hasData;
        restoreFocus(focusTag);
    }

    /** render() has run once; a forecast has been on screen. */
    private boolean opened, dataShown;

    private void loadingText() {
        TextView t = Ui.text(this, getString(R.string.widget_loading), 22, R.string.font_light, Ui.TEXT_DIM);
        t.setPadding(0, Ui.dp(this, 80), 0, 0);
        content.addView(t);
    }

    private void setupPrompt() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 90), 0, 0);
        box.addView(Ui.text(this, getString(R.string.weather_setup_title), 40, R.string.font_extralight, Ui.TEXT));
        TextView t = Ui.text(this, getString(R.string.weather_setup_body), 17, R.string.font_light, Ui.TEXT_DIM);
        t.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(t);
        content.addView(box);
    }

    private void body(JSONObject f, Widgets.Weather now, boolean fahr) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1);
        rlp.topMargin = Ui.dp(this, 10);
        content.addView(row, rlp);

        // Left: now, the day's facts, the next hours.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout big = new LinearLayout(this);
        big.setGravity(Gravity.CENTER_VERTICAL);
        TextView temp = Ui.text(this, Math.round(now.temp) + "°", 84, R.string.font_extralight, Ui.TEXT);
        temp.setLetterSpacing(-0.04f);
        big.addView(temp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(Ui.dp(this, 20), 0, 0, 0);
        words.addView(Ui.text(this, Widgets.glyph(now.code, now.day) + "  " + getString(Widgets.describe(now.code)), 22,
                R.string.font_light, Ui.TEXT));
        TextView hilo = Ui.text(this, getString(R.string.widget_weather_hilo, Math.round(now.high),
                Math.round(now.low)),
                16, R.string.font_regular, 0xCCFFFFFF);
        hilo.setPadding(0, Ui.dp(this, 8), 0, 0);
        words.addView(hilo);
        String sun = sunTimes(f);
        if (sun != null) {
            TextView st = Ui.text(this, sun, 13, R.string.font_regular, 0xB3FFFFFF);
            st.setPadding(0, Ui.dp(this, 6), 0, 0);
            words.addView(st);
        }
        big.addView(words);
        left.addView(big);
        JSONObject cur = f.optJSONObject("current");
        LinearLayout facts = new LinearLayout(this);
        facts.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);
        facts.addView(fact(getString(R.string.weather_feels), Math.round(now.feels) + "°"));
        if (cur != null && cur.has("relative_humidity_2m")) facts.addView(fact(getString(R.string.weather_humidity),
                cur.optInt("relative_humidity_2m") + "%"));
        if (cur != null && cur.has("wind_speed_10m")) facts.addView(fact(getString(R.string.weather_wind),
                Math.round(cur.optDouble("wind_speed_10m")) + (fahr ? " mph" : " km/h")));
        JSONObject today = f.optJSONObject("daily");
        JSONArray rain = today != null ? today.optJSONArray("precipitation_probability_max") : null;
        if (rain != null && rain.length() > 0) facts.addView(fact(getString(R.string.weather_rain_chance),
                rain.optInt(0) + "%"));
        left.addView(facts);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1);
        hlp.topMargin = Ui.dp(this, 14);
        left.addView(hourly(f), hlp);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, Ui.MATCH, 1);
        llp.setMarginEnd(Ui.dp(this, 24));
        row.addView(left, llp);

        // Right: the week.
        row.addView(week(f), new LinearLayout.LayoutParams(Ui.dp(this, 340), Ui.MATCH));
    }

    /** "Sunrise 7:32  ·  Sunset 18:41" in the TV's clock format, or null when the reading has neither. */
    private String sunTimes(JSONObject f) {
        JSONObject d = f.optJSONObject("daily");
        JSONArray up = d != null ? d.optJSONArray("sunrise") : null, down = d != null ? d.optJSONArray("sunset") : null;
        if (up == null || down == null || up.length() == 0 || down.length() == 0) return null;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(android.text.format.DateFormat.is24HourFormat(this)
                ? "HH:mm" : "h:mm a", Locale.getDefault());
        return getString(R.string.weather_sunrise) + " " + LocalDateTime.parse(up.optString(0)).format(fmt) + "  ·  "
                + getString(R.string.weather_sunset) + " " + LocalDateTime.parse(down.optString(0)).format(fmt);
    }

    private View fact(String label, String value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, 0, Ui.dp(this, 26), 0);
        TextView l = Ui.text(this, label, 10, R.string.font_medium, 0x99FFFFFF);
        l.setAllCaps(true);
        l.setLetterSpacing(0.16f);
        c.addView(l);
        TextView v = Ui.text(this, value, 20, R.string.font_light, Ui.TEXT);
        v.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(v);
        return c;
    }

    /** The soft pill behind today's row and the "Now" column. */
    private GradientDrawable highlight() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1FFFFFFF);
        g.setCornerRadius(Ui.dp(this, 12));
        return g;
    }

    private LinearLayout glassBox() {
        LinearLayout box = new LinearLayout(this);
        box.setBackground(Ui.sheen(this, 22, 0x33FFFFFF, 0x12FFFFFF, 0x38FFFFFF, Math.max(1, Ui.dp(this, 1))));
        return box;
    }

    private View week(JSONObject f) {
        LinearLayout box = glassBox();
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 10));
        TextView title = Ui.text(this, getString(R.string.weather_week), 11, R.string.font_medium, 0x99FFFFFF);
        title.setAllCaps(true);
        title.setLetterSpacing(0.16f);
        title.setPadding(0, 0, 0, Ui.dp(this, 6));
        box.addView(title);
        JSONObject d = f.optJSONObject("daily");
        if (d == null) return box;
        JSONArray days = d.optJSONArray("time"), hi = d.optJSONArray("temperature_2m_max"),
                lo = d.optJSONArray("temperature_2m_min"),
                code = d.optJSONArray("weather_code"), rain = d.optJSONArray("precipitation_probability_max");
        if (days == null || hi == null || lo == null) return box;
        double weekLo = Double.MAX_VALUE, weekHi = -Double.MAX_VALUE;
        for (int i = 0; i < days.length(); i++) {
            weekLo = Math.min(weekLo, lo.optDouble(i));
            weekHi = Math.max(weekHi, hi.optDouble(i));
        }
        for (int i = 0; i < days.length(); i++) {
            LocalDate date = LocalDate.parse(days.optString(i));
            String name = i == 0 ? getString(R.string.weather_today)
                    : date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            if (i == 0) r.setBackground(highlight());   // today stands out from the week
            r.addView(Ui.text(this, name, 16, R.string.font_regular, Ui.TEXT), new LinearLayout.LayoutParams(Ui.dp(this,
                    56), Ui.WRAP));
            int c = code != null ? code.optInt(i) : 3;
            r.addView(Ui.text(this, Widgets.glyph(c, true), 16, R.string.font_regular, Ui.TEXT),
                    new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.WRAP));
            int chance = rain != null ? rain.optInt(i) : 0;
            TextView pc = Ui.text(this, chance >= 20 ? chance + "%" : "", 12, R.string.font_regular, 0xFF7DD3FC);
            r.addView(pc, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.WRAP));
            r.addView(Ui.text(this, Math.round(lo.optDouble(i)) + "°", 15, R.string.font_light, 0xB3FFFFFF),
                    new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.WRAP));
            r.addView(rangeBar(weekLo, weekHi, lo.optDouble(i), hi.optDouble(i)), new LinearLayout.LayoutParams(0,
                    Ui.dp(this, 5), 1));
            TextView h = Ui.text(this, Math.round(hi.optDouble(i)) + "°", 15, R.string.font_regular, Ui.TEXT);
            h.setGravity(Gravity.END);
            r.addView(h, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.WRAP));
            box.addView(r);
        }
        return box;
    }

    /** The day's range on the week's scale, cool to warm, as Apple's Weather draws it. */
    private View rangeBar(double weekLo, double weekHi, double lo, double hi) {
        FrameLayout track = new FrameLayout(this);
        GradientDrawable t = new GradientDrawable();
        t.setColor(0x26FFFFFF);
        t.setCornerRadius(Ui.dp(this, 3));
        track.setBackground(t);
        View fill = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF67E8F9,
                0xFFFDE047, 0xFFFB923C});
        g.setCornerRadius(Ui.dp(this, 3));
        fill.setBackground(g);
        double span = Math.max(1, weekHi - weekLo);
        track.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            int start = (int) (w * (lo - weekLo) / span), end = (int) (w * (hi - weekLo) / span);
            if (lp.leftMargin != start || lp.width != Math.max(Ui.dp(this, 6), end - start)) {
                lp.leftMargin = start;
                lp.width = Math.max(Ui.dp(this, 6), end - start);
                fill.post(fill::requestLayout);
            }
        });
        track.addView(fill, new FrameLayout.LayoutParams(0, Ui.MATCH));
        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(0, Ui.dp(this, 5), 1);
        m.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        track.setLayoutParams(m);
        return track;
    }

    private View hourly(JSONObject f) {
        LinearLayout box = glassBox();
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView title = Ui.text(this, getString(R.string.weather_next_hours), 10, R.string.font_medium, 0x99FFFFFF);
        title.setAllCaps(true);
        title.setLetterSpacing(0.16f);
        box.addView(title);
        JSONObject h = f.optJSONObject("hourly");
        JSONArray times = h != null ? h.optJSONArray("time") : null, temps = h != null
                ? h.optJSONArray("temperature_2m") : null;
        if (times == null || temps == null) return box;
        JSONArray codes = h.optJSONArray("weather_code"), days = h.optJSONArray("is_day");
        int n = Math.min(12, Math.min(times.length(), temps.length()));
        boolean twelve = !android.text.format.DateFormat.is24HourFormat(this);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(twelve ? "h a" : "HH:00", Locale.getDefault());
        float[] values = new float[n];
        String[] labels = new String[n], glyphs = new String[n];
        for (int i = 0; i < n; i++) {
            values[i] = (float) temps.optDouble(i);
            labels[i] = i == 0 ? getString(R.string.weather_now) : LocalDateTime.parse(times.optString(i)).format(fmt);
            glyphs[i] = Widgets.glyph(codes != null ? codes.optInt(i) : 3, days == null || days.optInt(i) == 1);
        }
        box.addView(new TempGraph(this, values, labels, glyphs), new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        return box;
    }

    /** The next hours as one drawn curve: the symbol on top, the temperature over the line, the hour under it. */
    private static final class TempGraph extends View {
        private final float[] values;
        private final String[] labels, glyphs;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path curve = new Path();
        private final Path fill = new Path();
        private final float[] xs, ys;
        private final float small, glyphSize;

        TempGraph(Context c, float[] values, String[] labels, String[] glyphs) {
            super(c);
            this.values = values;
            this.labels = labels;
            this.glyphs = glyphs;
            xs = new float[values.length];
            ys = new float[values.length];
            small = Ui.dp(c, 11);
            glyphSize = Ui.dp(c, 14);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(0xE6FFFFFF);
            dot.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(UiFont.get(c, R.string.font_regular));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            int n = values.length;
            if (n == 0 || w == 0 || h == 0) return;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (float v : values) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            float top = glyphSize + small * 2.4f, bottom = h - small * 1.8f;
            float span = Math.max(1f, hi - lo);
            float cell = w / (float) n;
            curve.reset();
            for (int i = 0; i < n; i++) {
                xs[i] = cell * i + cell / 2;
                ys[i] = bottom - (bottom - top) * (values[i] - lo) / span;
                if (i == 0) {
                    curve.moveTo(xs[i], ys[i]);
                } else {
                    float mid = (xs[i - 1] + xs[i]) / 2;
                    curve.cubicTo(mid, ys[i - 1], mid, ys[i], xs[i], ys[i]);
                }
            }
            fill.set(curve);
            fill.lineTo(xs[n - 1], h);
            fill.lineTo(xs[0], h);
            fill.close();
            area.setShader(new LinearGradient(0, top, 0, h, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = values.length;
            if (n == 0) return;
            c.drawPath(fill, area);
            c.drawPath(curve, line);
            for (int i = 0; i < n; i++) {
                c.drawCircle(xs[i], ys[i], Ui.dp(getContext(), i == 0 ? 4 : 2.5f), dot);
                text.setTextSize(glyphSize);
                text.setColor(0xFFFFFFFF);
                c.drawText(glyphs[i], xs[i], glyphSize, text);
                text.setTextSize(small);
                c.drawText(Math.round(values[i]) + "°", xs[i], ys[i] - small * 0.7f, text);
                text.setColor(i == 0 ? 0xFFFFFFFF : 0xB3FFFFFF);
                c.drawText(labels[i], xs[i], getHeight() - small * 0.3f, text);
            }
        }
    }

    private TextView pill(String text, String tag, View.OnClickListener click) {
        TextView p = Ui.pill(this, text, click);
        p.setTag(tag);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 42));
        lp.setMarginStart(Ui.dp(this, 10));
        p.setLayoutParams(lp);
        return p;
    }

    private void restoreFocus(Object tag) {
        View target = tag != null ? root.findViewWithTag(tag) : null;
        if (target == null) target = root.findViewWithTag(Widgets.city(this).isEmpty() ? "loc" : "city");
        if (target != null) {
            View t = target;
            t.post(t::requestFocus);
        }
    }

    // --- actions -----------------------------------------------------------------------------------------------------

    private void useLocation() {
        io.execute(() -> {
            boolean ok;
            try {
                ok = Widgets.useLocation(getApplicationContext());
            } catch (RuntimeException unavailable) {
                ok = false;   // no location service on this firmware: an exception here would take the app down
            }
            boolean found = ok;
            main.post(() -> {
                if (isDestroyed()) return;
                if (!found) {
                    Toast.makeText(this, R.string.weather_location_none, Toast.LENGTH_LONG).show();
                    return;
                }
                render();
                refresh(true);
            });
        });
    }

    private void chooseCity() {
        Ui.askText(this, getString(R.string.widget_weather_city_prompt), Widgets.usesLocation(this)
                ? "" : Widgets.city(this), 40,
                query -> io.execute(() -> {
                    List<Widgets.Place> found;
                    try {
                        found = Widgets.searchCity(query);
                    } catch (Exception e) {
                        main.post(() -> {
                            if (!isDestroyed()) Toast.makeText(this, R.string.widget_offline, Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    main.post(() -> {
                        if (isDestroyed()) return;
                        if (found.isEmpty()) {
                            Toast.makeText(this, R.string.widget_weather_no_city, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Ui.ListDialog d = Ui.listDialog(this, getString(R.string.widget_weather_pick));
                        for (Widgets.Place p : found) {
                            d.add(p.name, TextUtils.isEmpty(p.detail) ? null : p.detail, () -> {
                                Widgets.setCity(this, p.name, p.lat, p.lon);
                                render();
                                refresh(true);
                            });
                        }
                        d.show();
                    });
                }));
    }

    /** Back returns to whatever opened Weather (Home's widget, Settings). Opened as a tile it is a task of its own:
     *  then Back goes to this launcher's Home, never to what Android keeps as Home underneath (Google TV's). */
    @Override
    public void onBackPressed() {
        if (isTaskRoot()) HomeOpen.open(this);
        finish();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        Ui.closeDialogs(this);
        super.onDestroy();
    }
}
