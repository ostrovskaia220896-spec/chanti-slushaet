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
    // A missing sample must not become an unlimited playback estimate.
    static final long MAX_SAMPLE_GAP_MS = 15000L;
    static boolean observationFresh(long observedAt,long now) {
        return observedAt>0 && now>=observedAt && now-observedAt<=MAX_SAMPLE_GAP_MS;
    }
    static long observedPlayingMs(long since,long observedAt,long now) {
        if(since<=0 || observedAt<since || now<since)return 0L;
        long end=observationFresh(observedAt,now)?now:Math.min(now,observedAt);
        return Math.max(0L,end-since);
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
