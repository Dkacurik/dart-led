package sk.kacurik.dartled.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Short animations for big match moments (180, ton+, bust, game shot, 26 and the winner).
 * Each animation is a list of pictures with how long to show each one; the app plays them
 * and then goes back to the normal scoreboard.
 */
public final class Moments {

    public static final class Step {
        public final Frame frame;
        public final int ms;
        /** What the narrator says when this step starts (null = nothing). */
        public final String say;
        public final boolean slovak;

        Step(Frame frame, int ms) {
            this(frame, ms, null, false);
        }

        Step(Frame frame, int ms, String say, boolean slovak) {
            this.frame = frame;
            this.ms = ms;
            this.say = say;
            this.slovak = slovak;
        }
    }

    /** Puts a spoken line on the first step of an animation. */
    static List<Step> withSay(List<Step> s, String say, boolean slovak) {
        if (s.isEmpty() || say == null) return s;
        Step first = s.get(0);
        s.set(0, new Step(first.frame, first.ms, say, slovak));
        return s;
    }

    /** Spoken versions of jokes that a voice would read badly from the display text. */
    private static final java.util.Map<String, String> SPOKEN = new java.util.HashMap<>();
    /** Jokes in Slovak (read with a Slovak voice, with proper accents). */
    private static final java.util.Map<String, String> SLOVAK = new java.util.HashMap<>();

    static {
        SPOKEN.put("6-7! 6-7!", "Six seven! Six seven!");
        SPOKEN.put("IT'S 6-7 BRO!", "It's six seven, bro!");
        SPOKEN.put("67?! SIX SEVEN", "Sixty seven? Six seven!");
        SPOKEN.put("SIIIX SEVENNN!", "Siiix, sevennnn!");
        SPOKEN.put("S D T SHANGHAI!", "Single, double, treble. Shanghai!");
        SPOKEN.put("20 20 5 CLASSIC!", "Twenty, twenty, five. Classic!");
        SPOKEN.put("20 20 1 CLASSIC!", "Twenty, twenty, one. Classic!");
        SPOKEN.put("20 1 5 SO CLOSE!", "Twenty, one, five. So close!");
        SPOKEN.put("NICE.", "Nice.");
        SPOKEN.put("HEHE 69", "Hehe. Sixty nine.");
        SPOKEN.put("69 NICE!", "Sixty nine. Nice!");
        SPOKEN.put("OUCH! 26...", "Ouch! Twenty six.");
        SLOVAK.put("RANAJKY PODANE!", "Raňajky podané!");
        SLOVAK.put("ZASA VLAVO!", "Zasa vľavo!");
        SLOVAK.put("ZASA VPRAVO!", "Zasa vpravo!");
        SLOVAK.put("DOBRE RANO!", "Dobré ráno!");
        SLOVAK.put("PRAZENICA HOTOVA!", "Praženica hotová!");
        SLOVAK.put("CHLIEB S MASLOM", "Chlieb s maslom.");
        SLOVAK.put("KAVICKU? POTREBUJES", "Kávičku? Potrebuješ!");
        SLOVAK.put("ZASA 26?!", "Zasa dvadsaťšesť?!");
        SLOVAK.put("VSETKY SPOLU!", "Všetky spolu!");
        SLOVAK.put("NAJS!", "Najs!");
    }

