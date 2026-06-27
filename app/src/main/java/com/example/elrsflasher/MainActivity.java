package com.example.elrsflasher;

import android.Manifest;
import android.app.Activity;
import android.text.InputType;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.Base64;
import android.webkit.WebViewClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String ELRS_URL = "http://10.0.0.1";
    private static final String DEFAULT_SSID_RX = "ExpressLRS RX";
    private static final String DEFAULT_SSID_RX_HF = "ExpressLRS RX HF";
    private static final String DEFAULT_WIFI_PASSWORD = "expresslrs";
    private static final String ASSET_FIRMWARE = "OR5b4_FlyFish_9624R_wifi_auto_2s.bin";

    private static final String DEFAULT_BINDING_UID = "24,99,211,80,18,169";
    private static final int DEFAULT_PACKET_RATE_IDX = 23;
    private static final int DEFAULT_WIFI_AUTO_INTERVAL_SEC = 2;
    private static final int DEFAULT_CYCLE_DELAY_SEC = 60;
    private static final int DEFAULT_WIFI_CONNECT_SHORT_SEC = 12;
    private static final int DEFAULT_WIFI_CONNECT_AFTER_REBOOT_SEC = 25;

    private String ssidRx = DEFAULT_SSID_RX;
    private String ssidRxHf = DEFAULT_SSID_RX_HF;
    private String wifiPassword = DEFAULT_WIFI_PASSWORD;
    private String bindingUidText = DEFAULT_BINDING_UID;
    private int packetRateIdx = DEFAULT_PACKET_RATE_IDX;
    private int wifiAutoIntervalSec = DEFAULT_WIFI_AUTO_INTERVAL_SEC;
    private int cycleDelaySec = DEFAULT_CYCLE_DELAY_SEC;
    private int wifiConnectShortSec = DEFAULT_WIFI_CONNECT_SHORT_SEC;
    private int wifiConnectAfterRebootSec = DEFAULT_WIFI_CONNECT_AFTER_REBOOT_SEC;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView statusView;
    private Button btnWifi;
    private Button btnFlash;
    private Button btnSettings;
    private Button btnCycle;
    private Button btnSettingsMenu;
    private Button btnStop;
    private WebView webView;

    private volatile CountDownLatch webViewLatch;
    private volatile String webViewResult = "";

    private volatile boolean running = false;
    private volatile boolean cycleMode = false;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network activeNetwork;

    private volatile CountDownLatch permissionLatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        loadAppSettings();
        buildUi();
        requestRuntimePermissions();

        log("Готово. v0.12 editable settings.");
        log("Прошивка встроена в APK: " + ASSET_FIRMWARE);
        log("Wi-Fi: " + ssidRxHf + " / " + ssidRx + ", пароль " + wifiPassword);
        log("Текущие настройки: " + currentSettingsText());
        log("Android может показать системное окно разрешений и окно подключения — оба надо подтвердить.");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        statusView = new TextView(this);
        statusView.setText("ELRS Android Flasher");
        statusView.setTextSize(20);
        statusView.setTypeface(null, 1);
        root.addView(statusView);

        TextView info = new TextView(this);
        info.setText(currentSettingsText());
        info.setTextSize(14);
        info.setPadding(0, dp(6), 0, dp(8));
        root.addView(info);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        btnWifi = new Button(this);
        btnWifi.setText("Подключить Wi-Fi");
        btnWifi.setOnClickListener(v -> runAsync(() -> {
            setRunning(true);
            try {
                connectToAnyElrsWifiBlocking(wifiConnectShortSec);
            } finally {
                setRunning(false);
            }
        }));
        row1.addView(btnWifi, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnFlash = new Button(this);
        btnFlash.setText("Прошить");
        btnFlash.setOnClickListener(v -> startOneCycle(false));
        row1.addView(btnFlash, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        btnSettings = new Button(this);
        btnSettings.setText("Применить настройки");
        btnSettings.setOnClickListener(v -> runAsync(() -> {
            setRunning(true);
            try {
                log("Ручное применение настроек. Только Wi-Fi: ExpressLRS RX.");
                connectOnlyRxWifiBlocking(wifiConnectAfterRebootSec);
                if (!waitWebUiAvailable(35)) {
                    log("WebUI не отвечает, настройки не применены.");
                    return;
                }
                if (applySettings()) {
                    logBig("НАСТРОЙКИ ОТПРАВЛЕНЫ.");
                } else {
                    log("Настройки не применились.");
                }
            } finally {
                setRunning(false);
            }
        }));
        row2.addView(btnSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnCycle = new Button(this);
        btnCycle.setText("Цикл");
        btnCycle.setOnClickListener(v -> startCycleMode());
        row2.addView(btnCycle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnSettingsMenu = new Button(this);
        btnSettingsMenu.setText("Настройки");
        btnSettingsMenu.setOnClickListener(v -> showSettingsDialog());
        row2.addView(btnSettingsMenu, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnStop = new Button(this);
        btnStop.setText("Стоп");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> {
            cycleMode = false;
            running = false;
            log("Остановка запрошена.");
            setRunning(false);
        });
        row2.addView(btnStop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(row2);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        root.addView(webView, new LinearLayout.LayoutParams(1, 1));

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }


    private String currentSettingsText() {
        return "UID=" + bindingUidText
                + "; rateidx=" + packetRateIdx
                + "; wifi-on-interval=" + wifiAutoIntervalSec
                + "; цикл=" + cycleDelaySec + "с";
    }

    private void loadAppSettings() {
        SharedPreferences p = getSharedPreferences("elrs_settings", MODE_PRIVATE);
        ssidRxHf = p.getString("ssid_rx_hf", DEFAULT_SSID_RX_HF);
        ssidRx = p.getString("ssid_rx", DEFAULT_SSID_RX);
        wifiPassword = p.getString("wifi_password", DEFAULT_WIFI_PASSWORD);
        bindingUidText = p.getString("binding_uid", DEFAULT_BINDING_UID);
        packetRateIdx = p.getInt("packet_rate_idx", DEFAULT_PACKET_RATE_IDX);
        wifiAutoIntervalSec = p.getInt("wifi_auto_interval", DEFAULT_WIFI_AUTO_INTERVAL_SEC);
        cycleDelaySec = p.getInt("cycle_delay", DEFAULT_CYCLE_DELAY_SEC);
        wifiConnectShortSec = p.getInt("wifi_connect_short", DEFAULT_WIFI_CONNECT_SHORT_SEC);
        wifiConnectAfterRebootSec = p.getInt("wifi_connect_after", DEFAULT_WIFI_CONNECT_AFTER_REBOOT_SEC);
    }

    private void saveAppSettings() {
        getSharedPreferences("elrs_settings", MODE_PRIVATE).edit()
                .putString("ssid_rx_hf", ssidRxHf)
                .putString("ssid_rx", ssidRx)
                .putString("wifi_password", wifiPassword)
                .putString("binding_uid", bindingUidText)
                .putInt("packet_rate_idx", packetRateIdx)
                .putInt("wifi_auto_interval", wifiAutoIntervalSec)
                .putInt("cycle_delay", cycleDelaySec)
                .putInt("wifi_connect_short", wifiConnectShortSec)
                .putInt("wifi_connect_after", wifiConnectAfterRebootSec)
                .apply();
    }

    private int parsePositiveInt(String s, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(String.valueOf(s).trim());
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (Exception e) {
            return fallback;
        }
    }

    private int[] getBindingUidArray() {
        int[] fallback = new int[] {24, 99, 211, 80, 18, 169};
        try {
            String[] parts = bindingUidText.split(",");
            if (parts.length != 6) return fallback;
            int[] arr = new int[6];
            for (int i = 0; i < 6; i++) {
                int v = Integer.parseInt(parts[i].trim());
                if (v < 0 || v > 255) return fallback;
                arr[i] = v;
            }
            return arr;
        } catch (Exception e) {
            return fallback;
        }
    }

    private EditText makeSettingsEdit(LinearLayout parent, String label, String value, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        parent.addView(tv);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setInputType(inputType);
        parent.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        EditText eSsidHf = makeSettingsEdit(box, "SSID до прошивки", ssidRxHf, InputType.TYPE_CLASS_TEXT);
        EditText eSsidRx = makeSettingsEdit(box, "SSID после ребута / для настроек", ssidRx, InputType.TYPE_CLASS_TEXT);
        EditText ePass = makeSettingsEdit(box, "Пароль Wi-Fi", wifiPassword, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText eUid = makeSettingsEdit(box, "Binding UID, 6 чисел через запятую", bindingUidText, InputType.TYPE_CLASS_TEXT);
        EditText eRate = makeSettingsEdit(box, "Packet rate index", String.valueOf(packetRateIdx), InputType.TYPE_CLASS_NUMBER);
        EditText eWifiInterval = makeSettingsEdit(box, "WiFi auto-on interval, секунд", String.valueOf(wifiAutoIntervalSec), InputType.TYPE_CLASS_NUMBER);
        EditText eCycle = makeSettingsEdit(box, "Пауза нового цикла, секунд", String.valueOf(cycleDelaySec), InputType.TYPE_CLASS_NUMBER);
        EditText eConnBefore = makeSettingsEdit(box, "Таймаут подключения до прошивки, секунд", String.valueOf(wifiConnectShortSec), InputType.TYPE_CLASS_NUMBER);
        EditText eConnAfter = makeSettingsEdit(box, "Таймаут подключения после ребута, секунд", String.valueOf(wifiConnectAfterRebootSec), InputType.TYPE_CLASS_NUMBER);

        new AlertDialog.Builder(this)
                .setTitle("Настройки ELRS Flasher")
                .setView(scroll)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    ssidRxHf = eSsidHf.getText().toString().trim();
                    ssidRx = eSsidRx.getText().toString().trim();
                    wifiPassword = ePass.getText().toString();
                    bindingUidText = eUid.getText().toString().trim();

                    packetRateIdx = parsePositiveInt(eRate.getText().toString(), DEFAULT_PACKET_RATE_IDX, 0, 200);
                    wifiAutoIntervalSec = parsePositiveInt(eWifiInterval.getText().toString(), DEFAULT_WIFI_AUTO_INTERVAL_SEC, 0, 3600);
                    cycleDelaySec = parsePositiveInt(eCycle.getText().toString(), DEFAULT_CYCLE_DELAY_SEC, 1, 3600);
                    wifiConnectShortSec = parsePositiveInt(eConnBefore.getText().toString(), DEFAULT_WIFI_CONNECT_SHORT_SEC, 3, 120);
                    wifiConnectAfterRebootSec = parsePositiveInt(eConnAfter.getText().toString(), DEFAULT_WIFI_CONNECT_AFTER_REBOOT_SEC, 3, 180);

                    if (ssidRxHf.length() == 0) ssidRxHf = DEFAULT_SSID_RX_HF;
                    if (ssidRx.length() == 0) ssidRx = DEFAULT_SSID_RX;
                    if (wifiPassword.length() == 0) wifiPassword = DEFAULT_WIFI_PASSWORD;

                    saveAppSettings();
                    log("Настройки сохранены: " + currentSettingsText());
                    log("Wi-Fi: " + ssidRxHf + " / " + ssidRx + ", пароль " + wifiPassword);
                })
                .setNeutralButton("Сброс", (dialog, which) -> {
                    ssidRxHf = DEFAULT_SSID_RX_HF;
                    ssidRx = DEFAULT_SSID_RX;
                    wifiPassword = DEFAULT_WIFI_PASSWORD;
                    bindingUidText = DEFAULT_BINDING_UID;
                    packetRateIdx = DEFAULT_PACKET_RATE_IDX;
                    wifiAutoIntervalSec = DEFAULT_WIFI_AUTO_INTERVAL_SEC;
                    cycleDelaySec = DEFAULT_CYCLE_DELAY_SEC;
                    wifiConnectShortSec = DEFAULT_WIFI_CONNECT_SHORT_SEC;
                    wifiConnectAfterRebootSec = DEFAULT_WIFI_CONNECT_AFTER_REBOOT_SEC;
                    saveAppSettings();
                    log("Настройки сброшены: " + currentSettingsText());
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void requestRuntimePermissions() {
        // Стартовый запрос. Основная проверка всё равно делается прямо перед Wi-Fi подключением.
        requestWifiPermissionsAsync(false);
    }

    private boolean hasWifiConnectPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }

        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= 33) {
            boolean nearby = checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            // Ошибка Android пишет "either of permissions", поэтому принимаем nearby ИЛИ location.
            return nearby || fine || coarse;
        }

        return fine || coarse;
    }

    private ArrayList<String> missingWifiPermissions() {
        ArrayList<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT < 23) {
            return perms;
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        return perms;
    }

    private void requestWifiPermissionsAsync(boolean verbose) {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }

        ArrayList<String> perms = missingWifiPermissions();
        if (perms.isEmpty()) {
            if (verbose) log("Wi-Fi permissions уже выданы.");
            return;
        }

        if (verbose) {
            log("Android требует разрешение для Wi-Fi подключения.");
            log("Выдай Nearby devices / Устройства поблизости и Location / Геолокация.");
        }

        requestPermissions(perms.toArray(new String[0]), 100);
    }

    private boolean ensureWifiPermissionsBlocking(int timeoutSec) {
        if (hasWifiConnectPermission()) {
            return true;
        }

        permissionLatch = new CountDownLatch(1);
        ui.post(() -> requestWifiPermissionsAsync(true));

        try {
            permissionLatch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        boolean ok = hasWifiConnectPermission();
        if (!ok) {
            log("Разрешения для Wi-Fi не выданы.");
            log("Открой Android: Настройки → Приложения → ELRS Flasher → Разрешения.");
            log("Включи Nearby devices / Устройства поблизости и Location / Геолокация.");
            log("Также включи саму Геолокацию в шторке Android, если планшет Android 10-12.");
        }

        return ok;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 100) {
            boolean ok = hasWifiConnectPermission();
            if (ok) {
                log("Разрешения для Wi-Fi выданы.");
            } else {
                log("Разрешения для Wi-Fi НЕ выданы или выданы не полностью.");
            }

            CountDownLatch latch = permissionLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private void startOneCycle(boolean fromCycleMode) {
        runAsync(() -> {
            if (!fromCycleMode) {
                cycleMode = false;
            }
            setRunning(true);
            try {
                boolean ok = runFullFlashCycle();
                if (ok) {
                    logBig("ЭТАП ПРОШИВКИ И POST-FLASH НАСТРОЙКИ ЗАВЕРШЕНЫ.");
                } else {
                    log("Цикл завершился ошибкой.");
                }
            } finally {
                if (!fromCycleMode) {
                    setRunning(false);
                }
            }
        });
    }

    private void startCycleMode() {
        runAsync(() -> {
            cycleMode = true;
            setRunning(true);
            int n = 1;
            try {
                log("=== Режим Цикл включён ===");
                while (cycleMode && running) {
                    log("=== ЦИКЛ #" + n + ": старт ===");
                    boolean ok = runFullFlashCycle();
                    if (!ok) {
                        log("Цикл остановлен: проход не завершился успешно.");
                        break;
                    }

                    logBig("ЭТАП ПРОШИВКИ И POST-FLASH НАСТРОЙКИ ЗАВЕРШЕНЫ.");

                    for (int left = cycleDelaySec; left > 0; left--) {
                        if (!cycleMode || !running) break;
                        if (left == cycleDelaySec || left <= 5 || left % 10 == 0) {
                            log("Следующий цикл через " + left + " сек...");
                        }
                        sleep(1000);
                    }
                    n++;
                }
            } finally {
                cycleMode = false;
                setRunning(false);
                log("Режим Цикл остановлен.");
            }
        });
    }

    private boolean runFullFlashCycle() {
        try {
            if (!connectToAnyElrsWifiBlocking(wifiConnectShortSec)) {
                log("Не удалось подключиться к ExpressLRS Wi-Fi.");
                return false;
            }

            if (!waitWebUiAvailable(35)) {
                log("WebUI не отвечает до прошивки.");
                return false;
            }

            if (!uploadFirmware()) {
                log("Загрузка прошивки не удалась.");
                return false;
            }

            log("Прошивка отправлена. Жду ребут...");
            waitWebUiDown(60);

            log("После ребута снова подключаюсь к Wi-Fi. Приоритет: ExpressLRS RX.");
            if (!connectAfterRebootWifiBlocking(wifiConnectAfterRebootSec)) {
                log("Не удалось повторно подключиться после ребута.");
                return false;
            }

            if (!waitWebUiAvailable(45)) {
                log("WebUI после ребута не отвечает.");
                return false;
            }

            boolean okSettings = applySettings();
            if (!okSettings) {
                log("Post настройки не применились. Делаю автоповтор один раз с приоритетом ExpressLRS RX...");
                connectAfterRebootWifiBlocking(wifiConnectAfterRebootSec);
                waitWebUiAvailable(35);
                okSettings = applySettings();
            }

            return okSettings;

        } catch (Exception e) {
            log("Ошибка цикла: " + e.getMessage());
            return false;
        }
    }


    private boolean isWebUiReachableNow() {
        try {
            HttpResult r = httpGet("/");
            return r.code >= 200 && r.code < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openWifiSettingsHint() {
        try {
            log("Открываю системные Wi-Fi настройки. Выбери ExpressLRS RX / RX HF вручную, если авто-подключение не сработало.");
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            log("Не удалось открыть Wi-Fi настройки: " + e.getMessage());
        }
    }

    private void releaseCurrentWifiRequest() {
        try {
            if (networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                networkCallback = null;
            }
        } catch (Exception ignored) {}

        try {
            connectivityManager.bindProcessToNetwork(null);
        } catch (Exception ignored) {}

        activeNetwork = null;
    }

    private boolean connectOnlyRxHfWifiBlocking(int timeoutSec) {
        log("Запрашиваю подключение к Wi-Fi ExpressLRS. Только: " + ssidRxHf);
        if (isWebUiReachableNow()) {
            log("WebUI уже доступен через текущую Wi-Fi сеть.");
            return true;
        }
        boolean ok = connectWifiBlocking(ssidRxHf, timeoutSec);
        if (!ok && isWebUiReachableNow()) return true;
        return ok;
    }

    private boolean connectOnlyRxWifiBlocking(int timeoutSec) {
        log("Запрашиваю подключение к Wi-Fi ExpressLRS. Только: " + ssidRx);
        releaseCurrentWifiRequest();
        sleep(500);
        boolean ok = connectWifiBlocking(ssidRx, timeoutSec);
        if (!ok && isWebUiReachableNow()) return true;
        return ok;
    }

    private boolean connectToAnyElrsWifiBlocking(int timeoutSec) {
        return connectOnlyRxHfWifiBlocking(Math.min(timeoutSec, wifiConnectShortSec));
    }

    private boolean connectAfterRebootWifiBlocking(int timeoutSec) {
        return connectOnlyRxWifiBlocking(Math.min(timeoutSec, wifiConnectAfterRebootSec));
    }

    private boolean connectPreferredElrsWifiBlocking(String primarySsid, String fallbackSsid, int timeoutSec, boolean acceptAlreadyConnected) {
        log("Запрашиваю подключение к Wi-Fi ExpressLRS. Приоритет: " + primarySsid);

        if (acceptAlreadyConnected && isWebUiReachableNow()) {
            log("WebUI уже доступен через текущую Wi-Fi сеть.");
            return true;
        }

        if (!acceptAlreadyConnected) {
            log("Сбрасываю старый Wi-Fi request, чтобы не остаться на старой сети.");
            releaseCurrentWifiRequest();
            sleep(700);
        }

        int partTimeout = Math.max(20, timeoutSec / 2);

        if (connectWifiBlocking(primarySsid, partTimeout)) return true;
        if (isWebUiReachableNow()) {
            log("WebUI доступен после попытки " + primarySsid + ".");
            return true;
        }

        if (fallbackSsid != null && fallbackSsid.length() > 0) {
            log("Пробую fallback SSID: " + fallbackSsid);
            if (connectWifiBlocking(fallbackSsid, partTimeout)) return true;
            if (isWebUiReachableNow()) {
                log("WebUI доступен после fallback " + fallbackSsid + ".");
                return true;
            }
        }

        log("Автоподключение не сработало.");
        log("Fallback: можно подключить Wi-Fi вручную к " + primarySsid + ".");
        openWifiSettingsHint();

        long end = System.currentTimeMillis() + Math.max(30, timeoutSec) * 1000L;
        while (running && System.currentTimeMillis() < end) {
            if (isWebUiReachableNow()) {
                log("WebUI стал доступен после ручного подключения.");
                return true;
            }
            sleep(1000);
        }

        return false;
    }

    private boolean connectWifiBlocking(String ssid, int timeoutSec) {
        if (!ensureWifiPermissionsBlocking(30)) {
            log("Ошибка подключения к " + ssid + ": нет разрешений Android.");
            return false;
        }

        if (Build.VERSION.SDK_INT < 29) {
            log("WifiNetworkSpecifier требует Android 10+.");
            return false;
        }

        try {
            if (networkCallback != null) {
                try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
                networkCallback = null;
            }

            CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = {false};

            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(wifiPassword)
                    .build();

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    activeNetwork = network;
                    connectivityManager.bindProcessToNetwork(network);
                    ok[0] = true;
                    log("Подключено к " + ssid + ". HTTP будет идти через эту Wi-Fi сеть.");
                    latch.countDown();
                }

                @Override
                public void onUnavailable() {
                    log("Android не подключился к " + ssid + ".");
                    latch.countDown();
                }

                @Override
                public void onLost(Network network) {
                    if (activeNetwork == network) {
                        activeNetwork = null;
                        try { connectivityManager.bindProcessToNetwork(null); } catch (Exception ignored) {}
                    }
                    log("Wi-Fi сеть потеряна: " + ssid);
                }
            };

            log("Android может показать окно подтверждения для сети: " + ssid);
            connectivityManager.requestNetwork(request, networkCallback);

            boolean finished = latch.await(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                log("Таймаут подключения к " + ssid + ".");
                return false;
            }
            return ok[0];

        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            log("Ошибка подключения к " + ssid + ": " + msg);
            if (msg.toLowerCase(Locale.ROOT).contains("permission") || msg.toLowerCase(Locale.ROOT).contains("granted")) {
                log("Это ошибка Android-разрешений. Проверь Nearby devices / Location в настройках приложения.");
            }
            return false;
        }
    }

    private boolean waitWebUiAvailable(int timeoutSec) {
        log("Жду WebUI " + ELRS_URL + "/");
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (running && System.currentTimeMillis() < end) {
            try {
                HttpResult r = httpGet("/");
                if (r.code >= 200 && r.code < 500) {
                    log("WebUI доступен. HTTP " + r.code);
                    return true;
                }
            } catch (Exception ignored) {}
            sleep(1000);
        }
        log("WebUI не стал доступен за таймаут.");
        return false;
    }

    private boolean waitWebUiDown(int timeoutSec) {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (running && System.currentTimeMillis() < end) {
            try {
                HttpResult r = httpGet("/");
                if (r.code <= 0) {
                    log("WebUI пропал — похоже, ребут.");
                    return true;
                }
            } catch (Exception e) {
                log("WebUI пропал — похоже, ребут.");
                return true;
            }
            sleep(1000);
        }
        log("WebUI не пропал за таймаут. Продолжаю.");
        return false;
    }

    private String extractJsonStatus(String body) {
        if (body == null || body.length() == 0) return "";
        try {
            JSONObject obj = new JSONObject(body);
            return obj.optString("status", "").trim().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean uploadFirmware() {
        // V0.8: upload нативный через HttpURLConnection.
        // WebView upload с base64 зависал на большой прошивке.
        try {
            byte[] firmware = readAsset(ASSET_FIRMWARE);
            String boundary = "----ELRSAndroidBoundary" + System.currentTimeMillis();
            String filename = ASSET_FIRMWARE;

            log("Загружаю прошивку нативно: " + filename + ", размер " + firmware.length + " байт");

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);

            writeUtf8(out, "--" + boundary + "\r\n");
            writeUtf8(out, "Content-Disposition: form-data; name=\"file_name\"\r\n\r\n");
            writeUtf8(out, filename + "\r\n");

            writeUtf8(out, "--" + boundary + "\r\n");
            writeUtf8(out, "Content-Disposition: form-data; name=\"upload\"; filename=\"" + filename + "\"\r\n");
            writeUtf8(out, "Content-Type: application/octet-stream\r\n\r\n");
            out.write(firmware);
            writeUtf8(out, "\r\n--" + boundary + "--\r\n");
            out.flush();

            HttpResult r;
            try {
                r = httpPostRaw("/update", body.toByteArray(), "multipart/form-data; boundary=" + boundary,
                        new String[][] {{"X-FileSize", String.valueOf(firmware.length)}});
            } catch (Exception uploadEx) {
                String msg = String.valueOf(uploadEx.getMessage());
                log("Ошибка upload: " + msg);
                log("После upload соединение могло оборваться из-за старта прошивки. Проверяю, не ушёл ли WebUI в ребут...");
                if (waitWebUiDown(12)) {
                    log("WebUI пропал после upload error — считаю, что прошивка стартовала.");
                    return true;
                }
                return false;
            }

            log("Ответ /update: HTTP " + r.code + " " + safeShort(r.body, 500));

            String low = r.body == null ? "" : r.body.toLowerCase(Locale.ROOT);
            String status = extractJsonStatus(r.body);

            if (r.code == 200 && "ok".equals(status)) {
                log("Устройство приняло прошивку: status=ok.");
                return true;
            }

            if (r.code == 200 && ("mismatch".equals(status) || low.contains("\"status\":\"mismatch\"") || low.contains("\"status\": \"mismatch\""))) {
                log("Target mismatch. Автоматически подтверждаю Flash Anyway...");
                if (confirmFlashAnywayNative()) {
                    log("Flash Anyway подтверждён, ребут проверен.");
                    return true;
                }

                log("Native confirm не сработал. Пробую подтверждение через WebView...");
                if (confirmFlashAnywayWebView()) {
                    log("Flash Anyway подтверждён через WebView.");
                    return true;
                }

                log("Flash Anyway не подтвердился. Проверяю, не стартовала ли прошивка...");
                if (waitWebUiDown(15)) {
                    log("WebUI пропал — считаю, что прошивка всё же стартовала.");
                    return true;
                }

                return false;
            }

            if (r.code == 200 && "error".equals(status)) {
                log("Устройство вернуло ошибку update.");
                return false;
            }

            if (r.code == 200) {
                log("HTTP 200 без явного OK/mismatch. Проверяю ребут...");
                if (waitWebUiDown(15)) {
                    log("WebUI пропал — считаю upload успешным.");
                    return true;
                }
                return true;
            }

            return false;

        } catch (Exception e) {
            log("Ошибка upload: " + e.getMessage());
            return false;
        }
    }

    private String extractJsonStatus(String body) {
        if (body == null || body.length() == 0) return "";
        try {
            JSONObject obj = new JSONObject(body);
            return obj.optString("status", "").trim().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean waitOrForceRebootAfterFlashAnyway() {
        log("Жду, что WebUI пропадёт после Flash Anyway...");
        if (waitWebUiDown(20)) {
            log("WebUI пропал после Flash Anyway — ребут/перезапуск пошёл.");
            return true;
        }

        log("WebUI не пропал после Flash Anyway. Пробую принудительный /reboot...");
        try {
            HttpResult rb = httpGet("/reboot");
            log("GET /reboot after Flash Anyway: HTTP " + rb.code + " " + safeShort(rb.body, 200));
        } catch (Exception e) {
            log("GET /reboot after Flash Anyway оборвался, это может быть нормально: " + e.getMessage());
        }

        if (waitWebUiDown(15)) {
            log("WebUI пропал после принудительного /reboot.");
            return true;
        }

        log("После Flash Anyway и /reboot WebUI не пропал. Считаю, что ребут не стартовал.");
        return false;
    }

    private boolean confirmFlashAnywayNative() {
        String[] paths = new String[] {
                "/forceupdate?action=confirm",
                "/forceupdate?confirm=true",
                "/forceupdate"
        };

        for (String path : paths) {
            try {
                HttpResult c = httpGet(path);
                log("GET " + path + ": HTTP " + c.code + " " + safeShort(c.body, 200));
                String status = extractJsonStatus(c.body);
                String low = c.body == null ? "" : c.body.toLowerCase(Locale.ROOT);
                if (c.code == 200 && ("ok".equals(status) || low.contains("\"status\":\"ok\"") || low.length() == 0)) {
                    sleep(800);
                    return waitOrForceRebootAfterFlashAnyway();
                }
            } catch (Exception e) {
                log("GET " + path + " оборвался: " + e.getMessage());
                if (waitWebUiDown(8)) return true;
            }

            try {
                HttpResult p = httpPostRaw(path, new byte[0], "text/plain", null);
                log("POST " + path + ": HTTP " + p.code + " " + safeShort(p.body, 200));
                String status = extractJsonStatus(p.body);
                String low = p.body == null ? "" : p.body.toLowerCase(Locale.ROOT);
                if (p.code == 200 && ("ok".equals(status) || low.contains("\"status\":\"ok\"") || low.length() == 0)) {
                    sleep(800);
                    return waitOrForceRebootAfterFlashAnyway();
                }
            } catch (Exception e) {
                log("POST " + path + " оборвался: " + e.getMessage());
                if (waitWebUiDown(8)) return true;
            }
        }

        return false;
    }

    private boolean confirmFlashAnywayWebView() {
        try {
            if (!webViewLoadBlocking("/", 30)) {
                log("WebView не загрузил WebUI для Flash Anyway.");
                return false;
            }

            String js =
                    "(async function(){\\n" +
                    "  try {\\n" +
                    "    const r = await fetch('/forceupdate?action=confirm', {method:'GET', headers:{'Accept':'application/json'}});\\n" +
                    "    const t = await r.text();\\n" +
                    "    AndroidBridge.onWebResult('CONFIRM_WEBVIEW:' + r.status + ':' + t.substring(0,400));\\n" +
                    "  } catch(e) { AndroidBridge.onWebResult('CONFIRM_ERROR:' + e.message); }\\n" +
                    "})();";

            String result = webViewEvalBlocking(js, 30);
            log("WebView Flash Anyway result: " + safeShort(result, 500));
            String low = result.toLowerCase(Locale.ROOT);
            boolean ok = low.contains("confirm_webview:200") && (low.contains("\"status\":\"ok\"") || low.contains("ok"));
            if (ok) {
                return waitOrForceRebootAfterFlashAnyway();
            }
            return false;
        } catch (Exception e) {
            log("Ошибка WebView Flash Anyway: " + e.getMessage());
            return false;
        }
    }

    private String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\\\", "\\\\\\\\").replace("'", "\\\\'").replace("\\n", " ").replace("\\r", " ");
    }

    private boolean confirmFlashAnyway() {
        String[] paths = new String[] {
                "/forceupdate?action=confirm",
                "/forceupdate?confirm=true",
                "/forceupdate"
        };

        for (String path : paths) {
            try {
                HttpResult c = httpGet(path);
                log("GET " + path + ": HTTP " + c.code + " " + safeShort(c.body, 160));
                String low = c.body == null ? "" : c.body.toLowerCase(Locale.ROOT);
                if (c.code == 200 && (low.contains("ok") || low.contains("success") || low.length() == 0)) {
                    sleep(800);
                    return true;
                }
            } catch (Exception e) {
                log("GET " + path + " оборвался: " + e.getMessage());
                if (waitWebUiDown(8)) return true;
            }

            try {
                HttpResult p = httpPostRaw(path, new byte[0], "text/plain", null);
                log("POST " + path + ": HTTP " + p.code + " " + safeShort(p.body, 160));
                String low = p.body == null ? "" : p.body.toLowerCase(Locale.ROOT);
                if (p.code == 200 && (low.contains("ok") || low.contains("success") || low.length() == 0)) {
                    sleep(800);
                    return true;
                }
            } catch (Exception e) {
                log("POST " + path + " оборвался: " + e.getMessage());
                if (waitWebUiDown(8)) return true;
            }
        }

        return false;
    }

    private boolean applySettings() {
        // V0.10: без WebView-ожиданий. Быстро применяем через HTTP API.
        // Flash Anyway уже подтверждается отдельно. Для Options после POST обязательно /reboot.
        try {
            log("=== Быстро применяю Model + Options через HTTP API ===");

            HttpResult cfgRes = httpGet("/config");
            if (cfgRes.code < 200 || cfgRes.code >= 300) {
                log("GET /config не удался: HTTP " + cfgRes.code);
                return false;
            }

            JSONObject root = new JSONObject(cfgRes.body);
            JSONObject cfg = root.optJSONObject("config");
            if (cfg == null) cfg = root;

            JSONArray uid = new JSONArray();
            for (int b : getBindingUidArray()) uid.put(b);

            cfg.put("uid", uid);
            cfg.put("vbind", cfg.optInt("vbind", 0));
            cfg.put("force-tlm", 1);
            if (!cfg.has("serial-protocol")) cfg.put("serial-protocol", 0);
            if (!cfg.has("serial1-protocol")) cfg.put("serial1-protocol", 0);
            if (!cfg.has("sbus-failsafe")) cfg.put("sbus-failsafe", 0);
            if (!cfg.has("modelid")) cfg.put("modelid", 255);
            if (!cfg.has("pwm")) cfg.put("pwm", new JSONArray());

            HttpResult cfgPost = httpPostJson("/config", cfg.toString());
            log("POST /config: HTTP " + cfgPost.code);
            if (cfgPost.code < 200 || cfgPost.code >= 300) return false;

            sleep(400);

            HttpResult optRes = httpGet("/options.json");
            if (optRes.code < 200 || optRes.code >= 300) {
                log("GET /options.json не удался: HTTP " + optRes.code);
                return false;
            }

            JSONObject options = new JSONObject(optRes.body);
            options.put("rateidx", packetRateIdx);
            options.put("wifi-on-interval", wifiAutoIntervalSec);
            options.put("customised", true);

            HttpResult optPost = httpPostJson("/options.json", options.toString());
            log("POST /options.json: HTTP " + optPost.code);
            if (optPost.code < 200 || optPost.code >= 300) return false;

            try {
                HttpResult rb = httpGet("/reboot");
                log("GET /reboot: HTTP " + rb.code);
            } catch (Exception e) {
                log("GET /reboot оборвался, это может быть нормально.");
            }

            log("HTTP настройки отправлены.");
            return true;

        } catch (Exception e) {
            log("Ошибка HTTP настроек: " + e.getMessage());
            return false;
        }
    }

    private String webViewEvalReturnBlocking(String bodyScript, int timeoutSec) {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {""};

        String script = "(function(){ try { " + bodyScript + " } catch(e) { return 'ERR:' + e.message; } })();";

        ui.post(() -> {
            try {
                webView.evaluateJavascript(script, value -> {
                    result[0] = decodeJsValue(value);
                    latch.countDown();
                });
            } catch (Exception e) {
                result[0] = "ERR:" + e.getMessage();
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        if (result[0] == null || result[0].length() == 0) return "TIMEOUT";
        return result[0];
    }

    private String decodeJsValue(String value) {
        if (value == null) return "";
        if ("null".equals(value)) return "";
        try {
            JSONArray arr = new JSONArray("[" + value + "]");
            Object v = arr.get(0);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception ignored) {
            return value;
        }
    }

    private boolean waitWebViewSelector(String selector, int timeoutSec) {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        String safe = selector.replace("\\", "\\\\").replace("'", "\\'");
        while (running && System.currentTimeMillis() < end) {
            String r = webViewEvalReturnBlocking(
                    "return document.querySelector('" + safe + "') ? 'YES' : 'NO';",
                    3
            );
            if ("YES".equals(r)) return true;
            sleep(300);
        }
        return false;
    }

    private boolean clickWebViewButtonLoop(String exactText, int timeoutSec) {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        String safe = exactText.replace("\\", "\\\\").replace("'", "\\'");
        while (running && System.currentTimeMillis() < end) {
            String r = webViewEvalReturnBlocking(
                    "var txt='" + safe + "'.toLowerCase();" +
                    "var btns=Array.from(document.querySelectorAll('button,input[type=button],input[type=submit],.swal2-confirm'));" +
                    "var b=btns.find(x=>(((x.innerText||x.textContent||x.value||'')+'').trim().toLowerCase()===txt));" +
                    "if(b){b.click(); return 'CLICKED';} return 'NO_BUTTON';",
                    3
            );
            if ("CLICKED".equals(r)) return true;
            sleep(400);
        }
        return false;
    }

    private boolean clickWebViewButtonContainsLoop(String lowerText, int timeoutSec) {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        String safe = lowerText.replace("\\", "\\\\").replace("'", "\\'");
        while (running && System.currentTimeMillis() < end) {
            String r = webViewEvalReturnBlocking(
                    "var txt='" + safe + "';" +
                    "var btns=Array.from(document.querySelectorAll('button,input[type=button],input[type=submit]'));" +
                    "var b=btns.find(x=>(((x.innerText||x.textContent||x.value||'')+'').toLowerCase().includes(txt)));" +
                    "if(b){b.click(); return 'CLICKED';} return 'NO_BUTTON';",
                    3
            );
            if ("CLICKED".equals(r)) return true;
            sleep(400);
        }
        return false;
    }

    private boolean applySettingsDirectHttpFallback() {
        try {
            log("=== HTTP fallback Model + Options ===");

            HttpResult cfgRes = httpGet("/config");
            if (cfgRes.code < 200 || cfgRes.code >= 300) {
                log("GET /config не удался: HTTP " + cfgRes.code);
                return false;
            }

            JSONObject root = new JSONObject(cfgRes.body);
            JSONObject cfg = root.optJSONObject("config");
            if (cfg == null) cfg = root;

            JSONArray uid = new JSONArray();
            for (int b : getBindingUidArray()) uid.put(b);
            cfg.put("uid", uid);
            cfg.put("vbind", cfg.optInt("vbind", 0));
            cfg.put("force-tlm", 1);
            if (!cfg.has("serial-protocol")) cfg.put("serial-protocol", 0);
            if (!cfg.has("serial1-protocol")) cfg.put("serial1-protocol", 0);
            if (!cfg.has("sbus-failsafe")) cfg.put("sbus-failsafe", 0);
            if (!cfg.has("modelid")) cfg.put("modelid", 255);
            if (!cfg.has("pwm")) cfg.put("pwm", new JSONArray());

            HttpResult cfgPost = httpPostJson("/config", cfg.toString());
            log("POST /config fallback: HTTP " + cfgPost.code);
            if (cfgPost.code < 200 || cfgPost.code >= 300) return false;

            sleep(600);

            HttpResult optRes = httpGet("/options.json");
            if (optRes.code < 200 || optRes.code >= 300) {
                log("GET /options.json не удался: HTTP " + optRes.code);
                return false;
            }

            JSONObject options = new JSONObject(optRes.body);
            options.put("rateidx", packetRateIdx);
            options.put("wifi-on-interval", wifiAutoIntervalSec);
            options.put("customised", true);

            HttpResult optPost = httpPostJson("/options.json", options.toString());
            log("POST /options.json fallback: HTTP " + optPost.code);
            if (optPost.code < 200 || optPost.code >= 300) return false;

            try {
                HttpResult rb = httpGet("/reboot");
                log("GET /reboot fallback: HTTP " + rb.code);
            } catch (Exception e) {
                log("Команда /reboot fallback оборвалась, это может быть нормально.");
            }

            log("HTTP fallback настройки отправлены.");
            return true;

        } catch (Exception e) {
            log("Ошибка HTTP fallback настроек: " + e.getMessage());
            return false;
        }
    }

    private boolean webViewLoadBlocking(String path, int timeoutSec) {
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};

        ui.post(() -> {
            try {
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        ok[0] = true;
                        latch.countDown();
                    }
                });
                webView.loadUrl(ELRS_URL + path);
            } catch (Exception e) {
                log("WebView load error: " + e.getMessage());
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        return ok[0];
    }

    private String webViewEvalBlocking(String script, int timeoutSec) {
        webViewResult = "";
        webViewLatch = new CountDownLatch(1);

        ui.post(() -> {
            try {
                webView.evaluateJavascript(script, null);
            } catch (Exception e) {
                webViewResult = "ERROR:" + e.getMessage();
                CountDownLatch latch = webViewLatch;
                if (latch != null) latch.countDown();
            }
        });

        try {
            webViewLatch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        String r = webViewResult == null ? "" : webViewResult;
        if (r.length() == 0) return "TIMEOUT";
        return r;
    }

    public class JsBridge {
        @JavascriptInterface
        public void onWebResult(String s) {
            webViewResult = s == null ? "" : s;
            log("WebView: " + safeShort(webViewResult, 350));

            CountDownLatch latch = webViewLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private HttpResult httpGet(String path) throws Exception {
        URL url = new URL(ELRS_URL + path);
        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept-Encoding", "identity");
        int code = conn.getResponseCode();
        String body = readResponse(conn);
        conn.disconnect();
        return new HttpResult(code, body);
    }

    private HttpResult httpPostJson(String path, String json) throws Exception {
        byte[] data = json.getBytes("UTF-8");
        return httpPostRaw(path, data, "application/json; charset=utf-8", null);
    }

    private HttpResult httpPostRaw(String path, byte[] data, String contentType, String[][] extraHeaders) throws Exception {
        URL url = new URL(ELRS_URL + path);
        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("Origin", ELRS_URL);
        conn.setRequestProperty("Referer", ELRS_URL + "/");

        if (extraHeaders != null) {
            for (String[] h : extraHeaders) {
                conn.setRequestProperty(h[0], h[1]);
            }
        }

        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        String body = readResponse(conn);
        conn.disconnect();
        return new HttpResult(code, body);
    }

    private HttpURLConnection openConnection(URL url) throws Exception {
        if (activeNetwork != null && Build.VERSION.SDK_INT >= 21) {
            return (HttpURLConnection) activeNetwork.openConnection(url);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private String readResponse(HttpURLConnection conn) {
        try {
            InputStream is;
            int code = conn.getResponseCode();
            if (code >= 400) is = conn.getErrorStream();
            else is = conn.getInputStream();
            if (is == null) return "";
            byte[] data = readAll(is);
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream is = getAssets().open(name);
        return readAll(is);
    }

    private byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        try { is.close(); } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private void writeUtf8(DataOutputStream out, String s) throws Exception {
        out.write(s.getBytes("UTF-8"));
    }

    private void runAsync(Runnable r) {
        new Thread(r, "ELRS-worker").start();
    }

    private void setRunning(boolean value) {
        running = value;
        ui.post(() -> {
            btnWifi.setEnabled(!value);
            btnFlash.setEnabled(!value);
            btnSettings.setEnabled(!value);
            btnCycle.setEnabled(!value);
            btnSettingsMenu.setEnabled(!value);
            btnStop.setEnabled(value);
            statusView.setText(value ? "Работает..." : "Готово");
        });
    }

    private void log(String s) {
        ui.post(() -> {
            logView.append(s + "\n");
            final ScrollView parent = (ScrollView) logView.getParent();
            parent.post(() -> parent.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void logBig(String s) {
        ui.post(() -> {
            logView.append("\n" + s + "\n\n");
            final ScrollView parent = (ScrollView) logView.getParent();
            parent.post(() -> parent.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    private String safeShort(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ").trim();
        if (s.length() > max) return s.substring(0, max);
        return s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        cycleMode = false;
        try {
            if (networkCallback != null) connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        try {
            connectivityManager.bindProcessToNetwork(null);
        } catch (Exception ignored) {}
    }

    private static class HttpResult {
        int code;
        String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
