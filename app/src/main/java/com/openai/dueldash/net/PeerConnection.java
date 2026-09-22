package com.openai.dueldash.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PeerConnection implements Closeable {
    public interface Listener {
        void onMessage(String line);
        void onClosed(Throwable error);
    }

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Closeable socketLike;
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Listener listener;

    public PeerConnection(InputStream input, OutputStream output, Closeable socketLike) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.socketLike = socketLike;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        readExecutor.execute(() -> {
            Throwable failure = null;
            try {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    Listener current = listener;
                    if (current != null) current.onMessage(line);
                }
            } catch (Throwable t) {
                failure = t;
            } finally {
                if (!closed.get()) {
                    Listener current = listener;
                    if (current != null) current.onClosed(failure);
                }
                closeQuietly();
            }
        });
    }

    public void send(String line) {
        if (closed.get()) return;
        writeExecutor.execute(() -> {
            try {
                synchronized (writer) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                Listener current = listener;
                if (current != null) current.onClosed(e);
                closeQuietly();
            }
        });
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (!closed.compareAndSet(false, true)) return;
        try { socketLike.close(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        try { writer.close(); } catch (Exception ignored) {}
        readExecutor.shutdownNow();
        writeExecutor.shutdownNow();
    }
}
