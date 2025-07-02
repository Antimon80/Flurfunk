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
import java.util.function.BiConsumer;

/**
 * LoRa-USB-Manager mit robustem Frame-Parser (SerialFrameReader).
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
    private BiConsumer<String, String> onMessageReceived;
    private final SerialFrameReader frameReader;


    public ATLoRaManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.frameReader = new SerialFrameReader(this::handleFrame);
        new Thread(this::txLoop, "LoRaTxWorker").start();
    }

    @Override
    public void setOnMessageReceived(BiConsumer<String, String> handler) {
        this.onMessageReceived = handler;
    }

    @Override
    public void sendMessageTo(String peerId, String message) {
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
            Log.w(TAG, "Message too large - not queued");
            return;
        }
        txQueue.offer(message);
    }

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

    private void bindLoRaService() {
        if (!bound) {
            Intent intent = new Intent(context, LoRaService.class);
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

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

    @Override
    public void onConnected() {
        Log.i(TAG, "USB connected");
        new Thread(this::initializeDongle).start();
    }

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

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "LoRa error", e);
        initialized = false;
        readyToSend = false;
        stop();
        retryInitLater();
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "USB disconnected");
        initialized = false;
        readyToSend = false;
        retryInitLater();
    }

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

    private void sendOut(String message) {
        if (!readyToSend || loRaService == null) {
            Log.w(TAG, "TX skipped - LoRaService not ready");
            txQueue.offer(message);
            return;
        }
        loRaService.write(message.getBytes(StandardCharsets.UTF_8));
        Log.d(TAG, "TX → " + message.length() + " B");
    }

    private void handleFrame(String src, String frame) {
        Log.i(TAG, "Full message to be parsed: " + frame);
        if (onMessageReceived != null) onMessageReceived.accept(src, frame);
    }


    private static final class SerialFrameReader {

        private static final byte START = (byte) '#';
        private static final byte[] END = ";EOM".getBytes(StandardCharsets.US_ASCII);

        private byte[] buf = new byte[1024];
        private int len = 0;

        private final BiConsumer<String, String> cb;
        private final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        private volatile boolean on = false;

        SerialFrameReader(BiConsumer<String, String> cb) {
            this.cb = cb;
        }

        void setInitialized(boolean i) {
            on = i;
            if (!i) len = 0;
        }

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
                cb.accept("dongle", frame);
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
