package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * The top-shelf widgets: up to four glass cards in a 2 x 2 grid right of the focused app's name, chosen per profile
 * in Settings > Widgets. Up from the dock reaches the card above, Up from the top row goes on to the top bar, Down
 * from the bottom row comes back to the dock. Cards are rebuilt (cheaply, no images but app icons) when Home
 * resumes and once a minute with the clock; only the weather reads the network, at most every 30 minutes.
 */
final class WidgetPanel {
    interface Host {
        void widgetsUp(boolean rightColumn);

        void widgetsDown(boolean rightColumn);

        void widgetFocused();
    }

    private final Activity activity;
    private final Host host;
    private final LinearLayout panel;
    private final List<View> cards = new ArrayList<>();
    /** The Recently opened card: four icons on the card, up to six in its list. */
    private static final int RECENT_MAX = 6;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    /** Small copies of the cards' app icons: the minute's rebuild must not decode them again. */
    private final java.util.Map<String, Drawable> icons = new java.util.HashMap<>();
    /** No weather service: try again in a while, not every minute. */
    private static final long RETRY_MS = 5 * 60_000L;
    private long lastTry = -RETRY_MS;
    private boolean offline;
    private boolean fetching;
    private boolean closed;
    private boolean shown = true;

    WidgetPanel(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.host = host;
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        // Room around the cards for the focused one's lift: Home's root clips each child to its own bounds.
        int room = Ui.dp(activity, 14);
        panel.setPadding(room, room, room, room);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = Ui.dp(activity, 112) - room;
        lp.setMarginEnd(Ui.dp(activity, 56) - room);
        root.addView(panel, lp);
    }

    boolean hasCards() {
        return !cards.isEmpty() && shown;
    }

    /** Fades the cards with the top shelf: they belong to the dock's view, not the grid below it. */
    void setShown(boolean show) {
        if (show == shown) return;
        shown = show;
        panel.animate().cancel();
        float lift = show ? 0 : -Ui.dp(activity, 12);
        if (!Motion.enabled) {
            panel.setAlpha(show ? 1f : 0f);
            panel.setTranslationY(lift);
        } else {
            panel.animate().alpha(show ? 1f : 0f).translationY(lift).setStartDelay(0)
                    .setDuration(show ? Motion.NORMAL_MS : Motion.EXIT_MS).setInterpolator(Motion.easeOut()).start();
        }
        for (View c : cards) c.setFocusable(show);
    }

    /** Up from the dock: the bottom-row card nearest the tile's side of the screen. */
    void focusFromBelow(boolean rightHalf) {
        if (cards.isEmpty()) return;
        int rows = (cards.size() + 1) / 2;
        int index = (rows - 1) * 2 + (rightHalf ? 1 : 0);
        if (index >= cards.size()) index = cards.size() - 1;
        cards.get(index).requestFocus();
    }

    /** What the cards were last built from; the minute's refresh skips the rebuild when nothing changed. */
    private int builtFrom;

    /** What the cards show: the profile's widget data (shown, notes, weather, history, place), its recent apps and
     *  the date. Reading it costs a map copy; rebuilding costs views, icons and JSON, so compare first. */
    private int contentSignature() {
        return java.util.Objects.hash(Profiles.currentId(activity), Widgets.prefs(activity).getAll(),
                LastOpened.recent(activity, RECENT_MAX), LocalDate.now());
    }

