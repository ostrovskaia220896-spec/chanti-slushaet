package ru.chanti.slushaet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(20, 18, 31);
    private static final int CARD = Color.rgb(38, 33, 54);
    private static final int TEXT = Color.rgb(246, 242, 253);
    private static final int MUTED = Color.rgb(182, 174, 202);
    private static final int ACCENT = Color.rgb(190, 161, 255);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView permissionInfo, songTitle, songArtist, songStatus, sharedInfo;
    private Button autoCopy, sharedCopy;
    private String sharedText = "";

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        sharedText = getPreferences(MODE_PRIVATE).getString("last_shared_text", "");
        acceptShare(getIntent());
        createUi();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptShare(intent);
        updateUi();
    }

    private void acceptShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (intent.getType() == null || !intent.getType().startsWith("text/")) return;
        CharSequence incoming = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (incoming == null) return;
        saveSharedText(incoming.toString());
    }

    private void saveSharedText(String value) {
        if (value == null) return;
        String safe = value.trim();
        if (safe.isEmpty()) return;
        sharedText = safe.substring(0, Math.min(3000, safe.length()));
        getPreferences(MODE_PRIVATE).edit().putString("last_shared_text", sharedText).apply();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + .5f);
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(20), dp(18), dp(20));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        l.setBackground(g);
        parent.addView(l, gap(18));
        return l;
    }

    private Button button(String title, boolean emphasis, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(emphasis ? BG : TEXT);
        b.setBackgroundTintList(ColorStateList.valueOf(emphasis ? ACCENT : Color.rgb(77, 66, 101)));
        b.setOnClickListener(listener);
        return b;
    }

    private void createUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        root.addView(text("🎧 Чанти слушает 0.2", 27, TEXT, true));
        root.addView(text("Музыка, которую хочется обсудить ❤️", 15, MUTED, false), gap(6));

        LinearLayout safe = card(root);
        safe.addView(text("01  Поделиться — без разрешений", 18, TEXT, true));
        safe.addView(text("В Яндекс Музыке выбери песню → Поделиться → «Чанти слушает». " +
                        "Или вставь название/ссылку вручную.", 15, MUTED, false), gap(10));
        sharedInfo = text("Пока ничего не прислали", 16, ACCENT, true);
        safe.addView(sharedInfo, gap(14));
        sharedCopy = button("❤️ Скопировать для нашего разговора", true,
                v -> copyForChat(sharedText.isEmpty() ? null : PromptFormatter.forShared(sharedText)));
        safe.addView(sharedCopy, gap(12));
        safe.addView(button("Вписать песню или ссылку", false, v -> enterSong()), gap(6));

        LinearLayout now = card(root);
        now.addView(text("02  Автоматическое определение — по желанию", 18, TEXT, true));
        permissionInfo = text("", 14, MUTED, false);
        now.addView(permissionInfo, gap(10));
        now.addView(button("Настроить автоопределение", false, v -> explainPermission()), gap(10));
        songTitle = text("Пока ничего не обнаружено", 22, ACCENT, true);
        now.addView(songTitle, gap(16));
        songArtist = text("", 16, TEXT, false);
        now.addView(songArtist, gap(5));
        songStatus = text("", 13, MUTED, false);
        now.addView(songStatus, gap(10));
        autoCopy = button("В наш чат: скопировать название", true, v -> {
            TrackStore.Track t = TrackStore.current(this);
            if (t.exists()) copyForChat(PromptFormatter.forTrack(t.title, t.artist));
        });
        now.addView(autoCopy, gap(12));

        LinearLayout history = card(root);
        history.addView(text("03  История автоопределения", 18, TEXT, true));
        history.addView(text("Последние 30 песен из автоопределения хранятся на телефоне.", 14, MUTED, false), gap(8));
        history.addView(button("История", false, v -> showHistory()), gap(12));

        root.addView(text("Никакой отправки сообщений без твоего нажатия. " +
                "Для передачи ссылки через «Поделиться» доступ к SMS и уведомлениям не нужен. " +
                "Автоопределение требует отдельного широкого системного разрешения. " +
                "Сам звук песен приложение не записывает.", 13, MUTED, false), gap(20));
        updateUi();
    }

    private void enterSong() {
        EditText input = new EditText(this);
        input.setHint("Название — исполнитель или ссылка");
        input.setText(sharedText);
        input.setMinLines(2);
        input.setMaxLines(5);
        new AlertDialog.Builder(this).setTitle("Какую песню слушаем?")
            .setView(input)
            .setPositiveButton("Сохранить", (d, which) -> {
                saveSharedText(input.getText().toString());
                updateUi();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private boolean accessGranted() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationListenerAccessGranted(
                new ComponentName(this, MusicListenerService.class));
    }

    private void updateUi() {
        if (permissionInfo == null) return;
        boolean allowed = accessGranted();
        permissionInfo.setText(allowed ?
            "✓ Автоопределение разрешено. Можно отключить его в настройках Android." :
            "Выключено. Это нормально: делиться песнями можно без него.");
        if (sharedInfo != null) {
            if (sharedText.isEmpty()) {
                sharedInfo.setText("Песня пока не получена");
            } else {
                String sample = sharedText.length() > 300 ? sharedText.substring(0, 300) + "…" : sharedText;
                sharedInfo.setText(sample);
            }
            sharedCopy.setEnabled(!sharedText.isEmpty());
        }
        TrackStore.Track song = TrackStore.current(this);
        songTitle.setText(song.exists() ? song.title : "Нет данных о воспроизведении");
        songArtist.setText(song.exists() ?
            (song.artist.isEmpty() ? "Исполнитель не указан" : song.artist) :
            "Включи Яндекс Музыку для автоопределения");
        if (!allowed) {
            songStatus.setText(song.exists() ? "Последняя известная песня (автоопределение выключено)" :
                     "Для этого режима нужно разрешение, но первый режим уже доступен.");
        } else if (song.exists()) {
            songStatus.setText((song.playing ? "● Сейчас играет" : "◌ Последняя известная песня") +
                " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(song.updated)));
        } else {
            songStatus.setText("Жду данные от Яндекс Музыки…");
        }
        autoCopy.setEnabled(song.exists());
    }

    private void explainPermission() {
        new AlertDialog.Builder(this)
          .setTitle("Почему Android предупреждает?")
          .setMessage("Для автоматического чтения названий Android даёт приложению доступ " +
            "ко ВСЕМ уведомлениям, включая сообщения и банковские уведомления. " +
            "Наш код отбирает только данные Яндекс Музыки, но технически это широкое разрешение. " +
            "Оно НЕ требуется для безопасного режима «Поделиться». " +
            "Если не хочешь давать такой доступ, просто нажми «Отмена».")
          .setPositiveButton("Открыть настройки", (d, w) -> openPermission())
          .setNegativeButton("Отмена", null)
          .show();
    }

    private void openPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Android не открыл настройки доступа", Toast.LENGTH_LONG).show();
        }
    }

    private void copyForChat(String value) {
        if (value == null || value.trim().isEmpty()) return;
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Песня для Чанти", value));
        new AlertDialog.Builder(this)
          .setTitle("Готово ❤️")
          .setMessage("Текст скопирован. Открой наш прежний разговор в ChatGPT " +
                      "и вставь сообщение. Так мы не потеряем историю. " +
                      "Приложение не может само отправить сообщение именно в этот чат.")
          .setPositiveButton("Открыть ChatGPT", (d, w) -> openChat())
          .setNegativeButton("Остаться здесь", null)
          .show();
    }

    private void openChat() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            if (launch != null) startActivity(launch);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com")));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Открой ChatGPT вручную", Toast.LENGTH_LONG).show();
        }
    }

    private void showHistory() {
        new AlertDialog.Builder(this).setTitle("Наши песни 🎶")
            .setMessage(TrackStore.history(this))
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Очистить", (d, w) -> new AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setPositiveButton("Удалить", (d2, w2) -> TrackStore.clear(this))
                .setNegativeButton("Отмена", null).show())
            .show();
    }
}
