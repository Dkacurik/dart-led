package sk.kacurik.dartled;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import sk.kacurik.dartled.core.Scoreboard;

/**
 * Receives darts from the DartCam phone over Wi-Fi and hands them to {@link DartTyper}.
 * Announced on the network as "_dartcam._tcp" so the phone finds it without typing an address.
 * Text lines (see DartCam's TabletLink): HELLO, DART T20, SUBMIT, UNDO, PING.
 */
public final class CamServer {

    public static final int PORT = 47474;
    private static final String SERVICE_TYPE = "_dartcam._tcp.";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile String lastState = "";
    private volatile String lastCommand = "-";
    private volatile long lastCommandAt;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;

    public CamServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(this::acceptLoop, "dartcam-server").start();
    }

    public int clientCount() { return clients.size(); }
    public String lastCommand() { return lastCommand; }
    public long lastCommandAt() { return lastCommandAt; }

    /** This tablet's Wi-Fi address(es), shown on the settings screen for manual setup. */
    public static String addresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        return sb.length() == 0 ? "no Wi-Fi" : sb.toString();
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                server = ss;
                register();
                while (running) {
                    Socket s = ss.accept();
                    new Thread(() -> serve(s), "dartcam-client").start();
                }
            } catch (Exception e) {
                unregister();
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void serve(Socket s) {
        OutputStream out = null;
        try {
            s.setTcpNoDelay(true);
            out = s.getOutputStream();
            clients.add(out);
            DartTyper.log("phone connected (" + s.getInetAddress().getHostAddress() + ")");
            if (!lastState.isEmpty()) write(out, lastState);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.isEmpty()) continue;
                lastCommand = cmd;
                lastCommandAt = System.currentTimeMillis();
                OutputStream reply = out;
                main.post(() -> handle(cmd, reply));
            }
        } catch (Exception ignored) {
        } finally {
            if (out != null) { clients.remove(out); DartTyper.log("phone disconnected"); }
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Runs on the main thread. */
    private void handle(String cmd, OutputStream reply) {
        String upper = cmd.toUpperCase(Locale.ROOT);
        DartTyper typer = DartApp.get().typer();
        if (upper.startsWith("HELLO") || upper.equals("PING")) {
            write(reply, "OK " + upper.split(" ")[0]);
            pushState(DartApp.get().board());
        } else if (upper.startsWith("DART ")) {
            typer.enterDart(upper.substring(5).trim(), result -> write(reply, result));
        } else if (upper.equals("SUBMIT")) {
            typer.submit(result -> write(reply, result));
        } else if (upper.equals("UNDO")) {
            typer.undo(result -> write(reply, result));
        } else {
            write(reply, "ERR unknown command " + cmd);
        }
    }

    /** Send the current DartCounter state to the phone: STATE|name|remaining|entered|typer. */
    public void pushState(Scoreboard sb) {
        String name = "", score = "";
        for (Scoreboard.Player p : sb.players) {
            if (p.active) { name = p.name; score = String.valueOf(p.score); }
        }
        if (name.isEmpty() && sb.players.size() == 1) {
            name = sb.players.get(0).name;
            score = String.valueOf(sb.players.get(0).score);
        }
        String state = "STATE|" + clean(name) + "|" + score + "|" + String.join(",", sb.entered)
                + "|" + (DartApp.get().typingOn() ? "on" : "off");
        if (state.equals(lastState)) return;
        lastState = state;
        for (OutputStream o : clients) write(o, state);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', ' ').replace('\n', ' ');
    }

    private void write(OutputStream o, String line) {
        if (o == null) return;
        new Thread(() -> {
            try {
                synchronized (o) {
                    o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (Exception e) {
                clients.remove(o);
            }
        }).start();
    }

    private void register() {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("DartLED");
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int code) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int code) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception ignored) { }
    }

    private void unregister() {
        try { if (nsd != null && registration != null) nsd.unregisterService(registration); } catch (Exception ignored) { }
        registration = null;
    }
}
