package com.distanceuprav.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-PC controller. The WoL and reachability implementation is shared with
 * SofaPower via an adapted WolUtils. Pairing understands SofaPower Hub v1 QR.
 * No Windows listener or open TCP port is required.
 */
public class MainActivity extends Activity {
    private static final long STATUS_INTERVAL_MS = 20_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean(false);

    private EditText macInput, ipInput;
    private TextView pcStatus, actionStatus, networkStatus, computerTitle;
    private Button checkButton, powerButton;
    private final int background = Color.rgb(14, 18, 31);
    private final int card = Color.rgb(28, 35, 53);
    private final int accent = Color.rgb(109, 146, 255);
    private final int muted = 0xFF9BA8C5;

    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            checkPcStatus(false);
            main.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private TextView label(String text, int size, int color) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextColor(color);
        value.setTextSize(size);
        return value;
    }

    private void add(LinearLayout root, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        root.addView(view, params);
    }

    private EditText field(LinearLayout root, String hint, String value, int top) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF8590A9);
        input.setTextSize(15);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(background(card));
        add(root, input, top);
        return input;
    }

    private Button button(LinearLayout root, String caption, int color, int top) {
        Button value = new Button(this);
        value.setAllCaps(false);
        value.setText(caption);
        value.setTextColor(Color.WHITE);
        value.setTextSize(15);
        value.setBackground(background(color));
        add(root, value, top);
        return value;
    }

    private String pref(String key) {
        return getPreferences(MODE_PRIVATE).getString(key, "");
    }

    private void saveSettings() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("mac", macInput.getText().toString().trim())
                .putString("host", ipInput.getText().toString().trim())
                .apply();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(38), dp(24), dp(32));
        scroll.addView(root);

        TextView title = label("⚡ DistanceUprav", 28, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        add(root, title, 0);
        add(root, label("Управление одним ПК через домашнюю сеть", 14, muted), 8);

        computerTitle = label("🖥  Мой компьютер", 21, Color.WHITE);
        computerTitle.setTypeface(null, Typeface.BOLD);
        add(root, computerTitle, 32);
        pcStatus = label("○ Статус неизвестен", 15, muted);
        add(root, pcStatus, 10);
        networkStatus = label("Определяю локальную сеть…", 12, muted);
        add(root, networkStatus, 12);


        add(root, label("Подключение без QR • как в старом SofaPower", 16, Color.WHITE), 28);
        add(root, label("Нужен MAC-адрес Ethernet-адаптера ПК. Телефон и компьютер должны быть в одной домашней сети. IP компьютера нужен только для проверки статуса.", 12, muted), 10);

        add(root, label("MAC-адрес Ethernet", 14, 0xFFD8E1F5), 18);
        macInput = field(root, "AA:BB:CC:DD:EE:FF", pref("mac"), 8);
        Button clipboardMac = button(root, "⎘  Вставить MAC из буфера", card, 10);
        add(root, label("Локальный IPv4 компьютера (необязательно для включения)", 14, 0xFFD8E1F5), 18);
        ipInput = field(root, "192.168.1.100", pref("host"), 8);

        Button saveButton = button(root, "✓  Сохранить настройки", card, 16);
        powerButton = button(root, "⏻  Включить компьютер", accent, 25);
        checkButton = button(root, "◎  Проверить доступность", card, 12);

        actionStatus = label("Для включения нужен только MAC. Проверка доступности использует IPv4 и ping, который может блокироваться Windows.", 13, 0xFFD8E1F5);
        actionStatus.setMinHeight(dp(68));
        actionStatus.setPadding(dp(14), dp(14), dp(14), dp(14));
        actionStatus.setBackground(background(card));
        add(root, actionStatus, 14);
        add(root, label("Важно: отсутствие ответа на ping не доказывает, что ПК выключен. Отправка Wake-on-LAN не требует открытого TCP-порта.", 12, muted), 12);

        add(root, label("Дополнительно: QR новой версии SofaPower Hub", 15, Color.WHITE), 30);
        add(root, label("В твоей старой версии SofaPower QR отсутствует — эти кнопки можно не использовать.", 12, muted), 8);
        Button scanButton = button(root, "▣  Сканировать QR нового Hub", card, 12);
        Button pasteButton = button(root, "⎘  Вставить QR-код из буфера", card, 10);


        clipboardMac.setOnClickListener(v -> {
            try {
                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = manager == null ? null : manager.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) throw new IllegalArgumentException("Буфер обмена пуст");
                CharSequence value = clip.getItemAt(0).coerceToText(this);
                String raw = value == null ? "" : value.toString();
                java.util.regex.Matcher match = java.util.regex.Pattern
                        .compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}").matcher(raw);
                String parsed = match.find() ? WolUtils.normalizeMac(match.group()) : WolUtils.normalizeMac(raw);
                if (parsed == null) throw new IllegalArgumentException("MAC-адрес не найден");
                macInput.setText(parsed);
                saveSettings();
                actionStatus.setText("✓ MAC скопирован. IP не требуется для Wake-on-LAN.");
            } catch (Exception error) {
                actionStatus.setText("⚠ Не удалось прочитать MAC: " + error.getMessage());
            }
        });
        scanButton.setOnClickListener(v -> scanHubQr());
        pasteButton.setOnClickListener(v -> pastePairing());
        saveButton.setOnClickListener(v -> {
            String normalized = WolUtils.normalizeMac(macInput.getText().toString());
            String ip = ipInput.getText().toString().trim();
            if (normalized == null) {
                macInput.setError("Неверный MAC-адрес");
                actionStatus.setText("⚠ Укажи корректный MAC Ethernet-адаптера.");
                return;
            }
            if (!ip.isEmpty() && !WolUtils.isValidIpv4(ip)) {
                ipInput.setError("Неверный IPv4");
                actionStatus.setText("⚠ Проверь IPv4 компьютера или оставь поле пустым.");
                return;
            }
            macInput.setText(normalized);
            saveSettings();
            actionStatus.setText(ip.isEmpty()
                    ? "✓ MAC сохранён. Wake-on-LAN готов; для проверки состояния укажи IPv4."
                    : "✓ MAC и IPv4 сохранены. Можно проверить доступность.");
            refreshNetwork();
            if (!ip.isEmpty()) checkPcStatus(false);
        });
        powerButton.setOnClickListener(v -> wakePc());
        checkButton.setOnClickListener(v -> checkPcStatus(true));

        setContentView(scroll);
        refreshNetwork();
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(statusLoop);
        main.post(statusLoop);
    }

    @Override protected void onPause() {
        main.removeCallbacks(statusLoop);
        super.onPause();
    }

    private void refreshNetwork() {
        WolUtils.NetworkDetails network = WolUtils.network(this);
        networkStatus.setText(network == null
                ? "⚠ Локальная IPv4-сеть не определена. Проверь Wi-Fi и VPN."
                : "Телефон: " + network.ip + "/" + network.prefix +
                  "  •  Broadcast: " + network.broadcast + "  •  UDP 9");
    }

    private void wakePc() {
        String mac = WolUtils.normalizeMac(macInput.getText().toString());
        if (mac == null) {
            macInput.setError("Нужен MAC-адрес");
            actionStatus.setText("⚠ Укажи корректный MAC Ethernet-адаптера ПК.");
            return;
        }
        macInput.setText(mac);
        saveSettings();
        refreshNetwork();
        if (WolUtils.network(this) == null) {
            actionStatus.setText("⚠ Нет активной IPv4-сети. Подключись к домашнему Wi-Fi.");
            return;
        }
        powerButton.setEnabled(false);
        actionStatus.setText("⏳ Отправляю Magic Packet, как в SofaPower…");
        worker.execute(() -> {
            try {
                WolUtils.send(getApplicationContext(), mac);
                main.post(() -> {
                    actionStatus.setText("✓ Команда Wake-on-LAN отправлена трижды через UDP 9. Это не подтверждает запуск ПК.");
                    powerButton.setEnabled(true);
                    main.postDelayed(() -> checkPcStatus(false), 2200);
                    main.postDelayed(() -> checkPcStatus(false), 6000);
                    main.postDelayed(() -> checkPcStatus(false), 11000);
                });
            } catch(Exception e) {
                main.post(() -> {
                    actionStatus.setText("⚠ Не удалось отправить Wake-on-LAN: " + e.getMessage());
                    powerButton.setEnabled(true);
                });
            }
        });
    }

    private void checkPcStatus(boolean fromButton) {
        String ip = ipInput.getText().toString().trim();
        if (!WolUtils.isValidIpv4(ip)) {
            if (fromButton) {
                ipInput.setError("Укажи IPv4 компьютера");
                actionStatus.setText("⚠ Укажи IPv4 ПК (команда ipconfig в Windows).");
            }
            pcStatus.setText("○ IPv4 компьютера не настроен");
            return;
        }
        saveSettings();
        if (!checking.compareAndSet(false, true)) {
            if (fromButton) actionStatus.setText("⏳ Проверка уже выполняется…");
            return;
        }
        checkButton.setEnabled(false);
        pcStatus.setText("◌ Проверяю компьютер…");
        if (fromButton) actionStatus.setText("⏳ Проверяю " + ip + " без подключения к порту RDP…");
        worker.execute(() -> {
            boolean online = WolUtils.isOnline(ip);
            main.post(() -> {
                checking.set(false);
                checkButton.setEnabled(true);
                pcStatus.setText(online ? "● ПК онлайн" : "○ ПК не отвечает / недоступен");
                pcStatus.setTextColor(online ? 0xFF79E5A6 : muted);
                actionStatus.setText(online
                        ? "✓ Компьютер " + ip + " отвечает. Соединение с сетью подтверждено."
                        : "⚠ Компьютер " + ip + " не ответил на ping / isReachable. Проверь Wi-Fi, IPv4 и настройки брандмауэра. Это не означает, что ПК выключен.");
            });
        });
    }

    private void scanHubQr() {
        actionStatus.setText("Открываю сканер QR SofaPower Hub…");
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> applyPairing(barcode.getRawValue()))
                .addOnCanceledListener(() -> actionStatus.setText("Сканирование отменено."))
                .addOnFailureListener(e -> actionStatus.setText(
                        "⚠ Сканер недоступен. Используй обычную камеру для QR, скопируй ссылку и нажми «Вставить код связи», либо введи MAC/IP вручную."));
    }

    private void pastePairing() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) throw new IllegalArgumentException("Буфер обмена пуст");
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) throw new IllegalArgumentException("Буфер обмена пуст");
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) throw new IllegalArgumentException("В буфере нет текста");
            applyPairing(text.toString().trim());
        } catch(Exception e) {
            actionStatus.setText("⚠ " + e.getMessage());
        }
    }

    private void applyPairing(String raw) {
        try {
            if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Некорректный QR");
            Uri uri = Uri.parse(raw.trim());
            if (!"sofapower".equalsIgnoreCase(uri.getScheme())
                    || !"pair".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("Ожидается QR-ссылка из SofaPower Hub");
            }
            String encoded = uri.getQueryParameter("data");
            if (encoded == null || encoded.isEmpty() || encoded.length() > 2048) {
                throw new IllegalArgumentException("Некорректные данные QR");
            }
            byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (!"sofapower".equals(payload.optString("t"))
                    || payload.optInt("v", 0) != 1) {
                throw new IllegalArgumentException("Неподдерживаемая версия QR");
            }
            String mac = WolUtils.normalizeMac(payload.optString("mac", ""));
            String ip = payload.optString("ip", "").trim();
            String name = payload.optString("name", "Домашний ПК").trim();
            if (mac == null || !WolUtils.isValidIpv4(ip)) {
                throw new IllegalArgumentException("В QR нет корректных MAC и IPv4");
            }
            if (name.isEmpty()) name = "Домашний ПК";
            if (name.length() > 32) name = name.substring(0, 32);
            macInput.setText(mac);
            ipInput.setText(ip);
            computerTitle.setText("🖥  " + name.replace('\n', ' ').replace('\r', ' '));
            saveSettings();
            refreshNetwork();
            actionStatus.setText("✓ ПК связан с SofaPower Hub через QR. MAC/IP сохранены локально.");
            Toast.makeText(this, "Компьютер добавлен: " + name, Toast.LENGTH_SHORT).show();
            checkPcStatus(false);
        } catch(Exception e) {
            actionStatus.setText("⚠ QR-код не принят: " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
