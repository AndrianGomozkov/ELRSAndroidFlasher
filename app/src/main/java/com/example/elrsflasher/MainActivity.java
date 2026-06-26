package com.example.elrsflasher;

import android.Manifest;
import android.app.Activity;
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
    private static final String SSID_RX = "ExpressLRS RX";
    private static final String SSID_RX_HF = "ExpressLRS RX HF";
    private static final String WIFI_PASSWORD = "expresslrs";
    private static final String ASSET_FIRMWARE = "OR5b4_FlyFish_9624R_wifi_auto_2s.bin";

    // UID, который твой WebUI показывает для Binding Phrase = Test.
    private static final int[] BINDING_UID_TEST = {24, 99, 211, 80, 18, 169};

    private static final int PACKET_RATE_IDX = 23;       // S-Band RUS 24Hz(-126dBm)
    private static final int WIFI_AUTO_INTERVAL_SEC = 2; // просили auto interval = 2
    private static final int CYCLE_DELAY_SEC = 60;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView statusView;
    private Button btnWifi;
    private Button btnFlash;
    private Button btnSettings;
    private Button btnCycle;
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
        buildUi();
        requestRuntimePermissions();

        log("Готово. v0.8 native upload + visible WebView settings.");
        log("Прошивка встроена в APK: " + ASSET_FIRMWARE);
        log("Wi-Fi: " + SSID_RX_HF + " / " + SSID_RX + ", пароль " + WIFI_PASSWORD);
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
        info.setText("Настройки: Binding UID Test = 24,99,211,80,18,169; rateidx=23; wifi-on-interval=2.");
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
                connectToAnyElrsWifiBlocking(60);
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
                log("Ручное применение настроек. Приоритет Wi-Fi: ExpressLRS RX.");
                connectAfterRebootWifiBlocking(60);
                if (!waitWebUiAvailable(90)) {
                    log("WebUI не отвечает, настройки не применены.");
                    return;
                }
                applySettings();
                logBig("НАСТРОЙКИ ОТПРАВЛЕНЫ.");
            } finally {
                setRunning(false);
            }
        }));
        row2.addView(btnSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnCycle = new Button(this);
        btnCycle.setText("Цикл");
        btnCycle.setOnClickListener(v -> startCycleMode());
        row2.addView(btnCycle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

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

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView webHint = new TextView(this);
        webHint.setText("Встроенный WebView для подтверждений WebUI:");
        webHint.setTextSize(12);
        root.addView(webHint);

        webView = new WebView(this);
        webView.setVisibility(View.VISIBLE);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));

        setContentView(root);
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

                    for (int left = CYCLE_DELAY_SEC; left > 0; left--) {
                        if (!cycleMode || !running) break;
                        if (left == CYCLE_DELAY_SEC || left <= 5 || left % 10 == 0) {
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
            if (!connectToAnyElrsWifiBlocking(60)) {
                log("Не удалось подключиться к ExpressLRS Wi-Fi.");
                return false;
            }

            if (!waitWebUiAvailable(90)) {
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
            if (!connectAfterRebootWifiBlocking(90)) {
                log("Не удалось повторно подключиться после ребута.");
                return false;
            }

            if (!waitWebUiAvailable(180)) {
                log("WebUI после ребута не отвечает.");
                return false;
            }

            boolean okSettings = applySettings();
            if (!okSettings) {
                log("Post настройки не применились. Делаю автоповтор один раз с приоритетом ExpressLRS RX...");
                connectAfterRebootWifiBlocking(60);
                waitWebUiAvailable(90);
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

    private boolean connectToAnyElrsWifiBlocking(int timeoutSec) {
        // До прошивки чаще сеть ExpressLRS RX HF, но RX тоже оставляем fallback.
        return connectPreferredElrsWifiBlocking(SSID_RX_HF, SSID_RX, timeoutSec, true);
    }

    private boolean connectAfterRebootWifiBlocking(int timeoutSec) {
        // После прошивки нужна новая сеть ExpressLRS RX. Не принимаем старый RX HF только потому,
        // что на нём ещё отвечает 10.0.0.1.
        return connectPreferredElrsWifiBlocking(SSID_RX, SSID_RX_HF, timeoutSec, false);
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
                    .setWpa2Passphrase(WIFI_PASSWORD)
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
                    log("Flash Anyway подтверждён.");
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
                    sleep(1000);
                    return true;
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
                    sleep(1000);
                    return true;
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
            return low.contains("confirm_webview:200") && (low.contains("\"status\":\"ok\"") || low.contains("ok"));
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
        // V0.8: сначала WebView-клики как на ПК. Если WebView завис/не сработал — HTTP fallback.
        try {
            log("=== Применяю Model + Options через видимый WebView, с подтверждениями ===");

            if (!webViewLoadBlocking("/", 60)) {
                log("WebView не открыл WebUI для настроек. Пробую HTTP fallback.");
                return applySettingsDirectHttpFallback();
            }

            String js =
                    "(async function(){\\n" +
                    "  function sleep(ms){return new Promise(r=>setTimeout(r,ms));}\\n" +
                    "  function ev(el,t){ if(el) el.dispatchEvent(new Event(t,{bubbles:true})); }\\n" +
                    "  async function waitSel(sel, ms){\\n" +
                    "    const end = Date.now()+ms;\\n" +
                    "    while(Date.now()<end){ const el=document.querySelector(sel); if(el) return el; await sleep(200); }\\n" +
                    "    return null;\\n" +
                    "  }\\n" +
                    "  function clickExactButton(txt){\\n" +
                    "    const btns = Array.from(document.querySelectorAll('button,input[type=button],input[type=submit],.swal2-confirm'));\\n" +
                    "    const b = btns.find(x => { const s=((x.innerText||x.textContent||x.value||'')+'').trim().toLowerCase(); return s===txt.toLowerCase(); });\\n" +
                    "    if(b){ b.click(); return true; } return false;\\n" +
                    "  }\\n" +
                    "  try {\\n" +
                    "    let modelTab = document.querySelector(\\\"a[data-mui-controls='pane-justified-3']\\\");\\n" +
                    "    if (modelTab) modelTab.click();\\n" +
                    "    let phrase = await waitSel('#phrase', 10000);\\n" +
                    "    if (!phrase) throw new Error('Model phrase field not found');\\n" +
                    "    phrase.value = 'Test'; ev(phrase,'input'); ev(phrase,'change'); phrase.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));\\n" +
                    "    await sleep(600);\\n" +
                    "    let cb = document.querySelector('#force-tlm');\\n" +
                    "    if (cb) { cb.checked = true; ev(cb,'input'); ev(cb,'change'); }\\n" +
                    "    let modelSave = document.querySelector('#config button[type=submit], form#config button[type=submit]');\\n" +
                    "    if (!modelSave) throw new Error('Model Save button not found');\\n" +
                    "    modelSave.disabled = false; modelSave.click();\\n" +
                    "    await sleep(2500);\\n" +
                    "    clickExactButton('OK');\\n" +
                    "    await sleep(1000);\\n" +
                    "    let optTab = document.querySelector(\\\"a[data-mui-controls='pane-justified-1']\\\");\\n" +
                    "    if (optTab) optTab.click();\\n" +
                    "    let rate = await waitSel('#rateidx', 10000);\\n" +
                    "    if (!rate) throw new Error('Options rateidx not found');\\n" +
                    "    rate.value = '23'; ev(rate,'input'); ev(rate,'change');\\n" +
                    "    let wifi = document.querySelector('#wifi-on-interval');\\n" +
                    "    if (wifi) { wifi.value = '2'; ev(wifi,'input'); ev(wifi,'change'); wifi.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true})); }\\n" +
                    "    await sleep(500);\\n" +
                    "    let optSave = document.querySelector('#submit-options');\\n" +
                    "    if (!optSave) throw new Error('Options Save button not found');\\n" +
                    "    optSave.disabled = false; optSave.click();\\n" +
                    "    await sleep(2500);\\n" +
                    "    let rebootClicked = clickExactButton('REBOOT') || clickExactButton('Reboot');\\n" +
                    "    if (!rebootClicked) {\\n" +
                    "      const btns = Array.from(document.querySelectorAll('button,input[type=button],input[type=submit]'));\\n" +
                    "      const rb = btns.find(x => ((x.innerText||x.textContent||x.value||'')+'').toLowerCase().includes('reboot'));\\n" +
                    "      if (rb) { rb.click(); rebootClicked = true; }\\n" +
                    "    }\\n" +
                    "    AndroidBridge.onWebResult('SETTINGS_OK:reboot=' + rebootClicked);\\n" +
                    "  } catch(e) { AndroidBridge.onWebResult('SETTINGS_ERROR:' + e.message); }\\n" +
                    "})();";

            String result = webViewEvalBlocking(js, 120);
            log("WebView settings result: " + safeShort(result, 700));

            if (result.startsWith("SETTINGS_OK")) {
                log("Post-flash настройки отправлены через WebView.");
                return true;
            }

            log("WebView настройки не подтвердились. Пробую HTTP fallback.");
            return applySettingsDirectHttpFallback();

        } catch (Exception e) {
            log("Ошибка настроек через WebView: " + e.getMessage());
            log("Пробую HTTP fallback.");
            return applySettingsDirectHttpFallback();
        }
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
            for (int b : BINDING_UID_TEST) uid.put(b);
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
            options.put("rateidx", PACKET_RATE_IDX);
            options.put("wifi-on-interval", WIFI_AUTO_INTERVAL_SEC);
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
