package ru.chanti.slushaet;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Date;
import java.text.DateFormat;

/** Stores only the current Yandex Music song and last 30 songs, on device. */
public final class TrackStore {
    private static final String PREFS = "chanti_music_v1";
    private TrackStore() {}

    static final class Track {
        final String title, artist;
        final boolean playing;
        final long updated;
        Track(String title, String artist, boolean playing, long updated) {
            this.title = title; this.artist = artist; this.playing = playing; this.updated = updated;
        }
        boolean exists() { return !title.isEmpty(); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized Track current(Context c) {
        SharedPreferences p = prefs(c);
        return new Track(p.getString("title", ""), p.getString("artist", ""),
                p.getBoolean("playing", false), p.getLong("updated", 0));
    }

    static synchronized void save(Context c, String title, String artist, boolean playing) {
        if (title == null || title.trim().isEmpty()) return;
        title = title.trim();
        artist = artist == null ? "" : artist.trim();
        SharedPreferences p = prefs(c);
        boolean changed = !title.equals(p.getString("title", "")) ||
                !artist.equals(p.getString("artist", ""));
        SharedPreferences.Editor edit = p.edit().putString("title", title)
                .putString("artist", artist).putBoolean("playing", playing);
        if (changed) {
            long now = System.currentTimeMillis();
            edit.putLong("updated", now);
            try {
                JSONArray old = new JSONArray(p.getString("history", "[]"));
                JSONArray recent = new JSONArray();
                JSONObject first = new JSONObject();
                first.put("title", title); first.put("artist", artist);
                first.put("time", now); recent.put(first);
                for (int i = 0; i < old.length() && recent.length() < 30; i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item == null) continue;
                    if (title.equals(item.optString("title")) && artist.equals(item.optString("artist"))) continue;
                    recent.put(item);
                }
                edit.putString("history", recent.toString());
            } catch (JSONException ignored) {}
        }
        edit.apply();
    }

    static synchronized void markStopped(Context c) {
        prefs(c).edit().putBoolean("playing", false).apply();
    }

    static synchronized String history(Context c) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("history", "[]"));
            if (a.length() == 0) return "Пока нет песен. Включи Яндекс Музыку.";
            StringBuilder sb = new StringBuilder();
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(i + 1).append(". ").append(x.optString("title"));
                String artist = x.optString("artist");
                if (!artist.isEmpty()) sb.append(" — ").append(artist);
                sb.append("\n").append(df.format(new Date(x.optLong("time", 0))));
            }
            return sb.toString();
        } catch (JSONException e) {
            return "Ошибка чтения истории.";
        }
    }

    static synchronized void clear(Context c) {
        prefs(c).edit().remove("history").apply();
    }
}
