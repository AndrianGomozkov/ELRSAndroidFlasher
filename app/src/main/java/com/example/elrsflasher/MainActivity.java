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

        log("Готово. v0.5 Flash Anyway + RX after reboot fix.");
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
                connectToAnyElrsWifiBlocking(60);
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
                log("Post настройки не применились. Делаю автоповтор один раз...");
                connectToAnyElrsWifiBlocking(60);
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
        try {
            byte[] firmware = readAsset(ASSET_FIRMWARE);
            String boundary = "----ELRSAndroidBoundary" + System.currentTimeMillis();
            String filename = ASSET_FIRMWARE;

            log("Загружаю прошивку: " + filename + ", размер " + firmware.length + " байт");

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

            log("Ответ /update: HTTP " + r.code + " " + safeShort(r.body, 350));

            String low = r.body == null ? "" : r.body.toLowerCase(Locale.ROOT);

            if (r.code == 200 && low.contains("\"status\"") && low.contains("ok")) {
                log("Устройство приняло прошивку.");
                return true;
            }

            if (r.code == 200 && low.contains("mismatch")) {
                log("Target mismatch. Автоматически подтверждаю Flash Anyway...");
                if (confirmFlashAnyway()) {
                    log("Flash Anyway подтверждён.");
                    return true;
                }

                log("Flash Anyway не подтвердился обычными запросами.");
                log("Проверяю, не стартовала ли прошивка после mismatch...");
                if (waitWebUiDown(15)) {
                    log("WebUI пропал — считаю, что прошивка всё же стартовала.");
                    return true;
                }

                return false;
            }

            // Некоторые WebUI могут оборвать/перезагрузиться без JSON.
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
        try {
            log("=== Применяю Model + Options через HTTP ===");

            // Model /config
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
            log("POST /config: HTTP " + cfgPost.code);
            if (cfgPost.code < 200 || cfgPost.code >= 300) return false;

            sleep(500);

            // Options /options.json
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
            log("POST /options.json: HTTP " + optPost.code);
            if (optPost.code < 200 || optPost.code >= 300) return false;

            // Применение Options обычно требует reboot.
            try {
                HttpResult rb = httpGet("/reboot");
                log("GET /reboot: HTTP " + rb.code);
                if (rb.code < 200 || rb.code >= 500) {
                    try {
                        HttpResult rb2 = httpPostRaw("/reboot", new byte[0], "text/plain", null);
                        log("POST /reboot: HTTP " + rb2.code);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log("Команда /reboot оборвалась, это может быть нормально.");
            }

            log("Post-flash настройки отправлены.");
            return true;

        } catch (Exception e) {
            log("Ошибка настроек: " + e.getMessage());
            return false;
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
