package sk.kacurik.dartled;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import sk.kacurik.dartled.core.Frame;
import sk.kacurik.dartled.core.IdleClock;
import sk.kacurik.dartled.core.Moments;
import sk.kacurik.dartled.core.Renderer;
import sk.kacurik.dartled.core.Scoreboard;
import sk.kacurik.dartled.core.ScreenParser;

/** App-wide state shared by the screen reader, the Bluetooth link and the settings screen. */
public final class DartApp extends Application {

    public static final String[] SIZES = {"auto", "64x16", "32x16", "96x16", "32x32", "64x32", "64x64"};

    private static DartApp instance;

    private SharedPreferences prefs;
    private LedLink link;
    private Narrator narrator;

    private volatile Scoreboard board = new Scoreboard();
    private volatile String boardKey = "";
    private volatile Frame frame;
    private volatile List<String> lastTexts = new ArrayList<>();
    private volatile String lastPackage = "-";
    private volatile long lastSeenAt;

    // Match-moment animations (180, bust, game shot, 26...)
    private final ScheduledExecutorService anim = Executors.newSingleThreadScheduledExecutor();
    private final Random rnd = new Random();
    private volatile int momentToken;
    private volatile boolean playing;

    // "+60" shown instead of a name for a few seconds after a visit.
    private volatile int flashIdx = -1;
    private volatile String flashText;
    private volatile long flashUntil;

    // Idle clock: shown when no match is on screen.
    private volatile boolean idle;
    private volatile int idleSeconds;
    private volatile long nonMatchSince;   // when DartCounter stopped showing a match (0 = it shows one)
    private volatile long lastMatchAt;     // last time a match screen changed

