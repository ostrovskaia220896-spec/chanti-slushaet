package ru.chanti.slushaet;

import android.app.Notification;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.List;
import java.util.ArrayList;

/**
 * Observes only Yandex Music playback metadata (not sound).
 * The OS grants notification access globally; other notifications are ignored.
 */
public class MusicListenerService extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private MediaSessionManager sessions;
    private boolean connected = false;

    static boolean isYandex(String name) {
        return name != null &&
          (name.equals("ru.yandex.music") || name.equals("com.yandex.music") ||
           name.contains("yandex.music"));
    }

    private final MediaController.Callback playbackCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { read(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { read(); }
        @Override public void onSessionDestroyed() { handler.post(() -> refresh()); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionCallback =
            changed -> refresh();

    private ComponentName component() {
        return new ComponentName(this, MusicListenerService.class);
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (sessions != null) {
            try { sessions.addOnActiveSessionsChangedListener(sessionCallback, component()); }
            catch (SecurityException ignored) {}
        }
        refresh();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (connected && sbn != null && isYandex(sbn.getPackageName())) refresh();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (connected && sbn != null && isYandex(sbn.getPackageName())) refresh();
    }

    private void refresh() {
        if (!connected) return;
        for (MediaController c : controllers) {
            try { c.unregisterCallback(playbackCallback); } catch (RuntimeException ignored) {}
        }
        controllers.clear();
        if (sessions != null) {
            try {
                for (MediaController c : sessions.getActiveSessions(component())) {
                    if (!isYandex(c.getPackageName())) continue;
                    controllers.add(c);
                    c.registerCallback(playbackCallback, handler);
                }
            } catch (SecurityException ignored) {}
        }
        read();
    }

    private String get(MediaMetadata m, String key) {
        CharSequence value = m == null ? null : m.getText(key);
        return value == null ? "" : value.toString().trim();
    }

    private void read() {
        if (!connected) return;
        MediaController choice = null;
        for (MediaController c : controllers) {
            MediaMetadata m = c.getMetadata();
            String title = get(m, MediaMetadata.METADATA_KEY_TITLE);
            if (title.isEmpty()) title = get(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title.isEmpty()) continue;
            PlaybackState p = c.getPlaybackState();
            boolean playing = p != null && p.getState() == PlaybackState.STATE_PLAYING;
            if (choice == null || playing) choice = c;
            if (playing) break;
        }
        if (choice != null) {
            MediaMetadata m = choice.getMetadata();
            String title = get(m, MediaMetadata.METADATA_KEY_TITLE);
            if (title.isEmpty()) title = get(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            PlaybackState p = choice.getPlaybackState();
            boolean playing = p != null && p.getState() == PlaybackState.STATE_PLAYING;
            TrackStore.save(this, title, get(m, MediaMetadata.METADATA_KEY_ARTIST), playing);
            return;
        }
        // Fallback for versions that expose a music notification but no media session.
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications != null) for (StatusBarNotification sbn : notifications) {
                if (sbn == null || !isYandex(sbn.getPackageName())) continue;
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                Bundle extras = n.extras;
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence artist = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (title == null || title.toString().trim().isEmpty()) continue;
                String value = title.toString().trim();
                if (value.equalsIgnoreCase("Яндекс Музыка")) continue;
                TrackStore.save(this, value, artist == null ? "" : artist.toString(), false);
                return;
            }
        } catch (RuntimeException ignored) {}
        TrackStore.markStopped(this);
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        if (sessions != null) {
            try { sessions.removeOnActiveSessionsChangedListener(sessionCallback); }
            catch (RuntimeException ignored) {}
        }
        for (MediaController c : controllers) {
            try { c.unregisterCallback(playbackCallback); }
            catch (RuntimeException ignored) {}
        }
        controllers.clear();
        TrackStore.markStopped(this);
        super.onListenerDisconnected();
    }
}
