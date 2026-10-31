package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the Continue watching widget: while an app plays something through a media session (YouTube, Netflix, most
 * streaming apps publish one), its title goes into the active profile's history. Android only shows other apps' media
 * sessions to an enabled notification listener, which is all this service is for: it reads no notifications.
 * Enable with {@code cmd notification allow_listener com.gathin.tvhome/.WatchListener}.
 */
public final class WatchListener extends NotificationListenerService {
    private MediaSessionManager sessions;
    private final Map<MediaController.Callback, MediaController> watched = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;
    /** The last title written, so the many playback updates of one title cost nothing. */
    private String lastRecorded;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, WatchListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException notAllowed) {
            sessions = null;
        }
    }

    @Override
    public void onListenerDisconnected() {
        unwatchAll();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        sessions = null;
        super.onListenerDisconnected();
    }

    /** Android may stop the service without a disconnect first: never leave callbacks on the apps' sessions. */
    @Override
    public void onDestroy() {
        unwatchAll();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        sessions = null;
        super.onDestroy();
    }

    private void watch(List<MediaController> controllers) {
        unwatchAll();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (getPackageName().equals(c.getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    record(c, metadata, c.getPlaybackState());
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    record(c, c.getMetadata(), state);
                }
            };
            c.registerCallback(cb);
            watched.put(cb, c);
            record(c, c.getMetadata(), c.getPlaybackState());
        }
    }

    private void unwatchAll() {
        for (Map.Entry<MediaController.Callback, MediaController> e : watched.entrySet()) {
            e.getValue().unregisterCallback(e.getKey());
        }
        watched.clear();
    }

    /** Only what actually played: a paused or buffering title counts, a session that never started does not. */
    private void record(MediaController c, MediaMetadata m, PlaybackState state) {
        if (m == null || state == null) return;
        int s = state.getState();
        if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_PAUSED) return;
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) return;
        String key = c.getPackageName() + '|' + title + '|' + Profiles.currentId(this);
        if (key.equals(lastRecorded)) return;
        lastRecorded = key;
        String sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        Widgets.recordWatching(this, title, sub, c.getPackageName());
    }

    private static String text(MediaMetadata m, String first, String second) {
        CharSequence v = m.getText(first);
        if (v == null || v.length() == 0) v = m.getText(second);
        return v == null ? null : v.toString();
    }
}