    public static DartApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("dartled", MODE_PRIVATE);
        link = new LedLink(this);
        narrator = new Narrator(this);
        link.setAddress(prefs.getString("address", null));
        anim.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
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
        if (sb.isEmpty()) {             // not the match screen: keep the last score for now
            if (nonMatchSince == 0) nonMatchSince = System.currentTimeMillis();
            return;
        }
        nonMatchSince = 0;
        lastMatchAt = System.currentTimeMillis();
        if (idle) leaveIdle();
        show(sb);
    }

    /** The reader saw another app come to the front (home screen, browser...). */
    public void onOtherAppInFront() {
        if (nonMatchSince == 0) nonMatchSince = System.currentTimeMillis();
    }

    public boolean idleOn() {
        return prefs.getBoolean("idle", true);
    }

    public void setIdleOn(boolean on) {
        prefs.edit().putBoolean("idle", on).apply();
    }

    /** Test button: pretend the match ended a while ago so the clock appears now. */
    public void showIdleNow() {
        nonMatchSince = System.currentTimeMillis() - 60_000;
    }

    private synchronized void leaveIdle() {
        idle = false;
        momentToken++;          // stop a dart-throw animation if one is running
        playing = false;
        boardKey = "";          // repaint the scoreboard even if it didn't change
    }

    /** Runs every second: decides whether to show the idle clock and animates it. */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            boolean shouldIdle = idleOn() && (board.isEmpty()
                    || (nonMatchSince > 0 && now - nonMatchSince > 20_000)
                    || (lastMatchAt > 0 && now - lastMatchAt > 10 * 60_000));
            if (!shouldIdle) {
                if (idle) { leaveIdle(); redraw(); }
                return;
            }
            if (!idle) { idle = true; idleSeconds = 0; }
            idleSeconds++;
            if (playing) return;
            int[] s = size();
            if (idleSeconds % 30 == 0) {
                play(IdleClock.dartThrow(s[0], s[1], rnd));
                return;
            }
            redraw();
        } catch (Exception ignored) {
            // never let the timer die
        }
    }

    public void showDemo() {
        nonMatchSince = 0;
        lastMatchAt = System.currentTimeMillis();
        if (idle) leaveIdle();
        boardKey = "";
        show(Scoreboard.demo());
    }

    private synchronized void show(Scoreboard sb) {
        String key = sb.key();
        if (key.equals(boardKey)) return;
        Scoreboard before = board;
        boardKey = key;
        board = sb;
        List<Moments.Step> moment = null;
        if (momentsOn()) {
            int[] s = size();
            moment = Moments.forChange(before, sb, s[0], s[1], rnd);
        }
        if (moment != null) {
            play(moment);
            return;
        }
        int[] visit = Moments.lastVisit(before, sb);
        if (narratorOn()) speakTurn(before, sb, visit);
        if (visit != null && visit[1] > 0) {
            flashIdx = visit[0];
            flashText = "+" + visit[1];
            flashUntil = System.currentTimeMillis() + 3500;
            anim.schedule(() -> {
                if (System.currentTimeMillis() >= flashUntil) redraw();
            }, 3600, TimeUnit.MILLISECONDS);
        }
        if (!playing) redraw();
    }

    // ------------------------------------------------------------------ narrator

    public boolean narratorOn() { return prefs.getBoolean("narrator", true); }
    public void setNarratorOn(boolean on) { prefs.edit().putBoolean("narrator", on).apply(); if (!on) narrator.stop(); }
    public boolean callScores() { return prefs.getBoolean("callScores", false); }
    public void setCallScores(boolean on) { prefs.edit().putBoolean("callScores", on).apply(); }
    public boolean callRequire() { return prefs.getBoolean("callRequire", true); }
    public void setCallRequire(boolean on) { prefs.edit().putBoolean("callRequire", on).apply(); }

    public void testVoice() {
        narrator.say("Game on! Dávid to throw first. One hundred and eighty!", false, true);
        narrator.say("Raňajky podané!", true, false);
    }

    public boolean voiceReady() { return narrator.isReady(); }

    /** Ordinary visit (no big moment): optionally call the score, and "you require" on a finish. */
    private void speakTurn(Scoreboard before, Scoreboard now, int[] visit) {
        if (visit != null && callScores()) narrator.say(visit[1] == 0 ? "No score." : Integer.toString(visit[1]), false, true);
        if (!callRequire() || now.cricket || before.players.size() != now.players.size()) return;
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player p = now.players.get(i);
            boolean wasActive = before.players.get(i).active;
            if (p.active && !wasActive && !p.checkout.isEmpty() && p.score <= 170) {
                narrator.say(p.name + ", you require " + p.score + ".", false, false);
            }
        }
    }

    public boolean momentsOn() {
        return prefs.getBoolean("moments", true);
    }

    public void setMomentsOn(boolean on) {
        prefs.edit().putBoolean("moments", on).apply();
    }

    /** For the test buttons: "180", "26", "ton", "bust", "noscore", "game". */
    public void testMoment(String kind) {
        int[] s = size();
        String name = board.players.isEmpty() ? "Dávid" : board.players.get(0).name;
        switch (kind) {
            case "180": play(Moments.oneEighty(s[0], s[1])); break;
            case "26": play(Moments.twentySix(rnd, s[0], s[1])); break;
            case "ton": play(Moments.ton(name, 140, s[0], s[1])); break;
            case "bust": play(Moments.bust(name, s[0], s[1])); break;
            case "noscore": play(Moments.noScore(name, s[0], s[1])); break;
            case "45": play(Moments.forChange(visitBoard(name, 501, 0), visitBoard(name, 456, 45), s[0], s[1], rnd)); break;
            case "41": play(Moments.forChange(visitBoard(name, 501, 0), visitBoard(name, 460, 41), s[0], s[1], rnd)); break;
            case "69": play(Moments.forChange(visitBoard(name, 501, 0), visitBoard(name, 432, 69), s[0], s[1], rnd)); break;
            case "67": play(Moments.forChange(visitBoard(name, 501, 0), visitBoard(name, 434, 67), s[0], s[1], rnd)); break;
            case "high": play(Moments.highFinish(121, s[0], s[1])); break;
            case "fish": play(Moments.highFinish(170, s[0], s[1])); break;
            case "gameon": play(Moments.gameOn(Scoreboard.demo(), s[0], s[1])); break;
            case "bull": play(Moments.throwsFirst(name, s[0], s[1])); break;
            case "aim": play(Moments.forChange(visitBoard(name, 501, 0), visitBoard(name, 496, 5), s[0], s[1], rnd)); break;
            case "111": play(Moments.forChange(entering(visitBoard(name, 501, 0), "S1", "S1", "S1"), visitBoard(name, 498, 3), s[0], s[1], rnd)); break;
            case "robin": play(Moments.forChange(entering(visitBoard(name, 501, 0), "S19", "S19", "S19"), visitBoard(name, 444, 57), s[0], s[1], rnd)); break;
            case "shanghai": play(Moments.forChange(entering(visitBoard(name, 501, 0), "S20", "D20", "T20"), visitBoard(name, 381, 120), s[0], s[1], rnd)); break;
            default: play(Moments.gameShot(name, s[0], s[1])); break;
        }
    }

    /** A one-player board after a visit, for testing the score jokes. */
    private static Scoreboard visitBoard(String name, int score, int last) {
        Scoreboard b = new Scoreboard();
        Scoreboard.Player p = new Scoreboard.Player(name, score, 0, "", true);
        p.lastScore = last;
        p.darts = last == 0 ? 0 : 3;
        b.players.add(p);
        return b;
    }

    private static Scoreboard entering(Scoreboard b, String... darts) {
        for (String d : darts) b.entered.add(d);
        return b;
    }

    private void play(List<Moments.Step> steps) {
        if (steps == null) return;
        int token = ++momentToken;
        playing = true;
        anim.execute(() -> playStep(steps, 0, token));
    }

    private void playStep(List<Moments.Step> steps, int i, int token) {
        if (token != momentToken) return;            // a newer moment took over
        if (i >= steps.size()) {
            playing = false;
            redraw();                                // back to the scoreboard
            return;
        }
        Moments.Step st = steps.get(i);
        if (st.say != null && narratorOn()) narrator.say(st.say, st.slovak, i == 0);
        frame = st.frame;
        link.submitFrame(st.frame);
        anim.schedule(() -> playStep(steps, i + 1, token), st.ms, TimeUnit.MILLISECONDS);
    }

    private void redraw() {
        if (playing) return;
        if (idle) {
            int[] s = size();
            java.util.Calendar c = java.util.Calendar.getInstance();
            frame = IdleClock.clock(s[0], s[1], c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND) % 2 == 0);
            link.submitFrame(frame);
            return;
        }
        if (board.isEmpty()) return;
        int[] s = size();
        String[] labels = null;
        if (System.currentTimeMillis() < flashUntil && flashIdx >= 0 && flashIdx < board.players.size()) {
            labels = new String[board.players.size()];
            labels[flashIdx] = flashText;
        }
        frame = Renderer.render(board, s[0], s[1], labels);
        link.submitFrame(frame);
    }

    /** After (re)connecting: set brightness and repaint, since the panel may have reset. */
    void onLinkReady() {
        link.setBrightness(brightness());
        if (!board.isEmpty() && !playing) {
            int[] s = size();
            frame = Renderer.render(board, s[0], s[1]);
            link.submitFrame(frame);
        }
    }
}
