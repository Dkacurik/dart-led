package sk.kacurik.dartcam;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wi-Fi link to Dart LED on the tablet. Finds it automatically (Bonjour/NSD service
 * "_dartcam._tcp"), or uses a fixed "host[:port]" if the user typed one. Plain text lines:
 *   phone -> tablet: HELLO, DART T20 / DART MISS, SUBMIT, UNDO, PING
 *   tablet -> phone: STATE|name|remaining|entered darts|typer status, OK ..., ERR ...
 */
public final class TabletLink {

    public static final String SERVICE_TYPE = "_dartcam._tcp.";
    public static final int DEFAULT_PORT = 47474;

    public interface Listener {
        void onStatus(String status, boolean connected);
        void onLine(String line);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicastLock;

    private volatile String fixedHost;           // user override, null = auto
    private volatile String foundHost;
    private volatile int foundPort = DEFAULT_PORT;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private Thread connector;

    public TabletLink(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void setFixedHost(String hostPort) {
        fixedHost = hostPort == null || hostPort.trim().isEmpty() ? null : hostPort.trim();
        closeSocket();
    }

    public boolean connected() { return out != null; }

    public synchronized void start() {
        if (running) return;
        running = true;
        startDiscovery();
        connector = new Thread(this::connectLoop, "tablet-link");
        connector.start();
    }

    public synchronized void stop() {
        running = false;
        stopDiscovery();
        closeSocket();
        if (connector != null) connector.interrupt();
    }

    /** Send one line; silently dropped when not connected (the UI shows the state). */
    public void send(String line) {
        sender.execute(() -> {
            OutputStream o = out;
            if (o == null) return;
            try {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Exception e) {
                closeSocket();
            }
        });
    }

    private void connectLoop() {
        while (running) {
            String host;
            int port;
            String fh = fixedHost;
            if (fh != null) {
                int c = fh.lastIndexOf(':');
                host = c > 0 ? fh.substring(0, c) : fh;
                port = c > 0 ? parsePort(fh.substring(c + 1)) : DEFAULT_PORT;
            } else {
                host = foundHost;
                port = foundPort;
            }
            if (host == null) {
                status("Looking for Dart LED on the Wi-Fi…", false);
                sleep(1000);
                continue;
            }
            Socket s = new Socket();
            try {
                status("Connecting to " + host + "…", false);
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setTcpNoDelay(true);
                socket = s;
                out = s.getOutputStream();
                status("Connected to Dart LED (" + host + ")", true);
                send("HELLO DartCam 1");
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    String l = line;
                    main.post(() -> listener.onLine(l));
                }
            } catch (Exception e) {
                // fall through to retry
            } finally {
                closeSocket();
            }
            if (running) {
                status("Tablet not reachable – retrying…", false);
                if (fixedHost == null) foundHost = null; // rediscover, the tablet may have a new address
                sleep(2000);
            }
        }
    }

    private static int parsePort(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return DEFAULT_PORT; }
    }

    private void closeSocket() {
        out = null;
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    private void status(String s, boolean connected) {
        main.post(() -> listener.onStatus(s, connected));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ---------- discovery ----------

    private void startDiscovery() {
        try {
            WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("dartcam");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int code) { }
                @Override public void onStopDiscoveryFailed(String t, int code) { }
                @Override public void onServiceLost(NsdServiceInfo info) { }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (!info.getServiceType().contains("_dartcam")) return;
                    resolve(info);
                }
            };
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            status("Auto-discovery not available – enter the tablet address", false);
        }
    }

    @SuppressWarnings("deprecation")
    private void resolve(NsdServiceInfo info) {
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int code) { }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    if (i.getHost() == null) return;
                    foundHost = i.getHost().getHostAddress();
                    foundPort = i.getPort() > 0 ? i.getPort() : DEFAULT_PORT;
                }
            });
        } catch (Exception ignored) { }
    }

    private void stopDiscovery() {
        try { if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        discovery = null;
        try { if (multicastLock != null) multicastLock.release(); } catch (Exception ignored) { }
    }
}
