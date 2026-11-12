package sk.kacurik.dartled;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import sk.kacurik.dartled.core.Frame;
import sk.kacurik.dartled.core.IPixel;
import sk.kacurik.dartled.core.Png;

/** Bluetooth connection to the iPixel LED panel. All sending happens on one worker thread. */
@SuppressLint("MissingPermission")
public final class LedLink {
    private static final String TAG = "DartLED";

    private final Context ctx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final Semaphore setupDone = new Semaphore(0);
    private final Semaphore writeDone = new Semaphore(0);
    private final Semaphore notified = new Semaphore(0);
    private volatile byte[] lastNotify;
    private volatile boolean lastWriteOk;

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile BluetoothGattCharacteristic notifyChar;
    private volatile boolean servicesOk;
    private volatile boolean ready;
    private volatile int mtu = 23;

    private volatile String address;
    private volatile String status = "Not connected";
    private volatile int[] detectedSize;

    private final AtomicReference<Frame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean drainQueued = new AtomicBoolean(false);

    public LedLink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setAddress(String address) {
        if (address != null && address.equals(this.address)) return;
        this.address = address;
        worker.execute(this::closeGatt);
    }

    public String address() { return address; }
    public String status() { return status; }
    public boolean isReady() { return ready; }
    public int[] detectedSize() { return detectedSize; }

    /** Show this picture; if several arrive quickly, only the newest is sent. */
    public void submitFrame(Frame f) {
        pendingFrame.set(f);
        if (drainQueued.compareAndSet(false, true)) worker.execute(this::drain);
    }

    public void connectNow() {
        worker.execute(this::ensureConnected);
    }

    public void setBrightness(int level) {
        worker.execute(() -> {
            if (ensureConnected()) sendWindow(IPixel.brightness(level), true);
        });
    }

    // ------------------------------------------------------------------ worker side

    private void drain() {
        drainQueued.set(false);
        Frame f = pendingFrame.getAndSet(null);
        if (f == null) return;
        if (!ensureConnected()) return;
        byte[] png = Png.encode(f);
        for (byte[] w : IPixel.imageWindows(png)) {
            if (!sendWindow(w, true)) {
                status = "Sending failed, will retry";
                closeGatt();
                return;
            }
        }
        status = "Connected · last update " + time();
    }

    private boolean ensureConnected() {
        if (ready && gatt != null) return true;
        String addr = address;
        if (addr == null) { status = "No display chosen yet"; return false; }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { status = "Bluetooth is off"; return false; }

        closeGatt();
        status = "Connecting…";
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(addr);
            setupDone.drainPermits();
            servicesOk = false;
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (!setupDone.tryAcquire(15, TimeUnit.SECONDS) || !servicesOk) {
                status = "Display not reachable – is it on and the iPixel app closed?";
                closeGatt();
                return false;
            }
            // Ask the panel for its size.
            Calendar c = Calendar.getInstance();
            notified.drainPermits();
            lastNotify = null;
            writeRaw(IPixel.deviceInfo(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)));
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until) {
                if (notified.tryAcquire(300, TimeUnit.MILLISECONDS)) {
                    int[] size = IPixel.sizeFromInfo(lastNotify);
                    if (size != null) { detectedSize = size; break; }
                }
            }
            ready = true;
            status = "Connected";
            DartApp.get().onLinkReady();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "connect failed", e);
            status = "Connection error: " + e.getMessage();
            closeGatt();
            return false;
        }
    }

    private boolean sendWindow(byte[] data, boolean waitAck) {
        notified.drainPermits();
        int chunk = Math.max(20, Math.min(244, mtu - 3));
        for (int pos = 0; pos < data.length; pos += chunk) {
            byte[] part = new byte[Math.min(chunk, data.length - pos)];
            System.arraycopy(data, pos, part, 0, part.length);
            if (!writeRaw(part)) return false;
        }
        if (waitAck) {
            long until = System.currentTimeMillis() + 3000;
            try {
                while (System.currentTimeMillis() < until) {
                    if (notified.tryAcquire(200, TimeUnit.MILLISECONDS) && IPixel.isAck(lastNotify)) break;
                }
            } catch (InterruptedException ignored) { }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean writeRaw(byte[] data) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic ch = writeChar;
        if (g == null || ch == null) return false;
        writeDone.drainPermits();
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothStatusCodes.SUCCESS;
        } else {
            ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ch.setValue(data);
            started = g.writeCharacteristic(ch);
        }
        if (!started) return false;
        try {
            return writeDone.tryAcquire(3, TimeUnit.SECONDS) && lastWriteOk;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void closeGatt() {
        ready = false;
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        notifyChar = null;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) { }
            try { g.close(); } catch (Exception ignored) { }
        }
    }

    private static String time() {
        Calendar c = Calendar.getInstance();
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    // ------------------------------------------------------------------ callbacks

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (g == gatt) {
                    ready = false;
                    status = "Disconnected – will reconnect on next score";
                }
                setupDone.release();
                lastWriteOk = false;
                writeDone.release();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int st) {
            if (st == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
            g.discoverServices();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattCharacteristic w = null, n = null;
            List<BluetoothGattService> services = g.getServices();
            for (BluetoothGattService s : services) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (IPixel.WRITE_UUID.equals(c.getUuid())) w = c;
                    if (IPixel.NOTIFY_UUID.equals(c.getUuid())) n = c;
                }
            }
            if (w == null) {
                status = "This device doesn't look like an iPixel display";
                setupDone.release();
                return;
            }
            writeChar = w;
            notifyChar = n;
            servicesOk = true;
            if (n == null) { setupDone.release(); return; }
            g.setCharacteristicNotification(n, true);
            BluetoothGattDescriptor d = n.getDescriptor(IPixel.CCCD_UUID);
            if (d == null) { setupDone.release(); return; }
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                started = g.writeDescriptor(d);
            }
            if (!started) setupDone.release();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            setupDone.release();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            lastWriteOk = st == BluetoothGatt.GATT_SUCCESS;
            writeDone.release();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onNotify(c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(value);
        }

        private void onNotify(byte[] v) {
            if (v == null) return;
            lastNotify = v.clone();
            notified.release();
        }
    };
}
