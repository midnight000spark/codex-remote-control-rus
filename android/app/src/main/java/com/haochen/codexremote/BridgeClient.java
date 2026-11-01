package com.haochen.codexremote;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class BridgeClient {
    public interface Listener {
        void onOpen();

        void onText(String text);

        void onDisconnected(int code, String reason, Throwable error);
    }

    private final URI uri;
    private final Listener listener;
    private final OkHttpClient client;

    private volatile boolean closed;
    private volatile boolean connected;
    private volatile boolean finished;
    private volatile WebSocket webSocket;

    public BridgeClient(URI uri, Listener listener, int connectTimeoutSeconds, int pingIntervalSeconds) {
        this.uri = uri;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void connect() {
        if (webSocket != null) {
            throw new IllegalStateException("bridge client already started");
        }
        Request request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", "CodexRemoteAndroid/0.1")
                .build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                connected = true;
                listener.onOpen();
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                listener.onText(text);
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
                finish(code, reason == null || reason.isEmpty() ? "Соединение разорвано" : reason, null);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                finish(code, reason == null || reason.isEmpty() ? "Соединение разорвано" : reason, null);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                finish(-1, "Не удалось подключиться", error);
            }
        });
    }

    public boolean isOpen() {
        return connected && webSocket != null && !closed;
    }

    public void sendText(String text) throws IOException {
        WebSocket socket = webSocket;
        if (socket == null || !connected || closed) {
            throw new IOException("bridge not connected");
        }
        if (!socket.send(text)) {
            throw new IOException("websocket send rejected");
        }
    }

    public void close() {
        closed = true;
        connected = false;
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.close(1000, "client closed");
        }
        client.dispatcher().executorService().shutdown();
    }

    private synchronized void finish(int code, String reason, Throwable error) {
        if (finished) {
            return;
        }
        finished = true;
        boolean notify = !closed;
        connected = false;
        webSocket = null;
        client.dispatcher().executorService().shutdown();
        if (notify) {
            listener.onDisconnected(code, reason, error);
        }
    }
}
