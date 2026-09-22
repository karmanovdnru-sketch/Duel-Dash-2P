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
    private ConnectionAttempt activeAttempt;
    private PeerConnection currentConnection;
    private DuelGameView gameView;
    private boolean inGame = false;
    private boolean atMainMenu = true;
    private Runnable pendingBluetoothAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        root.addView(title("DUEL DASH 2P", 32));
        root.addView(body("Мини-игры на двух Android-телефонах. Хост выбирает игру, второй телефон подключается по Wi‑Fi или Bluetooth."));
        root.addView(spacer(18));

        Button fight = actionButton("🥊  ДРАКА");
        fight.setOnClickListener(v -> {
            selectedMode = GameMode.FIGHT;
            showTransportMenu();
        });
        root.addView(fight);

        Button race = actionButton("🏁  ГОНКА");
        race.setOnClickListener(v -> {
            selectedMode = GameMode.RACE;
            showTransportMenu();
        });
        root.addView(race);

        root.addView(spacer(16));
        root.addView(body("Подсказка: для Bluetooth сначала спарьте телефоны в системных настройках Android. Для Wi‑Fi оба устройства должны быть в одной локальной сети или один телефон может раздать точку доступа."));
        setContentView(wrap(root));
    }

    private void showTransportMenu() {
        atMainMenu = false;
        cancelAttempt();
        LinearLayout root = baseLayout();
        root.addView(title(modeLabel() + " · подключение", 26));
        root.addView(body("На телефоне-хосте выберите способ связи и создайте комнату. На втором телефоне выберите тот же способ связи и подключитесь."));
        root.addView(spacer(14));

        Button wifi = actionButton("📶  WI‑FI / ТОЧКА ДОСТУПА");
        wifi.setOnClickListener(v -> showWifiMenu());
        root.addView(wifi);

        Button bluetooth = actionButton("🔵  BLUETOOTH");
        bluetooth.setOnClickListener(v -> ensureBluetoothPermission(this::showBluetoothMenu));
        root.addView(bluetooth);

        root.addView(spacer(10));
        Button back = secondaryButton("← Назад к играм");
        back.setOnClickListener(v -> showMainMenu());
        root.addView(back);
        setContentView(wrap(root));
    }

    private void showWifiMenu() {
        atMainMenu = false;
        cancelAttempt();
        LinearLayout root = baseLayout();
        root.addView(title("Wi‑Fi · " + modeLabel(), 26));

        String ip = WifiConnector.findLocalIpv4();
        TextView info = body("ХОСТ: создайте комнату и сообщите второму игроку IP.\n" +
                "Ваш локальный IP: " + ip + "\nПорт: " + WIFI_PORT + "\n\n" +
                "КЛИЕНТ: введите IP хоста. Режим игры клиент получит автоматически от хоста.");
        root.addView(info);
        root.addView(spacer(12));

        TextView status = statusView("Готово к подключению");
        root.addView(status);

        Button host = actionButton("Создать комнату (ХОСТ)");
        host.setOnClickListener(v -> {
            cancelAttempt();
            status.setText("Ожидаем второго игрока…\nIP: " + WifiConnector.findLocalIpv4() + ":" + WIFI_PORT);
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
        ipInput.setBackgroundColor(Color.rgb(30, 43, 63));
        ipInput.setPadding(dp(14), dp(11), dp(14), dp(11));
        root.addView(ipInput, fullWidth(dp(58)));

        Button join = actionButton("Подключиться к хосту");
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
        root.addView(title("Bluetooth · " + modeLabel(), 26));
        root.addView(body("Перед игрой спарьте два телефона через настройки Bluetooth Android. Один телефон запускает комнату, второй выбирает хост из списка спаренных устройств."));
        root.addView(spacer(10));

        TextView status = statusView("Готово к подключению");
        root.addView(status);

        if (adapter == null) {
            status.setText("На этом устройстве Bluetooth недоступен.");
        } else if (!adapter.isEnabled()) {
            status.setText("Bluetooth выключен.");
            Button settings = actionButton("Открыть настройки Bluetooth");
            settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            root.addView(settings);
        } else {
            Button host = actionButton("Создать Bluetooth-комнату (ХОСТ)");
            host.setOnClickListener(v -> {
                cancelAttempt();
                status.setText("Ожидаем подключение второго телефона по Bluetooth…");
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

            root.addView(label("Спаренные устройства:"));
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            List<BluetoothDevice> devices = new ArrayList<>(bonded);
            devices.sort(Comparator.comparing(d -> {
                String name = d.getName();
                return name == null ? "" : name;
            }));

            if (devices.isEmpty()) {
                root.addView(body("Список пуст. Сначала откройте настройки Bluetooth и выполните сопряжение телефонов."));
            } else {
                for (BluetoothDevice device : devices) {
                    String name = device.getName();
                    if (name == null || name.trim().isEmpty()) name = "Без имени";
                    Button deviceButton = secondaryButton("Подключиться: " + name + "\n" + device.getAddress());
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

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(v -> showTransportMenu());
        root.addView(back);
        setContentView(wrap(root));
    }

    private void handleConnected(PeerConnection connection, boolean host, TextView status) {
        activeAttempt = null;
        currentConnection = connection;
        status.setText(host ? "Игрок подключился. Запускаем игру…" : "Связь установлена. Получаем режим от хоста…");

        if (host) {
            connection.send("MODE|" + selectedMode.name());
            launchGame(connection, true, selectedMode);
            return;
        }

        connection.setListener(new PeerConnection.Listener() {
            private boolean launched = false;

            @Override
            public void onMessage(String line) {
                if (launched || !line.startsWith("MODE|")) return;
                try {
                    GameMode mode = GameMode.valueOf(line.substring("MODE|".length()));
                    launched = true;
                    runOnUiThread(() -> launchGame(connection, false, mode));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Неизвестный режим игры от хоста."));
                }
            }

            @Override
            public void onClosed(Throwable error) {
                if (!launched) {
                    runOnUiThread(() -> status.setText("Соединение закрыто: " + safeMessage(error)));
                }
            }
        });
        connection.start();
    }

    private void launchGame(PeerConnection connection, boolean host, GameMode mode) {
        atMainMenu = false;
        selectedMode = mode;
        inGame = true;
        gameView = new DuelGameView(this, mode, host, connection);
        setContentView(gameView);
        gameView.requestFocus();
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
            Toast.makeText(this, "Для игры по Bluetooth нужно разрешение «Устройства поблизости».", Toast.LENGTH_LONG).show();
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

    private String modeLabel() {
        return selectedMode == GameMode.FIGHT ? "ДРАКА" : "ГОНКА";
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
        scroll.setBackgroundColor(Color.rgb(12, 20, 35));
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout baseLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(12, 20, 35));
        return root;
    }

    private TextView title(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(4), 0, dp(12));
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(190, 205, 225));
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(0f, 1.15f);
        view.setPadding(dp(8), dp(4), dp(8), dp(8));
        return view;
    }

    private TextView label(String text) {
        TextView view = body(text);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.START);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(14), 0, dp(8));
        return view;
    }

    private TextView statusView(String text) {
        TextView view = body(text);
        view.setTextColor(Color.rgb(119, 209, 255));
        view.setBackgroundColor(Color.rgb(24, 38, 57));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = fullWidth(-2);
        lp.setMargins(0, 0, 0, dp(10));
        view.setLayoutParams(lp);
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.rgb(53, 89, 224));
        LinearLayout.LayoutParams lp = fullWidth(dp(58));
        lp.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(lp);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.rgb(44, 60, 82));
        LinearLayout.LayoutParams lp = fullWidth(-2);
        lp.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(lp);
        return button;
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