    /** "BED & BREAKFAST" -> "Bed and breakfast" (all-caps text can be spelled out by voices). */
    static String spoken(String display) {
        String t = display.trim();
        if (SPOKEN.containsKey(t)) return SPOKEN.get(t);
        t = t.replace("&", "and").toLowerCase(java.util.Locale.ROOT);
        StringBuilder b = new StringBuilder(t);
        boolean cap = true;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (cap && Character.isLetter(c)) { b.setCharAt(i, Character.toUpperCase(c)); cap = false; }
            else if (c == '.' || c == '?' || c == '!') cap = true;
        }
        return b.toString();
    }

    private static final int RED = 0xFF3020;
    private static final int GOLD = 0xFFC814;
    private static final int GREEN = 0x40FF40;
    private static final int CYAN = 0x28C8FF;
    private static final int PINK = 0xFF40C0;
    private static final int WHITE = 0xFFFFFF;

    /** Two lines each, max 10 letters per line so they fit a 64-pixel-wide panel. */
    static final String[][] TWENTY_SIX = {
            {"BED &", "BREAKFAST"},
            {"TEA AND", "TOAST"},
            {"FULL", "ENGLISH!"},
            {"20 1 5", "SO CLOSE!"},
            {"AIM?", "WHAT AIM?"},
            {"26 AGAIN?", "CLASSIC!"},
            {"PUB", "CHAMPION!"},
            {"OUCH!", "26..."},
            {"ROOM", "SERVICE!"},
            // more hotel & breakfast jokes
            {"LATE", "CHECKOUT?"},
            {"BOOKING", "CONFIRMED"},
            {"CHECK-IN", "COMPLETE!"},
            {"WAKE-UP", "CALL!"},
            {"ROOM 26,", "PLEASE"},
            {"5 STARS", "FOR 26!"},
            {"HONEYMOON", "SUITE"},
            {"BREAKFAST", "IN BED!"},
            {"KETTLE'S", "ON!"},
            {"BACON", "AND EGGS"},
            {"SUNNY SIDE", "UP!"},
            {"EGGS", "BENEDICT"},
            {"CROISSANT", "ANYONE?"},
            {"COFFEE", "PLEASE!"},
            {"PORRIDGE", "AGAIN?"},
            {"SNOOZE", "BUTTON!"},
            {"WIDE", "LEFT!"},
            {"NO 20 FOR", "YOU!"},
            {"THE MAGIC", "26!"},
            {"MAMA", "MIA!"},
    };

    /** 45 = the classic 20, 20, 5. */
    static final String[][] FORTY_FIVE = {
            {"WIDE", "LEFT!"},
            {"20 20 5", "CLASSIC!"},
            {"5 SAYS", "HELLO!"},
            {"SO CLOSE", "TO 60"},
            {"LEFT", "TURN!"},
            {"THE 5 IS", "LONELY"},
            {"WRONG", "BED!"},
            {"OOPS!", "WIDE LEFT"},
    };

    /** 41 = 20, 20, 1. */
    static final String[][] FORTY_ONE = {
            {"WIDE", "RIGHT!"},
            {"20 20 1", "CLASSIC!"},
            {"1 IS THE", "LONELIEST"},
            {"RIGHT", "TURN!"},
            {"ONE MORE", "TO THE 1"},
            {"OOPS!", "WIDE RIGHT"},
            {"SO CLOSE", "TO 60"},
    };

    static final String[][] SIXTY_NINE = {
            {"NICE.", ""},
            {"69", "NICE!"},
            {"NICE", "NICE NICE"},
            {"HEHE", "69"},
            {"VERY", "NICE!"},
    };

    static final String[][] SIXTY_SEVEN = {
            {"SIX", "SEVEN!"},
            {"6-7!", "6-7!"},
            {"SIIIX", "SEVENNN!"},
            {"67?!", "SIX SEVEN"},
            {"IT'S 6-7", "BRO!"},
            {"SIX", "SEVEN..."},
    };

    /** Three darts in the same bed. */
    static final String[][] SAME_BED = {
            {"ROBIN", "HOOD!"},
            {"SAME", "BED!"},
            {"CROWDED", "BED!"},
            {"NO ROOM", "IN BED!"},
            {"TRIPLETS!", ""},
            {"COSY IN", "THERE!"},
            {"BUNK", "BEDS!"},
    };

    /** 1, 1, 1: right next to the 20... */
    static final String[][] ONE_ONE_ONE = {
            {"GOOD", "GROUPING!"},
    };

    /** Single, double and treble of the same number. */
    static final String[][] SHANGHAI = {
            {"SHANGHAI!", ""},
            {"S D T", "SHANGHAI!"},
            {"FULL", "HOUSE!"},
    };

    private static int lastJoke = -1;
    private static final java.util.Map<Integer, Integer> lastPick = new java.util.HashMap<>();

    private Moments() {}

    /**
     * Compares two readings of the match screen and returns the animation for what just
     * happened, or null if nothing special happened.
     */
    public static List<Step> forChange(Scoreboard before, Scoreboard now, int w, int h, Random rnd) {
        if (before == null || now == null) return null;
        if (now.bullUp) {
            // Everyone has thrown now (and not before): announce who throws first.
            int best = closest(now);
            if (best >= 0 && closest(before) < 0) return throwsFirst(now.bullNames.get(best), w, h);
            return null;
        }
        if (now.winner != null) {
            if (before.winner != null) return null;
            // The match-winning visit: was it a high finish?
            List<Step> s = new ArrayList<>();
            for (Scoreboard.Player a : before.players) {
                if (a.active && ScreenParser.plain(a.name).equals(ScreenParser.plain(now.winner))
                        && a.score >= 100 && a.score <= 170) {
                    s.addAll(highFinish(a.score, w, h));
                }
            }
            s.addAll(winner(now, w, h));
            return s;
        }
        if (now.players.isEmpty()) return null;
        if (now.game121) return for121(before, now, w, h, rnd);
        if (isFreshStart(now) && (before.players.isEmpty() || before.bullUp || before.winner != null
                || !sameNames(before, now))) {
            resetMatch();
            return gameOn(now, w, h);
        }
        if (before.players.size() != now.players.size() || !sameNames(before, now)) return null; // new match
        // A leg won beats everything else.
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player a = before.players.get(i), b = now.players.get(i);
            if (b.legs == a.legs + 1) {
                List<Step> s = new ArrayList<>();
                if (a.score >= 100 && a.score <= 170) s.addAll(highFinish(a.score, w, h));
                s.addAll(gameShot(b.name, w, h));
                s.addAll(legsScore(now, w, h));
                s.addAll(legStats(now, w, h));
                zeroRun.clear();
                return s;
            }
        }
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player a = before.players.get(i), b = now.players.get(i);
            if (b.legs != a.legs) continue;

            int visit;
            if (a.darts >= 0 && b.darts >= 0) {
                if (b.darts <= a.darts) continue;                 // no new visit (or an undo)
                visit = b.lastScore >= 0 ? b.lastScore : a.score - b.score;
            } else {
                if (b.score >= a.score) continue;
                visit = a.score - b.score;
            }
            return visitMoment(before, a, b, visit, w, h, rnd);
        }
        return null;
    }

    // Per-match memory for streaks: visits of 60+ in a row, no-scores in a row, best visit.
    private static final java.util.Map<String, Integer> fireRun = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> zeroRun = new java.util.HashMap<>();
    private static int matchBest = 0;

    static void resetMatch() {
        fireRun.clear();
        zeroRun.clear();
        matchBest = 0;
    }

    /** The moment for one visit (a = before, b = after), plus streak extras. */
    static List<Step> visitMoment(Scoreboard before, Scoreboard.Player a, Scoreboard.Player b, int visit,
                                  int w, int h, Random rnd) {
        boolean bust = b.lastBust
                || (b.score == a.score && b.lastScore > 0
                    && (b.lastScore > a.score || a.score - b.lastScore == 1));
        int fire = visit >= 60 && !bust ? fireRun.getOrDefault(b.name, 0) + 1 : 0;
        fireRun.put(b.name, fire);
        int zeros = visit == 0 && !bust ? zeroRun.getOrDefault(b.name, 0) + 1 : 0;
        zeroRun.put(b.name, zeros);
        boolean best = !bust && visit >= 100 && visit > matchBest && matchBest >= 100;
        if (!bust) matchBest = Math.max(matchBest, visit);
        boolean onDouble = a.active && !a.checkout.isEmpty() && a.score <= 50;

        List<Step> m = baseMoment(before, a, b, visit, bust, onDouble, zeros, w, h, rnd);
        List<Step> out = m == null ? new ArrayList<>() : new ArrayList<>(m);
        if (best) out.addAll(withSay(Fx.bestVisit(visit, w, h), "Best visit of the match!", false));
        if (visit == 180 && b.darts == 6 && (b.score == 141 || b.score == 341 || b.score == 641)) {
            out.addAll(nineDartWatch(w, h));
        }
        if (fire == 3 || fire == 5) {
            out.addAll(withSay(Fx.onFire(b.name, w, h, rnd), b.name + " is on fire!", false));
        }
        if (out.isEmpty() && a.darts >= 0 && a.darts < 30 && b.darts >= 30) {
            out.addAll(withSay(Fx.zzz(b.darts, w, h), "This leg is taking a while.", false));
        }
        return out.isEmpty() ? null : out;
    }

    private static List<Step> baseMoment(Scoreboard before, Scoreboard.Player a, Scoreboard.Player b, int visit,
                                         boolean bust, boolean onDouble, int zeros, int w, int h, Random rnd) {
        // Bust = the visit scored more than was left, or would leave 1 (you must finish on
        // a double). The remaining score then stays the same. Missed darts are NOT a bust.
        if (bust) return bust(b.name, w, h);
        if (visit == 0 && b.score == a.score) {
            if (zeros >= 3) return withSay(Fx.shake("WAKE UP!", RED, WHITE, w, h), "Wake up!", false);
            if (onDouble) return soClose(w, h);
            return noScore(b.name, w, h);
        }
        if (visit == 180) return oneEighty(w, h);

        // Dart-by-dart jokes, when we saw the darts being entered.
        List<String> darts = visitDarts(a, before, visit);
        boolean dartsKnown = darts != null;
        if (dartsKnown) {
            if (isVisit(darts, "S1", "S1", "S1")) return scoreJoke(3, ONE_ONE_ONE, CYAN, GOLD, rnd, w, h);
            if (sameBed(darts)) return scoreJoke(visit, SAME_BED, GOLD, GREEN, rnd, w, h);
            if (shanghai(darts)) return scoreJoke(visit, SHANGHAI, RED, GOLD, rnd, w, h);
            if (isVisit(darts, "S20", "S20", "S5")) return scoreJoke(45, FORTY_FIVE, CYAN, PINK, rnd, w, h);
            if (isVisit(darts, "S20", "S20", "S1")) return scoreJoke(41, FORTY_ONE, CYAN, PINK, rnd, w, h);
        }
        // Missed the double, still on a finish.
        if (onDouble && b.score > 0) return soClose(w, h);
        // Under 10 while still far from a finish (more than 50 left before the visit).
        if (visit >= 1 && visit <= 9 && a.score > 50) {
            return scoreJoke(visit, new String[][]{{"AIM?", "WHAT AIM?"}}, CYAN, PINK, rnd, w, h);
        }
        if (visit == 26) return twentySix(rnd, w, h);
        if (visit == 45 && !dartsKnown) return scoreJoke(45, FORTY_FIVE, CYAN, PINK, rnd, w, h);
        if (visit == 41 && !dartsKnown) return scoreJoke(41, FORTY_ONE, CYAN, PINK, rnd, w, h);
        if (visit == 69) return scoreJoke(69, SIXTY_NINE, PINK, GOLD, rnd, w, h);
        if (visit == 67) return sixSeven(rnd, w, h);
        if (visit >= 10 && visit <= 19 && a.score > 50) {
            String[] sad = {"MEH.", "OH DEAR", "SAD DART", "UNLUCKY", "OOF."};
            String line = sad[rnd.nextInt(sad.length)];
            return withSay(Fx.sadFace(line, w, h), spoken(line), false);
        }
        if (visit >= 100 && visit < 180) return ton(b.name, visit, w, h);
        return null;
    }

    /** Missed the double: "SO CLOSE!" shakes its head. */
    public static List<Step> soClose(int w, int h) {
        return withSay(Fx.shake("SO CLOSE!", GOLD, WHITE, w, h), "So close!", false);
    }

    /** Two 180s to start a leg: nine-dart watch! */
    public static List<Step> nineDartWatch(int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Frame f = lines(w, h, "9-DART", i % 2 == 0 ? GOLD : RED, "WATCH!", i % 2 == 0 ? RED : GOLD);
            if (i % 2 == 0) Fx.sides(f, GOLD);
            s.add(new Step(f, 300));
        }
        s.add(new Step(lines(w, h, "9-DART", GOLD, "WATCH!", RED), 1500));
        return withSay(s, "Nine dart watch!", false);
    }

    /** After a leg: everyone's average scrolls past. */
    public static List<Step> legStats(Scoreboard now, int w, int h) {
        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Scoreboard.Player p : now.players) {
            if (p.avg == null) continue;
            parts.add(Renderer.shortName(p.name));
            colors.add(WHITE);
            parts.add("AVG " + p.avg);
            colors.add(GOLD);
            parts.add("-");
            colors.add(0x606060);
        }
        if (parts.isEmpty()) return new ArrayList<>();
        parts.remove(parts.size() - 1);
        colors.remove(colors.size() - 1);
        int[] c = new int[colors.size()];
        for (int i = 0; i < c.length; i++) c[i] = colors.get(i);
        return Fx.marquee(parts.toArray(new String[0]), c, w, h);
    }

    /**
     * The score ticks down to its new value after a visit, with "-55" in place of the name.
     * Empty when the change can't be seen on the panel (e.g. the other player's checkout is shown).
     */
    public static List<Step> scoreRoll(Scoreboard now, int idx, int from, String label, int w, int h) {
        List<Step> s = new ArrayList<>();
        Scoreboard.Player p = now.players.get(idx);
        int to = p.score;
        String[] labels = new String[now.players.size()];
        labels[idx] = label;
        Frame last = Renderer.render(now, w, h, labels);
        Frame first = Renderer.render(now.withPlayer(idx, from, p.checkout), w, h, labels);
        if (java.util.Arrays.equals(first.px, last.px)) return s;
        // While it counts down, the player who threw stays bright (the turn has already passed).
        Scoreboard lit = now.withActive(idx);
        double[] ease = {0, 0.35, 0.6, 0.78, 0.9, 0.97, 1};
        for (double e : ease) {
            int v = (int) Math.round(from + (to - from) * e);
            s.add(new Step(Renderer.render(lit.withPlayer(idx, v, ""), w, h, labels), e == 1 ? 350 : 80));
        }
        return s;
    }

    /** Legs that have been announced as "on a finish" (once per player per leg). */
    private static final java.util.Set<String> finishShown = new java.util.HashSet<>();

    /**
     * A player at the oche can finish for the first time this leg: the checkout darts appear
     * one by one and the finishing double blinks.
     */
    public static List<Step> checkoutReveal(Scoreboard before, Scoreboard now, int w, int h) {
        if (now.game121 || now.cricket || h >= 24) return null;
        int legs = 0;
        for (Scoreboard.Player p : now.players) legs += p.legs;
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player p = now.players.get(i);
            if (!p.active || p.checkout.isEmpty()) continue;
            String key = p.name + "|" + legs;
            if (finishShown.contains(key)) return null;
            finishShown.add(key);
            boolean wasShown = false;
            for (Scoreboard.Player b : before.players) {
                if (b.name.equals(p.name) && b.active && !b.checkout.isEmpty()) wasShown = true;
            }
            if (wasShown) return null;
            String[] darts = p.checkout.trim().split("\\s+");
            List<Step> s = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                s.add(new Step(lines(w, h, "CHECKOUT", GREEN, Integer.toString(p.score), GOLD), 300));
                s.add(new Step(new Frame(w, h), 100));
            }
            for (int k = 1; k <= darts.length; k++) {
                s.add(new Step(Renderer.render(now.withPlayer(i, p.score, hideFrom(darts, k)), w, h), 280));
            }
            for (int k = 0; k < 3; k++) {
                s.add(new Step(Renderer.render(now.withPlayer(i, p.score, hideFrom(darts, darts.length - 1)), w, h), 160));
                s.add(new Step(Renderer.render(now, w, h), 260));
            }
            return s;
        }
        return null;
    }

    /** The checkout with darts from index k on kept in place but hidden: "T20 ~T20 ~BULL". */
    private static String hideFrom(String[] darts, int k) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < darts.length; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(i >= k ? "~" : "").append(darts[i]);
        }
        return b.toString();
    }

    /** For the test buttons in the app. */
    public static List<Step> testFx(String kind, String name, int w, int h) {
        Random r = new Random();
        switch (kind) {
            case "bull": return withSay(Fx.bull(w, h), "Bull!", false);
            case "t20": return Fx.ping("T20", w, h);
            case "fire": return withSay(Fx.onFire(name, w, h, r), name + " is on fire!", false);
            case "best": return withSay(Fx.bestVisit(140, w, h), "Best visit of the match!", false);
            case "sad": return withSay(Fx.sadFace("OH DEAR", w, h), "Oh dear.", false);
            case "wake": return withSay(Fx.shake("WAKE UP!", RED, WHITE, w, h), "Wake up!", false);
            case "zzz": return withSay(Fx.zzz(30, w, h), "This leg is taking a while.", false);
            case "double": return Fx.doubleTarget("D16", w, h);
            case "match": return Fx.matchDarts(name, w, h);
            case "stats": {
                Scoreboard sb = Scoreboard.demo();
                sb.players.get(0).avg = "85.33";
                sb.players.get(1).avg = "62.10";
                return legStats(sb, w, h);
            }
            default: {
                Scoreboard sb = new Scoreboard();
                sb.winner = name;
                sb.winnerLegs = 2;
                sb.loserLegs = 1;
                return winner(sb, w, h);
            }
        }
    }

    /** Index of the player at the oche, or -1. */
    static int activeIndex(Scoreboard sb) {
        for (int i = 0; i < sb.players.size(); i++) if (sb.players.get(i).active) return i;
        return -1;
    }

    /** The turn passed to another player (same match). */
    public static boolean turnChanged(Scoreboard before, Scoreboard now) {
        if (now.game121 || before.players.size() != now.players.size() || !sameNames(before, now)) return false;
        int a = activeIndex(before), b = activeIndex(now);
        return b >= 0 && a >= 0 && a != b;
    }

    /** The new thrower's name blinks twice so everyone sees whose turn it is. */
    public static List<Step> turnBlink(Scoreboard now, int w, int h) {
        List<Step> s = new ArrayList<>();
        int idx = activeIndex(now);
        if (idx < 0) return s;
        String[] hide = new String[now.players.size()];
        hide[idx] = " ";
        Frame off = Renderer.render(now, w, h, hide);
        Frame on = Renderer.render(now, w, h);
        for (int i = 0; i < 2; i++) {
            s.add(new Step(off, 140));
            s.add(new Step(on, 200));
        }
        return s;
    }

    private static final java.util.Set<String> matchDartsShown = new java.util.HashSet<>();

    /** First time in a leg that the player at the oche has darts for the match. */
    public static List<Step> matchDartsMoment(Scoreboard now, int w, int h) {
        int idx = activeIndex(now);
        if (idx < 0 || !Renderer.matchDart(now, now.players.get(idx))) return null;
        int legs = 0;
        for (Scoreboard.Player p : now.players) legs += p.legs;
        Scoreboard.Player p = now.players.get(idx);
        if (!matchDartsShown.add(p.name + "|" + legs)) return null;
        return Fx.matchDarts(p.name, w, h);
    }

    /** A one-dart finish (D16, BULL) for the player who just came to the oche. */
    public static List<Step> oneDartFinish(Scoreboard now, int w, int h) {
        int idx = activeIndex(now);
        if (idx < 0 || now.cricket || now.game121) return null;
        String co = now.players.get(idx).checkout.trim().toUpperCase(java.util.Locale.ROOT);
        if (co.isEmpty() || co.contains(" ") || !(co.startsWith("D") || co.startsWith("BULL"))) return null;
        return Fx.doubleTarget(co, w, h);
    }

    /** A dart was just entered: T20 pings, a bull lights up rings. */
    public static List<Step> dartFx(Scoreboard before, Scoreboard now, int w, int h) {
        if (now.entered.size() != before.entered.size() + 1) return null;
        for (int i = 0; i < before.entered.size(); i++) if (!before.entered.get(i).equals(now.entered.get(i))) return null;
        String d = now.entered.get(now.entered.size() - 1);
        if (d.equals("BULL") || d.equals("DBULL") || d.equals("D25")) return withSay(Fx.bull(w, h), "Bull!", false);
        if (d.equals("T20")) return Fx.ping("T20", w, h);
        return null;
    }

    /** Legs score after a leg in a two-player match: "LEGS" / "1 - 0". */
    public static List<Step> legsScore(Scoreboard now, int w, int h) {
        List<Step> s = new ArrayList<>();
        if (now.players.size() != 2) return s;
        int a = now.players.get(0).legs, b = now.players.get(1).legs;
        String score = a + " - " + b;
        s.add(new Step(lines(w, h, "LEGS", WHITE, "", GOLD), 300));
        for (int k = 0; k < 3; k++) {
            s.add(new Step(lines(w, h, "LEGS", WHITE, score, k % 2 == 0 ? GOLD : CYAN), 300));
        }
        String n1 = Renderer.shortName(now.players.get(0).name), n2 = Renderer.shortName(now.players.get(1).name);
        s.add(new Step(lines(w, h, n1 + " " + a, a >= b ? GOLD : WHITE, n2 + " " + b, b >= a ? GOLD : WHITE), 2000));
        return s;
    }

    /**
     * 121 Checkout. A new round means the last one is over: checked out (the streak went up)
     * or not (back to the safehouse). Inside a round, visits get the usual X01 moments.
     */
    /** Last 121 screen seen (survives pop-ups in between) and the current round's target. */
    private static Scoreboard last121;
    private static int target121 = -1;

    static List<Step> for121(Scoreboard before, Scoreboard now, int w, int h, Random rnd) {
        Scoreboard prev = before.game121 && !before.players.isEmpty() ? before : last121;
        last121 = now;
        Scoreboard.Player b = now.players.get(0);
        int target = target121;
        if (now.dartsLeft == 9) target121 = b.score;           // a round starts on its target
        if (prev == null || !sameNames(prev, now)) {
            return now.round <= 1 && now.dartsLeft == 9 ? start121(b, w, h) : null;
        }
        Scoreboard.Player a = prev.players.get(0);
        if (roundOver(prev, now)) {
            boolean won = (now.streak > prev.streak && now.streak > 0)
                    || (now.highest > prev.highest && prev.highest > 0)
                    || (target > 0 && b.score > target);
            int done = target > 0 ? target : b.score - 1;
            return won ? checkedOut(b.name, done, b.score, w, h) : missed121(b.score, w, h);
        }
        if (a.darts < 0 || b.darts <= a.darts) return null;
        int visit = a.score - b.score;
        if (visit == 0) {
            int sum = 0;
            for (String x : prev.entered) sum += Math.max(0, ScreenParser.dartValue(x));
            return sum > 0 ? bust(b.name, w, h) : noScore(b.name, w, h);
        }
        if (visit < 0) return null;
        if (visit == 180) return oneEighty(w, h);
        List<String> darts = visitDarts(a, prev, visit);
        if (darts != null) {
            if (isVisit(darts, "S1", "S1", "S1")) return scoreJoke(3, ONE_ONE_ONE, CYAN, GOLD, rnd, w, h);
            if (sameBed(darts)) return scoreJoke(visit, SAME_BED, GOLD, GREEN, rnd, w, h);
            if (shanghai(darts)) return scoreJoke(visit, SHANGHAI, RED, GOLD, rnd, w, h);
        }
        if (visit == 26) return twentySix(rnd, w, h);
        if (visit == 69) return scoreJoke(69, SIXTY_NINE, PINK, GOLD, rnd, w, h);
        if (visit == 67) return sixSeven(rnd, w, h);
        if (visit >= 100) return ton(b.name, visit, w, h);
        return null;
    }

    /**
     * A new round started: the round number went up, or the darts left came back to 9,
     * or (checked out with the first visit) the target changed while 9 darts were left.
     */
    static boolean roundOver(Scoreboard prev, Scoreboard now) {
        if (now.round > 0 && prev.round > 0 && now.round != prev.round) return true;
        if (now.dartsLeft == 9 && prev.dartsLeft >= 0 && prev.dartsLeft < 9) return true;
        return now.dartsLeft == 9 && prev.dartsLeft == 9
                && now.players.get(0).score > prev.players.get(0).score;
    }

    public static List<Step> start121(Scoreboard.Player p, int w, int h) {
        List<Step> s = new ArrayList<>();
        int[] colors = {GOLD, RED, GREEN, CYAN};
        for (int i = 0; i < 4; i++) {
            s.add(new Step(lines(w, h, "121", colors[i], "CHECKOUT", WHITE), 300));
            s.add(new Step(new Frame(w, h), 100));
        }
        s.add(new Step(lines(w, h, Renderer.shortName(p.name), WHITE, "9 DARTS", GOLD), 2000));
        return withSay(s, "One twenty one checkout! " + p.name + ", nine darts. Game on!", false);
    }

    /**
     * Round checked out, animated: "CHECKED OUT!" flashes, the trophy rises and gets a shine,
     * the name types in, "121 OUT!" slides in, then sparkles; then the next target.
     */
    public static List<Step> checkedOut(String name, int done, int next, int w, int h) {
        String line = done + " OUT!";
        int nameLen = Math.min(Renderer.shortName(name).length(), Renderer.MAX_NAME);
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            s.add(new Step(lines(w, h, "CHECKED", i % 2 == 0 ? GOLD : GREEN, "OUT!", i % 2 == 0 ? GREEN : GOLD), 350));
            s.add(new Step(new Frame(w, h), 100));
        }
        // The trophy rises from the bottom.
        for (int dy : new int[]{h, h * 3 / 4, h / 2, h / 3, h / 5, 2, -1, 0}) {
            s.add(new Step(win(name, line, w, h, dy, 0, w * 4, GOLD), 90));
        }
        // A shine sweeps across it.
        int[] box = Renderer.trophyBox(name, line, w, h);
        for (int k = 0; k <= box[2] + box[3]; k += 2) {
            Frame f = win(name, line, w, h, 0, 0, w * 4, GOLD);
            for (int y = box[1]; y < box[1] + box[3]; y++) {
                for (int x = box[0]; x < box[0] + box[2]; x++) {
                    int d = (x - box[0]) + (y - box[1]);
                    if ((d == k || d == k + 1) && f.px[y * w + x] != 0) f.px[y * w + x] = WHITE;
                }
            }
            s.add(new Step(f, 70));
        }
        // The name types in, one letter at a time.
        for (int n = 1; n <= nameLen; n++) s.add(new Step(win(name, line, w, h, 0, n, w * 4, GOLD), 110));
        // "121 OUT!" slides in from the right.
        for (int dx : new int[]{w, w * 2 / 3, w / 2, w / 3, w / 5, w / 10, 2, 0}) {
            s.add(new Step(win(name, line, w, h, 0, nameLen, dx, WHITE), 70));
        }
        // Sparkles around it while the result changes colour.
        Random r = new Random(done);
        int[] colors = {GOLD, WHITE, GREEN, CYAN, PINK, GOLD};
        for (int i = 0; i < 12; i++) {
            Frame f = win(name, line, w, h, 0, nameLen, 0, colors[i % colors.length]);
            sparkle(f, r, 2 + w * h / 300);
            s.add(new Step(f, 170));
        }
        Frame end = win(name, line, w, h, 0, nameLen, 0, WHITE);
        s.add(new Step(end, 2000));
        s.add(new Step(lines(w, h, "NEXT", WHITE, Integer.toString(next), GOLD), 2200));
        return withSay(s, "Checked out! " + name + " takes " + done + "! Next target, " + next + ".", false);
    }

    private static Frame win(String name, String line, int w, int h, int trophyDy, int nameChars, int lineDx, int lineColor) {
        Frame f = new Frame(w, h);
        Renderer.winnerParts(f, name, line, w, h, trophyDy, nameChars, lineDx, lineColor, GOLD, GOLD);
        return f;
    }

    /** Twinkles: small stars on empty pixels (a bright centre with dim arms). */
    private static void sparkle(Frame f, Random r, int count) {
        for (int n = 0, tries = 0; n < count && tries < 200; tries++) {
            int x = r.nextInt(f.w), y = r.nextInt(f.h);
            if (!emptyAround(f, x, y)) continue;
            int c = r.nextBoolean() ? WHITE : GOLD;
            f.px[y * f.w + x] = c;
            if (r.nextBoolean()) {
                int dim = ((c >> 17) & 0x7F) << 16 | ((c >> 9) & 0x7F) << 8 | ((c >> 1) & 0x7F);
                int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] a : arms) {
                    int ax = x + a[0], ay = y + a[1];
                    if (ax >= 0 && ay >= 0 && ax < f.w && ay < f.h) f.px[ay * f.w + ax] = dim;
                }
            }
            n++;
        }
    }

    private static boolean emptyAround(Frame f, int x, int y) {
        for (int yy = y - 2; yy <= y + 2; yy++) for (int xx = x - 2; xx <= x + 2; xx++) {
            if (xx >= 0 && yy >= 0 && xx < f.w && yy < f.h && f.px[yy * f.w + xx] != 0) return false;
        }
        return true;
    }

    /** Round not finished: back to the safehouse. */
    public static List<Step> missed121(int back, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            s.add(new Step(lines(w, h, "NOT THIS", RED, "TIME", WHITE), 350));
            s.add(new Step(new Frame(w, h), 120));
        }
        s.add(new Step(lines(w, h, "BACK TO", WHITE, Integer.toString(back), CYAN), 2200));
        return withSay(s, "Not this time. Back to " + back + ".", false);
    }

    /**
     * The three darts of the visit that was just submitted, from the input bar seen just
     * before. If the last dart wasn't seen, it's worked out from the visit total.
     */
    static List<String> visitDarts(Scoreboard.Player a, Scoreboard before, int visit) {
        if (!a.active || before.entered.isEmpty()) return null;
        List<String> d = new ArrayList<>(before.entered);
        int sum = 0;
        for (String x : d) {
            int v = ScreenParser.dartValue(x);
            if (v < 0) return null;
            sum += v;
        }
        if (d.size() == 3) return sum == visit ? d : null;
        int rest = visit - sum;
        if (d.size() == 2 && rest >= 0 && rest <= 20) {
            d.add(rest == 0 ? "MISS" : "S" + rest);
            return d;
        }
        return null;
    }

    /** Which player threw a visit just now, and what it was worth: {player index, points}. */
    public static int[] lastVisit(Scoreboard before, Scoreboard now) {
        if (before == null || now == null || before.players.size() != now.players.size()
                || now.players.isEmpty() || !sameNames(before, now)) return null;
        if (now.game121 && (!before.game121 || roundOver(before, now))) return null; // round over, not a visit
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player a = before.players.get(i), b = now.players.get(i);
            if (b.legs != a.legs) return null;
            if (now.cricket) {
                if (b.score > a.score) return new int[]{i, b.score - a.score};
                continue;
            }
            if (a.darts >= 0 && b.darts >= 0) {
                if (b.darts > a.darts) return new int[]{i, b.lastScore >= 0 ? b.lastScore : a.score - b.score};
            } else if (b.score < a.score) {
                return new int[]{i, a.score - b.score};
            }
        }
        return null;
    }

    static boolean sameNames(Scoreboard a, Scoreboard b) {
        if (a.players.size() != b.players.size()) return false;
        for (int i = 0; i < a.players.size(); i++) {
            if (!a.players.get(i).name.equals(b.players.get(i).name)) return false;
        }
        return true;
    }

    /** A brand-new match: no legs won, everyone on the same score, nothing thrown yet. */
    static boolean isFreshStart(Scoreboard sb) {
        int score = sb.players.get(0).score;
        for (Scoreboard.Player p : sb.players) {
            if (p.legs != 0 || p.score != score || p.darts > 0) return false;
        }
        return true;
    }

    /** Index of the player closest to the bull once everyone has thrown, else -1. */
    static int closest(Scoreboard sb) {
        if (!sb.bullUp || sb.bullNames.isEmpty()) return -1;
        int best = -1;
        for (int i = 0; i < sb.bullDist.size(); i++) {
            Double d = sb.bullDist.get(i);
            if (d == null) return -1;
            if (best < 0 || d < sb.bullDist.get(best)) best = i;
        }
        return best;
    }

    static boolean sameBed(List<String> d) {
        return d.size() == 3 && d.get(0).equals(d.get(1)) && d.get(1).equals(d.get(2))
                && ScreenParser.dartValue(d.get(0)) > 0;
    }

    /** One single, one double and one treble of the same number (in any order). */
    static boolean shanghai(List<String> d) {
        if (d.size() != 3) return false;
        java.util.Set<Character> kinds = new java.util.HashSet<>();
        String number = null;
        for (String x : d) {
            if (!x.matches("^[SDT]\\d{1,2}$")) return false;
            kinds.add(x.charAt(0));
            String n = x.substring(1);
            if (number == null) number = n;
            else if (!number.equals(n)) return false;
        }
        return kinds.size() == 3;
    }

    /** True if the darts are exactly these three, in any order. */
    static boolean isVisit(List<String> d, String... want) {
        List<String> a = new ArrayList<>(d), b = new ArrayList<>(java.util.Arrays.asList(want));
        java.util.Collections.sort(a);
        java.util.Collections.sort(b);
        return a.equals(b);
    }

    // ---------------------------------------------------------------- animations

    public static List<Step> oneEighty(int w, int h) {
        List<Step> s = new ArrayList<>();
        int[] colors = {RED, GOLD, GREEN, CYAN, PINK, WHITE};
        for (int i = 0; i < 10; i++) {
            s.add(new Step(i % 2 == 0 ? big(w, h, "180!", colors[(i / 2) % colors.length]) : new Frame(w, h), i % 2 == 0 ? 320 : 120));
        }
        s.add(new Step(lines(w, h, "ONE-EIGHTY", GOLD, "MAXIMUM!", RED), 2500));
        s.add(new Step(big(w, h, "180!", GOLD), 1200));
        return withSay(s, "One hundred and eighty!", false);
    }

    /** 100-139: fireworks. 140-179: a rocket. */
    public static List<Step> ton(String name, int score, int w, int h) {
        Random r = new Random(score * 31L + System.nanoTime() % 1000);
        List<Step> s = score >= 140 ? Fx.rocket(score + "!", w, h, r) : Fx.fireworks(score + "!", w, h, r);
        s.add(new Step(lines(w, h, Renderer.shortName(name), WHITE, score + "!", GOLD), 900));
        return withSay(s, score + "!", false);
    }

    public static List<Step> bust(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            s.add(new Step(big(w, h, "BUST!", RED), 350));
            s.add(new Step(new Frame(w, h), 150));
        }
        s.add(new Step(lines(w, h, Renderer.shortName(name), WHITE, "BUST!", RED), 1500));
        return withSay(s, "Bust!", false);
    }

    public static List<Step> noScore(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            s.add(new Step(lines(w, h, "NO", GOLD, "SCORE!", GOLD), 350));
            s.add(new Step(new Frame(w, h), 150));
        }
        s.add(new Step(lines(w, h, Renderer.shortName(name), WHITE, "NO SCORE", GOLD), 1500));
        return withSay(s, "No score.", false);
    }

    /** Checkout of 100 or more; 170 is the big fish. */
    public static List<Step> highFinish(int checkout, int w, int h) {
        List<Step> s = new ArrayList<>();
        boolean fish = checkout == 170;
        for (int i = 0; i < 4; i++) {
            s.add(new Step(fish ? lines(w, h, "THE BIG", i % 2 == 0 ? GOLD : CYAN, "FISH!", i % 2 == 0 ? CYAN : GOLD)
                                : lines(w, h, "HIGH", i % 2 == 0 ? GOLD : RED, "FINISH!", i % 2 == 0 ? RED : GOLD), 350));
            s.add(new Step(new Frame(w, h), 120));
        }
        int[] colors = {GOLD, RED, GREEN, CYAN};
        for (int i = 0; i < 4; i++) s.add(new Step(big(w, h, checkout + "!", colors[i]), 300));
        s.add(new Step(big(w, h, checkout + "!", GOLD), 1200));
        return withSay(s, fish ? "The big fish! One hundred and seventy!" : "What a finish! " + checkout + "!", false);
    }

    /** New match: GAME ON!, then who throws first. */
    public static List<Step> gameOn(Scoreboard sb, int w, int h) {
        String first = sb.players.get(0).name;
        for (Scoreboard.Player p : sb.players) if (p.active) first = p.name;
        List<Step> s = new ArrayList<>();
        int[] colors = {GOLD, RED, GREEN, CYAN};
        for (int i = 0; i < 4; i++) {
            s.add(new Step(big(w, h, "GAME ON!", colors[i]), 300));
            s.add(new Step(new Frame(w, h), 100));
        }
        s.add(new Step(big(w, h, "GAME ON!", GOLD), 900));
        s.add(new Step(lines(w, h, Renderer.shortName(first), WHITE, "TO THROW", GOLD), 2000));
        return withSay(s, "Game on! " + first + " to throw first.", false);
    }

    /** Bull-up decided. */
    public static List<Step> throwsFirst(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        String n = Renderer.shortName(name);
        for (int i = 0; i < 3; i++) {
            s.add(new Step(lines(w, h, n, GOLD, "THROWS 1ST", WHITE), 400));
            s.add(new Step(lines(w, h, n, GOLD, "", WHITE), 150));
        }
        s.add(new Step(lines(w, h, n, GOLD, "THROWS 1ST", WHITE), 2000));
        return withSay(s, name + " throws first.", false);
    }

    public static List<Step> gameShot(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        String n = Renderer.shortName(name);
        for (int i = 0; i < 6; i++) {
            s.add(new Step(lines(w, h, "GAME SHOT!", i % 2 == 0 ? GOLD : WHITE, n, i % 2 == 0 ? WHITE : GOLD), 350));
        }
        s.add(new Step(lines(w, h, "GAME SHOT!", GOLD, n, WHITE), 2500));
        return withSay(s, "Game shot, and the leg, " + name + "!", false);
    }

    public static List<Step> twentySix(Random rnd, int w, int h) {
        int pick = rnd.nextInt(TWENTY_SIX.length);
        if (pick == lastJoke) pick = (pick + 1 + rnd.nextInt(TWENTY_SIX.length - 1)) % TWENTY_SIX.length;
        lastJoke = pick;
        String[] msg = TWENTY_SIX[pick];
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            s.add(new Step(big(w, h, "26", CYAN), 300));
            s.add(new Step(new Frame(w, h), 120));
        }
        s.add(new Step(big(w, h, "26", CYAN), 500));
        s.add(jokeStep(lines(w, h, msg[0], CYAN, msg[1], PINK), msg));
        return s;
    }

    /** The joke frame, with the joke read out (Slovak jokes with a Slovak voice). */
    private static Step jokeStep(Frame f, String[] msg) {
        String key = (msg[0] + " " + msg[1]).trim();
        if (SLOVAK.containsKey(key)) return new Step(f, 3500, SLOVAK.get(key), true);
        return new Step(f, 3500, spoken(key), false);
    }

    /** Flashes the score, then a random joke for it (never the same twice in a row). */
    public static List<Step> scoreJoke(int score, String[][] jokes, int c1, int c2, Random rnd, int w, int h) {
        int pick = rnd.nextInt(jokes.length);
        Integer prev = lastPick.get(score);
        if (prev != null && pick == prev && jokes.length > 1) pick = (pick + 1 + rnd.nextInt(jokes.length - 1)) % jokes.length;
        lastPick.put(score, pick);
        String[] msg = jokes[pick];
        List<Step> s = new ArrayList<>();
        String n = Integer.toString(score);
        for (int i = 0; i < 2; i++) {
            s.add(new Step(big(w, h, n, c1), 300));
            s.add(new Step(new Frame(w, h), 120));
        }
        s.add(new Step(big(w, h, n, c1), 500));
        s.add(jokeStep(msg[1].isEmpty() ? big(w, h, msg[0], c2) : lines(w, h, msg[0], c1, msg[1], c2), msg));
        return s;
    }

    /** 67: "SIX" ... "SEVEN" bouncing between the two numbers, then a joke. */
    public static List<Step> sixSeven(Random rnd, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            s.add(new Step(big(w, h, "6", GOLD), 280));
            s.add(new Step(big(w, h, "7", PINK), 280));
        }
        s.addAll(scoreJoke(67, SIXTY_SEVEN, GOLD, PINK, rnd, w, h).subList(5, 6)); // just the joke frame
        return withSay(s, "Six... seven!", false);
    }

    /** Blinks the winner screen a few times, then confetti; the app then keeps it on. */
    public static List<Step> winner(Scoreboard sb, int w, int h) {
        List<Step> s = new ArrayList<>();
        Frame win = Renderer.render(sb, w, h);
        for (int i = 0; i < 4; i++) {
            s.add(new Step(win, 450));
            s.add(new Step(new Frame(w, h), 150));
        }
        s.addAll(Fx.confetti(win, 30, new Random()));
        s.add(new Step(win, 500));
        return withSay(s, "Game shot, and the match, " + sb.winner + "!", false);
    }

    // ---------------------------------------------------------------- drawing helpers

    /** One line, as large as fits, centred. */
    static Frame big(int w, int h, String text, int color) {
        Frame f = new Frame(w, h);
        PixelFont font = PixelFont.BIG;
        int scale = 1;
        for (int k = 4; k >= 1; k--) {
            if (font.h * k <= h && font.width(text, k) <= w) { scale = k; break; }
        }
        font.draw(f, text, (w - font.width(text, scale)) / 2, (h - font.h * scale) / 2, scale, color);
        return f;
    }

    /** Two centred lines, as large as fits. */
    static Frame lines(int w, int h, String l1, int c1, String l2, int c2) {
        Frame f = new Frame(w, h);
        PixelFont font = PixelFont.BIG;
        int scale = 1;
        for (int k = 4; k >= 1; k--) {
            if (font.h * k * 2 + k <= h && font.width(l1, k) <= w && font.width(l2, k) <= w) { scale = k; break; }
        }
        PixelFont f1 = font.width(l1, scale) <= w ? font : PixelFont.SMALL;
        PixelFont f2 = font.width(l2, scale) <= w ? font : PixelFont.SMALL;
        int lineH = font.h * scale;
        int gap = Math.max(0, (h - 2 * lineH) / 3);
        int y1 = gap;
        int y2 = h - gap - lineH;
        f1.draw(f, l1, (w - f1.width(l1, scale)) / 2, y1, scale, c1);
        f2.draw(f, l2, (w - f2.width(l2, scale)) / 2, y2, scale, c2);
        return f;
    }
}
