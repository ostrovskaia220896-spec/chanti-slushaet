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
    static String ratingNote(String previous, String current) {
        if ("liked".equals(current)) {
            return "unliked".equals(previous) ?
                "лайк появился (по данным плеера)" : "лайк уже стоит (по данным плеера)";
        }
        if ("unliked".equals(current)) {
            return "liked".equals(previous) ?
                "лайк снят (по данным плеера)" : "сейчас без лайка (по данным плеера)";
        }
        return "";
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
