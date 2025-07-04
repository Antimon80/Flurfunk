package com.example.flurfunk.network;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.flurfunk.usb.LoRaListener;
import com.example.flurfunk.usb.LoRaService;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * A LoRa communication manager for USB-based AT-command-compatible devices.
 * <p>
 * This class handles:
 * <ul>
 *     <li>USB dongle detection and permission handling</li>
 *     <li>Serial connection via {@link UsbSerialPort}</li>
 *     <li>Initialization of LoRa dongle using AT commands</li>
 *     <li>Reliable transmission queue with delay management</li>
 *     <li>Message reception using a robust serial frame parser</li>
 * </ul>
 * Messages are expected to be framed using a '#' start byte and ending with ";EOM".
 */
public class ATLoRaManager implements LoRaManager, LoRaListener {

    private static final String TAG = "ATLoRaManager";
    private static final String ACTION_USB_PERMISSION = "com.example.flurfunk.USB_PERMISSION";
    private static final int MAX_LORA_BYTES = 960;
    private static final int MIN_GAP_MS = 1500;
    private long lastTx = 0;

    private final Context context;
    private final UsbManager usbManager;

    private UsbSerialPort usbSerialPort;
    private volatile LoRaService loRaService;
    private volatile boolean readyToSend = false;
    private volatile boolean initialized = false;

    private boolean receiverRegistered = false;
    private boolean bound = false;
    private volatile boolean isReceiving = false;
    private final BlockingQueue<String> txQueue = new LinkedBlockingQueue<>();
    private Consumer<String> onMessageReceived;
    private final SerialFrameReader frameReader;

