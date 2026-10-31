package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The on-screen keyboard: a system input method made for a remote. The highlight moves with the D-pad and OK types,
 * with no focus to lose. One drawn view, no bitmaps, no dictionary, nothing stored and no network, so what is typed
 * (passwords included) goes to the app it was typed into and nowhere else.
 *
 * Turn on with: ime enable com.gathin.tvhome/.TvKeyboard; ime set com.gathin.tvhome/.TvKeyboard
 */
public final class TvKeyboard extends InputMethodService {
    private static final int SHIFT = -1, LAYER = -2, BACKSPACE = -3, ACTION = -4, SPACE = 32;

    /** One key: what it types (or one of the codes above), its label, and how many of the 10 columns it spans. */
    private static final class Key {
        final int code;
        final String label;
        final int span;

        Key(int code, String label, int span) {
            this.code = code;
            this.label = label;
            this.span = span;
        }
    }

    private static final String[] LETTERS = {"1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.?"};
    private static final String[] SYMBOLS = {"1234567890", "@#$%&*-+()", "!\"':;/=_~\\", "[]{}<>^|,."};

    private KeyView view;
    private boolean symbols;
    /** Off, the next letter only (once), or every letter (locked: Shift pressed twice). */
    private boolean shifted, shiftLocked;
    private int row = 1, column = 0;
    private Key[][] keys;

    private Key[][] build() {
        String[] top = symbols ? SYMBOLS : LETTERS;
        Key[][] out = new Key[top.length + 1][];
        for (int r = 0; r < top.length; r++) {
            out[r] = new Key[top[r].length()];
            for (int c = 0; c < top[r].length(); c++) out[r][c] = new Key(top[r].charAt(c), String.valueOf(top[r].charAt(c)), 1);
        }
        CharSequence action = actionLabel();
        out[top.length] = new Key[]{
                new Key(SHIFT, "⇧", 1),
                new Key(LAYER, symbols ? "abc" : "?123", 1),
                new Key(SPACE, getString(R.string.keyboard_space), 5),
                new Key(BACKSPACE, "⌫", 1),
                new Key(ACTION, action.toString(), 2),
        };
        return out;
    }

    private CharSequence actionLabel() {
        EditorInfo info = getCurrentInputEditorInfo();
        CharSequence label = info != null ? getTextForImeAction(info.imeOptions) : null;
        return label != null ? label : getString(R.string.keyboard_done);
    }

    /** The app's field stays in view: never the whole-screen editor Android offers on a wide screen. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** Android counts a remote as a hardware keyboard and would otherwise keep this one hidden. */
    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    /** The other half of that: the stock answer refuses every request an app makes without SHOW_EXPLICIT, which is
     *  nearly all of them, as soon as Android's configuration reports a keyboard, and on a TV the remote is one. */
    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        return true;
    }

    @Override
    public View onCreateInputView() {
        view = new KeyView(this);
        return view;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        symbols = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE;
        shiftLocked = false;
        keys = build();
        row = symbols ? 0 : 1;
        column = 0;
        updateCaps();
        if (view != null) view.refresh();
    }

    /** The field's own wish: a capital at the start of a sentence or name, none in a password or an address. */
    private void updateCaps() {
        if (shiftLocked) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        shifted = ic != null && info != null && info.inputType != 0 && ic.getCursorCapsMode(info.inputType) != 0;
    }

    private void move(int dr, int dc) {
        if (dc != 0) {
            column = Math.max(0, Math.min(keys[row].length - 1, column + dc));
        } else {
            int next = Math.max(0, Math.min(keys.length - 1, row + dr));
            if (next != row) {
                // Keep the highlight over the same place on the keyboard: the bottom row's keys have other widths.
                int at = startOf(row, column) + keys[row][column].span / 2;
                row = next;
                int c = 0;
                while (c < keys[row].length - 1 && startOf(row, c) + keys[row][c].span <= at) c++;
                column = c;
            }
        }
        view.invalidate();
    }

    private int startOf(int r, int c) {
        int x = 0;
        for (int i = 0; i < c; i++) x += keys[r][i].span;
        return x;
    }

    private void press() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        Key k = keys[row][column];
        switch (k.code) {
            case SHIFT:
                if (shiftLocked) {
                    shiftLocked = false;
                    shifted = false;
                } else if (shifted) {
                    shiftLocked = true;
                } else {
                    shifted = true;
                }
                break;
            case LAYER:
                symbols = !symbols;
                keys = build();
                column = Math.min(column, keys[row].length - 1);
                break;
            case BACKSPACE:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                updateCaps();
                break;
            case ACTION:
                if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
                break;
            default:
                String s = String.valueOf((char) k.code);
                ic.commitText(shifted ? s.toUpperCase(java.util.Locale.getDefault()) : s, 1);
                updateCaps();
                break;
        }
        view.invalidate();
    }

    private boolean ours(int code) {
        return isInputViewShown() && keys != null && view != null && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (!ours(code)) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); break;
            default:
                // Holding OK repeats Backspace only: one long press must not fill a field with one letter.
                if (e.getRepeatCount() == 0 || keys[row][column].code == BACKSPACE) press();
                break;
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        return ours(code) || super.onKeyUp(code, e);
    }

    /** All the keys, drawn in one view: a redraw per D-pad press and nothing at rest. */
    private final class KeyView extends View {
        private static final int ROW_DP = 42, GAP_DP = 6, PAD_DP = 16, WIDTH_DP = 640;
        private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float rowH, gap, pad, radius, width;
        private final float textBig, textSmall;

        KeyView(Context c) {
            super(c);
            rowH = Ui.dp(c, ROW_DP);
            gap = Ui.dp(c, GAP_DP);
            pad = Ui.dp(c, PAD_DP);
            radius = Ui.dp(c, 12);
            width = Ui.dp(c, WIDTH_DP);
            textBig = Ui.dp(c, 19);
            textSmall = Ui.dp(c, 14);
            panel.setColor(0xF7101119);
            Typeface font = UiFont.get(c, R.string.font_regular);
            text.setTypeface(font);
            text.setTextAlign(Paint.Align.CENTER);
        }

        void refresh() {
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int rows = keys != null ? keys.length : 5;
            setMeasuredDimension(MeasureSpec.getSize(wSpec), Math.round(rows * (rowH + gap) - gap + pad * 2));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (keys == null) return;
            rect.set(0, 0, getWidth(), getHeight() + radius * 2);
            c.drawRoundRect(rect, radius * 2, radius * 2, panel);
            float left = (getWidth() - width) / 2f;
            float unit = (width + gap) / 10f;
            for (int r = 0; r < keys.length; r++) {
                float y = pad + r * (rowH + gap);
                int x = 0;
                for (int k = 0; k < keys[r].length; k++) {
                    Key key = keys[r][k];
                    boolean on = r == row && k == column;
                    rect.set(left + x * unit, y, left + (x + key.span) * unit - gap, y + rowH);
                    boolean lit = key.code == SHIFT && (shifted || shiftLocked);
                    cap.setColor(on ? 0xFFFFFFFF : lit ? 0x66FFFFFF : key.code < 0 ? 0x14FFFFFF : 0x24FFFFFF);
                    c.drawRoundRect(rect, radius, radius, cap);
                    String label = key.code > 0 && key.code != SPACE && (shifted || shiftLocked)
                            ? key.label.toUpperCase(java.util.Locale.getDefault()) : key.label;
                    text.setTextSize(label.length() > 1 ? textSmall : textBig);
                    text.setColor(on ? Ui.ON_FOCUS : Ui.TEXT);
                    c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text);
                    x += key.span;
                }
            }
        }
    }
}
