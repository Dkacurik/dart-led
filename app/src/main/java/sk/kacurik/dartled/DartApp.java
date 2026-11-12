package sk.kacurik.dartled;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import sk.kacurik.dartled.core.Frame;
import sk.kacurik.dartled.core.Renderer;
import sk.kacurik.dartled.core.Scoreboard;
import sk.kacurik.dartled.core.ScreenParser;

/** App-wide state shared by the screen reader, the Bluetooth link and the settings screen. */
public final class DartApp extends Application {

    public static final String[] SIZES = {"auto", "64x16", "32x16", "96x16", "32x32", "64x32", "64x64"};

    private static DartApp instance;

    private SharedPreferences prefs;
    private LedLink link;

    private volatile Scoreboard board = new Scoreboard();
    private volatile String boardKey = "";
    private volatile Frame frame;
    private volatile List<String> lastTexts = new ArrayList<>();
    private volatile String lastPackage = "-";
    private volatile long lastSeenAt;

    public static DartApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("dartled", MODE_PRIVATE);
        link = new LedLink(this);
        link.setAddress(prefs.getString("address", null));
    }

    public LedLink link() { return link; }
    public Scoreboard board() { return board; }
    public Frame frame() { return frame; }
    public List<String> lastTexts() { return lastTexts; }
    public String lastPackage() { return lastPackage; }
    public long lastSeenAt() { return lastSeenAt; }

    public void chooseDisplay(String address, String name) {
        prefs.edit().putString("address", address).putString("name", name).apply();
        link.setAddress(address);
        link.connectNow();
    }

    public String displayName() {
        return prefs.getString("name", null);
    }

    public String sizeSetting() {
        return prefs.getString("size", "auto");
    }

    public void setSizeSetting(String s) {
        prefs.edit().putString("size", s).apply();
        redraw();
    }

    public int brightness() {
        return prefs.getInt("brightness", 60);
    }

    public void setBrightness(int b) {
        prefs.edit().putInt("brightness", b).apply();
        link.setBrightness(b);
    }

    /** Panel size in pixels: the user's choice, else what the panel reported, else 64x16. */
    public int[] size() {
        String s = sizeSetting();
        if (!"auto".equals(s)) {
            String[] p = s.split("x");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        }
        int[] d = link.detectedSize();
        return d != null ? d : new int[]{64, 16};
    }

    /** Called by the reader with everything currently visible in DartCounter. */
    public void onScreenTexts(String pkg, List<String> texts) {
        lastPackage = pkg;
        lastTexts = texts;
        lastSeenAt = System.currentTimeMillis();
        Scoreboard sb = ScreenParser.parse(texts);
        if (sb.isEmpty()) return; // not the match screen: keep showing the last score
        show(sb);
    }

    public void showDemo() {
        boardKey = "";
        show(Scoreboard.demo());
    }

    private synchronized void show(Scoreboard sb) {
        String key = sb.key();
        if (key.equals(boardKey)) return;
        boardKey = key;
        board = sb;
        redraw();
    }

    private void redraw() {
        if (board.isEmpty()) return;
        int[] s = size();
        frame = Renderer.render(board, s[0], s[1]);
        link.submitFrame(frame);
    }

    /** After (re)connecting: set brightness and repaint, since the panel may have reset. */
    void onLinkReady() {
        link.setBrightness(brightness());
        if (!board.isEmpty()) {
            int[] s = size();
            frame = Renderer.render(board, s[0], s[1]);
            link.submitFrame(frame);
        }
    }
}
