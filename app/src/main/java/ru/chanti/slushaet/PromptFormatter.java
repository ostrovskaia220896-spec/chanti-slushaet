package ru.chanti.slushaet;

/** Formats an optional, user-sent message; never uploads automatically. */
public final class PromptFormatter {
    private PromptFormatter() {}

    public static String forTrack(String title, String artist) {
        String t = clean(title);
        String a = clean(artist);
        if (t.isEmpty()) throw new IllegalArgumentException("Нет названия песни");
        String description = a.isEmpty() ? "«" + t + "»" : "«" + t + "» — " + a;
        return "Чанти, у меня сейчас играет " + description +
            ". Давай обсудим её смысл и эмоции. Не придумывай слова песни, " +
            "если у тебя нет текста. Сначала спроси, что меня зацепило! 🎧";
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").trim();
    }
}
