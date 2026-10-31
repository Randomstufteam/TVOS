package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** Settings > Screensaver > Preview: the chosen scene full screen until any key. */
public final class ScenePreviewActivity extends Activity {
    private SceneView scene;
    private ClockScene clock;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String chosen = Scenes.chosen(this);
        if (Scenes.CLOCK.equals(chosen)) {
            setContentView(R.layout.dream_clock);
            clock = new ClockScene(getWindow().getDecorView());
            clock.useHomeWallpaper(this);
            return;
        }
        scene = new SceneView(this, chosen);
        setContentView(scene);
    }

    @Override
    protected void onResume() {
        super.onResume();
        acceptKeysAfter = android.os.SystemClock.uptimeMillis() + 700;
        // Started here, not in onCreate: onPause stops both, and a preview brought back must move again.
        if (scene != null) scene.start();
        if (clock != null) clock.start();
    }

    @Override
    protected void onPause() {
        if (scene != null) scene.stop();
        if (clock != null) clock.stop();
        super.onPause();
    }

    /** Keys before this uptime are the tail of the OK press that opened the preview, not a request to close it. */
    private long acceptKeysAfter;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (android.os.SystemClock.uptimeMillis() < acceptKeysAfter) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }
}
