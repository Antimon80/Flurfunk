package com.example.flurfunk.network;

import android.util.Log;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A simulated implementation of the {@link LoRaManager} interface that uses HTTP
 * communication to mimic LoRa behavior in an Android emulator environment.
 * <p>
 * This class is designed for local testing and development when physical LoRa hardware
 * is not available. It connects to a proxy server running on {@code http://10.0.2.2:8080},
 * which emulates LoRa message delivery using standard HTTP endpoints.
 *
 * <p>Key behaviors:
 * <ul>
 *     <li>Uses POST requests to {@code /send} to simulate message sending</li>
 *     <li>Continuously polls {@code /poll} for incoming messages</li>
 *     <li>Runs all polling in a background thread</li>
 * </ul>
 */
public class OkHttpLoRaManager implements LoRaManager {

    private static final String TAG = "OkHttpLoRaManager";
    private static final String BASE_URL = "http://10.0.2.2:8080";
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");


    private final OkHttpClient client = new OkHttpClient();
    private Consumer<String> onMessageReceived;

    private final StringBuilder httpBuffer = new StringBuilder();
    private volatile boolean running = false;
    /**
     * Sets the handler that will be called whenever a new message is received from the proxy.
     *
     * @param listener a {@link Consumer} that takes a message string
     */
    @Override
    public void setOnMessageReceived(Consumer<String> listener) {
        this.onMessageReceived = listener;
    }

    /**
     * Sends a message to the proxy by POSTing it to {@code /send}.
     * The message is suffixed with {@code ;EOM} if not already present.
     *
     * @param message the protocol-formatted message string
     */
    @Override
    public void sendBroadcast(String message) {
        if (!message.endsWith(";EOM")) {
            message += ";EOM";
        }

        RequestBody body = RequestBody.create(message, TEXT);
        Request request = new Request.Builder()
                .url(BASE_URL + "/send")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Send failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    /**
     * Starts the polling loop that periodically checks for new messages from the proxy.
     * <p>
     * This loop runs in a separate thread and performs HTTP GET requests to {@code /poll}.
     * If a message is received, it is passed to the registered handler (if any).
     */
    @Override
    public void start() {
        running = true;
        Log.i(TAG, "Starting HTTP polling loop...");

        new Thread(() -> {
            while (running) {
                try {
                    Request request = new Request.Builder()
                            .url(BASE_URL + "/poll")
                            .build();
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "";
                        if (response.body() != null) response.body().close();

                        if (!body.isEmpty()) {
                            httpBuffer.append(body);

                            while (true) {
                                int start = httpBuffer.indexOf("#");
                                int end = httpBuffer.indexOf(";EOM", start);

                                if (start != 0 || end == -1) break;

                                String fullMessage = httpBuffer.substring(start, end).trim();
                                httpBuffer.delete(0, end + 4);
                                Log.d(TAG, "Full message before parse: " + fullMessage);

                                try {
                                    if (onMessageReceived != null) {
                                        onMessageReceived.accept(fullMessage);
                                    }
                                } catch (IllegalArgumentException e) {
                                    Log.w(TAG, "Invalid protocol message: " + fullMessage, e);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Polling failed", e);
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }).start();
    }

    /**
     * Stops the polling loop and terminates message reception.
     * This will cause the background thread to exit after the next sleep cycle.
     */
    @Override
    public void stop() {
        running = false;
    }
}
