/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One WebSocket connection to a Steam CM (wss://host:port/cmsocket/). */
final class CMConnection {
    interface Listener {
        void onPacket(CMConnection connection, byte[] data);

        void onClosed(CMConnection connection, Throwable cause);
    }

    private final OkHttpClient http;
    private final Listener listener;
    private final CompletableFuture<Void> opened = new CompletableFuture<>();
    private volatile WebSocket socket;
    private volatile boolean closed;

    CMConnection(OkHttpClient http, Listener listener) {
        this.http = http;
        this.listener = listener;
    }

    void connect(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "SkyrimAndroidborn/0.2")
                .build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                opened.complete(null);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (!closed) {
                    listener.onPacket(CMConnection.this, bytes.toByteArray());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // Steam only speaks binary on the CM socket.
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                opened.completeExceptionally(t);
                if (!closed) {
                    closed = true;
                    listener.onClosed(CMConnection.this, t);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!closed) {
                    closed = true;
                    listener.onClosed(CMConnection.this, null);
                }
            }
        });
        opened.get(20, TimeUnit.SECONDS);
    }

    void send(byte[] data) throws SteamException {
        WebSocket ws = socket;
        if (ws == null || closed) {
            throw new SteamException("not connected to Steam");
        }
        if (!ws.send(ByteString.of(data))) {
            throw new SteamException("Steam send queue is full");
        }
    }

    void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) {
            ws.close(1000, "bye");
        }
    }
}
