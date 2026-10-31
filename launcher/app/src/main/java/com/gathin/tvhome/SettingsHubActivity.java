package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's Settings: what the gear pill, the Settings tile and the remote's Settings button open. Two panes: the
 * categories down the left, always there, and on the right the page of the one in focus, which follows focus without
 * a press. Right steps into the page, Left and Back step out again, a page level at a time. Android's own Settings
 * stays installed and untouched, one row away under System.
 */
public final class SettingsHubActivity extends Activity {
    static final String SYSTEM_SETTINGS = "com.android.tv.settings";
    /** Which category to open on, as its place in the left column. */
    static final String EXTRA_CATEGORY = "category";
    static final int CATEGORY_NETWORK = 4;
    /** Packages never offered for closing: Android, Home itself, the keyboard and the TV's own services. */
    private static final Set<String> NEVER_CLOSE = new HashSet<>(java.util.Arrays.asList(
            "android", SYSTEM_SETTINGS, "com.android.systemui", "com.google.android.inputmethod.latin",
            "com.google.android.katniss", "com.google.android.apps.tv.launcherx", "com.mediatek.wwtv.tvcenter"));

    interface Page {
        void build(LinearLayout rows);
    }

    /** One thread for the TV pages' slow reads (app sizes and icons), stopped with this screen. */
    private final java.util.concurrent.ExecutorService io = Ui.worker();
    private final TvPages pages = new TvPages(this, new TvPages.Host() {
        @Override
        public void push(String title, Page page) {
            SettingsHubActivity.this.push(title, page);
        }

        @Override
        public void refresh() {
            if (!stack.isEmpty()) show(stack.peek(), false);
        }

        @Override
        public void open(Intent intent) {
            SettingsHubActivity.this.open(intent);
        }

        @Override
        public <T> void load(java.util.function.Supplier<T> work, java.util.function.Consumer<T> done) {
            SettingsHubActivity.this.load(work, done);
        }
    });

