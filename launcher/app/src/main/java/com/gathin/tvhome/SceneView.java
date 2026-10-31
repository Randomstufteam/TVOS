package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.text.DateFormat;
import java.util.Date;
import java.util.Random;

/**
 * The drawn screensaver scenes: Aurora (curtains of light over a starry sky), Warp (a starfield rushing past) and
 * Nebula (big colour clouds drifting through each other), with a thin clock that wanders slowly so nothing stays in
 * one place. Plain Canvas shapes and gradients at most 30 frames a second, no bitmaps and no blur.
 *
 * Every gradient is made once per size and only moved, scaled or faded per frame (a shader's local matrix and the
 * paint's alpha), and the clock's text is formatted once a second: a frame allocates nothing, so the garbage
 * collector has nothing to do while the screensaver runs.
 */
final class SceneView extends View {
    private static final long FRAME_MS = 33;
    private static final int[][] CURTAINS = {{0x0034D399, 0xFF34D399, 0x0010B981}, {0x0060A5FA, 0xFF22D3EE, 0x000EA5E9},
            {0x00A78BFA, 0xFFC084FC, 0x008B5CF6}};
    /** Each curtain's peak opacity: the colours above are full strength, the paint's alpha brings them down. */
    private static final int[] CURTAIN_ALPHA = {0x88, 0x66, 0x55};
    private static final int[] NEBULA = {0x99EC4899, 0x998B5CF6, 0x8806B6D4, 0x77F59E0B, 0x8810B981};

    private final String scene;
    private final Random rnd = new Random(7);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path ridge = new Path();
    private final Matrix matrix = new Matrix();
    private final long start = SystemClock.uptimeMillis();
    private float[] sx, sy, sz, tw;   // stars: position, depth (warp) or twinkle phase
    private boolean running;
    private final DateFormat timeFormat;
    private final DateFormat dateFormat;
    private final Date now = new Date();
    private String timeText = "";
    private String dateText = "";
    private long textSecond = -1;
    private final float clockLeft, clockBottom, clockDx, clockDy, dateGap;

    // Made once per size.
    private Shader sky;
    private final Shader[] curtains = new Shader[3];
    private final float[] curtainBase = new float[3], curtainAmp = new float[3], curtainDepth = new float[3];
    private Shader core;
    private final Shader[] clouds = new Shader[NEBULA.length];

