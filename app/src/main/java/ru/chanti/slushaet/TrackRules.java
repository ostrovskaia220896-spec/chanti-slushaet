package ru.chanti.slushaet;

/** Pure domain rules tested without a device. */
final class TrackRules {
    private TrackRules() {}
    static boolean changed(String prevTitle, String prevArtist, String title, String artist) {
        return !safe(prevTitle).equals(safe(title)) || !safe(prevArtist).equals(safe(artist));
    }
    static String switchNote(long heardMs, boolean trackChange) {
        return trackChange && heardMs >= 0 && heardMs < 30000
            ? "короткое прослушивание — не обязательно дизлайк" : "";
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
