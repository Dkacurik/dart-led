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

        Step(Frame frame, int ms) {
            this.frame = frame;
            this.ms = ms;
        }
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
            {"RANAJKY", "PODANE!"},
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
            // po slovensky
            {"DOBRE", "RANO!"},
            {"PRAZENICA", "HOTOVA!"},
            {"CHLIEB", "S MASLOM"},
            {"KAVICKU?", "POTREBUJES"},
            {"ZASA", "26?!"},
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
            {"ZASA", "VLAVO!"},
    };

    /** 41 = 20, 20, 1. */
    static final String[][] FORTY_ONE = {
            {"WIDE", "RIGHT!"},
            {"20 20 1", "CLASSIC!"},
            {"1 IS THE", "LONELIEST"},
            {"RIGHT", "TURN!"},
            {"ONE MORE", "TO THE 1"},
            {"OOPS!", "WIDE RIGHT"},
            {"ZASA", "VPRAVO!"},
            {"SO CLOSE", "TO 60"},
    };

    static final String[][] SIXTY_NINE = {
            {"NICE.", ""},
            {"69", "NICE!"},
            {"NICE", "NICE NICE"},
            {"HEHE", "69"},
            {"VERY", "NICE!"},
            {"NAJS!", ""},
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
            {"VSETKY", "SPOLU!"},
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
        if (isFreshStart(now) && (before.players.isEmpty() || before.bullUp || before.winner != null
                || !sameNames(before, now))) {
            return gameOn(now, w, h);
        }
        if (before.players.size() != now.players.size() || !sameNames(before, now)) return null; // new match
        // A leg won beats everything else.
        for (int i = 0; i < now.players.size(); i++) {
            Scoreboard.Player a = before.players.get(i), b = now.players.get(i);
            if (b.legs == a.legs + 1) {
                if (a.score >= 100 && a.score <= 170) {
                    List<Step> s = new ArrayList<>(highFinish(a.score, w, h));
                    s.addAll(gameShot(b.name, w, h));
                    return s;
                }
                return gameShot(b.name, w, h);
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

            // Bust = the visit scored more than was left, or would leave 1 (you must finish on
            // a double). The remaining score then stays the same. Missed darts are NOT a bust.
            boolean bust = b.lastBust
                    || (b.score == a.score && b.lastScore > 0
                        && (b.lastScore > a.score || a.score - b.lastScore == 1));
            if (bust) return bust(b.name, w, h);
            if (visit == 0 && b.score == a.score) return noScore(b.name, w, h);
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
            // Under 10 while still far from a finish (more than 50 left before the visit).
            if (visit >= 1 && visit <= 9 && a.score > 50) {
                return scoreJoke(visit, new String[][]{{"AIM?", "WHAT AIM?"}}, CYAN, PINK, rnd, w, h);
            }
            if (visit == 26) return twentySix(rnd, w, h);
            if (visit == 45 && !dartsKnown) return scoreJoke(45, FORTY_FIVE, CYAN, PINK, rnd, w, h);
            if (visit == 41 && !dartsKnown) return scoreJoke(41, FORTY_ONE, CYAN, PINK, rnd, w, h);
            if (visit == 69) return scoreJoke(69, SIXTY_NINE, PINK, GOLD, rnd, w, h);
            if (visit == 67) return sixSeven(rnd, w, h);
            if (visit >= 100 && visit < 180) return ton(b.name, visit, w, h);
        }
        return null;
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
        return s;
    }

    public static List<Step> ton(String name, int score, int w, int h) {
        List<Step> s = new ArrayList<>();
        String n = Renderer.shortName(name);
        for (int i = 0; i < 3; i++) {
            s.add(new Step(lines(w, h, n, WHITE, score + "!", GOLD), 400));
            s.add(new Step(lines(w, h, n, WHITE, "", GOLD), 150));
        }
        s.add(new Step(lines(w, h, n, WHITE, score + "!", GOLD), 1500));
        return s;
    }

    public static List<Step> bust(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            s.add(new Step(big(w, h, "BUST!", RED), 350));
            s.add(new Step(new Frame(w, h), 150));
        }
        s.add(new Step(lines(w, h, Renderer.shortName(name), WHITE, "BUST!", RED), 1500));
        return s;
    }

    public static List<Step> noScore(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            s.add(new Step(lines(w, h, "NO", GOLD, "SCORE!", GOLD), 350));
            s.add(new Step(new Frame(w, h), 150));
        }
        s.add(new Step(lines(w, h, Renderer.shortName(name), WHITE, "NO SCORE", GOLD), 1500));
        return s;
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
        return s;
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
        return s;
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
        return s;
    }

    public static List<Step> gameShot(String name, int w, int h) {
        List<Step> s = new ArrayList<>();
        String n = Renderer.shortName(name);
        for (int i = 0; i < 6; i++) {
            s.add(new Step(lines(w, h, "GAME SHOT!", i % 2 == 0 ? GOLD : WHITE, n, i % 2 == 0 ? WHITE : GOLD), 350));
        }
        s.add(new Step(lines(w, h, "GAME SHOT!", GOLD, n, WHITE), 2500));
        return s;
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
        s.add(new Step(lines(w, h, msg[0], CYAN, msg[1], PINK), 3500));
        return s;
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
        s.add(new Step(msg[1].isEmpty() ? big(w, h, msg[0], c2) : lines(w, h, msg[0], c1, msg[1], c2), 3500));
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
        return s;
    }

    /** Blinks the winner screen a few times; the app then keeps it on. */
    public static List<Step> winner(Scoreboard sb, int w, int h) {
        List<Step> s = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            s.add(new Step(Renderer.render(sb, w, h), 450));
            s.add(new Step(new Frame(w, h), 150));
        }
        s.add(new Step(Renderer.render(sb, w, h), 500));
        return s;
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
