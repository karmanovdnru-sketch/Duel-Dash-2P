package com.openai.dueldash;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.openai.dueldash.game.DuelGameView;
import com.openai.dueldash.game.FighterStyle;
import com.openai.dueldash.game.GameMode;
import com.openai.dueldash.net.BluetoothConnector;
import com.openai.dueldash.net.ConnectionAttempt;
import com.openai.dueldash.net.PeerConnection;
import com.openai.dueldash.net.WifiConnector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int WIFI_PORT = 45876;
    private static final int REQ_BLUETOOTH_CONNECT = 700;

    private GameMode selectedMode = GameMode.FIGHT;
    private int selectedFighter = 0;
    private ConnectionAttempt activeAttempt;
    private PeerConnection currentConnection;
    private DuelGameView gameView;
    private boolean inGame = false;
    private boolean atMainMenu = true;
    private Runnable pendingBluetoothAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 14, 27));
        showMainMenu();
    }

    @Override
    protected void onDestroy() {
        cancelAttempt();
        closeConnection();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (inGame) {
            closeConnection();
            inGame = false;
            showMainMenu();
            return;
        }
        if (atMainMenu) {
            super.onBackPressed();
            return;
        }
        cancelAttempt();
        showMainMenu();
    }

    private void showMainMenu() {
        atMainMenu = true;
        cancelAttempt();
        if (!inGame) closeConnection();

        LinearLayout root = baseLayout();
        TextView logo = title("DUEL DASH", 36);
        logo.setTextColor(Color.rgb(255, 230, 91));
        root.addView(logo);
        root.addView(title("2P ARCADE", 18));
        root.addView(body("Яркие мини-игры для одного телефона против бота или для двух Android-телефонов по Wi‑Fi / Bluetooth."));
        root.addView(spacer(14));

        Button fighter = cardButton("🎭  Боец: " + FighterStyle.get(selectedFighter).name + " " + FighterStyle.get(selectedFighter).emoji,
                "Нажмите, чтобы выбрать персонажа", FighterStyle.get(selectedFighter).body);
        fighter.setOnClickListener(v -> showFighterMenu());
        root.addView(fighter);

        root.addView(section("ВЫБЕРИТЕ МИНИ-ИГРУ"));
        for (GameMode mode : GameMode.values()) {
            Button game = cardButton(mode.icon + "  " + mode.title, mode.description, gameColor(mode));
            game.setOnClickListener(v -> {
                selectedMode = mode;
                showGameOptions();
            });
            root.addView(game);
        }

        root.addView(spacer(10));
        root.addView(body("Совет: режим «Против бота» запускается сразу и не требует второго телефона, Wi‑Fi или Bluetooth."));
        setContentView(wrap(root));
    }

    private void showFighterMenu() {
        atMainMenu = false;
        LinearLayout root = baseLayout();
        root.addView(title("ВЫБОР БОЙЦА", 30));
        root.addView(body("Персонаж используется во всех мини-играх. У каждого свой цвет и мультяшный образ."));
        root.addView(spacer(10));

        for (int i = 0; i < FighterStyle.count(); i++) {
            FighterStyle style = FighterStyle.get(i);
            String mark = i == selectedFighter ? "  ✓ ВЫБРАН" : "";
            Button b = cardButton(style.emoji + "  " + style.name + mark,
                    "Основной цвет персонажа", style.body);
            final int index = i;
            b.setOnClickListener(v -> {
                selectedFighter = index;
                showMainMenu();
            });
            root.addView(b);
        }

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(v -> showMainMenu());
        root.addView(back);
        setContentView(wrap(root));
    }

    private void showGameOptions() {
        atMainMenu = false;
        cancelAttempt();
        LinearLayout root = baseLayout();
        root.addView(title(selectedMode.icon + "  " + selectedMode.title, 30));
        root.addView(body(selectedMode.description));
        root.addView(spacer(8));
        root.addView(pill("Ваш боец: " + FighterStyle.get(selectedFighter).name + " " + FighterStyle.get(selectedFighter).emoji,
                FighterStyle.get(selectedFighter).body));
        root.addView(spacer(12));

        Button solo = actionButton("🤖  ИГРАТЬ СЕЙЧАС ПРОТИВ БОТА", Color.rgb(50, 186, 120));
        solo.setOnClickListener(v -> launchSoloGame());
        root.addView(solo);

        Button twoPhones = actionButton("📱  ИГРА НА ДВУХ ТЕЛЕФОНАХ", Color.rgb(64, 104, 235));
        twoPhones.setOnClickListener(v -> showTransportMenu());
        root.addView(twoPhones);

        root.addView(spacer(8));
        Button fighter = secondaryButton("🎭 Сменить бойца");
        fighter.setOnClickListener(v -> showFighterMenu());
        root.addView(fighter);

        Button back = secondaryButton("← Назад к играм");
        back.setOnClickListener(v -> showMainMenu());
        root.addView(back);
        setContentView(wrap(root));
    }

    private void launchSoloGame() {
        cancelAttempt();
        closeConnection();
        int botFighter = (selectedFighter + 1 + (int) (System.nanoTime() % (FighterStyle.count() - 1))) % FighterStyle.count();
        launchGame(null, true, selectedMode, true, botFighter);
    }

    private void showTransportMenu() {
        atMainMenu = false;
        cancelAttempt();
        LinearLayout root = baseLayout();
        root.addView(title(selectedMode.icon + "  " + selectedMode.title, 28));
        root.addView(body("Один телефон создаёт комнату, второй подключается. Хост управляет симуляцией и синхронизирует игру."));
        root.addView(spacer(12));

        Button wifi = actionButton("📶  WI‑FI / ТОЧКА ДОСТУПА", Color.rgb(45, 149, 226));
        wifi.setOnClickListener(v -> showWifiMenu());
        root.addView(wifi);

        Button bluetooth = actionButton("🔵  BLUETOOTH", Color.rgb(74, 91, 220));
        bluetooth.setOnClickListener(v -> ensureBluetoothPermission(this::showBluetoothMenu));
        root.addView(bluetooth);

        Button solo = secondaryButton("🤖 Запустить без второго телефона");
        solo.setOnClickListener(v -> launchSoloGame());
        root.addView(solo);

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(v -> showGameOptions());
        root.addView(back);
        setContentView(wrap(root));
    }

    private void showWifiMenu() {
        atMainMenu = false;
        cancelAttempt();
        LinearLayout root = baseLayout();
        root.addView(title("📶  Wi‑Fi · " + selectedMode.title, 27));

        String ip = WifiConnector.findLocalIpv4();
        root.addView(body("ХОСТ: создайте комнату и сообщите IP второму игроку.\n" +
                "IP: " + ip + "   Порт: " + WIFI_PORT + "\n\n" +
                "КЛИЕНТ: введите IP хоста. Оба телефона должны быть в одной Wi‑Fi сети или точке доступа."));
        root.addView(spacer(10));

        TextView status = statusView("Готово к подключению");
        root.addView(status);

        Button host = actionButton("Создать комнату (ХОСТ)", Color.rgb(50, 186, 120));
        host.setOnClickListener(v -> {
            cancelAttempt();
            status.setText("Ожидаем второго игрока…\n" + WifiConnector.findLocalIpv4() + ":" + WIFI_PORT);
            activeAttempt = WifiConnector.host(WIFI_PORT, new WifiConnector.Callback() {
                @Override
                public void onConnected(PeerConnection connection) {
                    runOnUiThread(() -> handleConnected(connection, true, status));
                }

                @Override
                public void onError(Throwable error) {
                    runOnUiThread(() -> status.setText("Ошибка хоста: " + safeMessage(error)));
                }
            });
        });
        root.addView(host);

        EditText ipInput = new EditText(this);
        ipInput.setHint("IP хоста, например 192.168.1.23");
        ipInput.setSingleLine(true);
        ipInput.setTextColor(Color.WHITE);
        ipInput.setHintTextColor(Color.rgb(145, 160, 184));
        ipInput.setBackground(rounded(Color.rgb(29, 41, 62), 16, Color.rgb(64, 82, 112)));
        ipInput.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams ipLp = fullWidth(dp(58));
        ipLp.setMargins(0, dp(6), 0, dp(6));
        root.addView(ipInput, ipLp);

        Button join = actionButton("Подключиться к хосту", Color.rgb(64, 104, 235));
        join.setOnClickListener(v -> {
            String target = ipInput.getText().toString().trim();
            if (target.isEmpty()) {
                Toast.makeText(this, "Введите IP хоста", Toast.LENGTH_SHORT).show();
                return;
            }
            cancelAttempt();
            status.setText("Подключаемся к " + target + ":" + WIFI_PORT + "…");
            activeAttempt = WifiConnector.join(target, WIFI_PORT, new WifiConnector.Callback() {
                @Override
                public void onConnected(PeerConnection connection) {
                    runOnUiThread(() -> handleConnected(connection, false, status));
                }

                @Override
                public void onError(Throwable error) {
                    runOnUiThread(() -> status.setText("Ошибка подключения: " + safeMessage(error)));
                }
            });
        });
        root.addView(join);

        Button solo = secondaryButton("🤖 Не ждать второго игрока, играть с ботом");
        solo.setOnClickListener(v -> launchSoloGame());
        root.addView(solo);

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(v -> showTransportMenu());
        root.addView(back);
        setContentView(wrap(root));
    }

    @SuppressLint("MissingPermission")
    private void showBluetoothMenu() {
        atMainMenu = false;
        cancelAttempt();
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();

        LinearLayout root = baseLayout();
        root.addView(title("🔵  Bluetooth · " + selectedMode.title, 27));
        root.addView(body("Сначала спарьте телефоны в настройках Android. Затем на одном устройстве создайте комнату, а на втором выберите хост."));
        root.addView(spacer(8));

        TextView status = statusView("Готово к подключению");
        root.addView(status);

        if (adapter == null) {
            status.setText("На этом устройстве Bluetooth недоступен.");
        } else if (!adapter.isEnabled()) {
            status.setText("Bluetooth выключен.");
            Button settings = actionButton("Открыть настройки Bluetooth", Color.rgb(74, 91, 220));
            settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            root.addView(settings);
        } else {
            Button host = actionButton("Создать Bluetooth-комнату (ХОСТ)", Color.rgb(50, 186, 120));
            host.setOnClickListener(v -> {
                cancelAttempt();
                status.setText("Ожидаем второй телефон по Bluetooth…");
                activeAttempt = BluetoothConnector.host(adapter, new BluetoothConnector.Callback() {
                    @Override
                    public void onConnected(PeerConnection connection) {
                        runOnUiThread(() -> handleConnected(connection, true, status));
                    }

                    @Override
                    public void onError(Throwable error) {
                        runOnUiThread(() -> status.setText("Ошибка Bluetooth-хоста: " + safeMessage(error)));
                    }
                });
            });
            root.addView(host);

            root.addView(section("СПАРЕННЫЕ УСТРОЙСТВА"));
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            List<BluetoothDevice> devices = new ArrayList<>(bonded);
            devices.sort(Comparator.comparing(d -> {
                String name = d.getName();
                return name == null ? "" : name;
            }));

            if (devices.isEmpty()) {
                root.addView(body("Список пуст. Откройте настройки Bluetooth и выполните сопряжение телефонов."));
            } else {
                for (BluetoothDevice device : devices) {
                    Button deviceButton = secondaryButton("📱  " + safeDeviceName(device) + "\n" + device.getAddress());
                    BluetoothDevice chosen = device;
                    deviceButton.setOnClickListener(v -> {
                        cancelAttempt();
                        status.setText("Подключаемся к " + safeDeviceName(chosen) + "…");
                        activeAttempt = BluetoothConnector.join(adapter, chosen, new BluetoothConnector.Callback() {
                            @Override
                            public void onConnected(PeerConnection connection) {
                                runOnUiThread(() -> handleConnected(connection, false, status));
                            }

                            @Override
                            public void onError(Throwable error) {
                                runOnUiThread(() -> status.setText("Ошибка Bluetooth: " + safeMessage(error)));
                            }
                        });
                    });
                    root.addView(deviceButton);
                }
            }

            Button settings = secondaryButton("Настройки Bluetooth / сопряжение");
            settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            root.addView(settings);
        }

        Button solo = secondaryButton("🤖 Не ждать второго игрока, играть с ботом");
        solo.setOnClickListener(v -> launchSoloGame());
        root.addView(solo);

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(v -> showTransportMenu());
        root.addView(back);
        setContentView(wrap(root));
    }

    private void handleConnected(PeerConnection connection, boolean host, TextView status) {
        activeAttempt = null;
        currentConnection = connection;
        status.setText(host ? "Игрок подключился. Запускаем…" : "Связь установлена. Получаем игру от хоста…");

        if (host) {
            connection.send("MODE|" + selectedMode.name() + "|" + selectedFighter);
            launchGame(connection, true, selectedMode, false, (selectedFighter + 1) % FighterStyle.count());
            return;
        }

        connection.setListener(new PeerConnection.Listener() {
            private boolean launched = false;

            @Override
            public void onMessage(String line) {
                if (launched || !line.startsWith("MODE|")) return;
                try {
                    String[] parts = line.split("\\|");
                    GameMode mode = GameMode.valueOf(parts[1]);
                    int hostFighter = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                    launched = true;
                    runOnUiThread(() -> launchGame(connection, false, mode, false, hostFighter));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Не удалось прочитать режим игры от хоста."));
                }
            }

            @Override
            public void onClosed(Throwable error) {
                if (!launched) runOnUiThread(() -> status.setText("Соединение закрыто: " + safeMessage(error)));
            }
        });
        connection.start();
    }

    private void launchGame(PeerConnection connection, boolean host, GameMode mode, boolean solo, int remoteFighter) {
        atMainMenu = false;
        selectedMode = mode;
        inGame = true;
        currentConnection = connection;
        gameView = new DuelGameView(this, mode, host, connection, solo, selectedFighter, remoteFighter);
        setContentView(gameView);
        gameView.requestFocus();
        if (connection != null && !host) connection.send("CHAR|" + selectedFighter);
    }

    private void ensureBluetoothPermission(Runnable action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingBluetoothAction = action;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH_CONNECT);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLUETOOTH_CONNECT) return;
        Runnable action = pendingBluetoothAction;
        pendingBluetoothAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) action.run();
        } else {
            Toast.makeText(this, "Для Bluetooth нужно разрешение «Устройства поблизости».", Toast.LENGTH_LONG).show();
        }
    }

    private void cancelAttempt() {
        if (activeAttempt != null) {
            activeAttempt.cancel();
            activeAttempt = null;
        }
    }

    private void closeConnection() {
        if (gameView != null) {
            gameView.shutdown();
            gameView = null;
        } else if (currentConnection != null) {
            currentConnection.close();
        }
        currentConnection = null;
    }

    private int gameColor(GameMode mode) {
        switch (mode) {
            case FIGHT: return Color.rgb(221, 73, 79);
            case RACE: return Color.rgb(48, 145, 219);
            case TAP_DUEL: return Color.rgb(238, 161, 32);
            case PONG: return Color.rgb(63, 180, 130);
            case PENALTY: return Color.rgb(63, 158, 80);
            case REACTION: return Color.rgb(147, 92, 214);
            case TUG: return Color.rgb(201, 110, 52);
            default: return Color.rgb(64, 104, 235);
        }
    }

    private String safeMessage(Throwable t) {
        if (t == null) return "соединение закрыто";
        String msg = t.getMessage();
        return msg == null || msg.trim().isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.trim().isEmpty() ? device.getAddress() : name;
    }

    private ScrollView wrap(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 16, 30));
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout baseLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(10, 16, 30));
        return root;
    }

    private TextView title(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(2), 0, dp(7));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(191, 204, 225));
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(0f, 1.12f);
        view.setPadding(dp(8), dp(3), dp(8), dp(6));
        return view;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(137, 156, 188));
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.START);
        view.setPadding(dp(4), dp(18), 0, dp(7));
        view.setLayoutParams(fullWidth(-2));
        return view;
    }

    private TextView pill(String text, int color) {
        TextView view = body(text);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setBackground(rounded(darken(color, 0.42f), 18, color));
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = fullWidth(-2);
        lp.setMargins(0, dp(5), 0, dp(5));
        view.setLayoutParams(lp);
        return view;
    }

    private TextView statusView(String text) {
        TextView view = body(text);
        view.setTextColor(Color.rgb(124, 215, 255));
        view.setBackground(rounded(Color.rgb(23, 36, 55), 16, Color.rgb(52, 73, 103)));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = fullWidth(-2);
        lp.setMargins(0, 0, 0, dp(10));
        view.setLayoutParams(lp);
        return view;
    }

    private Button cardButton(String headline, String detail, int color) {
        Button button = new Button(this);
        button.setText(headline + "\n" + detail);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
        button.setBackground(rounded(darken(color, 0.53f), 20, color));
        LinearLayout.LayoutParams lp = fullWidth(dp(76));
        lp.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(lp);
        return button;
    }

    private Button actionButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(color, 18, lighten(color, 1.25f)));
        LinearLayout.LayoutParams lp = fullWidth(dp(60));
        lp.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(lp);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(38, 52, 75), 16, Color.rgb(68, 84, 110)));
        LinearLayout.LayoutParams lp = fullWidth(-2);
        lp.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(lp);
        return button;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int darken(int color, float factor) {
        return Color.rgb(
                Math.max(0, Math.min(255, Math.round(Color.red(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.green(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.blue(color) * factor))));
    }

    private int lighten(int color, float factor) {
        return Color.rgb(
                Math.max(0, Math.min(255, Math.round(Color.red(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.green(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.blue(color) * factor))));
    }

    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
