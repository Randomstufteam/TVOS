package com.gathin.tvhome;

import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Motion for the launcher's own screens (Settings, the profile picker, widgets, Weather): one set of durations and
 * curves so every screen moves alike, and the reduced-motion spring Home's highlight uses.
 *
 * Only alpha, translation and scale are animated, which the GPU composes without redrawing the views, so a 1 GB TV
 * keeps up. With animations off or reduced_motion on (Config), nothing here animates: views are simply in place.
 */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    /** A state change (focus out, a value swap). */
    static final int FAST_MS = 150;
    /** Focus in. */
    static final int NORMAL_MS = 220;
    /** A view arriving, and one leaving: arrivals take their time, departures get out of the way. */
    static final int ENTER_MS = 320;
    static final int EXIT_MS = 180;
    /** Between the items of a list arriving one after another; capped so a long list is still quick. */
    static final int STAGGER_MS = 40;
    static final int STAGGER_MAX = 8;

    /**
     * animations on and reduced_motion off, as Config last read them. Config.load runs in every activity's
     * attachBaseContext (LauncherLocale), so this is current before any screen builds.
     */
    static volatile boolean enabled = true;

    /** Made on first use: android.view.animation classes cannot be built while the JVM tests load this class. */
    private static TimeInterpolator easeOut, pop;

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }

    /** Cubic ease-out: quick to start, soft to land. */
    static TimeInterpolator easeOut() {
        if (easeOut == null) easeOut = new PathInterpolator(0.33f, 1f, 0.68f, 1f);
        return easeOut;
    }

    /** Ease-out with a small overshoot, for things that come forward (focus, a profile appearing). */
    static TimeInterpolator pop() {
        if (pop == null) pop = new OvershootInterpolator(1.1f);
        return pop;
    }

    /**
     * The view arrives: fades in while sliding the last few dp into place from (dxDp, dyDp), the index-th of a group.
     * Its alpha must be 1 and translation 0 at rest; a view still arriving from an earlier call starts over.
     */
    static void enter(View v, int index, float dxDp, float dyDp) {
        v.animate().cancel();
        v.setLayerType(View.LAYER_TYPE_NONE, null);   // an earlier entrance cut short keeps no layer
        if (!enabled) {
            v.setAlpha(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            return;
        }
        float density = v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationX(dxDp * density);
        v.setTranslationY(dyDp * density);
        layered(v, v.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(ENTER_MS)
                .setStartDelay((long) Math.min(index, STAGGER_MAX) * STAGGER_MS)
                .setInterpolator(easeOut())).start();
    }

    /** Arrives growing from a little smaller, with the pop: for avatars and cards. */
    static void popIn(View v, int index) {
        v.animate().cancel();
        v.setLayerType(View.LAYER_TYPE_NONE, null);
        if (!enabled) {
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        v.setAlpha(0f);
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        layered(v, v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ENTER_MS)
                .setStartDelay((long) Math.min(index, STAGGER_MAX) * STAGGER_MS)
                .setInterpolator(pop())).start();
    }

    /**
     * A fade of a whole group draws it once into a GPU layer instead of every child at every frame. withLayer()
     * builds that layer as the animation starts, which a view not laid out yet cannot have (Android logs
     * "Incorrectly called buildLayer" and throws it away): such a view, just built for a page, gets the layer
     * type instead, and the layer is made with its first frame.
     */
    private static android.view.ViewPropertyAnimator layered(View v, android.view.ViewPropertyAnimator a) {
        if (v.isLaidOut()) return a.withLayer();
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return a.withEndAction(() -> v.setLayerType(View.LAYER_TYPE_NONE, null));
    }

    /** Focus in: scale (and lift, in px) with the pop; focus out: back with a plain ease, a little faster. */
    static void focus(View v, boolean has, float scale, float liftPx) {
        // No cancel(): a new animation takes over only the properties it animates, so a row that gets focus while
        // it is still arriving keeps fading and sliding in.
        float s = has ? scale : 1f;
        float z = has ? liftPx : 0f;
        if (!enabled) {
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationZ(z);
            return;
        }
        v.animate().scaleX(s).scaleY(s).translationZ(z).setStartDelay(0)
                .setDuration(has ? NORMAL_MS : FAST_MS).setInterpolator(has ? pop() : easeOut()).start();
    }

    /** Fades a view to the given alpha (0 hides it), or sets it at once with motion off. */
    static void fade(View v, float alpha, int ms) {
        if (!enabled) {
            v.setAlpha(alpha);
            return;
        }
        v.animate().alpha(alpha).setStartDelay(0).setDuration(ms).setInterpolator(easeOut()).start();
    }
}
