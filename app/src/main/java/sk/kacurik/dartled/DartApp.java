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
import sk.kacurik.dartled.core.Quips;
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
    private CamServer camServer;
    private final DartTyper typer = new DartTyper();

    private volatile Scoreboard board = new Scoreboard();
    private volatile String boardKey = "";
    private volatile Frame frame;
    private volatile List<String> lastTexts = new ArrayList<>();
    private volatile String lastPackage = "-";
    private volatile long lastSeenAt;

    // Match-moment animations (180, bust, game shot, 26...)
    private final ScheduledExecutorService anim = Executors.newSingleThreadScheduledExecutor();
    private final Random rnd = new Random();
    private final Quips quips = new Quips(rnd);
    private volatile Quips.Quip pendingQuip;   // said after the current animation ends
    private volatile boolean momentInterrupts = true;   // false: the moment's first line queues after the score call
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
        narrator.setUseRecordedVoice(recordedVoice());
        quips.language = "en";
        quips.chance = quipsChance();
        link.setAddress(prefs.getString("address", null));
        anim.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        camServer = new CamServer(this);
        camServer.start();
    }

    public CamServer camServer() { return camServer; }
    public DartTyper typer() { return typer; }

    /** Enter darts from the DartCam phone into DartCounter (presses DartCounter's keypad). */
    public boolean typingOn() { return prefs.getBoolean("typing", true); }
    public void setTypingOn(boolean on) { prefs.edit().putBoolean("typing", on).apply(); camServer.pushState(board); }

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
        if (camServer != null) camServer.pushState(sb);
        typer.onBoardChanged(sb);
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
        playingPrio = 0;
        queued.clear();
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
                else if (!playing && hasMatchDart()) { Renderer.pulse = !Renderer.pulse; redraw(); }
                return;
            }
            if (!idle) { idle = true; idleSeconds = 0; }
            idleSeconds++;
            if (playing) return;
            int[] s = size();
            if (idleSeconds % 30 == 0) {
                play(IdleClock.dartThrow(s[0], s[1], rnd), PRIO_TINY);
                return;
            }
            redraw();
        } catch (Exception ignored) {
            // never let the timer die
        }
    }

    private boolean hasMatchDart() {
        Scoreboard b = board;
        for (Scoreboard.Player p : b.players) if (Renderer.matchDart(b, p)) return true;
        return false;
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
        boolean talk = narratorOn() && !"off".equals(quipsMode());
        Quips.Quip quip = talk && legWon(before, sb) ? quips.afterLeg(sb) : null;
        int[] visit = Moments.lastVisit(before, sb);
        // Call the score first ("Forty five!"), then the joke / comment follows.
        boolean scoreCalled = narratorOn() && callScores() && visit != null && callScore(visit[1], moment);
        if (moment == null && momentsOn()) {
            // Smaller animations. At most two things in a row: the score ticking down, then ONE of
            // match darts / checkout reveal / one-dart finish / turn blink. A dart being entered
            // only gets its little effect when nothing else is playing.
            int[] s = size();
            List<Moments.Step> small = new java.util.ArrayList<>();
            int prio = PRIO_SMALL;
            if (visit != null && visit[1] > 0 && visit[0] < before.players.size()) {
                small.addAll(Moments.scoreRoll(sb, visit[0], before.players.get(visit[0]).score,
                        visitLabel(sb, visit[1]), s[0], s[1]));
            }
            List<Moments.Step> next = Moments.matchDartsMoment(sb, s[0], s[1]);
            if (next == null) next = Moments.checkoutReveal(before, sb, s[0], s[1]);
            if (next == null && Moments.turnChanged(before, sb)) {
                next = Moments.oneDartFinish(sb, s[0], s[1]);
                if (next == null) next = Moments.turnBlink(sb, s[0], s[1]);
            }
            if (next != null) small.addAll(next);
            if (small.isEmpty() && visit == null) {
                List<Moments.Step> dart = Moments.dartFx(before, sb, s[0], s[1]);
                if (dart != null) { small.addAll(dart); prio = PRIO_TINY; }
            }
            if (!small.isEmpty()) {
                if (narratorOn()) speakTurn(before, sb, visit, scoreCalled);
                if (talk && quip == null) quip = quips.afterVisit(before, sb, System.currentTimeMillis());
                startFlash(visit);
                if (play(small, prio)) {
                    pendingQuip = quip;
                    momentInterrupts = !scoreCalled;
                } else if (quip != null) {
                    narrator.say(quip.text, quip.slovak, false);
                }
                return;
            }
        }
        if (moment != null) {
            pendingQuip = quip;
            momentInterrupts = !scoreCalled;
            play(moment, PRIO_BIG);
            return;
        }
        if (narratorOn()) speakTurn(before, sb, visit, scoreCalled);
        if (talk && quip == null) quip = quips.afterVisit(before, sb, System.currentTimeMillis());
        if (quip != null) narrator.say(quip.text, quip.slovak, false);
        startFlash(visit);
        if (!playing) redraw();
    }

    /** "-55" in place of the name for a few seconds after a visit ("+" in Cricket, where points go up). */
    private void startFlash(int[] visit) {
        if (visit == null || visit[1] <= 0) return;
        flashIdx = visit[0];
        flashText = visitLabel(board, visit[1]);
        flashUntil = System.currentTimeMillis() + 3500;
        anim.schedule(() -> {
            if (System.currentTimeMillis() >= flashUntil) redraw();
        }, 3600, TimeUnit.MILLISECONDS);
    }

    private static String visitLabel(Scoreboard sb, int points) {
        return (sb.cricket ? "+" : "-") + points;
    }

    // ------------------------------------------------------------------ narrator

    public boolean narratorOn() { return prefs.getBoolean("narrator", true); }
    public void setNarratorOn(boolean on) { prefs.edit().putBoolean("narrator", on).apply(); if (!on) narrator.stop(); }
    public boolean recordedVoice() { return prefs.getBoolean("recordedVoice", true); }
    public void setRecordedVoice(boolean on) { prefs.edit().putBoolean("recordedVoice", on).apply(); narrator.setUseRecordedVoice(on); }
    /** Hlášky: "en" (on, English only) or "off". Older "mix"/"sk" settings count as on. */
    public String quipsMode() { return "off".equals(prefs.getString("quips", "en")) ? "off" : "en"; }
    public void setQuipsMode(String m) {
        prefs.edit().putString("quips", m).apply();
        quips.language = "en";
    }
    public double quipsChance() { return prefs.getFloat("quipsChance", 0.4f); }
    public void setQuipsChance(double c) { prefs.edit().putFloat("quipsChance", (float) c).apply(); quips.chance = c; }

    /** Someone's legs went up between these two readings of the same match. */
    private static boolean legWon(Scoreboard before, Scoreboard now) {
        if (before.players.size() != now.players.size()) return false;
        for (int i = 0; i < now.players.size(); i++) {
            if (!before.players.get(i).name.equals(now.players.get(i).name)) return false;
            if (now.players.get(i).legs > before.players.get(i).legs) return true;
        }
        return false;
    }

    public boolean callScores() { return prefs.getBoolean("callScores", true); }
    public void setCallScores(boolean on) { prefs.edit().putBoolean("callScores", on).apply(); }
    public boolean callRequire() { return prefs.getBoolean("callRequire", true); }
    public void setCallRequire(boolean on) { prefs.edit().putBoolean("callRequire", on).apply(); }

    public void testVoice() {
        narrator.say("Game on! Dávid to throw first.", false, true);
        narrator.say("One hundred and eighty!", false, false);
        narrator.say("Dávid, you require 40.", false, false);
        narrator.say("Bed and breakfast", false, false);
        narrator.say("Tea and toast!", false, false);
    }

    public boolean voiceReady() { return narrator.isReady(); }

    /** Ordinary visit (no big moment): optionally call the score, and "you require" on a finish. */
    /**
     * Says the visit's score unless the moment already says it (180, ton, bust, no score).
     * @return true if the score was spoken
     */
    private boolean callScore(int points, List<Moments.Step> moment) {
        if (points <= 0) return false;                      // "No score." comes from the moment
        if (moment != null) {
            for (Moments.Step st : moment) {
                if (st.say == null) continue;
                String s = st.say.trim();
                if (s.equals(points + "!") || s.startsWith("One hundred and eighty") || s.equals("Bust!")
                        || s.equals("No score.")) return false;
                break;
            }
        }
        narrator.say(points + "!", false, true);
        return true;
    }

    private void speakTurn(Scoreboard before, Scoreboard now, int[] visit, boolean scoreCalled) {
        if (!callRequire() || now.cricket || before.players.size() != now.players.size()) return;
        if (now.game121) {
            // One player, always at the oche: call what's left after each visit in the round.
            Scoreboard.Player p = now.players.get(0);
            if (visit != null && visit[1] > 0 && !p.checkout.isEmpty() && p.score <= 170) {
                narrator.say("You require " + p.score + ".", false, false);
            }
            return;
        }
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player p = now.players.get(i);
            boolean wasActive = before.players.get(i).active;
            if (p.active && !wasActive && !p.checkout.isEmpty() && p.score <= 170) {
                narrator.say(p.name + ", you require " + p.score + ".", false, false);
                if (!"off".equals(quipsMode())) {
                    Quips.Quip md = quips.matchDarts(now, p);
                    if (md != null) narrator.say(md.text, md.slovak, false);
                }
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
            case "121": play(Moments.start121(new Scoreboard.Player(name, 121, 0, "", true), s[0], s[1])); break;
            case "checked": play(Moments.checkedOut(name, 121, 122, s[0], s[1])); break;
            case "missed": play(Moments.missed121(121, s[0], s[1])); break;
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
            case "fireworks": play(Moments.ton(name, 100, s[0], s[1])); break;
            case "bullhit": play(Moments.testFx("bull", name, s[0], s[1])); break;
            case "t20": play(Moments.testFx("t20", name, s[0], s[1])); break;
            case "fire": play(Moments.testFx("fire", name, s[0], s[1])); break;
            case "best": play(Moments.testFx("best", name, s[0], s[1])); break;
            case "sad": play(Moments.testFx("sad", name, s[0], s[1])); break;
            case "wake": play(Moments.testFx("wake", name, s[0], s[1])); break;
            case "zzz": play(Moments.testFx("zzz", name, s[0], s[1])); break;
            case "double": play(Moments.testFx("double", name, s[0], s[1])); break;
            case "close": play(Moments.soClose(s[0], s[1])); break;
            case "match": play(Moments.testFx("match", name, s[0], s[1])); break;
            case "nine": play(Moments.nineDartWatch(s[0], s[1])); break;
            case "stats": play(Moments.testFx("stats", name, s[0], s[1])); break;
            case "winner": play(Moments.testFx("winner", name, s[0], s[1])); break;
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

    /** How important the running animation is: a newer one only takes over if it matters as much. */
    static final int PRIO_TINY = 1, PRIO_SMALL = 2, PRIO_BIG = 3;
    private volatile int playingPrio;
    /** Big moments that arrived while another big moment was playing; they play next. */
    private final java.util.ArrayDeque<List<Moments.Step>> queued = new java.util.ArrayDeque<>();

    private void play(List<Moments.Step> steps) {
        play(steps, PRIO_BIG);
    }

    /**
     * Starts an animation unless something more important is playing.
     * A big moment never gets cut off: another big one waits in line behind it.
     * @return true if it will be shown
     */
    private synchronized boolean play(List<Moments.Step> steps, int prio) {
        if (steps == null || steps.isEmpty()) return false;
        if (playing) {
            if (prio < playingPrio) return false;
            if (prio == PRIO_BIG && playingPrio == PRIO_BIG) {
                if (queued.size() < 2) queued.add(steps);
                return true;
            }
            if (prio == PRIO_TINY) return false;            // never cut anything for a dart effect
        }
        int token = ++momentToken;
        playing = true;
        playingPrio = prio;
        anim.execute(() -> playStep(steps, 0, token));
        return true;
    }

    private void playStep(List<Moments.Step> steps, int i, int token) {
        if (token != momentToken) return;            // a newer moment took over
        if (i >= steps.size()) {
            Quips.Quip q = pendingQuip;
            pendingQuip = null;
            if (q != null && narratorOn()) narrator.say(q.text, q.slovak, false);
            List<Moments.Step> nextUp;
            synchronized (this) { nextUp = queued.poll(); }
            if (nextUp != null) {
                momentInterrupts = false;
                anim.execute(() -> playStep(nextUp, 0, token));
                return;
            }
            playing = false;
            playingPrio = 0;
            redraw();                                // back to the scoreboard
            return;
        }
        Moments.Step st = steps.get(i);
        if (st.say != null && narratorOn()) {
            narrator.say(st.say, st.slovak, i == 0 && momentInterrupts);
            momentInterrupts = true;   // only the first line of this moment may follow the score call
        }
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
