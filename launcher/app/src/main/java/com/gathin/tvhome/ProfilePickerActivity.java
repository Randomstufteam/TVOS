package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.CycleInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Who's watching?": the screen Home opens at power-on, and Settings > Profiles opens to switch or manage. The clock,
 * the weather and a greeting across the top; the profiles; and under them a card for the focused profile: what it was
 * last playing, its open notes and recent apps, so nobody has to open a Home to see where they left off. A profile
 * with a PIN shows none of that until it is unlocked.
 *
 * A PIN asks through a D-pad keypad or the remote's number keys, unlocking on the 4th digit. "Edit profiles", Menu or
 * a long press opens Rename, Avatar, Colour, PIN and Delete, behind that profile's PIN when it has one.
 * Ported from Remindr's picker.
 */
public final class ProfilePickerActivity extends Activity {
    static final String EXTRA_MANAGE = "manage";
    private static final int MAX_TRIES = 5;
    private static final long LOCKOUT_MS = 30_000;
    /** The focused profile's card shows this many recent apps. */
    private static final int CARD_ICONS = 4;

    private enum PinMode { ENTER, UNLOCK_TO_MANAGE, NEW, CONFIRM }

    /** What the card shows for a profile, read once off the main thread when the screen opens. */
    private static final class Summary {
        String title;
        String app;
        int openNotes;
        final List<Drawable> icons = new ArrayList<>();
    }

    private FrameLayout root;
    private View glow;
    private View glowNext;
    private LinearLayout column;
    private TextView title;
    private LinearLayout avatars;
    private FrameLayout overlay;
    private boolean manage;
    /** Edit mode: OK on a profile edits it instead of entering it. */
    private boolean editing;
    /** A profile was chosen and the screen is leaving: keys wait for Home. */
    private boolean leaving;

    private LinearLayout card;
    private TextView cardTitle;
    private TextView cardSub;
    private LinearLayout cardIcons;
    private TextView editPill;
    private Profiles.Profile cardFor;
    private final SparseArray<Summary> summaries = new SparseArray<>();
    private final ExecutorService io = Ui.worker();

