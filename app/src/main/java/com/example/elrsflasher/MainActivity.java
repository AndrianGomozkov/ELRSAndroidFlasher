package com.example.elrsflasher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        buildUi();
        requestRuntimePermissions();

        log("Готово.");
        log("Прошивка встроена в APK: " + ASSET_FIRMWARE);
        log("Wi-Fi: " + SSID_RX_HF + " / " + SSID_RX + ", пароль " + WIFI_PASSWORD);
        log("Android может показать системное окно подключения — его надо подтвердить.");
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
        btnWifi.setOnClickListener(v -> runAsync(() -> connectToAnyElrsWifiBlocking(60)));
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
        if (Build.VERSION.SDK_INT >= 23) {
            java.util.ArrayList<String> perms = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 100);
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

            log("После ребута снова подключаюсь к Wi-Fi.");
            if (!connectToAnyElrsWifiBlocking(90)) {
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

    private boolean connectToAnyElrsWifiBlocking(int timeoutSec) {
        log("Запрашиваю подключение к Wi-Fi ExpressLRS...");
        // До прошивки часто RX HF, после прошивки часто RX.
        if (connectWifiBlocking(SSID_RX_HF, Math.max(20, timeoutSec / 2))) return true;
        return connectWifiBlocking(SSID_RX, Math.max(20, timeoutSec / 2));
    }

    private boolean connectWifiBlocking(String ssid, int timeoutSec) {
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
            log("Ошибка подключения к " + ssid + ": " + e.getMessage());
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

            HttpResult r = httpPostRaw("/update", body.toByteArray(), "multipart/form-data; boundary=" + boundary,
                    new String[][] {{"X-FileSize", String.valueOf(firmware.length)}});

            log("Ответ /update: HTTP " + r.code + " " + safeShort(r.body, 200));

            String low = r.body == null ? "" : r.body.toLowerCase(Locale.ROOT);
            if (r.code == 200 && low.contains("\"status\"") && low.contains("ok")) {
                log("Устройство приняло прошивку.");
                return true;
            }

            if (r.code == 200 && low.contains("mismatch")) {
                log("Target mismatch. Подтверждаю Flash Anyway...");
                HttpResult c = httpGet("/forceupdate?action=confirm");
                log("Ответ /forceupdate: HTTP " + c.code + " " + safeShort(c.body, 200));
                return c.code == 200 && c.body != null && c.body.toLowerCase(Locale.ROOT).contains("ok");
            }

            // Некоторые WebUI могут оборвать ответ после старта прошивки.
            return r.code == 200;

        } catch (Exception e) {
            log("Ошибка upload: " + e.getMessage());
            return false;
        }
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
