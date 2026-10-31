package com.distanceuprav.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DistanceUprav 0.3.0.
 * Native dark dashboard with the same WOL protocol, preference keys, QR format,
 * network discovery and ping implementation as earlier versions.
 */
public class MainActivity extends Activity {
    private static final long STATUS_INTERVAL_MS = 20_000L;
    private static final int BG = 0xFF090D18;
    private static final int SURFACE = 0xFF151D30;
    private static final int SURFACE_2 = 0xFF202B43;
    private static final int WHITE = 0xFFF7F9FF;
    private static final int MUTED = 0xFFA1B1CC;
    private static final int PRIMARY = 0xFF8274FF;
    private static final int BLUE = 0xFF569AFF;
    private static final int SUCCESS = 0xFF65E4B5;
    private static final int WARNING = 0xFFFFCA81;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private EditText macInput, ipInput;
    private TextView pcName, pcState, pcSubtitle, networkText, macSummary, actionStatus, lastChecked;
    private TextView settingsToggle, advancedToggle;
    private LinearLayout settingsBody, advancedBody;
    private Button powerButton, checkButton;
    private AgentPanel agentPanel;
    private boolean settingsOpen, advancedOpen;

    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            refreshNetwork();
            checkPcStatus(false);
            main.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int fill, int radius, int stroke, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke > 0) drawable.setStroke(dp(stroke), strokeColor);
        return drawable;
    }

    private GradientDrawable powerShape() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF9387FF, 0xFF6551E4, 0xFF463EB7});
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(2), 0xFFA59AFF);
        return d;
    }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView value = new TextView(this);
        value.setText(label);
        value.setTextColor(color);
        value.setTextSize(size);
        value.setIncludeFontPadding(true);
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private void add(LinearLayout root, View view, int top) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        root.addView(view, params);
    }

    private LinearLayout card(int padding) {
        LinearLayout c = vertical();
        c.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        c.setBackground(shape(SURFACE, 22, 1, 0xFF29364D));
        return c;
    }

    private Button button(String caption, int fill) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(54));
        b.setMinimumHeight(dp(54));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                shape(fill, 15, 1, fill == SURFACE_2 ? 0xFF3A4A68 : fill), null));
        return b;
    }

    private EditText input(String hint, String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        field.setTextSize(16);
        field.setTextColor(WHITE);
        field.setHintTextColor(0xFF8394B2);
        field.setHint(hint);
        field.setPadding(dp(15), dp(13), dp(15), dp(13));
        field.setBackground(shape(0xFF0E1525, 14, 1, 0xFF34445F));
        return field;
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

    private String computerName() {
        String value = pref("pc_name");
        return value.isEmpty() ? "Мой компьютер" : value;
    }

    private void setAction(String message, int color) {
        actionStatus.setText(message);
        actionStatus.setTextColor(color);
    }

    private TextView heading(String caption) {
        TextView value = text(caption, 12, MUTED, true);
        value.setLetterSpacing(0.10f);
        return value;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF131B30, BG, BG}));
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("⚡", 23, WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(0xFF393A83, 15, 1, 0xFF6063B8));
        header.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout brand = vertical();
        brand.setPadding(dp(12), 0, 0, 0);
        brand.addView(text("DistanceUprav", 23, WHITE, true));
        brand.addView(text("CONTROL YOUR PC", 10, MUTED, true));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = text("v0.3", 12, 0xFFD5CFFF, true);
        version.setPadding(dp(10), dp(8), dp(10), dp(8));
        version.setBackground(shape(0xFF28274A, 20, 1, 0xFF4D497F));
        header.addView(version);
        add(root, header, 0);

        LinearLayout hero = card(20);
        add(root, hero, 24);
        add(hero, heading("ВАШ КОМПЬЮТЕР"), 0);

        pcName = text(computerName(), 25, WHITE, true);
        add(hero, pcName, 10);

        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        pcState = text("○  Статус неизвестен", 14, MUTED, true);
        pcState.setPadding(dp(12), dp(8), dp(12), dp(8));
        pcState.setBackground(shape(0xFF283348, 30, 0, 0));
        infoRow.addView(pcState);
        add(hero, infoRow, 12);

        pcSubtitle = text("Настрой MAC-адрес, чтобы включать ПК", 13, MUTED, false);
        add(hero, pcSubtitle, 12);

        FrameLayout powerFrame = new FrameLayout(this);
        LinearLayout.LayoutParams powerLayout = new LinearLayout.LayoutParams(-1, dp(202));
        powerLayout.topMargin = dp(10);
        hero.addView(powerFrame, powerLayout);

        View ring = new View(this);
        GradientDrawable ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setColor(0xFF1D2341);
        ringShape.setStroke(dp(1), 0xFF54519A);
        ring.setBackground(ringShape);
        FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER);
        powerFrame.addView(ring, ringParams);

        powerButton = new Button(this);
        powerButton.setAllCaps(false);
        powerButton.setText("⏻\nВКЛЮЧИТЬ ПК");
        powerButton.setTextSize(18);
        powerButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        powerButton.setTextColor(Color.WHITE);
        powerButton.setGravity(Gravity.CENTER);
        powerButton.setPadding(dp(8), dp(10), dp(8), dp(10));
        powerButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), powerShape(), null));
        powerButton.setContentDescription("Отправить Wake-on-LAN для включения компьютера");
        if (Build.VERSION.SDK_INT >= 21) powerButton.setElevation(dp(10));
        powerFrame.addView(powerButton, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));
        add(hero, text("Отправить команду Wake-on-LAN", 13, MUTED, false), 2);

        checkButton = button("↻   Проверить доступность", SURFACE_2);
        add(hero, checkButton, 18);

        lastChecked = text("Проверка состояния выполняется по локальному IPv4", 11, MUTED, false);
        lastChecked.setGravity(Gravity.CENTER);
        add(hero, lastChecked, 10);

        actionStatus = text("Готово к работе. Для включения достаточно MAC-адреса.", 13, 0xFFDCE4FB, false);
        actionStatus.setPadding(dp(15), dp(14), dp(15), dp(14));
        actionStatus.setBackground(shape(0xFF202B45, 14, 1, 0xFF33435F));
        add(hero, actionStatus, 18);

        LinearLayout networkCard = card(17);
        add(root, networkCard, 16);
        add(networkCard, heading("СЕТЬ И ПОДКЛЮЧЕНИЕ"), 0);
        networkText = text("Определяю локальную сеть…", 13, MUTED, false);
        add(networkCard, networkText, 12);
        macSummary = text("MAC: не настроен", 13, 0xFFD4DFFA, false);
        add(networkCard, macSummary, 8);
        Button refreshNetwork = button("↻  Обновить сведения о сети", SURFACE_2);
        refreshNetwork.setTextSize(13);
        add(networkCard, refreshNetwork, 14);
        refreshNetwork.setOnClickListener(v -> {
            refreshNetwork();
            setAction("✓ Информация о локальной сети обновлена.", SUCCESS);
        });

        agentPanel = new AgentPanel(this);
        add(root, agentPanel, 16);

        LinearLayout settingsCard = card(17);
        add(root, settingsCard, 16);
        settingsToggle = text("⚙  Настройки ПК                            ▾", 17, WHITE, true);
        settingsToggle.setPadding(dp(3), dp(8), dp(3), dp(8));
        settingsToggle.setContentDescription("Открыть или закрыть настройки компьютера");
        settingsCard.addView(settingsToggle);
        settingsBody = vertical();
        add(settingsCard, settingsBody, 4);

        add(settingsBody, text("MAC-АДРЕС ETHERNET", 12, MUTED, true), 12);
        macInput = input("AA:BB:CC:DD:EE:FF", pref("mac"));
        macInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        add(settingsBody, macInput, 8);
        Button clipboardMac = button("⎘  Вставить MAC из буфера", SURFACE_2);
        add(settingsBody, clipboardMac, 9);

        add(settingsBody, text("IPv4 КОМПЬЮТЕРА  ·  ДЛЯ ПРОВЕРКИ СТАТУСА", 12, MUTED, true), 19);
        ipInput = input("192.168.1.100", pref("host"));
        ipInput.setInputType(InputType.TYPE_CLASS_PHONE);
        add(settingsBody, ipInput, 8);
        add(settingsBody, text("Для включения ПК IPv4 не обязателен. Телефон должен быть в той же домашней сети.", 12, MUTED, false), 9);

        Button saveButton = button("✓  Сохранить настройки", PRIMARY);
        add(settingsBody, saveButton, 19);

        advancedToggle = text("▣  QR-подключение (необязательно)    ▾", 14, MUTED, true);
        advancedToggle.setPadding(dp(2), dp(16), dp(2), dp(10));
        add(settingsBody, advancedToggle, 10);
        advancedBody = vertical();
        add(settingsBody, advancedBody, 0);
        add(advancedBody, text("Для новой версии SofaPower Hub. Старому SofaPower QR-код не требуется.", 12, MUTED, false), 8);
        Button scanButton = button("▣  Сканировать QR SofaPower Hub", SURFACE_2);
        add(advancedBody, scanButton, 11);
        Button pasteButton = button("⎘  Вставить код связи из буфера", SURFACE_2);
        add(advancedBody, pasteButton, 9);

        add(root, text("DistanceUprav • Локальная сеть • UDP 9\nОтсутствие ответа на ping не означает, что компьютер выключен.", 11, MUTED, false), 23);

        settingsToggle.setOnClickListener(v -> showSettings(!settingsOpen));
        advancedToggle.setOnClickListener(v -> showAdvanced(!advancedOpen));
        powerButton.setOnClickListener(v -> wakePc());
        checkButton.setOnClickListener(v -> checkPcStatus(true));
        clipboardMac.setOnClickListener(v -> pasteMac());
        saveButton.setOnClickListener(v -> saveAndValidate());
        scanButton.setOnClickListener(v -> scanHubQr());
        pasteButton.setOnClickListener(v -> pastePairing());

        setContentView(scroll);
        showSettings(WolUtils.normalizeMac(pref("mac")) == null);
        showAdvanced(false);
        refreshSummaries();
        refreshNetwork();
    }

    private void showSettings(boolean visible) {
        settingsOpen = visible;
        settingsBody.setVisibility(visible ? View.VISIBLE : View.GONE);
        settingsToggle.setText(visible ? "⚙  Настройки ПК                            ▴"
                : "⚙  Настройки ПК                            ▾");
    }

    private void showAdvanced(boolean visible) {
        advancedOpen = visible;
        advancedBody.setVisibility(visible ? View.VISIBLE : View.GONE);
        advancedToggle.setText(visible ? "▣  QR-подключение (необязательно)    ▴"
                : "▣  QR-подключение (необязательно)    ▾");
    }

    private void refreshSummaries() {
        String mac = WolUtils.normalizeMac(macInput.getText().toString());
        macSummary.setText("MAC: " + (mac == null ? "не настроен" : WolUtils.maskMac(mac)));
        String ip = ipInput.getText().toString().trim();
        pcName.setText(computerName());
        pcSubtitle.setText(WolUtils.isValidIpv4(ip)
                ? "IPv4: " + ip + "  •  " + (mac == null ? "укажи MAC" : "Wake-on-LAN настроен")
                : (mac == null ? "Добавь MAC-адрес в настройках" : "Wake-on-LAN настроен • IPv4 не указан"));
    }

    private void refreshNetwork() {
        WolUtils.NetworkDetails network = WolUtils.network(this);
        networkText.setText(network == null
                ? "⚠ Не удалось определить локальную IPv4-сеть. Проверь домашний Wi-Fi и VPN."
                : "Телефон: " + network.ip + "/" + network.prefix
                  + "\nBroadcast: " + network.broadcast + "   •   UDP " + WolUtils.PORT);
    }

    private void saveAndValidate() {
        String mac = WolUtils.normalizeMac(macInput.getText().toString());
        String ip = ipInput.getText().toString().trim();
        if (mac == null) {
            macInput.setError("Неверный MAC Ethernet-адаптера");
            setAction("⚠ Проверь MAC-адрес. Пример: AA:BB:CC:DD:EE:FF", WARNING);
            showSettings(true);
            return;
        }
        if (!ip.isEmpty() && !WolUtils.isValidIpv4(ip)) {
            ipInput.setError("Некорректный локальный IPv4");
            setAction("⚠ Введи корректный IPv4 или оставь поле пустым.", WARNING);
            showSettings(true);
            return;
        }
        macInput.setText(mac);
        saveSettings();
        refreshSummaries();
        showSettings(false);
        hideKeyboard();
        setAction(ip.isEmpty()
                ? "✓ MAC сохранён. Для включения ПК этого достаточно."
                : "✓ Настройки сохранены. Можно включать ПК и проверять статус.", SUCCESS);
        if (!ip.isEmpty()) checkPcStatus(false);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && getCurrentFocus() != null)
            manager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    private void pasteMac() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) throw new IllegalArgumentException("Буфер обмена пуст");
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            String raw = value == null ? "" : value.toString();
            Matcher match = Pattern.compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}").matcher(raw);
            String mac = match.find() ? WolUtils.normalizeMac(match.group()) : WolUtils.normalizeMac(raw);
            if (mac == null) throw new IllegalArgumentException("MAC-адрес не найден");
            macInput.setText(mac);
            saveSettings();
            refreshSummaries();
            setAction("✓ MAC вставлен и сохранён.", SUCCESS);
        } catch(Exception e) {
            setAction("⚠ Не удалось вставить MAC: " + e.getMessage(), WARNING);
        }
    }

    private void wakePc() {
        String mac = WolUtils.normalizeMac(macInput.getText().toString());
        if (mac == null) {
            setAction("⚠ Сначала укажи MAC-адрес Ethernet-адаптера в настройках.", WARNING);
            showSettings(true);
            macInput.setError("Нужен MAC-адрес");
            return;
        }
        macInput.setText(mac);
        saveSettings();
        refreshSummaries();
        refreshNetwork();
        if (WolUtils.network(this) == null) {
            setAction("⚠ Нет локальной IPv4-сети. Подключись к домашнему Wi-Fi.", WARNING);
            return;
        }
        powerButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        powerButton.setEnabled(false);
        powerButton.animate().scaleX(0.95f).scaleY(0.95f).setDuration(110)
                .withEndAction(() -> powerButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start());
        setAction("⏳ Отправляю Wake-on-LAN на широковещательный адрес…", WHITE);
        worker.execute(() -> {
            try {
                // Identical functionality to 0.2.2: 3 Magic Packets to UDP 9.
                WolUtils.send(getApplicationContext(), mac);
                main.post(() -> {
                    setAction("✓ Три Magic Packet отправлены. Ждём ответа компьютера…", SUCCESS);
                    powerButton.setEnabled(true);
                    main.postDelayed(() -> checkPcStatus(false), 2200);
                    main.postDelayed(() -> checkPcStatus(false), 6000);
                    main.postDelayed(() -> checkPcStatus(false), 11000);
                });
            } catch(Exception e) {
                main.post(() -> {
                    setAction("⚠ Ошибка Wake-on-LAN: " + e.getMessage(), WARNING);
                    powerButton.setEnabled(true);
                });
            }
        });
    }

    private void checkPcStatus(boolean fromButton) {
        String ip = ipInput.getText().toString().trim();
        if (!WolUtils.isValidIpv4(ip)) {
            pcState.setText("○  Статус неизвестен");
            pcState.setTextColor(MUTED);
            pcState.setBackground(shape(0xFF283348, 30, 0, 0));
            if (fromButton) {
                setAction("⚠ Для проверки состояния нужен IPv4 компьютера. Для включения он не требуется.", WARNING);
                showSettings(true);
                ipInput.setError("Укажи локальный IPv4");
            }
            return;
        }
        saveSettings();
        if (!checking.compareAndSet(false, true)) {
            if (fromButton) setAction("⏳ Проверка уже выполняется.", MUTED);
            return;
        }
        checkButton.setEnabled(false);
        pcState.setText("◌  Проверяю ПК…");
        pcState.setTextColor(WHITE);
        pcState.setBackground(shape(0xFF343A5A, 30, 0, 0));
        if (fromButton) setAction("⏳ Проверяю доступность " + ip + "…", WHITE);
        worker.execute(() -> {
            boolean online = WolUtils.isOnline(ip);
            main.post(() -> {
                checking.set(false);
                checkButton.setEnabled(true);
                pcState.setText(online ? "●  Компьютер в сети" : "○  ПК не отвечает");
                pcState.setTextColor(online ? SUCCESS : WARNING);
                pcState.setBackground(shape(online ? 0xFF174C47 : 0xFF49402B, 30, 0, 0));
                lastChecked.setText("Последняя проверка: " +
                        android.text.format.DateFormat.getTimeFormat(this).format(new Date()));
                if (fromButton) {
                    setAction(online
                        ? "✓ Компьютер " + ip + " отвечает в локальной сети."
                        : "⚠ Нет ответа от " + ip + ". Это не обязательно означает выключение ПК: ping может блокироваться.", online ? SUCCESS : WARNING);
                }
            });
        });
    }

    private void scanHubQr() {
        setAction("Открываю сканер QR нового SofaPower Hub…", WHITE);
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> applyPairing(barcode.getRawValue()))
                .addOnCanceledListener(() -> setAction("Сканирование отменено.", MUTED))
                .addOnFailureListener(e -> setAction(
                        "⚠ QR-сканер недоступен. Вставь код из буфера либо введи MAC/IP вручную.", WARNING));
    }

    private void pastePairing() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) throw new IllegalArgumentException("Буфер обмена пуст");
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            if (value == null) throw new IllegalArgumentException("В буфере нет текста");
            applyPairing(value.toString().trim());
        } catch(Exception e) {
            setAction("⚠ " + e.getMessage(), WARNING);
        }
    }

    private void applyPairing(String raw) {
        try {
            if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Некорректный QR");
            Uri uri = Uri.parse(raw.trim());
            if (!"sofapower".equalsIgnoreCase(uri.getScheme())
                    || !"pair".equalsIgnoreCase(uri.getHost()))
                throw new IllegalArgumentException("Ожидается QR нового SofaPower Hub");
            String encoded = uri.getQueryParameter("data");
            if (encoded == null || encoded.isEmpty() || encoded.length() > 2048)
                throw new IllegalArgumentException("Неверные данные QR");
            byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (!"sofapower".equals(payload.optString("t")) || payload.optInt("v", 0) != 1)
                throw new IllegalArgumentException("Неподдерживаемая версия QR");
            String mac = WolUtils.normalizeMac(payload.optString("mac", ""));
            String ip = payload.optString("ip", "").trim();
            String name = payload.optString("name", "Домашний ПК").trim().replace('\n', ' ').replace('\r', ' ');
            if (mac == null || !WolUtils.isValidIpv4(ip))
                throw new IllegalArgumentException("В QR нет корректного MAC / IPv4");
            if (name.isEmpty()) name = "Домашний ПК";
            if (name.length() > 32) name = name.substring(0, 32);
            macInput.setText(mac);
            ipInput.setText(ip);
            saveSettings();
            getPreferences(MODE_PRIVATE).edit().putString("pc_name", name).apply();
            refreshSummaries();
            refreshNetwork();
            showSettings(false);
            setAction("✓ Компьютер добавлен через QR. Настройки сохранены.", SUCCESS);
            Toast.makeText(this, "ПК добавлен: " + name, Toast.LENGTH_SHORT).show();
            checkPcStatus(false);
        } catch(Exception e) {
            setAction("⚠ QR-код не принят: " + e.getMessage(), WARNING);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(statusLoop);
        main.post(statusLoop);
        if (agentPanel != null) agentPanel.onForeground();
    }

    @Override protected void onPause() {
        if (agentPanel != null) agentPanel.onBackground();
        main.removeCallbacks(statusLoop);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (agentPanel != null) agentPanel.shutdown();
        super.onDestroy();
    }
}