    /** Off the main thread, then back on it while the same page is still on screen; done gets null on failure. */
    private <T> void load(java.util.function.Supplier<T> work, java.util.function.Consumer<T> done) {
        Page asked = stack.peek();
        try {
            io.execute(() -> {
                T read;
                try {
                    read = work.get();
                } catch (RuntimeException failed) {
                    android.util.Log.w("TvHome.Settings", "page read failed", failed);
                    read = null;   // the page shows what it can rather than waiting for ever
                }
                T result = read;
                // Only into the page that asked: Back may have left it meanwhile.
                handler.post(() -> {
                    if (!isDestroyed() && stack.peek() == asked) done.accept(result);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // Settings is closing
        }
    }

    /** A column of the screen: Up and Down stay inside it, and Left or Right leave it only through sideways(). */
    private abstract static class Pane extends LinearLayout {
        Pane(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        abstract View sideways(int direction);

        @Override
        public View focusSearch(View focused, int direction) {
            View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
            if (next != null) return next;
            return direction == FOCUS_LEFT || direction == FOCUS_RIGHT ? sideways(direction) : null;
        }
    }

    /** One entry of the left column. */
    private static final class Category {
        final int title, icon, color;
        final Page page;
        View row;

        Category(int title, int icon, int color, Page page) {
            this.title = title;
            this.icon = icon;
            this.color = color;
            this.page = page;
        }
    }

    /** focusIndex for a page shown beside the categories while focus stays in them. */
    private static final int NO_FOCUS = -2;
    /** Waiting this long keeps the pages from being built one after another as focus runs down the column. */
    private static final int PREVIEW_DELAY_MS = 130;

    private FrameLayout root;
    private Pane side;
    private LinearLayout rows;
    private TextView pageTitle;
    private TextView crumb;
    private ScrollView scroll;
    private FrameLayout whoDisc;
    private TextView whoLetter;
    private TextView whoName;
    private int backdropColor;
    private final List<Category> categories = new ArrayList<>();
    private Category current;
    private Category pending;
    private final Runnable applyPending = () -> {
        if (pending != null && pending != current) select(pending, false);
        pending = null;
    };
    private final Deque<Page> stack = new ArrayDeque<>();
    private final Deque<String> titles = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Profiles.Profile me = Profiles.current(this);
        root = new FrameLayout(this);
        backdropColor = me.color;
        root.setBackground(Ui.backdrop(this, me.color));

        side = new Pane(this) {
            @Override
            View sideways(int direction) {
                if (direction == FOCUS_RIGHT) rows.post(SettingsHubActivity.this::focusContent);
                return null;
            }
        };
        side.setClipChildren(false);
        side.setClipToPadding(false);
        side.setPadding(Ui.dp(this, 36), Ui.dp(this, 30), Ui.dp(this, 14), 0);
        side.addView(whoRow(me));
        categories.add(new Category(R.string.hub_overview, R.drawable.ic_hub_layout, 0xFF0EA5E9, this::overviewPage));
        categories.add(new Category(R.string.hub_home, R.drawable.ic_hub_widgets, 0xFF8B5CF6, this::homePage));
        categories.add(new Category(R.string.hub_picture, R.drawable.ic_picture, 0xFFF97316, pages::display));
        categories.add(new Category(R.string.hub_sound, R.drawable.ic_hub_sound, 0xFFEC4899, soundPage));
        categories.add(new Category(R.string.hub_network, R.drawable.ic_wifi, 0xFF3B82F6, pages::network));
        categories.add(new Category(R.string.hub_apps, R.drawable.ic_apps, 0xFFEF4444, this::appsPage));
        categories.add(new Category(R.string.hub_system, R.drawable.ic_hub_info, 0xFF94A3B8, this::systemPage));
        for (Category c : categories) side.addView(categoryRow(c));
        root.addView(side, new FrameLayout.LayoutParams(Ui.dp(this, 300), Ui.MATCH));

        Pane content = new Pane(this) {
            @Override
            View sideways(int direction) {
                if (direction != FOCUS_LEFT) return null;
                if (stack.size() > 1) {
                    handler.post(SettingsHubActivity.this::onBackPressed);
                    return null;
                }
                return current.row;
            }
        };
        content.setClipChildren(false);
        FrameLayout head = new FrameLayout(this);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 6));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        crumb = Ui.text(this, "", 12, R.string.font_medium, Ui.TEXT_FAINT);
        crumb.setVisibility(View.GONE);
        crumb.setPadding(0, 0, 0, Ui.dp(this, 5));
        heading.addView(crumb);
        pageTitle = Ui.text(this, "", 28, R.string.font_light, Ui.TEXT);
        pageTitle.setLetterSpacing(-0.01f);
        heading.addView(pageTitle);
        head.addView(heading, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.START));
        TextClock clock = new TextClock(this);
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        clock.setTextColor(Ui.TEXT_DIM);
        clock.setTypeface(UiFont.get(this, R.string.font_regular));
        head.addView(clock, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.END));
        content.addView(head);

        scroll = Ui.centeringScroll(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 36));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        // Room for the focused row's lift: the scroll view clips the list to its own bounds. The list must not clip
        // to this padding itself, or the grown row is cut flat at its sides.
        rows.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        rows.setClipToPadding(false);
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH);
        clp.leftMargin = Ui.dp(this, 300);
        clp.rightMargin = Ui.dp(this, 28);
        root.addView(content, clp);
        setContentView(root);
        Motion.enter(side, 0, -14, 0);
        Category opening = categories.get(Math.max(0, Math.min(categories.size() - 1,
                getIntent().getIntExtra(EXTRA_CATEGORY, 0))));
        select(opening, false);
        // After the first layout: requested now, the window's first traversal hands focus to the profile row above.
        View first = opening.row;
        first.post(first::requestFocus);
    }

    /** Who is using the TV, at the head of the column; OK opens the profiles. */
    private View whoRow(Profiles.Profile me) {
        LinearLayout who = new LinearLayout(this);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setFocusable(true);
        who.setClickable(true);
        who.setBackground(Ui.focusGlass(this, 18));
        who.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        whoDisc = new FrameLayout(this);
        whoDisc.setBackground(Ui.avatar(me.color));
        whoLetter = Ui.text(this, me.initial(), 20, R.string.font_light, Ui.TEXT);
        whoDisc.addView(whoLetter, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        who.addView(whoDisc, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(this, 12), 0, 0, 0);
        whoName = Ui.text(this, me.name, 16, R.string.font_regular, Ui.TEXT);
        whoName.setSingleLine(true);
        Ui.textFocus(whoName, Ui.TEXT);
        names.addView(whoName);
        names.setDuplicateParentStateEnabled(true);
        TextView sub = Ui.text(this, getString(R.string.profiles_switch), 12, R.string.font_light, Ui.TEXT_FAINT);
        Ui.textFocus(sub, Ui.TEXT_FAINT);
        sub.setPadding(0, Ui.dp(this, 3), 0, 0);
        names.addView(sub);
        who.addView(names);
        who.setOnClickListener(v -> startActivity(new Intent(this,
                ProfilePickerActivity.class).putExtra(ProfilePickerActivity.EXTRA_MANAGE, true)));
        who.setOnFocusChangeListener((v, has) -> Motion.focus(v, has, 1.02f, Ui.dp(this, 6)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 16);
        who.setLayoutParams(lp);
        return who;
    }

    private View categoryRow(Category c) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Ui.FOCUS);
        on.setCornerRadius(Ui.dp(this, 14));
        GradientDrawable chosen = new GradientDrawable();
        chosen.setColor(0x1FFFFFFF);
        chosen.setCornerRadius(Ui.dp(this, 14));
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{android.R.attr.state_activated}, chosen);
        bg.addState(new int[]{}, new ColorDrawable(0));
        row.setBackground(bg);
        row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 12), 0);
        TextView t = Ui.text(this, getString(c.title), 16, R.string.font_regular, Ui.TEXT);
        t.setSingleLine(true);
        Ui.textFocus(t, Ui.TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Ui.withIcon(row, c.icon, c.color);
        row.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
        row.setOnClickListener(v -> {
            handler.removeCallbacks(applyPending);
            if (c != current) select(c, true);
            else focusContent();
        });
        row.setOnFocusChangeListener((v, has) -> {
            Motion.focus(v, has, 1.02f, Ui.dp(this, 6));
            if (!has) return;
            handler.removeCallbacks(applyPending);
            pending = c;
            if (c != current) handler.postDelayed(applyPending, PREVIEW_DELAY_MS);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 46));
        lp.bottomMargin = Ui.dp(this, 4);
        row.setLayoutParams(lp);
        c.row = row;
        return row;
    }

    private void select(Category c, boolean focus) {
        handler.removeCallbacks(applyPending);
        pending = null;
        if (current != null) current.row.setActivated(false);
        current = c;
        c.row.setActivated(true);
        stack.clear();
        titles.clear();
        openedFrom.clear();
        forgetPageData();
        stack.push(c.page);
        titles.push(getString(c.title));
        show(c.page, focus ? -1 : NO_FOCUS, 1);
    }

    /** Steps from the column into the page on screen, which may still be the last one if focus just moved. */
    private void focusContent() {
        handler.removeCallbacks(applyPending);
        applyPending.run();
        List<View> f = focusables();
        if (!f.isEmpty()) f.get(0).requestFocus();
    }

    /** What pages keep while they are open: icons, measured once. */
    private void forgetPageData() {
        pages.forgetApps();
        running = null;
    }

    /** The Sound page, kept as one object so the volume receiver can tell it is the page on screen. */
    private final Page soundPage = r -> pages.sound(r);

    /**
     * The volume changed while Settings is on screen (the remote's buttons, which this TV handles before any app
     * sees them, or the sound bar's own remote): rebuild the Sound page, so its bar, percentage and Mute row show
     * what the TV now plays at. AudioService sends this broadcast for every stream change; only the page cares.
     */
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(refreshSound);
            handler.postDelayed(refreshSound, 120);   // a held button sends many: rebuild once they settle
        }
    };
    private final Runnable refreshSound = () -> {
        if (!isDestroyed() && stack.peek() == soundPage) show(soundPage, false);
    };

    @Override
    protected void onPause() {
        unregisterReceiver(volumeChanged);
        super.onPause();
    }

    /** onCreate already built the first page; only a later resume needs a refresh. */
    private boolean firstResume = true;

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter volume = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        volume.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeChanged, volume, Context.RECEIVER_EXPORTED);
        else registerReceiver(volumeChanged, volume);
        // Back from an Android page or the profile picker: refresh what may have changed.
        if (!firstResume) {
            refreshWho();
            if (!stack.isEmpty()) show(stack.peek(), false);
        }
        firstResume = false;
    }

    /** Another profile may have been picked on the profiles screen. */
    private void refreshWho() {
        Profiles.Profile me = Profiles.current(this);
        whoLetter.setText(me.initial());
        whoName.setText(me.name);
        whoDisc.setBackground(Ui.avatar(me.color));
        if (me.color != backdropColor) {
            backdropColor = me.color;
            root.setBackground(Ui.backdrop(this, me.color));
        }
    }

    /** The row each page below the top one was opened from, so Back lands on it again. */
    private final Deque<Integer> openedFrom = new ArrayDeque<>();

    private void push(String title, Page page) {
        openedFrom.push(focusIndex(getCurrentFocus()));
        stack.push(page);
        titles.push(title);
        show(page, -1, 1);
    }

    /** Rebuilds the page in place (a value changed), keeping focus on the row it was on, or in the left column. */
    private void show(Page page, boolean focusFirst) {
        show(page, focusFirst ? -1 : rows.hasFocus() ? focusIndex(getCurrentFocus()) : NO_FOCUS, 0);
    }

    /**
     * Builds the page with focus on row focusIndex (the first focusable one for -1, none of them for NO_FOCUS).
     * direction: 1 a page opening, sliding in from the right; -1 going back, from the left; 0 a refresh, no motion.
     */
    private void show(Page page, int focusIndex, int direction) {
        // Rows leaving while one has focus hand it to the first focusable view on screen, which is the left column:
        // that would flash it white and change the page. Keep the column out of reach until the new row has focus.
        boolean hadFocus = rows.hasFocus();
        if (hadFocus) side.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rows.removeAllViews();
        pageTitle.setText(titles.peek());
        crumb.setText(stack.size() > 1 ? getString(current.title) : "");
        crumb.setVisibility(stack.size() > 1 ? View.VISIBLE : View.GONE);
        page.build(rows);
        groupRows();
        List<View> focusable = focusables();
        if (focusIndex != NO_FOCUS) {
            View v = focusable.isEmpty() ? current.row : focusable.get(focusIndex < 0 ? 0 : Math.min(focusIndex,
                    focusable.size() - 1));
            v.post(() -> {
                side.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                v.requestFocus();
            });
        } else if (hadFocus) {
            side.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (direction == 0) return;
        // A new page starts at its top; a page returned to scrolls back to its row when that row takes focus.
        if (focusIndex < 0) scroll.scrollTo(0, 0);
        // The page slides in as one: a single layer for its few frames, not one for every row.
        Motion.enter(rows, 0, 18 * direction, 0);
    }

    /** Every view of the page that takes focus, in screen order. */
    private List<View> focusables() {
        return rows.getFocusables(View.FOCUS_DOWN);
    }

    /** The focused row's place among focusables(), or -1. */
    private int focusIndex(View focused) {
        return focused == null ? -1 : focusables().indexOf(focused);
    }

    private static final String GROUP = "group";

    /**
     * Gathers each run of rows the page built into one glass card, with hairlines between them, as the TV's own
     * settings group them; headers, notes and other views stay as they are, between the cards.
     */
    private void groupRows() {
        List<View> built = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) built.add(rows.getChildAt(i));
        rows.removeAllViews();
        LinearLayout group = null;
        for (View v : built) {
            if (v.getTag(R.id.ui_row) == null) {
                group = null;
                rows.addView(v);
                continue;
            }
            if (group == null) {
                group = new LinearLayout(this);
                group.setTag(GROUP);
                group.setOrientation(LinearLayout.VERTICAL);
                group.setBackground(Ui.groupGlass(this));
                int pad = Ui.dp(this, 4);
                group.setPadding(pad, pad, pad, pad);
                group.setClipChildren(false);
                group.setClipToPadding(false);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.MATCH,
                        Ui.WRAP);
                glp.bottomMargin = Ui.dp(this, 10);
                rows.addView(group, glp);
            } else {
                group.addView(hairline(v));
            }
            v.setBackground(Ui.focusOnly(this, 16));
            ((LinearLayout.LayoutParams) v.getLayoutParams()).bottomMargin = 0;
            group.addView(v);
        }
    }

    /** The line between two rows of a card, starting where the row's text starts (after its icon tile, if any). */
    private View hairline(View nextRow) {
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        boolean icon = nextRow instanceof ViewGroup && ((ViewGroup) nextRow).getChildCount() > 0
                && !(((ViewGroup) nextRow).getChildAt(0) instanceof TextView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Math.max(1, Ui.dp(this, 1)));
        lp.setMarginStart(Ui.dp(this, icon ? 62 : 22));
        lp.setMarginEnd(Ui.dp(this, 18));
        line.setLayoutParams(lp);
        return line;
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.pop();
            titles.pop();
            forgetPageData();
            show(stack.peek(), openedFrom.isEmpty() ? -1 : openedFrom.pop(), -1);
        } else if (rows.hasFocus()) {
            current.row.requestFocus();
        } else {
            // Always back to this launcher's Home, never to whatever Android keeps as Home underneath (Google TV's).
            HomeOpen.open(this);
            finish();
            overridePendingTransition(0, 0);
        }
    }

    // --- pages -------------------------------------------------------------------------------------------------------

    /** A page of a category other than the one on screen: its row lights up and focus lands on the new page. */
    private void goTo(int categoryIndex, int title, Page page) {
        select(categories.get(categoryIndex), true);
        if (page != null) push(getString(title), page);
    }

    /** A focusable tile of the Overview: an icon, a value and what it is. */
    private View stat(int icon, int color, CharSequence label, CharSequence value, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(Ui.focusGlass(this, 18));
        int pad = Ui.dp(this, 14);
        t.setPadding(pad, pad, pad, Ui.dp(this, 12));
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.lighten(color,
                0.18f), Ui.darken(color, 0.12f)});
        bg.setCornerRadius(Ui.dp(this, 8));
        tile.setBackground(bg);
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        glyph.setColorFilter(0xFFFFFFFF);
        tile.addView(glyph, new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16), Gravity.CENTER));
        t.addView(tile, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        TextView v = Ui.text(this, value, 17, R.string.font_regular, Ui.TEXT);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        Ui.textFocus(v, Ui.TEXT);
        v.setPadding(0, Ui.dp(this, 12), 0, 0);
        t.addView(v);
        TextView l = Ui.text(this, label, 12, R.string.font_light, Ui.TEXT_DIM);
        Ui.textFocus(l, Ui.TEXT_DIM);
        l.setPadding(0, Ui.dp(this, 4), 0, 0);
        t.addView(l);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((x, has) -> Motion.focus(x, has, 1.04f, Ui.dp(this, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 118), 1);
        lp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout statRow() {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 10);
        row.setLayoutParams(lp);
        return row;
    }

    private void overviewPage(LinearLayout r) {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        Widgets.Weather w = Widgets.cachedWeather(this);
        String city = Widgets.city(this);
        LinearLayout one = statRow();
        one.addView(stat(R.drawable.ic_wifi, 0xFF3B82F6, getString(R.string.hub_stat_network), pages.networkSummary(),
                v -> goTo(4, 0, null)));
        one.addView(stat(R.drawable.ic_hub_sun, 0xFFF59E0B, getString(R.string.widget_weather),
                w != null ? Math.round(w.temp) + "°  " + city : getString(R.string.hub_not_set),
                v -> startActivity(new Intent(this, WeatherActivity.class))));
        one.addView(stat(R.drawable.ic_hub_widgets, 0xFF8B5CF6, getString(R.string.hub_widgets), widgetsSummary(),
                v -> goTo(1, R.string.hub_widgets, this::widgetsPage)));
        r.addView(one);
        LinearLayout two = statRow();
        two.addView(stat(R.drawable.ic_hub_speed, 0xFF10B981, getString(R.string.hub_stat_memory), freeMemory(),
                v -> goTo(5, R.string.hub_running, this::runningPage)));
        two.addView(stat(R.drawable.ic_apps, 0xFFEF4444, getString(R.string.hub_stat_storage),
                getString(R.string.hub_free, Formatter.formatShortFileSize(this, fs.getAvailableBytes())),
                v -> goTo(6, R.string.hub_storage, pages::storage)));
        two.addView(stat(R.drawable.ic_hub_moon, 0xFF6366F1, getString(R.string.hub_screensaver),
                getString(Scenes.nameOf(Scenes.chosen(this))),
                v -> goTo(1, R.string.hub_screensaver, this::screensaverPage)));
        r.addView(two);
    }

    private void homePage(LinearLayout r) {
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_widgets), widgetsSummary(),
                v -> push(getString(R.string.hub_widgets), this::widgetsPage)), R.drawable.ic_hub_widgets, 0xFF8B5CF6));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.widget_weather), Widgets.city(this).isEmpty()
                        ? getString(R.string.hub_not_set) : Widgets.city(this),
                v -> startActivity(new Intent(this, WeatherActivity.class))), R.drawable.ic_hub_sun, 0xFFF59E0B));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_layout), null, v -> {
            startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }), R.drawable.ic_hub_layout, 0xFF0EA5E9));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_hidden),
                String.valueOf(Profiles.hiddenApps(this).size()),
                v -> push(getString(R.string.hub_hidden), this::hiddenPage)), R.drawable.ic_hub_eye, 0xFF64748B));
        // A switch, not a page: no chevron, but its space is kept so the value lines up with the rows around it.
        LinearLayout dark = Ui.navRow(this, getString(R.string.dark_mode), getString(DarkMode.label(this)), v -> {
            DarkMode.cycle(this);
            show(stack.peek(), false);
        });
        dark.getChildAt(dark.getChildCount() - 1).setVisibility(View.INVISIBLE);
        r.addView(Ui.withIcon(dark, R.drawable.ic_hub_contrast, 0xFF334155));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_screensaver),
                getString(Scenes.nameOf(Scenes.chosen(this))),
                v -> push(getString(R.string.hub_screensaver), this::screensaverPage)), R.drawable.ic_hub_moon,
                        0xFF6366F1));
    }

    private void appsPage(LinearLayout r) {
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_all_apps), null, v -> {
            pages.forgetApps();
            push(getString(R.string.hub_all_apps), pages::apps);
        }), R.drawable.ic_apps, 0xFFEF4444));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_running), freeMemory(),
                v -> push(getString(R.string.hub_running), this::runningPage)), R.drawable.ic_hub_speed, 0xFF10B981));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_hidden),
                String.valueOf(Profiles.hiddenApps(this).size()),
                v -> push(getString(R.string.hub_hidden), this::hiddenPage)), R.drawable.ic_hub_eye, 0xFF64748B));
    }

    private void systemPage(LinearLayout r) {
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_about), null,
                v -> push(getString(R.string.hub_about), pages::about)), R.drawable.ic_hub_info, 0xFF94A3B8));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_storage), null,
                v -> push(getString(R.string.hub_storage), pages::storage)), R.drawable.ic_apps, 0xFFEF4444));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_datetime), null,
                v -> push(getString(R.string.hub_datetime), pages::dateTime)), R.drawable.ic_hub_sun, 0xFFF59E0B));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_keyboard), null,
                v -> push(getString(R.string.hub_keyboard), pages::keyboard)), R.drawable.ic_hub_layout, 0xFF8B5CF6));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_bluetooth), null,
                v -> push(getString(R.string.hub_bluetooth), pages::bluetooth)), R.drawable.ic_hub_sound, 0xFF3B82F6));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_language), null,
                v -> push(getString(R.string.hub_language), pages::language)), R.drawable.ic_hub_person, 0xFF14B8A6));
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_inputs), null, v -> {
            startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }), R.drawable.ic_input, 0xFF14B8A6));
        system(r, R.string.hub_accounts, Settings.ACTION_SYNC_SETTINGS, R.drawable.ic_hub_account, 0xFF6B7280);
        r.addView(Ui.withIcon(Ui.navRow(this, getString(R.string.hub_all_android), null, v -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(SYSTEM_SETTINGS);
            open(i != null ? i : new Intent(Settings.ACTION_SETTINGS));
        }), R.drawable.ic_settings, 0xFF475569));
    }

    private void system(LinearLayout r, int title, String action, int icon, int color) {
        Intent i = SystemSettings.intent(this, action);
        if (i == null) return;   // this firmware has no such page
        r.addView(Ui.withIcon(Ui.navRow(this, getString(title), null, v -> open(i)), icon, color));
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private String widgetsSummary() {
        int n = Widgets.shown(this).size();
        return n == 0 ? getString(R.string.hub_off) : getString(R.string.hub_widgets_on, n);
    }

    private void widgetsPage(LinearLayout r) {
        List<String> shown = Widgets.shown(this);
        for (String key : Widgets.ALL) {
            int at = shown.indexOf(key);
            String detail = at < 0 ? getString(R.string.hub_off) : getString(R.string.hub_widget_place, at + 1);
            View row = Ui.row(this, getString(widgetName(key)), detail, v -> {
                if (!Widgets.toggle(this, key)) Toast.makeText(this, getString(R.string.hub_widgets_full,
                        Widgets.MAX_SHOWN), Toast.LENGTH_SHORT).show();
                show(stack.peek(), false);
            });
            // Menu moves a shown widget one place earlier, so the four can be arranged.
            row.setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_MENU
                        || e.getAction() != KeyEvent.ACTION_UP) return code == KeyEvent.KEYCODE_MENU;
                Widgets.moveEarlier(this, key);
                show(stack.peek(), false);
                return true;
            });
            r.addView(row);
        }
        r.addView(Ui.header(this, getString(R.string.widget_weather)));
        String city = Widgets.city(this);
        r.addView(Ui.navRow(this, getString(R.string.hub_weather_city), city.isEmpty() ? getString(R.string.hub_not_set)
                : city,
                v -> startActivity(new Intent(this, WeatherActivity.class))));
        boolean f = Widgets.fahrenheit(this);
        r.addView(Ui.row(this, getString(R.string.hub_weather_unit), f ? "°F" : "°C", v -> {
            Widgets.setFahrenheit(this, !f);
            show(stack.peek(), false);
        }));
        r.addView(Ui.note(this, getString(R.string.hub_widgets_hint, Widgets.MAX_SHOWN)));
        r.addView(Ui.note(this, getString(R.string.hub_widgets_note)));
    }

    static int widgetName(String key) {
        switch (key) {
            case Widgets.WEATHER: return R.string.widget_weather;
            case Widgets.CALENDAR: return R.string.widget_calendar;
            case Widgets.WATCHING: return R.string.widget_watching;
            case Widgets.NOTES: return R.string.widget_notes;
            default: return R.string.widget_recent;
        }
    }

    private void hiddenPage(LinearLayout r) {
        PackageManager pm = getPackageManager();
        boolean any = false;
        for (String pkg : Profiles.hiddenApps(this)) {
            String label = label(pm, pkg);
            if (label == null) continue;   // not installed: nothing to show again
            any = true;
            r.addView(Ui.row(this, label, getString(R.string.hub_show), v -> {
                Profiles.setHidden(this, pkg, false);
                show(stack.peek(), false);
            }));
        }
        if (!any) r.addView(Ui.text(this, getString(R.string.hub_hidden_none), 16, R.string.font_light, Ui.TEXT_DIM));
        r.addView(Ui.note(this, getString(R.string.hub_hidden_note)));
    }

    private void screensaverPage(LinearLayout r) {
        String chosen = Scenes.chosen(this);
        for (String scene : Scenes.ALL) {
            r.addView(Ui.row(this, getString(Scenes.nameOf(scene)), scene.equals(chosen) ? "✓" : null, v -> {
                Scenes.choose(this, scene);
                show(stack.peek(), false);
            }));
        }
        r.addView(Ui.header(this, getString(R.string.hub_screensaver_more)));
        r.addView(Ui.row(this, getString(R.string.hub_screensaver_preview), null,
                v -> startActivity(new Intent(this, ScenePreviewActivity.class))));
        boolean active = Scenes.isSystemScreensaver(this);
        r.addView(Ui.toggle(this, getString(R.string.hub_screensaver_use), active, v -> {
            if (!Scenes.makeSystemScreensaver(this)) Toast.makeText(this, R.string.hub_screensaver_denied,
                    Toast.LENGTH_LONG).show();
            show(stack.peek(), false);
        }));
        r.addView(pages.screensaverDelay());
    }

    // --- background apps ---------------------------------------------------------------------------------------------

    private String freeMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        return getString(R.string.hub_free, Formatter.formatShortFileSize(this, mi.availMem));
    }

    private void runningPage(LinearLayout r) {
        ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        r.addView(memoryBar(mi));
        if (!usageAllowed()) {
            TextView t = Ui.text(this, getString(R.string.hub_running_no_access), 15, R.string.font_light, Ui.TEXT_DIM);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 16), 0, 0);
            r.addView(t);
            return;
        }
        if (running == null) {
            r.addView(Ui.note(this, getString(R.string.hub_loading)));
            load(this::readRunning, found -> {
                running = found != null ? found : new ArrayList<>();
                show(stack.peek(), false);
            });
            return;
        }
        List<Running> apps = running;
        if (apps.isEmpty()) {
            TextView t = Ui.text(this, getString(R.string.hub_running_none), 16, R.string.font_light, Ui.TEXT_DIM);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 16), 0, 0);
            r.addView(t);
            return;
        }
        r.addView(Ui.row(this, getString(R.string.hub_running_close_all), null, v -> {
            long before = availMem();
            for (Running u : apps) am.killBackgroundProcesses(u.pkg);
            afterClose(getString(R.string.hub_running_closed_all), before);
        }));
        r.addView(Ui.header(this, getString(R.string.hub_running_recent)));
        long now = System.currentTimeMillis();
        for (Running u : apps) {
            LinearLayout row = Ui.row(this, u.label, DateUtils.getRelativeTimeSpanString(u.used, now,
                    DateUtils.MINUTE_IN_MILLIS).toString(), v -> {
                long before = availMem();
                am.killBackgroundProcesses(u.pkg);
                afterClose(getString(R.string.hub_running_closed, u.label), before);
            });
            if (u.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(u.icon);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
                ilp.setMarginEnd(Ui.dp(this, 14));
                row.addView(iv, 0, ilp);
            }
            r.addView(row);
        }
        r.addView(Ui.note(this, getString(R.string.hub_running_note)));
    }

    private static final class Running {
        final String pkg, label;
        final long used;
        final Drawable icon;

        Running(String pkg, String label, long used, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.used = used;
            this.icon = icon;
        }
    }

    /** The last reading, kept while the page is open so a rebuild doesn't read Android's usage history again. */
    private List<Running> running;

    /** Off the main thread: usage history and every icon are slow reads. */
    private List<Running> readRunning() {
        PackageManager pm = getPackageManager();
        int px = Ui.dp(this, 26);
        List<Running> out = new ArrayList<>();
        for (UsageStats u : recentApps()) {
            String pkg = u.getPackageName();
            out.add(new Running(pkg, label(pm, pkg), u.getLastTimeUsed(), Ui.smallIcon(this, icon(pm, pkg), px)));
        }
        return out;
    }

    private long availMem() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        return mi.availMem;
    }

    /** Android frees the memory a moment after the kill: read it again then, and say how much came back. */
    private void afterClose(String message, long before) {
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            long freed = availMem() - before;
            Toast.makeText(this, freed > 1024 * 1024
                    ? getString(R.string.hub_running_freed, message, Formatter.formatShortFileSize(this, freed))
                    : message, Toast.LENGTH_SHORT).show();
            running = null;
            if (!stack.isEmpty()) show(stack.peek(), false);
        }, 900);
    }

    private View memoryBar(ActivityManager.MemoryInfo mi) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.glass(this, 18));
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, pad, pad, pad);
        long used = mi.totalMem - mi.availMem;
        box.addView(Ui.text(this, getString(R.string.hub_memory, Formatter.formatShortFileSize(this, used),
                Formatter.formatShortFileSize(this, mi.totalMem)), 17, R.string.font_regular, Ui.TEXT));
        FrameLayout track = new FrameLayout(this);
        GradientDrawable t = new GradientDrawable();
        t.setColor(0x26FFFFFF);
        t.setCornerRadius(Ui.dp(this, 4));
        track.setBackground(t);
        View fill = new View(this);
        GradientDrawable f = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF34D399, used > mi.totalMem * 0.8 ? 0xFFF87171 : 0xFF60A5FA});
        f.setCornerRadius(Ui.dp(this, 4));
        fill.setBackground(f);
        int width = Ui.dp(this, 500);
        track.addView(fill, new FrameLayout.LayoutParams((int) (width * Math.min(1.0, (double) used / mi.totalMem)),
                Ui.dp(this, 8)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(width, Ui.dp(this, 8));
        tl.topMargin = Ui.dp(this, 12);
        box.addView(track, tl);
        TextView free = Ui.text(this, freeMemory(), 13, R.string.font_light, Ui.TEXT_DIM);
        free.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(free);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 14);
        box.setLayoutParams(lp);
        return box;
    }

    private boolean usageAllowed() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int mode = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Apps with a TV entry used in the last 12 hours, most recent first, minus Home and Android's own. */
    private List<UsageStats> recentApps() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - 12 * DateUtils.HOUR_IN_MILLIS, now);
        PackageManager pm = getPackageManager();
        List<UsageStats> out = new ArrayList<>();
        for (UsageStats u : stats.values()) {
            String pkg = u.getPackageName();
            if (pkg.equals(getPackageName()) || NEVER_CLOSE.contains(pkg)) continue;
            if (u.getTotalTimeInForeground() <= 0
                    || u.getLastTimeUsed() < now - 12 * DateUtils.HOUR_IN_MILLIS) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null
                    && pm.getLaunchIntentForPackage(pkg) == null) continue;
            out.add(u);
        }
        out.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static Drawable icon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
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
