package com.example.flurfunk.network;

import java.util.function.Consumer;

/**
 * Interface for LoRa-based message transport between peers.
 */
public interface LoRaManager {

    /**
     * Sends a message to the given peer ID.
     *
     * @param message the message content as a JSON string
     */
    void sendBroadcast(String message);

    /**
     * Sets the handler for incoming messages.
     *
     * @param handler a callback with parameters (message)
     */
    void setOnMessageReceived(Consumer<String> handler);

    /**
     * Initializes and starts the communication channel.
     */
    void start();

    /**
     * Stops the communication and cleans up.
     */
    void stop();
}
