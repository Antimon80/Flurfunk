package com.example.flurfunk.network;

import java.util.function.BiConsumer;

/**
 * Interface for LoRa-based message transport between peers.
 */
public interface LoRaManager {

    /**
     * Sends a message to the given peer ID.
     *
     * @param peerId  the recipient's unique ID
     * @param message the message content as a JSON string
     */
    void sendMessageTo(String peerId, String message);

    /**
     * Sets the handler for incoming messages.
     *
     * @param handler a callback with parameters (senderPeerId, message)
     */
    void setOnMessageReceived(BiConsumer<String, String> handler);

    /**
     * Initializes and starts the communication channel.
     */
    void start();

    /**
     * Stops the communication and cleans up.
     */
    void stop();
}
