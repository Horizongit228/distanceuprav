package com.distanceuprav.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** New in 0.4: a separate optional Windows-agent control panel.
 * The old Wake-on-LAN UI does not depend on this panel or on the agent.
 */
public final class AgentPanel extends LinearLayout {
    private static final int SURFACE = 0xFF151D30;
    private static final int SURFACE2 = 0xFF202B43;
    private static final int WHITE = 0xFFF7F9FF;
    private static final int MUTED = 0xFFA1B1CC;
    private static final int SUCCESS = 0xFF65E4B5;
    private static final int WARNING = 0xFFFFCA81;
    private static final int PRIMARY = 0xFF8274FF;

    private final Activity activity;
    private final AgentStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView status;
    private final TextView endpoint;
    private final LinearLayout list;
    private final LinearLayout actions;
    private final Button refreshButton;
    private volatile boolean closed;
    private boolean foreground;
    private boolean agentElevated;
    private int lastRevision = -1;
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!closed && foreground) {
                if (store.read() != null) loadApps(true);
                handler.postDelayed(this, 5000);
            }
        }
    };
    private final LinearLayout closedList;
    private final TextView liveInfo;

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private Button button(String value, int color) {
        Button btn = new Button(activity);
        btn.setText(value);
        btn.setAllCaps(false);
        btn.setTextColor(WHITE);
        btn.setTextSize(14);
        btn.setMinHeight(dp(48));
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(color, 13, color == SURFACE2 ? 0xFF3C4964 : 0), null));
        return btn;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(VERTICAL);
        return l;
    }

    private void add(LinearLayout parent, View view, int margin) {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(margin);
        parent.addView(view, p);
    }

    private void feedback(String msg, int color) {
        status.setText(msg);
        status.setTextColor(color);
    }

    private void ui(Runnable action) {
        activity.runOnUiThread(() -> { if (!closed) action.run(); });
    }

    public AgentPanel(Activity activity) {
        super(activity);
        this.activity = activity;
        this.store = new AgentStore(activity);
        setOrientation(VERTICAL);
        setPadding(dp(17),dp(17),dp(17),dp(17));
        setBackground(shape(SURFACE, 22, 0xFF29364D));

        add(this, text("WINDOWS AGENT · LIVE", 12, MUTED, true), 0);
        add(this, text("Удалённое управление программами", 19, WHITE, true), 8);
        add(this, text("Список открытых окон, выборочное закрытие, выключение и перезапуск ПК. Только домашняя сеть, HTTPS и QR-привязка.", 12, MUTED, false), 9);

        endpoint = text("Агент не подключён", 13, MUTED, false);
        add(this, endpoint, 14);
        status = text("Установи Windows-агент на ПК и отсканируй его QR-код.", 13, MUTED, false);
        status.setPadding(dp(13),dp(12),dp(13),dp(12));
        status.setBackground(shape(SURFACE2, 14, 0));
        add(this, status, 11);

        Button scan = button("▣  Подключить Windows-агент по QR", PRIMARY);
        add(this, scan, 14);
        scan.setOnClickListener(v -> scanPair());

        Button paste = button("⎘  Вставить ссылку привязки", SURFACE2);
        add(this, paste, 9);
        paste.setOnClickListener(v -> pastePair());

        refreshButton = button("↻  Обновить список программ", SURFACE2);
        add(this, refreshButton, 10);
        refreshButton.setOnClickListener(v -> loadApps());

        actions = column();
        add(this, actions, 8);
        TextView info = text("⚠  Важно: изменения сохраняет сама программа. Если появится запрос «Сохранить?», подтверди его на компьютере. Принудительного закрытия нет.", 12, WARNING, false);
        info.setPadding(dp(14), dp(12), dp(14), dp(12));
        info.setBackground(shape(0xFF312B37, 14, 0xFF665064));
        add(actions, info, 10);

        Button off = button("⏻  Выключить компьютер", 0xFF47334E);
        Button restart = button("↻  Перезагрузить компьютер", 0xFF403B64);
        Button cancel = button("×  Отменить запланированное действие", SURFACE2);
        add(actions, off, 12);
        add(actions, restart, 9);
        add(actions, cancel, 9);
        off.setOnClickListener(v -> confirmPower("shutdown", "Выключить компьютер"));
        restart.setOnClickListener(v -> confirmPower("restart", "Перезагрузить компьютер"));
        cancel.setOnClickListener(v -> sendAction("/v1/power/cancel", new JSONObject(), "Запрос отмены отправлен."));
        actions.setVisibility(GONE);

        liveInfo = text("↻ Автообновление каждые 5 секунд", 12, MUTED, false);
        add(this, liveInfo, 12);
        list = column();
        add(this, list, 12);
        closedList = column();
        add(this, closedList, 16);
        updatePairState();
    }

    private void updatePairState() {
        AgentStore.Config cfg = store.read();
        if (cfg == null) {
            endpoint.setText("Агент не привязан");
            actions.setVisibility(GONE);
            refreshButton.setVisibility(GONE);
            list.removeAllViews();
            closedList.removeAllViews();
        } else {
            endpoint.setText("Защищённое подключение: " + cfg.ip + ":" + cfg.port);
            refreshButton.setVisibility(VISIBLE);
            actions.setVisibility(VISIBLE);
            feedback("Агент привязан. Список обновляется автоматически.", SUCCESS);
        }
    }

    private void scanPair() {
        feedback("Открываю QR-сканер Windows-агента…", WHITE);
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();
        GmsBarcodeScanning.getClient(activity, options).startScan()
                .addOnSuccessListener(barcode -> pair(barcode.getRawValue()))
                .addOnCanceledListener(() -> feedback("QR-сканирование отменено.", MUTED))
                .addOnFailureListener(e -> feedback(
                        "Не удалось открыть сканер. Скопируй QR-ссылку с ПК и вставь её через буфер.", WARNING));
    }

    private void pastePair() {
        try {
            ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) throw new IllegalArgumentException("Буфер обмена пуст");
            CharSequence raw = clip.getItemAt(0).coerceToText(activity);
            if (raw == null) throw new IllegalArgumentException("Нет текста для привязки");
            pair(raw.toString());
        } catch(Exception e) {
            feedback("Ошибка: " + e.getMessage(), WARNING);
        }
    }

    private void pair(String raw) {
        try {
            AgentStore.Config cfg = AgentClient.parsePairing(raw);
            store.save(cfg);
            updatePairState();
            feedback("Привязка сохранена в Android Keystore. Проверяю подключение…", WHITE);
            loadApps();
        } catch(Exception e) {
            feedback("Не удалось привязать агент: " + e.getMessage(), WARNING);
        }
    }

    public void onForeground() {
        foreground = true;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    public void onBackground() {
        foreground = false;
        handler.removeCallbacks(poll);
    }

    private void loadApps() {
        loadApps(false);
    }

    private void loadApps(boolean quiet) {
        AgentStore.Config cfg = store.read();
        if (cfg == null) {
            if (!quiet) feedback("Сначала подключи Windows-агент по QR.", WARNING);
            return;
        }
        if (!loading.compareAndSet(false, true)) return;
        refreshButton.setEnabled(false);
        if (!quiet) feedback("⏳ Обновляю список окон…", WHITE);
        worker.execute(() -> {
            try {
                JSONObject state = AgentClient.request(cfg, "GET", "/v1/status", null);
                JSONObject response = AgentClient.request(cfg, "GET", "/v1/apps", null);
                JSONArray apps = response.optJSONArray("apps");
                JSONArray recentlyClosed = response.optJSONArray("recently_closed");
                int revision = response.optInt("revision", 0);
                boolean elevated = state.optBoolean("agent_elevated", false);
                String name = state.optString("computer", "ПК");
                ui(() -> {
                    loading.set(false);
                    refreshButton.setEnabled(true);
                    agentElevated = elevated;
                    if (lastRevision != revision || !quiet) {
                        renderApps(apps);
                        renderClosed(recentlyClosed);
                        lastRevision = revision;
                    }
                    liveInfo.setText("● Онлайн  ·  Обновление каждые 5 с  ·  Агент " +
                            (elevated ? "администратор" : "обычный пользователь"));
                    if (!quiet || !status.getText().toString().contains("✓ Windows-агент")) {
                        feedback("✓ Windows-агент: " + name + " · открытых окон: " +
                                (apps == null ? 0 : apps.length()), SUCCESS);
                    }
                });
            } catch(Exception e) {
                ui(() -> {
                    loading.set(false);
                    refreshButton.setEnabled(true);
                    liveInfo.setText("○ Нет связи · повторная попытка через 5 с");
                    if (!quiet || lastRevision >= 0) {
                        feedback("⚠ Нет подключения к агенту: " + safeError(e), WARNING);
                    }
                    lastRevision = -1;
                });
            }
        });
    }

    private void renderApps(JSONArray apps) {
        list.removeAllViews();
        add(list, text("● ОТКРЫТЫЕ ОКНА WINDOWS", 12, SUCCESS, true), 5);
        if (apps == null || apps.length() == 0) {
            add(list, text("Открытых окон не найдено. Агент должен работать в пользовательском сеансе Windows.", 12, MUTED, false), 10);
            return;
        }
        int count = Math.min(60, apps.length());
        for(int i = 0; i < count; i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String title = app.optString("title", "Программа");
            String process = app.optString("process", "");
            String hwnd = app.optString("hwnd");
            int pid = app.optInt("pid", -1);
            boolean elevated = app.optBoolean("elevated", false);
            LinearLayout block = column();
            block.setPadding(dp(12),dp(12),dp(12),dp(12));
            block.setBackground(shape(SURFACE2, 14, 0));
            add(block, text(title, 14, WHITE, true), 0);
            add(block, text(process + "  ·  PID " + pid, 11, MUTED, false), 4);
            if (elevated) add(block, text("⚠ Запущено от администратора", 12, WARNING, true), 6);
            Button close = button("×  Закрыть окно", 0xFF4A3547);
            close.setTextSize(12);
            add(block, close, 8);
            close.setOnClickListener(v -> confirmClose(hwnd, pid, title, elevated));
            add(list, block, 10);
        }
    }

    private void renderClosed(JSONArray closedWindows) {
        closedList.removeAllViews();
        add(closedList, text("○ НЕДАВНО ЗАКРЫТЫЕ ОКНА", 12, MUTED, true), 0);
        if (closedWindows == null || closedWindows.length() == 0) {
            add(closedList, text("Пока закрытых окон не обнаружено. История ведётся со времени запуска Windows-агента.", 12, MUTED, false), 10);
            return;
        }
        for(int i = 0; i < Math.min(15, closedWindows.length()); i++) {
            JSONObject app = closedWindows.optJSONObject(i);
            if (app == null) continue;
            LinearLayout box = column();
            box.setPadding(dp(12), dp(10), dp(12), dp(10));
            box.setBackground(shape(0xFF192238, 13, 0));
            add(box, text("✓  " + app.optString("title", "Окно закрыто"), 13, WHITE, true), 0);
            long time = app.optLong("time", 0L);
            String when = time > 0 ? android.text.format.DateFormat.getTimeFormat(activity)
                    .format(new java.util.Date(time * 1000L)) : "";
            add(box, text(app.optString("process", "") + "  ·  " + when, 11, MUTED, false), 4);
            add(closedList, box, 8);
        }
    }

    private void confirmClose(String hwnd, int pid, String title, boolean elevated) {
        if (elevated && !agentElevated) {
            StyledConfirm.show(activity, "Нужны права администратора",
                    "Это окно запущено с повышенными правами. Обычный Windows-агент не может закрыть его из-за защиты UAC.\n\n" +
                    "При необходимости можно вручную запустить Windows-агент от администратора на ПК, подтвердив запрос UAC. Это повышает права всех разрешённых удалённых команд — используй только в доверенной домашней сети.",
                    "Понятно", false, () -> {});
            return;
        }
        StyledConfirm.show(activity, "Закрыть выбранное окно?",
                title + "\n\nПрограмма получит обычный запрос закрытия. Если документ не сохранён, диалог «Сохранить?» появится на компьютере. Агент не завершает процесс принудительно.",
                "Закрыть окно", true, () -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("hwnd", hwnd);
                        body.put("pid", pid);
                        sendAction("/v1/apps/close", body,
                                "Запрос закрытия отправлен. Ожидаем изменения списка.");
                    } catch(Exception e) {
                        feedback("Ошибка: " + safeError(e), WARNING);
                    }
                });
    }

    private void confirmPower(String kind, String caption) {
        StyledConfirm.show(activity, caption + "?",
                "Через 10 секунд Windows попробует выполнить действие. Команду можно отменить до её начала.\n\n" +
                "Программы не закрываются принудительно. При несохранённых данных компьютер может показать диалог и заблокировать выключение.",
                "Подтвердить", true,
                () -> sendAction("/v1/power/" + kind, new JSONObject(),
                        "Команда принята. Осталось 10 секунд."));
    }

    private void sendAction(String path, JSONObject body, String description) {
        AgentStore.Config cfg = store.read();
        if (cfg == null) {
            feedback("Агент не привязан", WARNING);
            return;
        }
        feedback("⏳ Отправляю команду…", WHITE);
        worker.execute(() -> {
            try {
                JSONObject response = AgentClient.request(cfg, "POST", path, body);
                ui(() -> {
                    feedback("✓ " + response.optString("message", description), SUCCESS);
                    if ("/v1/apps/close".equals(path)) {
                        handler.postDelayed(() -> loadApps(true), 2300);
                    }
                });
            } catch(Exception e) {
                ui(() -> feedback("⚠ Команда не выполнена: " + safeError(e), WARNING));
            }
        });
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        return message == null || message.length() > 180 ? e.getClass().getSimpleName() : message;
    }

    public void shutdown() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }
}