    // PIN pad state
    private Profiles.Profile pinFor;
    private PinMode pinMode;
    private final StringBuilder digits = new StringBuilder();
    private String firstNewPin;
    private LinearLayout dots;
    private TextView pinPrompt;
    private TextView pinError;
    private Runnable afterUnlock;
    /** Process-wide, not per screen: leaving the picker and coming back must not reset the lockout. */
    private static int wrongTries;
    private static long lockedUntil;
    /** The profile a menu or the PIN pad is about, so focus goes back to its avatar when it closes. */
    private int overlayFor;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        manage = getIntent().getBooleanExtra(EXTRA_MANAGE, false);
        if (!manage && !Profiles.pickerNeeded(this)) {
            Profiles.enter(this, Profiles.currentId(this));
            finish();
            return;
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF06070C);
        // Two glow layers, so a new profile's colour fades in over the last one instead of replacing it at once.
        glow = new View(this);
        glowNext = new View(this);
        for (View g : new View[]{glow, glowNext}) {
            g.setAlpha(0f);
            root.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(topStrip());

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        title = Ui.text(this, titleText(), 34, R.string.font_light, Ui.TEXT);
        title.setLetterSpacing(-0.02f);
        // A vertical LinearLayout gives a child the full width by default: centre the text in it, or the title
        // sits left of the avatars.
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(title);
        avatars = new LinearLayout(this);
        avatars.setOrientation(LinearLayout.HORIZONTAL);
        avatars.setGravity(Gravity.CENTER);
        avatars.setClipChildren(false);
        avatars.setClipToPadding(false);
        avatars.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 12));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 14);
        column.addView(avatars, alp);
        column.addView(profileCard());
        column.addView(footer());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        clp.bottomMargin = Ui.dp(this, 30);
        root.addView(column, clp);
        root.setClipChildren(false);
        column.setClipChildren(false);
        setContentView(root);
        Motion.enter(title, 0, 0, 10);
        buildAvatars(Profiles.currentId(this), true);
        Motion.enter(card, 5, 0, 10);
        Motion.enter((View) editPill.getParent(), 7, 0, 6);
        loadSummaries();
    }

    /** Settings asks for the profiles while this screen is still open (it is singleTask): show what it asked for. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean asked = intent.getBooleanExtra(EXTRA_MANAGE, false);
        if (root == null || asked == manage) return;
        manage = asked;
        editing = false;
        closeOverlay();
        title.setText(titleText());
        buildAvatars(Profiles.currentId(this), false);
    }

    private String titleText() {
        return getString(editing ? R.string.profiles_edit_title_mode : manage ? R.string.profiles_manage_title : R.string.profiles_title);
    }

    // --- top strip: greeting, weather, clock ---------------------------------------------------------------------------

    private View topStrip() {
        FrameLayout strip = new FrameLayout(this);
        int side = Ui.dp(this, 56);
        strip.setPadding(side, Ui.dp(this, 34), side, 0);
        strip.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Ui.text(this, getString(greetingRes()), 22, R.string.font_light, Ui.TEXT));
        String weather = weatherLine();
        if (weather != null) {
            TextView w = Ui.text(this, weather, 14, R.string.font_regular, Ui.TEXT_DIM);
            w.setPadding(0, Ui.dp(this, 8), 0, 0);
            left.addView(w);
        }
        strip.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 40);
        clock.setTextColor(Ui.TEXT);
        clock.setTypeface(UiFont.get(this, R.string.font_light));
        clock.setLetterSpacing(-0.03f);
        clock.setIncludeFontPadding(false);
        right.addView(clock);
        TextView date = Ui.text(this, DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM"),
                Calendar.getInstance()), 13, R.string.font_regular, Ui.TEXT_DIM);
        date.setPadding(0, Ui.dp(this, 6), 0, 0);
        right.addView(date);
        strip.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        if (Motion.enabled) {
            strip.setAlpha(0f);
            strip.animate().alpha(1f).setDuration(Motion.ENTER_MS * 2L).setInterpolator(Motion.easeOut()).start();
        }
        return strip;
    }

    private static int greetingRes() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning : hour < 17 ? R.string.good_afternoon
                : hour < 22 ? R.string.good_evening : R.string.good_night;
    }

    /** The last reading of a profile without a PIN: a locked profile's city is its own. */
    private String weatherLine() {
        for (Profiles.Profile p : Profiles.pickerOrder(this)) {
            if (p.hasPin()) continue;
            Widgets.Weather w = Widgets.cachedWeatherOf(this, p.id);
            if (w == null) continue;
            return Widgets.glyph(w.code, w.day) + "  " + Math.round(w.temp) + "°  ·  " + Widgets.cityOf(this, p.id);
        }
        return null;
    }

    // --- the focused profile's card ------------------------------------------------------------------------------------

    private View profileCard() {
        card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.sheen(this, 22, 0x1FFFFFFF, 0x0AFFFFFF, 0x24FFFFFF, Math.max(1, Ui.dp(this, 1))));
        card.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 18), 0);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        cardTitle = Ui.text(this, "", 16, R.string.font_regular, Ui.TEXT);
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(cardTitle);
        cardSub = Ui.text(this, "", 13, R.string.font_light, Ui.TEXT_DIM);
        cardSub.setSingleLine(true);
        cardSub.setEllipsize(TextUtils.TruncateAt.END);
        cardSub.setPadding(0, Ui.dp(this, 6), 0, 0);
        text.addView(cardSub);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cardIcons = new LinearLayout(this);
        cardIcons.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(cardIcons);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 560), Ui.dp(this, 74));
        lp.topMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);
        return card;
    }

    private View footer() {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER);
        f.setClipChildren(false);
        editPill = Ui.pill(this, getString(R.string.profiles_edit), v -> setEditing(!editing));
        f.addView(editPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMarginStart(Ui.dp(this, 22));
        f.addView(Ui.keyHint(this, getString(R.string.profiles_key_ok), getString(R.string.profiles_key_select)), hl);
        f.addView(Ui.keyHint(this, getString(R.string.profiles_key_menu), getString(R.string.profiles_key_edit)), hl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        f.setLayoutParams(lp);
        return f;
    }

    private void setEditing(boolean on) {
        editing = on;
        editPill.setText(on ? R.string.profiles_done : R.string.profiles_edit);
        title.setText(titleText());
    }

    /** Reads each profile's card once, off the main thread: its history, its notes and small copies of its icons. */
    private void loadSummaries() {
        Context app = getApplicationContext();
        // A locked profile stays out of memory; the one already open to whoever is at the TV is read like any other.
        List<Profiles.Profile> all = Profiles.list(this);
        all.removeIf(p -> !unlocked(p));
        int iconPx = Ui.dp(this, 34);
        try {
            io.execute(() -> {
                PackageManager pm = app.getPackageManager();
                for (Profiles.Profile p : all) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Summary s = new Summary();
                    List<Widgets.Watched> watched = Widgets.watchingOf(app, p.id);
                    List<String> recent = LastOpened.recentOf(app, p.id, CARD_ICONS);
                    if (!watched.isEmpty()) s.title = watched.get(0).title;
                    String pkg = !watched.isEmpty() ? watched.get(0).pkg : recent.isEmpty() ? null : recent.get(0);
                    s.openNotes = Widgets.openNotesOf(app, p.id);
                    for (String r : recent) {
                        try {
                            Drawable d = Ui.smallIcon(app, pm.getApplicationIcon(r), iconPx);
                            if (d != null) s.icons.add(d);
                        } catch (PackageManager.NameNotFoundException | RuntimeException gone) {
                            // uninstalled since, or an icon that will not draw: the card just shows fewer
                        }
                    }
                    if (pkg != null) {
                        try {
                            s.app = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
                        } catch (PackageManager.NameNotFoundException gone) {
                            // keep the title without its app
                        }
                    }
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        summaries.put(p.id, s);
                        if (cardFor != null && cardFor.id == p.id) showCard(cardFor);
                    });
                }
            });
        } catch (RejectedExecutionException closing) {
            // the screen is closing
        }
    }

    private void showCard(Profiles.Profile p) {
        cardFor = p;
        cardIcons.removeAllViews();
        if (p == null) {
            cardTitle.setText(R.string.profiles_add_detail);
            cardSub.setText(R.string.profiles_add_detail_sub);
            return;
        }
        if (p.hasPin() && !unlocked(p)) {
            cardTitle.setText(R.string.profiles_locked);
            cardSub.setText(getString(R.string.profiles_locked_sub, p.name));
            return;
        }
        Summary s = summaries.get(p.id);
        if (s == null || (s.title == null && s.app == null && s.openNotes == 0)) {
            cardTitle.setText(R.string.profiles_fresh);
            cardSub.setText(R.string.profiles_fresh_sub);
            return;
        }
        cardTitle.setText(s.title != null ? getString(R.string.profiles_continue, s.title)
                : s.app != null ? getString(R.string.profiles_recent_app, s.app) : p.name);
        String notes = s.openNotes > 0 ? getString(R.string.profiles_notes_open, s.openNotes) : null;
        String app = s.title != null ? s.app : null;
        cardSub.setText(app != null && notes != null ? app + "  ·  " + notes : app != null ? app : notes != null ? notes : "");
        int size = Ui.dp(this, 34);
        for (Drawable d : s.icons) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(Ui.dp(this, 8));
            cardIcons.addView(iv, lp);
        }
    }

    // --- avatars -------------------------------------------------------------------------------------------------------

    private void buildAvatars(int focusId, boolean animate) {
        avatars.removeAllViews();
        List<Profiles.Profile> all = manage ? Profiles.list(this) : Profiles.pickerOrder(this);
        View focus = null;
        for (Profiles.Profile p : all) {
            View v = avatarView(p.initial(), p.name, Ui.avatar(p.color), p.hasPin(), p.emoji != null);
            v.setOnClickListener(x -> {
                if (editing) openMenu(p);
                else select(p);
            });
            v.setOnLongClickListener(x -> {
                openMenu(p);
                return true;
            });
            v.setTag(p);
            v.setOnFocusChangeListener((x, has) -> {
                focusLift(x, has);
                if (has) {
                    tint(p.color);
                    showCard(p);
                }
            });
            avatars.addView(v);
            if (animate) arrive(v, avatars.getChildCount());
            if (p.id == focusId || focus == null) focus = v;
        }
        if (all.size() < Profiles.MAX_PROFILES) {
            GradientDrawable add = new GradientDrawable();
            add.setShape(GradientDrawable.OVAL);
            add.setColor(0x14FFFFFF);
            add.setStroke(Ui.dp(this, 2), 0x40FFFFFF);
            View v = avatarView("+", getString(R.string.profiles_add), add, false, false);
            v.setOnClickListener(x -> Ui.askText(this, getString(R.string.profiles_add_name), "", 16, name -> {
                Profiles.Profile p = Profiles.add(this, name, Profiles.nextColor(Profiles.list(this)));
                buildAvatars(p != null ? p.id : focusId, false);
                loadSummaries();
            }));
            v.setOnFocusChangeListener((x, has) -> {
                focusLift(x, has);
                if (has) {
                    tint(0xFF64748B);
                    showCard(null);
                }
            });
            avatars.addView(v);
            if (animate) arrive(v, avatars.getChildCount());
        }
        if (focus != null) {
            View f = focus;
            f.post(f::requestFocus);
        }
    }

    private View avatarView(String initial, String name, Drawable bg, boolean locked, boolean picture) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setClipChildren(false);
        int size = Ui.dp(this, 96);
        FrameLayout disc = new FrameLayout(this);
        disc.setBackground(bg);
        disc.setElevation(Ui.dp(this, 8));   // a soft round shadow under each avatar (the oval is its outline)
        TextView letter = Ui.text(this, initial, picture ? 44 : 42, R.string.font_light, Ui.TEXT);
        disc.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        if (locked) {
            ImageView lock = new ImageView(this);
            lock.setImageResource(R.drawable.ic_lock);
            lock.setColorFilter(0xFFFFFFFF);
            lock.setBackground(Ui.circle(0xCC000000));
            int pad = Ui.dp(this, 6);
            lock.setPadding(pad, pad, pad, pad);
            int s = Ui.dp(this, 28);
            disc.addView(lock, new FrameLayout.LayoutParams(s, s, Gravity.BOTTOM | Gravity.END));
        }
        // The focus ring: a white rim drawn around the disc while focused.
        View ring = new View(this);
        GradientDrawable r = new GradientDrawable();
        r.setShape(GradientDrawable.OVAL);
        r.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
        ring.setBackground(r);
        ring.setAlpha(0f);
        ring.setTag("ring");
        disc.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        item.addView(disc, new LinearLayout.LayoutParams(size, size));
        TextView label = Ui.text(this, name, 16, R.string.font_regular, Ui.TEXT_DIM);
        label.setTag("label");
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 14);
        item.addView(label, llp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 128), ViewGroup.LayoutParams.WRAP_CONTENT);
        item.setLayoutParams(lp);
        return item;
    }

    /** An avatar arriving, the index-th of the row: its disc pops in, its name follows. The item itself is left to
     *  the focus scale, so the two never fight over one view's animation. */
    private static void arrive(View item, int index) {
        ViewGroup g = (ViewGroup) item;
        Motion.popIn(g.getChildAt(0), index);
        Motion.enter(g.getChildAt(1), index, 0, 6);
    }

    private void focusLift(View item, boolean has) {
        Motion.focus(item, has, 1.12f, 0f);
        View ring = item.findViewWithTag("ring");
        if (ring != null) Motion.fade(ring, has ? 1f : 0f, has ? Motion.NORMAL_MS : Motion.FAST_MS);
        View label = item.findViewWithTag("label");
        if (label instanceof TextView) ((TextView) label).setTextColor(has ? Ui.TEXT : Ui.TEXT_DIM);
    }

    /** The colour on screen now, so focus landing on the same colour again changes nothing. */
    private int glowColor;

    /** The backdrop glows softly in the focused profile's colour, crossfading from the last one. */
    private void tint(int color) {
        if (color == glowColor && glow.getAlpha() > 0f) return;
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{Ui.withAlpha(color, 0x55), Ui.withAlpha(color, 0x14), 0x00000000});
        g.setGradientRadius(Math.max(root.getWidth(), Ui.dp(this, 900)) * 0.6f);
        g.setGradientCenter(0.5f, 0.42f);
        // The new colour goes on the layer underneath at full strength, the old one fades away on top of it.
        View old = glow;
        glow = glowNext;
        glowNext = old;
        boolean first = old.getAlpha() == 0f;
        glow.animate().cancel();
        glow.setBackground(g);
        root.removeView(glow);
        root.addView(glow, 0);
        if (first) {
            // Nothing on screen yet (the picker opening): the glow rises out of the black.
            glow.setAlpha(0f);
            Motion.fade(glow, 1f, Motion.ENTER_MS * 2);
            return;
        }
        glow.setAlpha(1f);
        old.animate().cancel();
        Motion.fade(old, 0f, Motion.ENTER_MS + 120);
    }

    /** Whether this profile is already open to whoever is at the TV: the one picked since power-on. */
    private boolean unlocked(Profiles.Profile p) {
        return !p.hasPin() || (p.id == Profiles.currentId(this) && Profiles.pickedThisBoot(this));
    }

    private void select(Profiles.Profile p) {
        if (p.hasPin() && !(manage && unlocked(p))) {
            showPin(p, PinMode.ENTER, null);
        } else {
            enter(p);
        }
    }

    /** The chosen avatar comes forward as everything else leaves, then Home takes over. */
    private void enter(Profiles.Profile p) {
        if (leaving) return;
        leaving = true;
        Profiles.enter(this, p.id);
        View chosen = null;
        for (int i = 0; i < avatars.getChildCount(); i++) {
            View v = avatars.getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof Profiles.Profile && ((Profiles.Profile) tag).id == p.id) chosen = v;
            else Motion.fade(v, 0f, Motion.EXIT_MS);
        }
        if (!Motion.enabled || chosen == null) {
            goHome();
            return;
        }
        for (View v : new View[]{title, card, (View) editPill.getParent()}) Motion.fade(v, 0f, Motion.EXIT_MS);
        chosen.animate().scaleX(1.3f).scaleY(1.3f).alpha(0f).setStartDelay(60).setDuration(Motion.ENTER_MS - 60)
                .setInterpolator(Motion.easeOut()).start();
        handler.postDelayed(this::goHome, Motion.ENTER_MS);
    }

    private void goHome() {
        HomeOpen.open(this);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // --- edit menu -----------------------------------------------------------------------------------------------------

    private void openMenu(Profiles.Profile p) {
        if (!unlocked(p)) {
            showPin(p, PinMode.UNLOCK_TO_MANAGE, () -> openMenu2(p));
        } else {
            openMenu2(p);
        }
    }

    private void openMenu2(Profiles.Profile p) {
        overlayFor = p.id;
        LinearLayout panel = overlayPanel(getString(R.string.profiles_edit_title, p.name), 460);
        panel.addView(Ui.row(this, getString(R.string.profiles_rename), p.name, v -> {
            closeOverlay();
            Ui.askText(this, getString(R.string.profiles_rename), p.name, 16, name -> {
                p.name = name;
                Profiles.update(this, p);
                buildAvatars(p.id, false);
            });
        }));
        panel.addView(Ui.header(this, getString(R.string.profiles_avatar)));
        panel.addView(avatarChoices(p));
        panel.addView(Ui.header(this, getString(R.string.profiles_colour_title)));
        panel.addView(colourChoices(p));
        View gap = new View(this);
        panel.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 18)));
        panel.addView(Ui.row(this, getString(p.hasPin() ? R.string.profiles_change_pin : R.string.profiles_set_pin), null, v -> {
            closeOverlay();
            firstNewPin = null;
            showPin(p, PinMode.NEW, null);
        }));
        if (p.hasPin()) panel.addView(Ui.row(this, getString(R.string.profiles_remove_pin), null, v -> {
            Profiles.setPin(p, null);
            Profiles.update(this, p);
            closeOverlay();
            buildAvatars(p.id, false);
            loadSummaries();
        }));
        if (Profiles.list(this).size() > 1) panel.addView(Ui.row(this, getString(R.string.profiles_delete), null, v -> {
            closeOverlay();
            confirmDelete(p);
        }));
        panel.getChildAt(1).requestFocus();
    }

    private LinearLayout choiceRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        return row;
    }

    /** One round choice: a white ring when focused, a fainter one on the current choice. */
    private TextView choice(LinearLayout row, Drawable bg, CharSequence text, boolean selected, Runnable pick) {
        Context c = this;
        TextView b = Ui.text(c, text, 20, R.string.font_regular, Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ringed(bg, 0xFFFFFFFF, 3));
        states.addState(new int[]{android.R.attr.state_activated}, ringed(bg, 0x99FFFFFF, 2));
        states.addState(new int[]{}, bg);
        b.setBackground(states);
        b.setActivated(selected);
        b.setOnClickListener(v -> {
            for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setActivated(row.getChildAt(i) == v);
            pick.run();
        });
        b.setOnFocusChangeListener((v, has) -> Motion.focus(v, has, 1.14f, 0f));
        int size = Ui.dp(this, 36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Ui.dp(this, 5));
        row.addView(b, lp);
        return b;
    }

    private Drawable ringed(Drawable bg, int color, int widthDp) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Ui.dp(this, widthDp), color);
        return new LayerDrawable(new Drawable[]{bg.getConstantState().newDrawable().mutate(), ring});
    }

    private View avatarChoices(Profiles.Profile p) {
        LinearLayout row = choiceRow();
        Drawable plain = Ui.circle(0x1FFFFFFF);
        String letter = new Profiles.Profile(p.id, p.name, p.color).initial();
        choice(row, plain, letter, p.emoji == null, () -> setEmoji(p, null));
        for (String e : Profiles.AVATARS) choice(row, plain, e, e.equals(p.emoji), () -> setEmoji(p, e));
        return row;
    }

    private void setEmoji(Profiles.Profile p, String emoji) {
        p.emoji = emoji;
        Profiles.update(this, p);
        buildAvatars(p.id, false);
    }

    private View colourChoices(Profiles.Profile p) {
        LinearLayout row = choiceRow();
        for (int color : Profiles.COLORS) {
            choice(row, Ui.circle(color), "", color == p.color, () -> {
                p.color = color;
                Profiles.update(this, p);
                buildAvatars(p.id, false);
            });
        }
        return row;
    }

    private void confirmDelete(Profiles.Profile p) {
        LinearLayout panel = overlayPanel(getString(R.string.profiles_delete_confirm, p.name), 380);
        panel.addView(Ui.row(this, getString(R.string.profiles_delete_yes), null, v -> {
            Profiles.delete(this, p.id);
            closeOverlay();
            summaries.remove(p.id);
            buildAvatars(Profiles.currentId(this), false);
        }));
        panel.addView(Ui.row(this, getString(android.R.string.cancel), null, v -> closeOverlay()));
        panel.getChildAt(2).requestFocus();
    }

    /** A dimmed overlay with a centred sheet, its title first. */
    private LinearLayout overlayPanel(CharSequence title, int widthDp) {
        closeOverlay();
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xCC000000);
        overlay.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.sheet(this));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pad = Ui.dp(this, 22);
        panel.setPadding(pad, pad, pad, Ui.dp(this, 14));
        TextView t = Ui.text(this, title, 22, R.string.font_light, Ui.TEXT);
        t.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 14));
        panel.addView(t);
        overlay.addView(panel, new FrameLayout.LayoutParams(Ui.dp(this, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The dimming comes up quickly and the panel pops forward out of it.
        overlay.setAlpha(0f);
        Motion.fade(overlay, 1f, Motion.FAST_MS);
        Motion.popIn(panel, 0);
        // The screen behind stays out of reach while a menu or the PIN pad is up, or Up/Down could land on an avatar
        // instead of the next row.
        column.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return panel;
    }

    private void closeOverlay() {
        if (overlay != null) root.removeView(overlay);
        overlay = null;
        column.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pinFor = null;
        View focus = avatars.getChildCount() > 0 ? avatars.getChildAt(0) : null;
        for (int i = 0; i < avatars.getChildCount(); i++) {
            Object tag = avatars.getChildAt(i).getTag();
            if (tag instanceof Profiles.Profile && ((Profiles.Profile) tag).id == overlayFor) focus = avatars.getChildAt(i);
        }
        if (focus != null) focus.requestFocus();
    }

    // --- PIN pad -------------------------------------------------------------------------------------------------------

    private void showPin(Profiles.Profile p, PinMode mode, Runnable after) {
        overlayFor = p.id;
        LinearLayout panel = overlayPanel("", 560);
        panel.removeViewAt(0);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        int pad0 = Ui.dp(this, 26);
        panel.setPadding(pad0, pad0, pad0, pad0);
        pinFor = p;
        pinMode = mode;
        afterUnlock = after;
        digits.setLength(0);

        // Left: who, the prompt, the dots. Right: the keypad. Side by side, so it all fits a 540 dp tall screen.
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout disc = new FrameLayout(this);
        disc.setBackground(Ui.avatar(p.color));
        disc.addView(Ui.text(this, p.initial(), 30, R.string.font_light, Ui.TEXT),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        int s = Ui.dp(this, 68);
        info.addView(disc, new LinearLayout.LayoutParams(s, s));
        pinPrompt = Ui.text(this, "", 18, R.string.font_light, Ui.TEXT);
        pinPrompt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 16);
        info.addView(pinPrompt, plp);
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Profiles.PIN_LENGTH; i++) {
            View d = new View(this);
            int ds = Ui.dp(this, 14);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ds, ds);
            dl.setMargins(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
            dots.addView(d, dl);
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 18);
        info.addView(dots, dlp);
        pinError = Ui.text(this, "", 14, R.string.font_regular, 0xFFFF6B6B);
        pinError.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        elp.topMargin = Ui.dp(this, 10);
        info.addView(pinError, elp);
        panel.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
        View first = null;
        for (String k : keys) {
            TextView b = Ui.text(this, k, 22, R.string.font_light, Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            int bs = Ui.dp(this, 54);
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = bs;
            gl.height = bs;
            gl.setMargins(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
            if (k.isEmpty()) {
                pad.addView(new View(this), gl);
                continue;
            }
            GradientDrawable on = new GradientDrawable();
            on.setShape(GradientDrawable.OVAL);
            on.setColor(Ui.FOCUS);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, on);
            bg.addState(new int[]{}, Ui.circle(0x1FFFFFFF));
            b.setBackground(bg);
            // The key is focusable itself: its own focus turns the digit dark on the white disc.
            b.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.ON_FOCUS, Ui.TEXT}));
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnClickListener(v -> {
                if (k.equals("⌫")) backspace();
                else typeDigit(k.charAt(0));
            });
            pad.addView(b, gl);
            if (first == null) first = b;
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMarginStart(Ui.dp(this, 24));
        panel.addView(pad, glp);
        updatePinViews();
        if (first != null) first.requestFocus();
    }

    private void updatePinViews() {
        int prompt;
        switch (pinMode) {
            case NEW: prompt = R.string.profiles_pin_new; break;
            case CONFIRM: prompt = R.string.profiles_pin_confirm; break;
            case UNLOCK_TO_MANAGE: prompt = R.string.profiles_pin_manage; break;
            default: prompt = R.string.profiles_pin_enter; break;
        }
        pinPrompt.setText(getString(prompt, pinFor.name));
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            if (i < digits.length()) {
                d.setColor(0xFFFFFFFF);
            } else {
                d.setStroke(Ui.dp(this, 2), 0x80FFFFFF);
            }
            dots.getChildAt(i).setBackground(d);
        }
    }

    private void typeDigit(char c) {
        if (pinFor == null || digits.length() >= Profiles.PIN_LENGTH) return;
        long now = SystemClock.elapsedRealtime();
        if (now < lockedUntil && (pinMode == PinMode.ENTER || pinMode == PinMode.UNLOCK_TO_MANAGE)) {
            pinError.setText(getString(R.string.profiles_pin_locked, (lockedUntil - now + 999) / 1000));
            return;
        }
        digits.append(c);
        pinError.setText("");
        updatePinViews();
        // A short beat lets the last dot fill before the PIN is checked, as in Remindr.
        if (digits.length() == Profiles.PIN_LENGTH) handler.postDelayed(this::submitPin, 120);
    }

    private void backspace() {
        if (digits.length() > 0) digits.setLength(digits.length() - 1);
        updatePinViews();
    }

    private void submitPin() {
        if (pinFor == null || digits.length() != Profiles.PIN_LENGTH) return;
        String pin = digits.toString();
        digits.setLength(0);
        Profiles.Profile p = pinFor;
        switch (pinMode) {
            case NEW:
                firstNewPin = pin;
                pinMode = PinMode.CONFIRM;
                updatePinViews();
                return;
            case CONFIRM:
                if (pin.equals(firstNewPin)) {
                    Profiles.setPin(p, pin);
                    Profiles.update(this, p);
                    firstNewPin = null;
                    closeOverlay();
                    buildAvatars(p.id, false);
                } else {
                    firstNewPin = null;
                    pinMode = PinMode.NEW;
                    rejected(getString(R.string.profiles_pin_mismatch));
                }
                return;
            default:
                if (Profiles.checkPin(p, pin)) {
                    wrongTries = 0;
                    Runnable after = afterUnlock;
                    closeOverlay();
                    if (after != null) after.run();
                    else enter(p);
                } else {
                    wrongTries++;
                    if (wrongTries >= MAX_TRIES) {
                        wrongTries = 0;
                        lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
                        rejected(getString(R.string.profiles_pin_locked, LOCKOUT_MS / 1000));
                    } else {
                        rejected(getString(R.string.profiles_pin_wrong));
                    }
                }
        }
    }

    private void rejected(String message) {
        pinError.setText(message);
        updatePinViews();
        dots.animate().translationX(Ui.dp(this, 10)).setInterpolator(new CycleInterpolator(3)).setDuration(360)
                .withEndAction(() -> dots.setTranslationX(0)).start();
    }

    // --- keys ----------------------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (leaving) return true;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (pinFor != null && overlay != null) {
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                if (down) typeDigit((char) ('0' + code - KeyEvent.KEYCODE_0));
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) {
                if (down) backspace();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_MENU && overlay == null) {
            if (!down) {
                View f = getCurrentFocus();
                if (f != null && f.getTag() instanceof Profiles.Profile) openMenu((Profiles.Profile) f.getTag());
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) {
            if (pinFor != null && digits.length() > 0) {
                backspace();
                return;
            }
            closeOverlay();
            return;
        }
        if (editing) {
            setEditing(false);
            return;
        }
        // At power-on someone has to pick; once a profile is active, Back returns to it.
        if (manage || Profiles.pickedThisBoot(this)) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        Ui.closeDialogs(this);
        super.onDestroy();
    }
}
