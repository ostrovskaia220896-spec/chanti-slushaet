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
    private TextView permissionInfo, songTitle, songArtist, songStatus;
    private Button shareButton, copyButton;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        createUi();
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
        LinearLayout.LayoutParams p = gap(18);
        parent.addView(l, p);
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

        root.addView(text("🎧 Чанти слушает", 28, TEXT, true));
        root.addView(text("Песня — повод поговорить ❤️", 15, MUTED, false), gap(6));

        LinearLayout access = card(root);
        access.addView(text("01  Доступ к музыке", 18, TEXT, true));
        permissionInfo = text("Проверяем разрешение…", 14, MUTED, false);
        access.addView(permissionInfo, gap(10));
        access.addView(button("Разрешить видеть текущую песню", false, v -> openPermission()), gap(14));

        LinearLayout now = card(root);
        now.addView(text("02  Сейчас в Яндекс Музыке", 18, TEXT, true));
        songTitle = text("Пока ничего не обнаружено", 23, ACCENT, true);
        now.addView(songTitle, gap(20));
        songArtist = text("Включи музыку", 17, TEXT, false);
        now.addView(songArtist, gap(6));
        songStatus = text("", 13, MUTED, false);
        now.addView(songStatus, gap(14));
        shareButton = button("🎤 Обсудить в ChatGPT", true, v -> shareSong());
        now.addView(shareButton, gap(14));
        copyButton = button("Скопировать для нашего чата", false, v -> copySong());
        now.addView(copyButton, gap(6));

        LinearLayout history = card(root);
        history.addView(text("03  История песен", 18, TEXT, true));
        history.addView(text("Последние 30 композиций хранятся только на телефоне.", 14, MUTED, false), gap(10));
        history.addView(button("Посмотреть историю", false, v -> showHistory()), gap(12));

        root.addView(text("Приложение не слушает аудио, не использует Интернет и не отправляет ничего само. " +
                "Разрешение Android даёт технический доступ к уведомлениям, " +
                "но программа обрабатывает только Яндекс Музыку.",
                13, MUTED, false), gap(20));
    }

    private boolean accessGranted() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationListenerAccessGranted(
                new ComponentName(this, MusicListenerService.class));
    }

    private void updateUi() {
        boolean allowed = accessGranted();
        permissionInfo.setText(allowed ?
            "✓ Доступ включён. Включи песню в Яндекс Музыке." :
            "Нажми кнопку и разреши приложению доступ к уведомлениям. " +
            "Android выдаёт доступ ко всем уведомлениям, но программа читает только музыкальные.");
        TrackStore.Track song = TrackStore.current(this);
        songTitle.setText(song.exists() ? song.title : "Пока ничего не обнаружено");
        songArtist.setText(song.exists() ?
            (song.artist.isEmpty() ? "Исполнитель не указан" : song.artist) :
            "Включи Яндекс Музыку и вернись сюда");
        songStatus.setText(song.exists() ?
            (song.playing ? "● Сейчас играет" : "◌ Последняя известная песня") + " · " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(song.updated)) :
            (allowed ? "Жду, пока приложение опубликует данные…" : "Сначала дай разрешение выше."));
        shareButton.setEnabled(song.exists());
        copyButton.setEnabled(song.exists());
    }

    private void openPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Открой настройки Android → Доступ к уведомлениям", Toast.LENGTH_LONG).show();
        }
    }

    private String prompt() {
        TrackStore.Track t = TrackStore.current(this);
        return t.exists() ? PromptFormatter.forTrack(t.title, t.artist) : null;
    }

    private void shareSong() {
        String p = prompt();
        if (p == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, p);
        try { startActivity(Intent.createChooser(send, "Выбери ChatGPT")); }
        catch (RuntimeException e) { copySong(); }
    }

    private void copySong() {
        String p = prompt();
        if (p == null) return;
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Для Чанти", p));
        new AlertDialog.Builder(this)
            .setTitle("Готово! ❤️")
            .setMessage("Текст скопирован. Вернись в наш разговор и вставь его — контекст сохранится.")
            .setPositiveButton("Открыть ChatGPT", (d, w) -> openChat())
            .setNegativeButton("Позже", null)
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
