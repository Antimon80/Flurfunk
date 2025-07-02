package com.example.flurfunk.usb;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the low-level USB serial communication with a LoRa dongle using the
 * {@link UsbSerialPort} interface.
 * <p>
 * This class is responsible for:
 * <ul>
 *     <li>Opening and configuring the USB serial port</li>
 *     <li>Starting a background I/O thread for reading incoming data</li>
 *     <li>Sending data to the LoRa dongle</li>
 *     <li>Forwarding received data and errors to a {@link LoRaListener}</li>
 * </ul>
 * <p>
 * It wraps the {@link SerialInputOutputManager} provided by the USB-Serial library
 * and delegates lifecycle and event handling appropriately.
 */
public class LoRaSocket implements SerialInputOutputManager.Listener {

    private static final String TAG = "LoRaSocket";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final UsbSerialPort port;
    private final LoRaListener listener;
    private SerialInputOutputManager ioManager;
    private Context context;
    private boolean intentionalClose = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RETRIES = 3;

    /**
     * Creates a new {@code LoRaSocket} for a given USB serial port and listener.
     *
     * @param port     the USB serial port to communicate with
     * @param listener the callback listener for connection events and data
     */
    public LoRaSocket(UsbSerialPort port, LoRaListener listener) {
        this.port = port;
        this.listener = listener;
    }

    /**
     * Connects to the USB serial port and starts the background I/O thread.
     * Configures the port with 115200 baud, 8 data bits, 1 stop bit, no parity.
     * If the connection fails, {@link LoRaListener#onError(Exception)} is invoked.
     *
     * @param context the Android context used to get the {@link UsbManager}
     */
    public void connect(Context context) {
        this.context = context.getApplicationContext();
        intentionalClose = false;

        try {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbDeviceConnection connection = usbManager.openDevice(port.getDriver().getDevice());
            if (connection == null) {
                throw new IOException("Cannot open USB device");
            }

            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            ioManager = new SerialInputOutputManager(port, this);
            executor.submit(ioManager::start);

            reconnectAttempts = 0;
            listener.onConnected();
        } catch (Exception e) {
            Log.e(TAG, "Connection failed", e);
            listener.onError(e);
        }
    }

    /**
     * Disconnects the USB serial connection.
     * Stops the I/O thread, closes the serial port, and notifies the listener.
     */
    public void disconnect() {
        intentionalClose = true;

        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        try {
            port.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing port", e);
        }
        listener.onDisconnected();
    }

    /**
     * Sends a byte array over the USB serial port.
     *
     * @param data the byte array to send
     */
    public void write(byte[] data) {
        try {
            port.write(data, 1000);
        } catch (IOException e) {
            listener.onError(e);
        }
    }

    /**
     * Called by the I/O thread when new data has been received from the USB port.
     * Forwards the data to the {@link LoRaListener}.
     *
     * @param data the received byte array
     */
    @Override
    public void onNewData(byte[] data) {
        Log.d(TAG, "onNewData: received " + data.length + " bytes");
        listener.onSerialRead(data);
    }

    /**
     * Called when an error occurs in the background I/O thread.
     * Forwards the error to the {@link LoRaListener}.
     *
     * @param e the exception that occurred
     */
    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "runRead crashed: " + e.getMessage(), e);

        if (intentionalClose) {
            Log.i(TAG, "runRead stopped intentionally → no reconnect");
            return;
        }

        listener.onError(e);

        if (++reconnectAttempts > MAX_RETRIES) {
            Log.e(TAG, "Too many reconnect attempts – giving up");
            return;
        }

        Log.i(TAG, "Attempting reconnect in 1s (attempt " + reconnectAttempts + ")");
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                connect(context);
            } catch (InterruptedException ignored) {
            }
        }).start();
    }
}
