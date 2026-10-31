package com.gathin.tvhome;

import android.service.dreams.DreamService;

/** The drawn screensaver scenes (SceneView) as Android's screensaver; the profile's choice picks the scene. */
public class SceneDream extends DreamService {
    private SceneView scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        String chosen = Scenes.chosen(this);
        scene = new SceneView(this, Scenes.CLOCK.equals(chosen) ? Scenes.AURORA : chosen);
        setContentView(scene);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