    /**
     * Constructs the LoRa manager and starts the TX thread.
     *
     * @param context the application context
     */
    public ATLoRaManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.frameReader = new SerialFrameReader(this::handleFrame);
        new Thread(this::txLoop, "LoRaTxWorker").start();
    }

    /**
     * Sets the callback to be invoked when a complete LoRa message is received.
     *
     * @param handler a consumer that handles parsed messages
     */
    @Override
    public void setOnMessageReceived(Consumer<String> handler) {
        this.onMessageReceived = handler;
    }

    /**
     * Queues a broadcast message to be sent over LoRa.
     * Messages exceeding 960 bytes are dropped.
     *
     * @param message the message string to send
     */
    @Override
    public void sendBroadcast(String message) {
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
            Log.w(TAG, "Message too large - not queued");
            return;
        }
        txQueue.offer(message);
    }

    /**
     * Starts the USB receiver and attempts to detect and bind to the LoRa dongle.
     */
    @Override
    public void start() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            ContextCompat.registerReceiver(context, usbReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        detectDongle();
    }

    /**
     * Stops the LoRa manager, disconnects from USB, unbinds the service, and resets internal state.
     */
    @Override
    public void stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException ignore) {
            }
            receiverRegistered = false;
        }
        if (bound) {
            context.unbindService(serviceConnection);
            bound = false;
        }
        if (loRaService != null) {
            loRaService.disconnect();
            loRaService = null;
        }
        readyToSend = false;
        initialized = false;
        frameReader.setInitialized(false);
    }

    /**
     * Scans for connected USB serial devices and requests permission if necessary.
     * <p>
     * If permission is already granted, retrieves the first available serial port
     * and proceeds to bind the LoRa service for communication.
     */
    private void detectDongle() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            Log.w(TAG, "No USB serial drivers found");
            return;
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();

        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usbManager.requestPermission(device, pi);
        } else {
            usbSerialPort = driver.getPorts().get(0);
            bindLoRaService();
        }
    }

    /**
     * Binds the Android service responsible for managing the USB serial connection.
     * <p>
     * If the service is not yet bound, it creates an intent and establishes the service connection.
     */
    private void bindLoRaService() {
        if (!bound) {
            Intent intent = new Intent(context, LoRaService.class);
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    /**
     * BroadcastReceiver that listens for the result of a USB permission request.
     * <p>
     * If permission is granted for the selected USB device, it initializes the
     * serial port and proceeds to bind the LoRa service.
     */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @SuppressLint("UnsafeIntentLaunch")
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (device != null && granted) {
                for (UsbSerialDriver d : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
                    if (d.getDevice().equals(device)) {
                        usbSerialPort = d.getPorts().get(0);
                        bindLoRaService();
                        break;
                    }
                }
            } else Log.w(TAG, "USB permission denied");
        }
    };

    /**
     * Defines the lifecycle of the connection to the {@link LoRaService}.
     * <ul>
     *     <li>On connect: assigns the service and calls {@code connect()} on it.</li>
     *     <li>On disconnect: resets state and disables message sending.</li>
     * </ul>
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder service) {
            LoRaService.LocalBinder b = (LoRaService.LocalBinder) service;
            loRaService = b.getService();
            loRaService.connect(usbSerialPort, ATLoRaManager.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            loRaService = null;
            bound = false;
            readyToSend = false;
            initialized = false;
            frameReader.setInitialized(false);
        }
    };

    /**
     * Sends a predefined set of AT commands to configure the LoRa dongle.
     * <p>
     * This includes switching to LoRa mode, setting spreading factor,
     * bandwidth, coding rate, TX/RX channels, and enabling RSSI reporting.
     * <p>
     * If initialization fails, the method schedules a retry.
     */
    private void initializeDongle() {
        Log.d(TAG, "Initializing LoRa dongle …");
        try {
            loRaService.write("+++\r\n".getBytes());
            Thread.sleep(1000);

            loRaService.write("AT\r\n".getBytes());
            loRaService.write("AT+MODE=1\r\n".getBytes());
            loRaService.write("AT+SF=10\r\n".getBytes());
            loRaService.write("AT+BW=0\r\n".getBytes());
            loRaService.write("AT+CR=1\r\n".getBytes());
            loRaService.write("AT+TXCH=18\r\n".getBytes());
            loRaService.write("AT+RXCH=18\r\n".getBytes());
            loRaService.write("AT+RSSI=1\r\n".getBytes());

            initialized = true;
            readyToSend = true;
            frameReader.setInitialized(true);

            Log.d(TAG, "Dongle init complete");
        } catch (Exception ex) {
            Log.e(TAG, "Dongle init failed", ex);
            retryInitLater();
        }
    }

    /**
     * Schedules a retry of the dongle detection and initialization after a short delay.
     * <p>
     * Called when initialization fails or when the USB connection is unexpectedly lost.
     */
    private void retryInitLater() {
        initialized = false;
        readyToSend = false;
        frameReader.setInitialized(false);
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignore) {
            }
            detectDongle();
        }).start();
    }

    /**
     * Called when the USB serial connection is successfully established.
     * Starts a separate thread to initialize the dongle with AT commands.
     */
    @Override
    public void onConnected() {
        Log.i(TAG, "USB connected");
        new Thread(this::initializeDongle).start();
    }

    /**
     * Handles raw byte input from the LoRa dongle and delegates parsing to {@link SerialFrameReader}.
     *
     * @param data raw byte data received via serial
     */
    @Override
    public void onSerialRead(byte[] data) {
        isReceiving = true;
        try {
            frameReader.onSerialRead(data);
        } finally {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> isReceiving = false, 200);
        }
    }

    /**
     * Handles LoRa communication errors.
     * Stops the current connection and retries initialization after a delay.
     *
     * @param e the encountered exception
     */
    @Override
    public void onError(Exception e) {
        Log.e(TAG, "LoRa error", e);
        initialized = false;
        readyToSend = false;
        stop();
        retryInitLater();
    }

    /**
     * Called when the USB device is disconnected.
     * Triggers reinitialization logic.
     */
    @Override
    public void onDisconnected() {
        Log.i(TAG, "USB disconnected");
        initialized = false;
        readyToSend = false;
        retryInitLater();
    }

    /**
     * Continuously processes the TX queue and ensures a minimum delay between messages.
     * Waits if the dongle is currently receiving or if the minimal gap has not passed.
     */
    private void txLoop() {
        for (; ; ) {
            try {
                String msg = txQueue.take();
                while (isReceiving) {
                    Thread.sleep(40);
                }

                long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastTx);
                if (wait > 0) {
                    Thread.sleep(wait);
                }

                Thread.sleep((long) (Math.random() * 120));

                sendOut(msg);
                lastTx = System.currentTimeMillis();
            } catch (InterruptedException ignored) {
                Log.w(TAG, "TX-Thread interrupted");
            }
        }
    }

    /**
     * Sends the given message over LoRa, or requeues it if the dongle is not ready.
     *
     * @param message the message to send
     */
    private void sendOut(String message) {
        if (!readyToSend || loRaService == null) {
            Log.w(TAG, "TX skipped - LoRaService not ready");
            txQueue.offer(message);
            return;
        }
        loRaService.write(message.getBytes(StandardCharsets.UTF_8));
        Log.d(TAG, "Message to be sent: " + message);
        Log.d(TAG, "TX → " + message.length() + " B");
    }

    /**
     * Handles a fully assembled and decoded message frame.
     * Invokes the message callback if registered.
     *
     * @param frame the complete message string
     */
    private void handleFrame(String frame) {
        Log.i(TAG, "Full message to be parsed: " + frame);
        if (onMessageReceived != null) onMessageReceived.accept(frame);
    }

    /**
     * Utility class for decoding framed serial input from the LoRa dongle.
     * <p>
     * Detects messages that:
     * <ul>
     *     <li>start with '#' (0x23)</li>
     *     <li>end with the byte sequence ";EOM"</li>
     * </ul>
     * Buffers incomplete chunks until a valid frame is completed.
     * Decodes using UTF-8 with error reporting enabled.
     */
    private static final class SerialFrameReader {

        private static final byte START = (byte) '#';
        private static final byte[] END = ";EOM".getBytes(StandardCharsets.US_ASCII);

        private byte[] buf = new byte[1024];
        private int len = 0;

        private final Consumer<String> cb;
        private final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        private volatile boolean on = false;

        SerialFrameReader(Consumer<String> cb) {
            this.cb = cb;
        }

        /**
         * Enables or disables the frame parser and clears the buffer if disabled.
         *
         * @param i {@code true} to enable parsing, {@code false} to disable and reset
         */
        void setInitialized(boolean i) {
            on = i;
            if (!i) len = 0;
        }

        /**
         * Feeds raw serial data into the parser. If a complete message frame is detected,
         * it is decoded and passed to the registered callback.
         *
         * @param data the incoming serial data chunk
         */
        synchronized void onSerialRead(byte[] data) {
            if (data == null || data.length == 0) return;

            ensureCapacity(data.length);
            System.arraycopy(data, 0, buf, len, data.length);
            len += data.length;

            Log.d(TAG, "Chunk (" + data.length + " B): " + toPrintable(data));
            if (!on) return;

            int scan = 0;
            while (true) {
                int s = indexOf(buf, scan, len, START);
                if (s < 0) {
                    len = 0;
                    break;
                }

                int nextS = indexOf(buf, s + 1, len, START);
                int e = indexOf(buf, s + 1, len, END);

                if (e < 0 || (nextS != -1 && nextS < e)) {
                    shift(nextS >= 0 ? nextS : s);
                    break;
                }
                int frameLen = e + END.length - s;
                String frame;
                try {
                    frame = dec.decode(ByteBuffer.wrap(buf, s, frameLen)).toString().trim();
                } catch (Exception ex) {
                    shift(e + END.length);
                    continue;
                }
                cb.accept(frame);
                shift(e + END.length);
                scan = 0;
            }
        }

        private void ensureCapacity(int extra) {
            if (len + extra <= buf.length) return;
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
        }

        private void shift(int off) {
            int rest = len - off;
            if (rest > 0) System.arraycopy(buf, off, buf, 0, rest);
            len = rest;
        }

        private static int indexOf(byte[] a, int from, int to, byte val) {
            for (int i = from; i < to; i++) if (a[i] == val) return i;
            return -1;
        }

        private static int indexOf(byte[] a, int from, int to, byte[] pat) {
            outer:
            for (int i = from; i <= to - pat.length; i++) {
                for (int j = 0; j < pat.length; j++)
                    if (a[i + j] != pat[j]) continue outer;
                return i;
            }
            return -1;
        }

        private static String toPrintable(byte[] d) {
            StringBuilder sb = new StringBuilder(d.length);
            for (byte b : d) {
                int v = b & 0xFF;
                sb.append(v >= 32 && v < 127 ? (char) v : '.');
            }
            return sb.toString();
        }
    }
}
