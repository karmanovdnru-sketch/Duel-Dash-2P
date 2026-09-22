package com.openai.dueldash.net;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BluetoothConnector {
    public static final UUID SERVICE_UUID = UUID.fromString("18b5d113-7c6a-4c87-b5f0-2e56ff9d62df");
    private static final String SERVICE_NAME = "DuelDash2P";

    private BluetoothConnector() {}

    public interface Callback {
        void onConnected(PeerConnection connection);
        void onError(Throwable error);
    }

    @SuppressLint("MissingPermission")
    public static ConnectionAttempt host(BluetoothAdapter adapter, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<BluetoothServerSocket> serverRef = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord(
                        SERVICE_NAME, SERVICE_UUID);
                serverRef.set(server);
                if (cancelled.get()) {
                    server.close();
                    return;
                }
                BluetoothSocket socket = server.accept();
                server.close();
                if (cancelled.get()) {
                    socket.close();
                    return;
                }
                callback.onConnected(new PeerConnection(
                        socket.getInputStream(), socket.getOutputStream(), socket));
            } catch (Throwable t) {
                if (!cancelled.get()) callback.onError(t);
            }
        }, "bt-host");
        thread.start();

        return () -> {
            cancelled.set(true);
            BluetoothServerSocket server = serverRef.get();
            if (server != null) {
                try { server.close(); } catch (Exception ignored) {}
            }
            thread.interrupt();
        };
    }

    @SuppressLint("MissingPermission")
    public static ConnectionAttempt join(BluetoothAdapter adapter, BluetoothDevice device, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<BluetoothSocket> socketRef = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                socketRef.set(socket);
                socket.connect();
                if (cancelled.get()) {
                    socket.close();
                    return;
                }
                callback.onConnected(new PeerConnection(
                        socket.getInputStream(), socket.getOutputStream(), socket));
            } catch (Throwable t) {
                if (!cancelled.get()) callback.onError(t);
            }
        }, "bt-client");
        thread.start();

        return () -> {
            cancelled.set(true);
            BluetoothSocket socket = socketRef.get();
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            thread.interrupt();
        };
    }
}
