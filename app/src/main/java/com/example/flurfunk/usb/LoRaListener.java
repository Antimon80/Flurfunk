package com.example.flurfunk.usb;

/**
 * Listener interface for handling events related to LoRa USB serial communication.
 * <p>
 * Implementations of this interface receive callbacks for connection status changes,
 * incoming serial data, and error conditions.
 * <p>
 * Typically used by classes such as {@link com.example.flurfunk.usb.LoRaSocket}
 * or {@link com.example.flurfunk.network.ATLoRaManager} to handle USB device events.
 */
public interface LoRaListener {
    /**
     * Called when the USB connection to the LoRa dongle is successfully established.
     */
    void onConnected();

    /**
     * Called when new data is received from the LoRa dongle via the USB serial interface.
     *
     * @param data the raw byte array received from the serial port
     */
    void onSerialRead(byte[] data);

    /**
     * Called when an error occurs during USB or serial communication.
     *
     * @param e the exception that describes the error
     */
    void onError(Exception e);

    /**
     * Called when the LoRa dongle is disconnected or the USB connection is lost.
     */
    void onDisconnected();
}
