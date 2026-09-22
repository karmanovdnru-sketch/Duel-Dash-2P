package com.openai.dueldash.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class WifiConnector {
    private WifiConnector() {}

    public interface Callback {
        void onConnected(PeerConnection connection);
        void onError(Throwable error);
    }

    public static ConnectionAttempt host(int port, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ServerSocket> serverRef = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(port);
                serverRef.set(server);
                if (cancelled.get()) {
                    server.close();
                    return;
                }
                Socket socket = server.accept();
                server.close();
                if (cancelled.get()) {
                    socket.close();
                    return;
                }
                socket.setTcpNoDelay(true);
                callback.onConnected(new PeerConnection(
                        socket.getInputStream(), socket.getOutputStream(), socket));
            } catch (Throwable t) {
                if (!cancelled.get()) callback.onError(t);
            }
        }, "wifi-host");
        thread.start();

        return () -> {
            cancelled.set(true);
            ServerSocket server = serverRef.get();
            if (server != null) {
                try { server.close(); } catch (Exception ignored) {}
            }
            thread.interrupt();
        };
    }

    public static ConnectionAttempt join(String host, int port, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Socket> socketRef = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                Socket socket = new Socket();
                socketRef.set(socket);
                socket.connect(new java.net.InetSocketAddress(host, port), 8000);
                if (cancelled.get()) {
                    socket.close();
                    return;
                }
                socket.setTcpNoDelay(true);
                callback.onConnected(new PeerConnection(
                        socket.getInputStream(), socket.getOutputStream(), socket));
            } catch (Throwable t) {
                if (!cancelled.get()) callback.onError(t);
            }
        }, "wifi-client");
        thread.start();

        return () -> {
            cancelled.set(true);
            Socket socket = socketRef.get();
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            thread.interrupt();
        };
    }

    public static String findLocalIpv4() {
        String privateFallback = null;
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nif : Collections.list(interfaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                boolean looksLikeWifi = name.contains("wlan") || name.contains("wifi") || name.startsWith("ap");
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip == null) continue;
                    boolean privateIp = ip.startsWith("192.168.") || ip.startsWith("10.") || isPrivate172(ip);
                    if (looksLikeWifi && privateIp) return ip;
                    if (privateIp && privateFallback == null) privateFallback = ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {}
        if (privateFallback != null) return privateFallback;
        return fallback == null ? "не найден" : fallback;
    }

    private static boolean isPrivate172(String ip) {
        if (!ip.startsWith("172.")) return false;
        String[] parts = ip.split("\\.");
        if (parts.length < 2) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
