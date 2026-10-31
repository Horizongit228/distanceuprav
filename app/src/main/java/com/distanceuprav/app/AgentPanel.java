package com.distanceuprav.app;

import android.app.Activity;
import android.app.AlertDialog;
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

        add(this, text("WINDOWS AGENT · v0.4", 12, MUTED, true), 0);
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
        TextView info = text("Закрытие не принудительное. Если документ не сохранён, Windows-приложение может показать диалог сохранения непосредственно на компьютере.", 12, WARNING, false);
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

        list = column();
        add(this, list, 12);
        updatePairState();
    }

    private void updatePairState() {
        AgentStore.Config cfg = store.read();
        if (cfg == null) {
            endpoint.setText("Агент не привязан");
            actions.setVisibility(GONE);
            refreshButton.setVisibility(GONE);
            list.removeAllViews();
        } else {
            endpoint.setText("Защищённое подключение: " + cfg.ip + ":" + cfg.port);
            refreshButton.setVisibility(VISIBLE);
            actions.setVisibility(VISIBLE);
            feedback("Агент привязан. Нажми «Обновить список программ».", SUCCESS);
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

    private void loadApps() {
        AgentStore.Config cfg = store.read();
        if (cfg == null) {
            feedback("Сначала подключи Windows-агент по QR.", WARNING);
            return;
        }
        refreshButton.setEnabled(false);
        feedback("⏳ Связываюсь с Windows-агентом через HTTPS…", WHITE);
        worker.execute(() -> {
            try {
                JSONObject state = AgentClient.request(cfg, "GET", "/v1/status", null);
                JSONObject response = AgentClient.request(cfg, "GET", "/v1/apps", null);
                JSONArray apps = response.optJSONArray("apps");
                String name = state.optString("computer", "ПК");
                ui(() -> {
                    refreshButton.setEnabled(true);
                    feedback("✓ Windows-агент подключён: " + name + ". Открытых окон: " + (apps == null ? 0 : apps.length()), SUCCESS);
                    renderApps(apps);
                });
            } catch(Exception e) {
                ui(() -> {
                    refreshButton.setEnabled(true);
                    list.removeAllViews();
                    feedback("⚠ Нет подключения к агенту. Проверь, что программа запущена на ПК, обе стороны в одной сети и порт 8765 разрешён для частной сети. " + safeError(e), WARNING);
                });
            }
        });
    }

    private void renderApps(JSONArray apps) {
        list.removeAllViews();
        add(list, text("ОТКРЫТЫЕ ОКНА WINDOWS", 12, MUTED, true), 5);
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
            LinearLayout block = column();
            block.setPadding(dp(12),dp(12),dp(12),dp(12));
            block.setBackground(shape(SURFACE2, 14, 0));
            add(block, text(title, 14, WHITE, true), 0);
            add(block, text(process + "  ·  PID " + pid, 11, MUTED, false), 4);
            Button close = button("×  Закрыть окно", 0xFF4A3547);
            close.setTextSize(12);
            add(block, close, 8);
            close.setOnClickListener(v -> confirmClose(hwnd, pid, title));
            add(list, block, 10);
        }
    }

    private void confirmClose(String hwnd, int pid, String title) {
        new AlertDialog.Builder(activity)
                .setTitle("Закрыть выбранное окно?")
                .setMessage(title + "\n\nБудет отправлена обычная команда закрытия. Если есть несохранённые изменения, программа может предложить сохранить их НА КОМПЬЮТЕРЕ. Агент не применяет принудительное завершение и не может гарантировать автоматическое сохранение.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Закрыть", (dialog, which) -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("hwnd", hwnd);
                        body.put("pid", pid);
                        sendAction("/v1/apps/close", body, "Запрос мягкого закрытия отправлен. Если программа запросит сохранение, подтверди его на ПК.");
                    } catch(Exception e) {
                        feedback("Ошибка: " + safeError(e), WARNING);
                    }
                }).show();
    }

    private void confirmPower(String kind, String caption) {
        new AlertDialog.Builder(activity)
                .setTitle(caption + "?")
                .setMessage("После подтверждения агент запланирует действие на 10 секунд. Его можно отменить кнопкой в приложении. Windows НЕ будет принудительно закрывать программы (/f не используется). Если есть несохранённые изменения, компьютер может показать запрос сохранения и заблокировать выключение или перезапуск.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Подтвердить", (dialog, which) ->
                        sendAction("/v1/power/" + kind, new JSONObject(),
                                "Команда принята. Через 10 секунд Windows попробует выполнить действие.")).show();
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
                ui(() -> feedback("✓ " + response.optString("message", description), SUCCESS));
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
        worker.shutdownNow();
    }
}
