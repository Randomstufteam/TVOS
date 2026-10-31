package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Views for the screens built in code (profile picker, Settings, widgets), in Home's own look: the Outfit
 * font, frosted glass cards with a hairline rim, white focus with a small lift. Plain views and drawables only,
 * no blur, so they stay cheap on a 1 GB TV.
 */
final class Ui {
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_DIM = 0xB3FFFFFF;
    static final int TEXT_FAINT = 0x80FFFFFF;
    static final int GLASS_TOP = 0x2EFFFFFF;
    static final int GLASS_BOTTOM = 0x14FFFFFF;
    static final int RIM = 0x38FFFFFF;
    static final int FOCUS = 0xF5FFFFFF;
    static final int ON_FOCUS = 0xFF101114;
    /** The sheet behind dialogs and overlays. */
    private static final int SHEET_TOP = 0xF71E2029;
    private static final int SHEET_BOTTOM = 0xF710111A;
    /** A switch that is on: the same green on the dark row and on the white focused one. */
    private static final int SWITCH_ON = 0xFF34C759;
    private static final int SWITCH_OFF = 0x668A8F99;

    /** The backdrop's texture: small on purpose, stretched over the screen by the GPU (about 230 KB). */
    private static final int BACKDROP_W = 320, BACKDROP_H = 180;
    private static Bitmap backdrop;
    private static int backdropAccent;

    private Ui() {
    }