    SceneView(Context c, String scene) {
        super(c);
        this.scene = scene;
        timePaint.setTypeface(UiFont.get(c, R.string.font_extralight));
        timePaint.setColor(0xF2FFFFFF);
        timePaint.setTextSize(Ui.dp(c, 64));
        datePaint.setTypeface(UiFont.get(c, R.string.font_medium));
        datePaint.setColor(0xB3FFFFFF);
        datePaint.setTextSize(Ui.dp(c, 13));
        datePaint.setLetterSpacing(0.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        timeFormat = android.text.format.DateFormat.getTimeFormat(c);
        dateFormat = DateFormat.getDateInstance(DateFormat.FULL);
        clockLeft = Ui.dp(c, 72);
        clockBottom = Ui.dp(c, 92);
        clockDx = Ui.dp(c, 30);
        clockDy = Ui.dp(c, 18);
        dateGap = Ui.dp(c, 6);
        setBackgroundColor(Color.BLACK);
    }

    void start() {
        running = true;
        postInvalidateOnAnimation();
    }

    void stop() {
        running = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        boolean warp = Scenes.WARP.equals(scene);
        int n = warp ? 260 : 140;
        sx = new float[n];
        sy = new float[n];
        sz = new float[n];
        tw = new float[n];
        for (int i = 0; i < n; i++) {
            if (warp) {
                // Warp's stars are offsets from the moving centre, spread as a respawn spreads them.
                sx[i] = (rnd.nextFloat() - 0.5f) * w * 2;
                sy[i] = (rnd.nextFloat() - 0.5f) * h * 2;
            } else {
                sx[i] = rnd.nextFloat() * w;
                sy[i] = rnd.nextFloat() * h * (Scenes.AURORA.equals(scene) ? 0.75f : 1f);
            }
            sz[i] = rnd.nextFloat();
            tw[i] = rnd.nextFloat() * 6.28f;
        }
        sky = new LinearGradient(0, 0, 0, h, 0xFF02030A, 0xFF071426, Shader.TileMode.CLAMP);
        for (int k = 0; k < 3; k++) {
            curtainBase[k] = h * (0.22f + k * 0.09f);
            curtainAmp[k] = h * (0.07f + 0.02f * k);
            curtainDepth[k] = h * (0.38f + 0.06f * k);
            curtains[k] = new LinearGradient(0, curtainBase[k] - curtainAmp[k], 0, curtainBase[k] + curtainDepth[k],
                    CURTAINS[k], new float[]{0f, 0.25f, 1f}, Shader.TileMode.CLAMP);
        }
        // Centred on the origin with radius 1: a frame moves and scales them with the local matrix.
        core = new RadialGradient(0, 0, 1, 0x332563EB, 0x00000000, Shader.TileMode.CLAMP);
        for (int k = 0; k < NEBULA.length; k++) {
            clouds[k] = new RadialGradient(0, 0, 1, NEBULA[k], 0x00000000, Shader.TileMode.CLAMP);
        }
        // The aurora's horizon ridge never moves.
        ridge.reset();
        ridge.moveTo(0, h);
        for (int i = 0; i <= 24; i++) {
            ridge.lineTo(w * i / 24f, h * 0.86f + h * 0.04f * (float) Math.sin(i * 1.3f) + h * 0.02f * (float) Math.sin(i * 3.1f));
        }
        ridge.lineTo(w, h);
        ridge.close();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (sx == null) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        int w = getWidth(), h = getHeight();
        switch (scene) {
            case Scenes.WARP: warp(c, t, w, h); break;
            case Scenes.NEBULA: nebula(c, t, w, h); break;
            default: aurora(c, t, w); break;
        }
        clock(c, t, h);
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** A shader moved to (x, y) and scaled by r: cached gradients drawn anywhere without a new object. */
    private void place(Shader s, float x, float y, float r) {
        matrix.setScale(r, r);
        matrix.postTranslate(x, y);
        s.setLocalMatrix(matrix);
        fill.setShader(s);
    }

    // --- Aurora --------------------------------------------------------------------------------------------------------

    private void aurora(Canvas c, float t, int w) {
        fill.setShader(sky);
        fill.setAlpha(255);
        c.drawPaint(fill);
        stars(c, t);
        // Three curtains: each a ribbon whose top edge ripples, filled with its gradient and pulsing through alpha.
        for (int k = 0; k < 3; k++) {
            float base = curtainBase[k], amp = curtainAmp[k], depth = curtainDepth[k];
            path.reset();
            int steps = 48;
            for (int i = 0; i <= steps; i++) {
                float x = w * i / (float) steps;
                float y = base + amp * (float) Math.sin(x / w * 6.28f * (1.2f + k * 0.35f) + t * (0.35f + k * 0.12f) + k)
                        + amp * 0.4f * (float) Math.sin(x / w * 17f - t * 0.6f);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            for (int i = steps; i >= 0; i--) {
                float x = w * i / (float) steps;
                path.lineTo(x, base + depth + amp * 0.6f * (float) Math.sin(x / w * 5f + t * 0.25f + k * 2));
            }
            path.close();
            float pulse = 0.75f + 0.25f * (float) Math.sin(t * 0.5f + k * 1.7f);
            fill.setShader(curtains[k]);
            fill.setAlpha(Math.round(CURTAIN_ALPHA[k] * pulse));
            c.drawPath(path, fill);
        }
        fill.setShader(null);
        fill.setAlpha(255);
        fill.setColor(0xFF010205);
        c.drawPath(ridge, fill);
    }

    private void stars(Canvas c, float t) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < sx.length; i++) {
            float a = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * (0.6f + sz[i]) + tw[i]));
            paint.setColor(Color.argb(Math.round(200 * a * (0.4f + 0.6f * sz[i])), 255, 255, 255));
            c.drawCircle(sx[i], sy[i], 0.6f + sz[i] * 1.4f, paint);
        }
    }

    // --- Warp ----------------------------------------------------------------------------------------------------------

    private void warp(Canvas c, float t, int w, int h) {
        c.drawColor(0xFF000005);
        float cx = w / 2f + w * 0.05f * (float) Math.sin(t * 0.13f), cy = h / 2f + h * 0.05f * (float) Math.cos(t * 0.11f);
        // A faint core glow where the stars come from.
        place(core, cx, cy, w * 0.35f);
        fill.setAlpha(255);
        c.drawPaint(fill);
        fill.setShader(null);
        float speed = 0.0045f * (1.2f + 0.6f * (float) Math.sin(t * 0.2f));
        for (int i = 0; i < sx.length; i++) {
            float z = sz[i] - speed;
            if (z <= 0.02f) {
                z = 1f;
                sx[i] = (rnd.nextFloat() - 0.5f) * w * 2;
                sy[i] = (rnd.nextFloat() - 0.5f) * h * 2;
            }
            sz[i] = z;
            float px = cx + sx[i] / z * 0.5f, py = cy + sy[i] / z * 0.5f;
            if (px < -20 || px > w + 20 || py < -20 || py > h + 20) {
                sz[i] = 0;   // off screen: respawn next frame
                continue;
            }
            float pz = z + 0.06f;
            float qx = cx + sx[i] / pz * 0.5f, qy = cy + sy[i] / pz * 0.5f;
            float b = 1f - z;
            int tint = i % 7 == 0 ? 0x9DB4FF : (i % 11 == 0 ? 0xFFD9A8 : 0xFFFFFF);
            paint.setColor((Math.round(255 * Math.min(1f, b * 1.4f)) << 24) | tint);
            paint.setStrokeWidth(0.8f + b * 3f);
            c.drawLine(qx, qy, px, py, paint);
        }
    }

    // --- Nebula --------------------------------------------------------------------------------------------------------

    private void nebula(Canvas c, float t, int w, int h) {
        c.drawColor(0xFF03020A);
        fill.setAlpha(255);
        for (int k = 0; k < clouds.length; k++) {
            float px = w * (0.5f + 0.38f * (float) Math.sin(t * (0.05f + k * 0.013f) + k * 1.9f));
            float py = h * (0.5f + 0.36f * (float) Math.cos(t * (0.043f + k * 0.011f) + k * 2.7f));
            float r = w * (0.32f + 0.08f * (float) Math.sin(t * 0.07f + k));
            place(clouds[k], px, py, r);
            c.drawCircle(px, py, r, fill);
        }
        fill.setShader(null);
        stars(c, t);
    }

    // --- clock -------------------------------------------------------------------------------------------------------

    /** Bottom-left, wandering a few dozen pixels over minutes so it never burns in. Text is made once a second. */
    private void clock(Canvas c, float t, int h) {
        long second = System.currentTimeMillis() / 1000;
        if (second != textSecond) {
            textSecond = second;
            now.setTime(second * 1000);
            timeText = timeFormat.format(now);
            dateText = dateFormat.format(now).toUpperCase(java.util.Locale.getDefault());
        }
        float x = clockLeft + clockDx * (float) Math.sin(t / 97f);
        float y = h - clockBottom + clockDy * (float) Math.cos(t / 71f);
        c.drawText(dateText, x + 3, y - timePaint.getTextSize() - dateGap, datePaint);
        c.drawText(timeText, x, y, timePaint);
    }
}
