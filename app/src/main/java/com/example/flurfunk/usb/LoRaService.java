package com.example.flurfunk.usb;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;

/**
 * Android {@link Service} responsible for managing a persistent connection to a LoRa dongle
 * via USB serial communication.
 * <p>
 * This service encapsulates a {@link LoRaSocket} and provides methods to:
 * <ul>
 *     <li>Connect to a {@link UsbSerialPort}</li>
 *     <li>Send data over the USB serial port</li>
 *     <li>Disconnect and clean up resources</li>
 * </ul>
 * <p>
 * The service is intended to be bound to by components like {@code ATLoRaManager}
 * that need to maintain serial communication across activity or process lifetimes.
 */
public class LoRaService extends Service {

    private static final String TAG = "LoRaService";
    private final IBinder binder = new LocalBinder();
    private LoRaSocket socket;

    /**
     * Binder that allows clients to retrieve the bound instance of {@link LoRaService}.
     */
    public class LocalBinder extends Binder {

        /**
         * Returns the current instance of the LoRaService.
         *
         * @return the service instance
         */
        public LoRaService getService() {
            return LoRaService.this;
        }
    }

    /**
     * Called when a client binds to this service.
     *
     * @param intent the intent used to bind
     * @return an {@link IBinder} for accessing the service
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Connects to the given USB serial port and starts communication via a {@link LoRaSocket}.
     *
     * @param port     the USB serial port to open
     * @param listener the listener to receive connection events and incoming data
     */
    public void connect(UsbSerialPort port, LoRaListener listener) {
        socket = new LoRaSocket(port, listener);
        socket.connect(getApplicationContext());
    }

    /**
     * Disconnects the active serial socket connection, if any.
     * Cleans up all related resources.
     */
    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    /**
     * Sends a byte array to the connected LoRa dongle.
     *
     * @param data the data to send
     */
    public void write(byte[] data) {
        if (socket != null) {
            socket.write(data);
        } else {
            Log.w(TAG, "Attempted to write with no socket connection.");
        }
    }
}