    /**
     * The screens' shared backdrop: near-black with one soft light in the accent colour and a cooler one low on the
     * right. Painted once into a small bitmap, so it costs the GPU one textured quad on every frame instead of three
     * full-screen gradients, and only one is kept, however many screens use it.
     */
    static synchronized Drawable backdrop(Context c, int accent) {
        if (backdrop == null || backdropAccent != accent) {
            Bitmap b = Bitmap.createBitmap(BACKDROP_W, BACKDROP_H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, BACKDROP_H, 0xFF0B0D16, 0xFF05060B, Shader.TileMode.CLAMP));
            canvas.drawPaint(p);
            p.setShader(new RadialGradient(BACKDROP_W * 0.16f, BACKDROP_H * 0.08f, BACKDROP_W * 0.72f,
                    withAlpha(accent, 0x5C), withAlpha(accent, 0), Shader.TileMode.CLAMP));
            canvas.drawPaint(p);
            p.setShader(new RadialGradient(BACKDROP_W * 0.92f, BACKDROP_H * 1.04f, BACKDROP_W * 0.6f,
                    0x476366F1, 0x006366F1, Shader.TileMode.CLAMP));
            canvas.drawPaint(p);
            backdrop = b;
            backdropAccent = accent;
        }
        BitmapDrawable d = new BitmapDrawable(c.getResources(), backdrop);
        d.setFilterBitmap(true);
        return d;
    }

    /** One background thread that exits after a few idle seconds, so a screen left open holds no idle stack. */
    static ExecutorService worker() {
        ThreadPoolExecutor e = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    /** An icon drawn once at the size it is shown: a full-density adaptive icon holds ten times those pixels. */
    static Drawable smallIcon(Context c, Drawable d, int px) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(b));
        return new BitmapDrawable(c.getResources(), b);
    }

    /** The dialogs each screen has open: a screen rebuilt under one must not leak it, or its whole view tree. */
    private static final Map<Activity, Set<Dialog>> DIALOGS = new WeakHashMap<>();

    private static void track(Activity a, Dialog d) {
        Set<Dialog> open = DIALOGS.get(a);
        if (open == null) DIALOGS.put(a, open = new HashSet<>());
        open.add(d);
        Set<Dialog> set = open;
        d.setOnDismissListener(x -> set.remove(d));
    }

    /** Closes this screen's dialogs. Called as it stops being on screen or is destroyed. */
    static void closeDialogs(Activity a) {
        Set<Dialog> open = DIALOGS.remove(a);
        if (open == null) return;
        for (Dialog d : new HashSet<>(open)) d.dismiss();
    }

    /** The sheet behind a dialog. */
    static GradientDrawable sheet(Context c) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SHEET_TOP,
                SHEET_BOTTOM});
        bg.setCornerRadius(dp(c, 28));
        bg.setStroke(Math.max(1, dp(c, 1)), RIM);
        return bg;
    }

    /** A capsule button that lifts on focus; its text turns dark on the white. */
    static TextView pill(Context c, CharSequence s, View.OnClickListener click) {
        TextView p = text(c, s, 15, R.string.font_medium, TEXT);
        p.setGravity(Gravity.CENTER);
        p.setFocusable(true);
        p.setClickable(true);
        p.setBackground(focusGlass(c, 21));
        p.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{ON_FOCUS, TEXT}));
        p.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        p.setMaxLines(1);
        // Outfit's last glyph draws a few pixels past its advance and TextView clips text at its padding: a little
        // more width than the text needs, with the text centred in it, keeps that overhang inside the clip.
        p.setMinWidth(Math.round(p.getPaint().measureText(s.toString())) + dp(c, 48 + 8));
        p.setOnClickListener(click);
        raiseOnFocus(p);
        return p;
    }

    /** A small key cap and what the key does: "OK  Select". */
    static LinearLayout keyHint(Context c, CharSequence key, CharSequence action) {
        LinearLayout h = new LinearLayout(c);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = text(c, key, 11, R.string.font_medium, TEXT_DIM);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 6));
        bg.setStroke(Math.max(1, dp(c, 1)), 0x4DFFFFFF);
        cap.setBackground(bg);
        cap.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        h.addView(cap);
        TextView t = text(c, action, 13, R.string.font_light, TEXT_FAINT);
        t.setPadding(dp(c, 8), 0, 0, 0);
        h.addView(t);
        return h;
    }

    /** Small text under a group of rows. */
    static TextView note(Context c, CharSequence s) {
        TextView t = text(c, s, 13, R.string.font_light, TEXT_FAINT);
        t.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), 0);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    /** A row with a switch at its end. */
    static LinearLayout toggle(Context c, CharSequence title, boolean on, View.OnClickListener click) {
        LinearLayout r = row(c, title, null, click);
        FrameLayout track = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? SWITCH_ON : SWITCH_OFF);
        bg.setCornerRadius(dp(c, 12));
        track.setBackground(bg);
        View knob = new View(c);
        knob.setBackground(circle(0xFFFFFFFF));
        int k = dp(c, 18);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(k, k,
                Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        klp.setMargins(dp(c, 3), 0, dp(c, 3), 0);
        track.addView(knob, klp);
        r.addView(track, new LinearLayout.LayoutParams(dp(c, 42), dp(c, 24)));
        return r;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, float sp, int font, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(c, font));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The frosted card behind widgets and Settings rows: a light vertical gradient with a hairline rim. */
    static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP,
                GLASS_BOTTOM});
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 1)), RIM);
        return g;
    }

    /** Quiet glass when idle, solid white when focused (text then turns dark through textFocus). */
    static StateListDrawable focusGlass(Context c, float radiusDp) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(FOCUS);
        on.setCornerRadius(dp(c, radiusDp));
        GradientDrawable idle = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x1AFFFFFF,
                0x0DFFFFFF});
        idle.setCornerRadius(dp(c, radiusDp));
        idle.setStroke(Math.max(1, dp(c, 1)), 0x1FFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, idle);
        return s;
    }

    /** The widgets' surface: dark frosted glass with a soft sheen at the top and a hairline rim, so it reads the same
     *  over any colour behind it. Focused, it lights up with a white rim. */
    static Drawable card(Context c, float radiusDp, boolean focused) {
        return focused ? sheen(c, radiusDp, 0x4DFFFFFF, 0x24FFFFFF, 0xF2FFFFFF, dp(c, 2))
                : sheen(c, radiusDp, 0xA61C1C22, 0xB30E0E12, 0x33FFFFFF, Math.max(1, dp(c, 1)));
    }

    /**
     * Glass in three static layers: a vertical fill, a light sheen across its top third, and a rim. Drawn once into
     * the view's display list, so it costs the GPU no more than a plain colour while it sits still.
     */
    static Drawable sheen(Context c, float radiusDp, int fillTop, int fillBottom, int rim, int rimPx) {
        float r = dp(c, radiusDp);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{fillTop,
                fillBottom});
        fill.setCornerRadius(r);
        GradientDrawable shine = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        shine.setCornerRadius(r);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(r);
        edge.setStroke(rimPx, rim);
        return new LayerDrawable(new Drawable[]{fill, shine, edge});
    }

    /** The grouped card Settings gathers its rows into: one sheet of glass, the rows inside it. */
    static Drawable groupGlass(Context c) {
        return sheen(c, 20, 0x1FFFFFFF, 0x0CFFFFFF, 0x1FFFFFFF, Math.max(1, dp(c, 1)));
    }

    /** A row inside a grouped card: nothing at rest, solid white while focused. */
    static StateListDrawable focusOnly(Context c, float radiusDp) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(FOCUS);
        on.setCornerRadius(dp(c, radiusDp));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A profile colour as a lit sphere: a diagonal gradient with a soft highlight up and to the left. The first layer
     *  is the oval, so a view with this background casts a round shadow. */
    static Drawable avatar(int color) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{lighten(color, 0.28f),
                color, darken(color, 0.3f)});
        g.setShape(GradientDrawable.OVAL);
        GradientDrawable shine = new GradientDrawable();
        shine.setShape(GradientDrawable.OVAL);
        shine.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        shine.setGradientCenter(0.32f, 0.26f);
        shine.setGradientRadius(80f);
        shine.setColors(new int[]{0x59FFFFFF, 0x00FFFFFF});
        return new LayerDrawable(new Drawable[]{g, shine});
    }

    static int lighten(int c, float f) {
        return Color.rgb(mix(Color.red(c), 255, f), mix(Color.green(c), 255, f), mix(Color.blue(c), 255, f));
    }

    static int darken(int c, float f) {
        return Color.rgb(mix(Color.red(c), 0, f), mix(Color.green(c), 0, f), mix(Color.blue(c), 0, f));
    }

    static int withAlpha(int c, int alpha) {
        return (c & 0x00FFFFFF) | (alpha << 24);
    }

    private static int mix(int a, int b, float f) {
        return Math.round(a + (b - a) * f);
    }

    /** Text that turns dark when its focusable parent (or itself) is focused on white. */
    static void textFocus(TextView t, int idle) {
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{ON_FOCUS, idle}));
        t.setDuplicateParentStateEnabled(true);
    }

    /** A row that opens another page: its detail is followed by a chevron. */
    static LinearLayout navRow(Context c, CharSequence title, CharSequence detail, View.OnClickListener click) {
        LinearLayout r = row(c, title, detail, click);
        TextView chevron = text(c, "›", 24, R.string.font_light, TEXT_FAINT);
        textFocus(chevron, TEXT_FAINT);
        chevron.setPadding(dp(c, 12), 0, 0, dp(c, 3));
        r.addView(chevron);
        return r;
    }

    /** A focusable glass row with a title and an optional detail on the right. */
    static LinearLayout row(Context c, CharSequence title, CharSequence detail, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setTag(R.id.ui_row, Boolean.TRUE);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        // A row that only shows a value (an IP address, the memory) takes focus, so the list scrolls through it,
        // but is no button: OK on it plays no click for nothing.
        r.setClickable(click != null);
        r.setBackground(focusGlass(c, 18));
        r.setPadding(dp(c, 22), 0, dp(c, 20), 0);
        TextView t = text(c, title, 17, R.string.font_regular, TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        textFocus(t, TEXT);
        r.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (detail != null) {
            TextView d = text(c, detail, 15, R.string.font_light, TEXT_DIM);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setMaxWidth(dp(c, 300));
            textFocus(d, TEXT_DIM);
            d.setTag("detail");
            r.addView(d);
        }
        if (click != null) r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(c, 54));
        lp.bottomMargin = dp(c, 10);
        r.setLayoutParams(lp);
        raiseOnFocus(r);
        return r;
    }

    /** Focus brings the row forward: a soft shadow and a slight grow that stays inside the list's side padding. */
    static void raiseOnFocus(View v) {
        v.setOnFocusChangeListener((view, has) -> Motion.focus(view, has, 1.015f, dp(view.getContext(), 10)));
    }

    /** Puts a rounded tile in the row's colour with a white glyph at the start of a row, as on Apple TV's Settings. */
    static LinearLayout withIcon(LinearLayout row, int icon, int color) {
        Context c = row.getContext();
        FrameLayout tile = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(color,
                0.18f), darken(color, 0.12f)});
        bg.setCornerRadius(dp(c, 9));
        tile.setBackground(bg);
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon);
        glyph.setColorFilter(0xFFFFFFFF);
        int g = dp(c, 18);
        tile.addView(glyph, new FrameLayout.LayoutParams(g, g, Gravity.CENTER));
        int s = dp(c, 32);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMarginEnd(dp(c, 16));
        row.addView(tile, 0, lp);
        row.setPadding(dp(c, 13), 0, row.getPaddingEnd(), 0);
        return row;
    }

    static void setDetail(View row, CharSequence detail) {
        View d = row.findViewWithTag("detail");
        if (d instanceof TextView) ((TextView) d).setText(detail);
    }

    static TextView header(Context c, CharSequence s) {
        TextView t = text(c, s, 11, R.string.font_medium, TEXT_FAINT);
        t.setAllCaps(true);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(c, 8), dp(c, 18), 0, dp(c, 10));
        return t;
    }

    /**
     * A vertical list that keeps the focused row in the middle of the screen as focus moves, gliding there, as
     * tvOS does, instead of the stock jump that leaves the row stuck to the screen's top or bottom edge. At the
     * list's ends it stops where the list ends.
     */
    static ScrollView centeringScroll(Context c) {
        ScrollView scroll = new ScrollView(c) {
            private final Rect focusRect = new Rect();

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;   // requestChildFocus does the scrolling, smoothly and centred
            }

            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                if (getHeight() == 0 || getChildCount() == 0) return;
                focused.getDrawingRect(focusRect);
                offsetDescendantRectToMyCoords(focused, focusRect);
                int range = Math.max(0,
                        getChildAt(0).getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
                smoothScrollTo(0, Math.max(0, Math.min(range, focusRect.centerY() - getHeight() / 2)));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return scroll;
    }

    /** A glass dialog with a title and a scrolling list of rows; picking a row closes it and runs the row's action. */
    static final class ListDialog {
        private final Dialog dialog;
        private final LinearLayout box;
        private final LinearLayout rows;
        private final Activity activity;

        ListDialog(Activity a, CharSequence title) {
            activity = a;
            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(a, 26), dp(a, 26), dp(a, 26), dp(a, 16));
            box.setBackground(sheet(a));
            TextView t = text(a, title, 22, R.string.font_light, TEXT);
            t.setPadding(dp(a, 4), 0, 0, dp(a, 14));
            box.addView(t);
            ScrollView scroll = centeringScroll(a);
            scroll.setClipToPadding(false);
            scroll.setPadding(0, dp(a, 4), 0, dp(a, 4));
            rows = new LinearLayout(a);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(dp(a, 6), 0, dp(a, 6), 0);
            rows.setClipChildren(false);
            rows.setClipToPadding(false);   // the padding is room for the focused row to grow into
            scroll.addView(rows);
            box.addView(scroll, new LinearLayout.LayoutParams(dp(a, 440), WRAP));
            dialog.setContentView(box);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setWindowAnimations(0);   // the panel's own motion below, not the stock zoom
            }
        }

        ListDialog add(CharSequence title, CharSequence detail, Runnable action) {
            rows.addView(row(activity, title, detail, v -> {
                dialog.dismiss();
                action.run();
            }));
            return this;
        }

        void show() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            track(activity, dialog);
            dialog.show();
            Motion.popIn(box, 0);
            for (int i = 0; i < rows.getChildCount(); i++) Motion.enter(rows.getChildAt(i), i + 1, 0, 10);
            if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            // Keep the dialog within the screen: the rows scroll beyond about five.
            if (dialog.getWindow() != null) {
                int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.82f);
                dialog.getWindow().getDecorView().measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                if (dialog.getWindow().getDecorView().getMeasuredHeight() > max) {
                    dialog.getWindow().setLayout(WRAP, max);
                }
            }
        }
    }

    static ListDialog listDialog(Activity a, CharSequence title) {
        return new ListDialog(a, title);
    }

    /** A glass dialog asking for one line of text with the TV keyboard. */
    static void askText(Activity a, CharSequence title, String initial, int maxLength, Consumer<String> done) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 32), dp(a, 28), dp(a, 32), dp(a, 26));
        box.setBackground(sheet(a));
        box.addView(text(a, title, 22, R.string.font_light, TEXT));
        EditText in = new EditText(a);
        in.setText(initial);
        in.setSelection(in.getText().length());
        in.setSingleLine(true);
        in.setTextColor(TEXT);
        in.setHintTextColor(TEXT_FAINT);
        in.setTypeface(UiFont.get(a, R.string.font_regular));
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        in.setImeOptions(EditorInfo.IME_ACTION_DONE);
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        in.setBackground(glass(a, 14));
        in.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 420), WRAP);
        lp.topMargin = dp(a, 18);
        box.addView(in, lp);
        // Enter reaches the editor action listener on both key down and key up: answer only once.
        boolean[] answered = {false};
        Runnable finish = () -> {
            if (answered[0]) return;
            answered[0] = true;
            String v = in.getText().toString().trim();
            d.dismiss();
            if (!v.isEmpty()) done.accept(v);
        };
        in.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                finish.run();
                return true;
            }
            return false;
        });
        LinearLayout ok = row(a, a.getString(android.R.string.ok), null, v -> finish.run());
        LinearLayout.LayoutParams okLp = (LinearLayout.LayoutParams) ok.getLayoutParams();
        okLp.topMargin = dp(a, 14);
        box.addView(ok, okLp);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            d.getWindow().setWindowAnimations(0);
            // Above the keyboard, which rises from the bottom of the screen.
            d.getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            d.getWindow().getAttributes().y = dp(a, 36);
        }
        if (a.isFinishing() || a.isDestroyed()) return;
        track(a, d);
        d.show();
        Motion.popIn(box, 0);
        in.requestFocus();
    }
}