    void rebuild() {
        int signature = contentSignature();
        if (signature == builtFrom && !cards.isEmpty()) {
            maybeFetchWeather();
            return;
        }
        builtFrom = signature;
        // Cards arriving where there were none (Home opening, a profile's first view) pop in one after another; a
        // card whose content changed is replaced in place, so the minute's refresh never moves the shelf.
        boolean arriving = cards.isEmpty();
        int focused = -1;
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).hasFocus()) focused = i;
        panel.removeAllViews();
        cards.clear();
        List<String> keys = Widgets.shown(activity);
        LinearLayout row = null;
        for (int i = 0; i < keys.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Ui.dp(activity, 12);
                panel.addView(row, rlp);
            }
            View card = card(keys.get(i));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(activity, 186), Ui.dp(activity, 124));
            if (i % 2 == 1) clp.setMarginStart(Ui.dp(activity, 12));
            row.addView(card, clp);
            int index = i;
            int count = keys.size();
            card.setOnKeyListener((v, code, e) -> navigate(index, count, code, e));
            card.setFocusable(shown);
            cards.add(card);
            if (arriving && shown) Motion.popIn(card, i + 1);
        }
        // The minute's refresh must not knock focus off the card being looked at.
        if (focused >= 0 && !cards.isEmpty()) cards.get(Math.min(focused, cards.size() - 1)).requestFocus();
        maybeFetchWeather();
    }

    private boolean navigate(int index, int count, int code, KeyEvent e) {
        boolean right = index % 2 == 1;
        boolean topRow = index < 2;
        boolean bottomRow = index >= ((count - 1) / 2) * 2;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!topRow) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) host.widgetsUp(right);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!bottomRow) {
                    if (index + 2 >= count) {   // odd count: the lone card below is the one to go to
                        if (e.getAction() == KeyEvent.ACTION_DOWN) cards.get(count - 1).requestFocus();
                        return true;
                    }
                    return false;
                }
                if (e.getAction() == KeyEvent.ACTION_DOWN) host.widgetsDown(right);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return !right;   // the left column is the panel's edge: never fall onto the shelf's text
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return right || index + 1 >= count;
            default:
                return false;
        }
    }

    // --- cards -----------------------------------------------------------------------------------------------------

    /** The card's accent, for its label dot; kept while the card is being built. */
    private int accent;

    private FrameLayout frame(int accent) {
        return frame(accent, null);
    }

    /**
     * A card's glass, washed from its top corner with the widget's colour so the four read apart at a glance; with
     * sky (the weather card), the card is that sky instead, as a weather app's own widget is.
     */
    private FrameLayout frame(int accent, int[] sky) {
        this.accent = accent;
        FrameLayout f = new FrameLayout(activity);
        f.setClickable(true);
        f.setFocusable(true);
        float radius = Ui.dp(activity, 24);
        GradientDrawable wash;
        if (sky != null) {
            // Dark mode keeps the sky but dims it, so the card does not light the room.
            int a = DarkMode.active(activity) ? 0x80 : 0xF2;
            wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Ui.withAlpha(sky[0], a), Ui.withAlpha(sky[1], a)});
        } else {
            wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Ui.withAlpha(accent, 0x4D), Ui.withAlpha(accent, 0x0A), 0x00000000});
        }
        wash.setCornerRadius(radius);
        int hairline = Math.max(1, Ui.dp(activity, 1));
        Drawable base = Ui.sheen(activity, 24, 0xC21A1C24, 0xD60D0E14, 0x1FFFFFFF, hairline);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, new LayerDrawable(new Drawable[]{
                sky != null ? wash : base, sky != null ? new ColorDrawable(0) : wash,
                Ui.sheen(activity, 24, 0x1FFFFFFF, 0x0DFFFFFF, 0xF2FFFFFF, Ui.dp(activity, 2))}));
        bg.addState(new int[]{}, new LayerDrawable(new Drawable[]{sky != null ? wash : base,
                sky != null ? Ui.sheen(activity, 24, 0x00000000, 0x00000000, 0x33FFFFFF, hairline) : wash}));
        f.setBackground(bg);
        int pad = Ui.dp(activity, 18);
        f.setPadding(pad, Ui.dp(activity, 15), pad, Ui.dp(activity, 14));
        f.setOnFocusChangeListener((v, has) -> {
            Motion.focus(v, has, 1.06f, Ui.dp(activity, 8));
            if (has) host.widgetFocused();
        });
        return f;
    }

    /** The card's label: a dot in its colour and small caps. */
    private View kicker(String s) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(activity);
        dot.setBackground(Ui.circle(accent));
        int d = Ui.dp(activity, 6);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(d, d);
        dl.setMarginEnd(Ui.dp(activity, 8));
        row.addView(dot, dl);
        TextView t = Ui.text(activity, s, 11, R.string.font_medium, 0x99FFFFFF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setAllCaps(true);   // after setSingleLine, which would otherwise replace the caps transformation
        t.setLetterSpacing(0.16f);
        row.addView(t);
        return row;
    }

    /** An empty card: what goes here, and how to start. */
    private void empty(LinearLayout col, int text, int hint) {
        TextView t = Ui.text(activity, activity.getString(text), 16, R.string.font_regular, Ui.TEXT);
        t.setMaxLines(2);
        col.addView(spaced(t, 12));
        if (hint != 0) col.addView(spaced(Ui.text(activity, activity.getString(hint), 12, R.string.font_light, Ui.TEXT_FAINT), 6));
    }

    private View card(String key) {
        switch (key) {
            case Widgets.WEATHER: return weatherCard();
            case Widgets.CALENDAR: return calendarCard();
            case Widgets.WATCHING: return watchingCard();
            case Widgets.NOTES: return notesCard();
            default: return recentCard();
        }
    }

    private View weatherCard() {
        Widgets.Weather w = Widgets.cachedWeather(activity);
        String city = Widgets.city(activity);
        FrameLayout f = w != null ? frame(Widgets.tint(w.code, w.day), Widgets.sky(w.code, w.day)) : frame(0xFF60A5FA);
        LinearLayout col = column();
        col.addView(kicker(city.isEmpty() ? activity.getString(R.string.widget_weather) : city));
        if (city.isEmpty()) {
            empty(col, R.string.widget_weather_set_city, R.string.widget_press_ok);
        } else if (w == null) {
            empty(col, offline ? R.string.widget_weather_retry : R.string.widget_loading, 0);
        } else {
            // Temperature and the condition's symbol share a row, the rest is one line: it all fits the card.
            LinearLayout now = new LinearLayout(activity);
            now.setGravity(Gravity.CENTER_VERTICAL);
            TextView temp = Ui.text(activity, Math.round(w.temp) + "°", 38, R.string.font_extralight, Ui.TEXT);
            temp.setLetterSpacing(-0.03f);
            now.addView(temp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            now.addView(Ui.text(activity, Widgets.glyph(w.code, w.day), 26, R.string.font_regular, Ui.TEXT));
            col.addView(spaced(now, 6));
            TextView line = Ui.text(activity, activity.getString(Widgets.describe(w.code)) + "  ·  "
                    + activity.getString(R.string.widget_weather_hilo, Math.round(w.high), Math.round(w.low)),
                    12, R.string.font_regular, Ui.TEXT_DIM);
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(spaced(line, 6));
        }
        f.addView(col);
        f.setOnClickListener(v -> activity.startActivity(new Intent(activity, WeatherActivity.class)));
        return f;
    }

    private View calendarCard() {
        LocalDate today = LocalDate.now();
        FrameLayout f = frame(0xFFEF4444);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = column();
        left.addView(kicker(today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault())));
        TextView day = Ui.text(activity, String.valueOf(today.getDayOfMonth()), 40, R.string.font_extralight, Ui.TEXT);
        left.addView(spaced(day, 4));
        left.addView(spaced(Ui.text(activity, today.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                + " " + today.getYear(), 12, R.string.font_light, Ui.TEXT_DIM), 2));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Wide enough for two-digit days in seven columns, today's disc around them included.
        row.addView(new MonthView(activity, today), new LinearLayout.LayoutParams(Ui.dp(activity, 96), Ui.dp(activity, 92)));
        f.addView(row);
        return f;
    }

    private View watchingCard() {
        List<Widgets.Watched> list = Widgets.watching(activity);
        FrameLayout f = frame(0xFF8B5CF6);
        LinearLayout col = column();
        col.addView(kicker(activity.getString(R.string.widget_watching_short)));
        if (list.isEmpty()) {
            empty(col, R.string.widget_watching_empty, R.string.widget_watching_hint);
        } else {
            Widgets.Watched w = list.get(0);
            TextView t = Ui.text(activity, w.title, 17, R.string.font_regular, Ui.TEXT);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(spaced(t, 10));
            LinearLayout app = new LinearLayout(activity);
            app.setGravity(Gravity.CENTER_VERTICAL);
            Drawable icon = icon(w.pkg);
            if (icon != null) {
                ImageView iv = new ImageView(activity);
                iv.setImageDrawable(icon);
                app.addView(iv, new LinearLayout.LayoutParams(Ui.dp(activity, 16), Ui.dp(activity, 16)));
            }
            TextView name = Ui.text(activity, label(w.pkg) + (list.size() > 1 ? "  ·  +" + (list.size() - 1) : ""),
                    12, R.string.font_light, Ui.TEXT_DIM);
            name.setSingleLine(true);
            name.setPadding(icon != null ? Ui.dp(activity, 6) : 0, 0, 0, 0);
            app.addView(name);
            col.addView(spaced(app, 8));
        }
        f.addView(col);
        f.setOnClickListener(v -> openWatching());
        return f;
    }

    private View notesCard() {
        List<Widgets.Note> notes = Widgets.notes(activity);
        FrameLayout f = frame(0xFFF59E0B);
        LinearLayout col = column();
        col.addView(kicker(activity.getString(R.string.widget_notes)));
        // Open notes first; done ones stay in the list (OK opens it) but no longer fill the card.
        List<Widgets.Note> open = new ArrayList<>();
        for (Widgets.Note n : notes) if (!n.done) open.add(n);
        if (notes.isEmpty()) {
            empty(col, R.string.widget_notes_empty, R.string.widget_press_ok);
        } else if (open.isEmpty()) {
            empty(col, R.string.widget_notes_all_done, R.string.widget_press_ok);
        } else {
            int shownCount = Math.min(3, open.size());
            for (int i = 0; i < shownCount; i++) {
                TextView t = Ui.text(activity, "○  " + open.get(i).text, 14, R.string.font_regular, Ui.TEXT);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(spaced(t, i == 0 ? 10 : 6));
            }
            if (open.size() > shownCount) {
                col.addView(spaced(Ui.text(activity, activity.getString(R.string.widget_more, open.size() - shownCount),
                        12, R.string.font_light, Ui.TEXT_DIM), 6));
            }
        }
        f.addView(col);
        f.setOnClickListener(v -> openNotes());
        return f;
    }

    private View recentCard() {
        List<String> recent = recentApps();
        FrameLayout f = frame(0xFF10B981);
        LinearLayout col = column();
        col.addView(kicker(activity.getString(R.string.widget_recent)));
        if (recent.isEmpty()) {
            empty(col, R.string.widget_recent_empty, 0);
        } else {
            LinearLayout icons = new LinearLayout(activity);
            for (int i = 0; i < Math.min(4, recent.size()); i++) {
                Drawable d = icon(recent.get(i));
                if (d == null) continue;
                ImageView iv = new ImageView(activity);
                iv.setImageDrawable(d);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(activity, 30), Ui.dp(activity, 30));
                ilp.setMarginEnd(Ui.dp(activity, 8));
                icons.addView(iv, ilp);
            }
            col.addView(spaced(icons, 14));
            TextView last = Ui.text(activity, label(recent.get(0)), 13, R.string.font_light, Ui.TEXT_DIM);
            last.setSingleLine(true);
            col.addView(spaced(last, 10));
        }
        f.addView(col);
        f.setOnClickListener(v -> openRecent(recent));
        return f;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private View spaced(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, topDp);
        v.setLayoutParams(lp);
        return v;
    }

    // --- actions ---------------------------------------------------------------------------------------------------

    private void maybeFetchWeather() {
        if (closed || fetching || !Widgets.shown(activity).contains(Widgets.WEATHER) || Widgets.city(activity).isEmpty()
                || !Widgets.weatherStale(activity, System.currentTimeMillis())
                || android.os.SystemClock.elapsedRealtime() - lastTry < RETRY_MS) return;
        lastTry = android.os.SystemClock.elapsedRealtime();
        fetching = true;
        executor().execute(() -> {
            boolean ok;
            try {
                // The application context: a download in flight must not keep a closed Home alive.
                ok = Widgets.fetchWeather(activity.getApplicationContext()) != null;
            } catch (Exception e) {
                ok = false;
            }
            boolean updated = ok;
            main.post(() -> {
                fetching = false;
                if (closed) return;
                boolean changed = updated || !offline;
                offline = !updated;
                if (changed) {
                    builtFrom = 0;   // the card says so even when the stored data did not change
                    rebuild();
                }
            });
        });
    }

    private void openWatching() {
        List<Widgets.Watched> list = Widgets.watching(activity);
        if (list.isEmpty()) return;
        Ui.ListDialog d = Ui.listDialog(activity, activity.getString(R.string.widget_watching));
        for (Widgets.Watched w : list) {
            d.add(w.title, label(w.pkg), () -> launch(w.pkg));
        }
        d.add(activity.getString(R.string.widget_watching_clear), null, () -> {
            Widgets.clearWatching(activity);
            rebuild();
        });
        d.show();
    }

    private void openNotes() {
        List<Widgets.Note> notes = Widgets.notes(activity);
        Ui.ListDialog d = Ui.listDialog(activity, activity.getString(R.string.widget_notes));
        d.add(activity.getString(R.string.widget_notes_add), null, () -> {
            if (notes.size() >= Widgets.MAX_NOTES) {
                Toast.makeText(activity, R.string.widget_notes_full, Toast.LENGTH_SHORT).show();
                return;
            }
            Ui.askText(activity, activity.getString(R.string.widget_notes_add), "", 60, text -> {
                List<Widgets.Note> now = Widgets.notes(activity);
                now.add(0, new Widgets.Note(text, false));
                Widgets.saveNotes(activity, now);
                rebuild();
                openNotes();
            });
        });
        for (int i = 0; i < notes.size(); i++) {
            int at = i;
            Widgets.Note n = notes.get(i);
            d.add((n.done ? "✓  " : "") + n.text, activity.getString(n.done ? R.string.widget_notes_undo : R.string.widget_notes_done), () -> {
                List<Widgets.Note> now = Widgets.notes(activity);
                if (at < now.size()) now.get(at).done = !now.get(at).done;
                Widgets.saveNotes(activity, now);
                rebuild();
                openNotes();
            });
        }
        boolean anyDone = false;
        for (Widgets.Note n : notes) anyDone |= n.done;
        if (anyDone) d.add(activity.getString(R.string.widget_notes_clear_done), null, () -> {
            List<Widgets.Note> now = Widgets.notes(activity);
            now.removeIf(n -> n.done);
            Widgets.saveNotes(activity, now);
            rebuild();
        });
        d.show();
    }

    private void openRecent(List<String> recent) {
        if (recent.isEmpty()) return;
        Ui.ListDialog d = Ui.listDialog(activity, activity.getString(R.string.widget_recent));
        for (String pkg : recent) d.add(label(pkg), null, () -> launch(pkg));
        d.show();
    }

    private void launch(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            LastOpened.record(activity, pkg);
        } catch (RuntimeException refused) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** This profile's apps by when Home last opened them, newest first. */
    private List<String> recentApps() {
        PackageManager pm = activity.getPackageManager();
        List<String> out = LastOpened.recent(activity, RECENT_MAX);
        out.removeIf(p -> pm.getLeanbackLaunchIntentForPackage(p) == null && pm.getLaunchIntentForPackage(p) == null);
        return out;
    }

    private Drawable icon(String pkg) {
        Drawable known = icons.get(pkg);
        if (known != null) return known;
        try {
            Drawable d = Ui.smallIcon(activity, activity.getPackageManager().getApplicationIcon(pkg), Ui.dp(activity, 30));
            icons.put(pkg, d);
            return d;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String label(String pkg) {
        try {
            ApplicationInfo info = activity.getPackageManager().getApplicationInfo(pkg, 0);
            return info.loadLabel(activity.getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private ExecutorService executor() {
        if (io == null) io = Ui.worker();
        return io;
    }

    /** Memory pressure while Home is hidden: drop the cards and their icons; the next rebuild() makes them again. */
    void release() {
        panel.removeAllViews();
        cards.clear();
        icons.clear();
        builtFrom = 0;
    }

    void shutdown() {
        closed = true;
        icons.clear();
        if (io != null) io.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    /** A compact month, starting on the week's first day where the TV is: weekday initials, the days, today in a white disc. Drawn, not laid out, to stay
     *  cheap. */
    private static final class MonthView extends View {
        private final LocalDate today;
        private final java.time.DayOfWeek firstDay = java.time.temporal.WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
        private final String[] heads = new String[7];
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float discRadius;

        MonthView(Activity a, LocalDate today) {
            super(a);
            this.today = today;
            for (int i = 0; i < 7; i++) {
                heads[i] = firstDay.plus(i).getDisplayName(TextStyle.NARROW, Locale.getDefault());
            }
            Typeface tf = UiFont.get(a, R.string.font_regular);
            text.setTypeface(tf);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Ui.dp(a, 8.5f));
            // Today's disc fits its number ("30" is the widest), rather than the cell, which is narrower.
            discRadius = text.measureText("30") * 0.5f + Ui.dp(a, 2.5f);
            disc.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cw = getWidth() / 7f;
            float rh = getHeight() / 7f;
            text.setColor(Ui.TEXT_FAINT);
            for (int i = 0; i < 7; i++) c.drawText(heads[i], cw * i + cw / 2, rh * 0.8f, text);
            LocalDate first = today.withDayOfMonth(1);
            int offset = (first.getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
            int days = today.lengthOfMonth();
            for (int d = 1; d <= days; d++) {
                int cell = offset + d - 1;
                float x = cw * (cell % 7) + cw / 2;
                float y = rh * (1 + cell / 7) + rh * 0.8f;
                if (d == today.getDayOfMonth()) {
                    c.drawCircle(x, y - text.getTextSize() * 0.35f, discRadius, disc);
                    text.setColor(Ui.ON_FOCUS);
                } else {
                    text.setColor(d < today.getDayOfMonth() ? Ui.TEXT_FAINT : Ui.TEXT_DIM);
                }
                c.drawText(String.valueOf(d), x, y, text);
            }
        }
    }
}
