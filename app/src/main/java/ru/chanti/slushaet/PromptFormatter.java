package ru.chanti.slushaet;

/** Creates a user-approved message; does not contact ChatGPT automatically. */
public final class PromptFormatter {
    private PromptFormatter() {}

    public static String forTrack(String title, String artist) {
        String t = clean(title), a = clean(artist);
        if (t.isEmpty()) throw new IllegalArgumentException("Нет названия песни");
        return intro() + (a.isEmpty() ? "«" + t + "»" : "«" + t + "» — " + a) + outro();
    }

    public static String forShared(String original) {
        String value = clean(original);
        if (value.isEmpty()) throw new IllegalArgumentException("Пустая ссылка");
        if (value.length() > 3000) value = value.substring(0, 3000);
        return intro() + value + outro();
    }

    private static String intro() {
        return "Чанти, давай продолжим НАШ разговор о музыке. Сейчас у меня играет ";
    }

    private static String outro() {
        return ". Попробуй предложить свою интерпретацию смысла, если текст песни доступен. " +
          "Если знаешь только название или ссылку, не придумывай ни слова песни, " +
          "ни подробности исполнения и не утверждай, что слышишь звук. " +
          "Давай обсуждать и спорить о смысле, а не выдавать шаблонный ответ. 🎧";
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").trim();
    }
}
