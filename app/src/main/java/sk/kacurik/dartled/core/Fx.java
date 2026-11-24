package sk.kacurik.dartled.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pixel effects for the match moments: rocket, fireworks, bull rings, flames, crown,
 * sad face, shaking text, floating Zzz, confetti and scrolling text.
 * Every effect is a list of Steps (picture + how long to show it).
 */
public final class Fx {

    static final int RED = 0xFF3020, ORANGE = 0xFF8010, GOLD = 0xFFC814, GREEN = 0x40FF40,
            CYAN = 0x28C8FF, PINK = 0xFF40C0, WHITE = 0xFFFFFF, BLUE = 0x3060FF;
    static final int[] PARTY = {GOLD, RED, GREEN, CYAN, PINK, WHITE, ORANGE};

    private Fx() {}

    private static Moments.Step step(Frame f, int ms) {
        return new Moments.Step(f, ms, null, false);
    }

    static void dot(Frame f, int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < f.w && y < f.h) f.px[y * f.w + x] = c & 0xFFFFFF;
    }

    static int dim(int c, int pct) {
        return (((c >> 16) & 0xFF) * pct / 100) << 16 | (((c >> 8) & 0xFF) * pct / 100) << 8 | ((c & 0xFF) * pct / 100);
    }

    /** Text centred, as big as fits, with an optional horizontal offset. */
    static Frame text(int w, int h, String s, int color, int dx) {
        Frame f = new Frame(w, h);
        PixelFont font = PixelFont.BIG;
        int scale = 1;
        for (int k = 4; k >= 1; k--) if (font.h * k <= h && font.width(s, k) <= w) { scale = k; break; }
        PixelFont use = font.width(s, scale) <= w ? font : PixelFont.SMALL;
        use.draw(f, s, (w - use.width(s, scale)) / 2 + dx, (h - use.h * scale) / 2, scale, color);
        return f;
    }

    // ------------------------------------------------------------------ 140+: rocket

    /** A rocket climbs with a flame trail, bursts, and the score appears in the sparks. */
    public static List<Moments.Step> rocket(String label, int w, int h, Random r) {
        List<Moments.Step> s = new ArrayList<>();
        int cx = w / 2;
        for (int y = h + 2; y >= h / 2 - 2; y -= 2) {
            Frame f = new Frame(w, h);
            // body: nose, tube, fins
            dot(f, cx, y - 3, WHITE);
            for (int k = -2; k <= 1; k++) { dot(f, cx - 1, y + k, WHITE); dot(f, cx, y + k, RED); dot(f, cx + 1, y + k, WHITE); }
            dot(f, cx - 2, y + 1, RED); dot(f, cx + 2, y + 1, RED);
            // flame trail
            for (int k = 2; k < 2 + 3 + r.nextInt(3); k++) {
                dot(f, cx + r.nextInt(3) - 1, y + k, k < 4 ? GOLD : ORANGE);
            }
            s.add(step(f, 70));
        }
        s.addAll(burst(cx, h / 2 - 2, w, h, GOLD, null, r));
        int[] cs = {GOLD, WHITE, RED, GOLD};
        for (int i = 0; i < 4; i++) {
            Frame f = text(w, h, label, cs[i], 0);
            sparks(f, r, 6);
            s.add(step(f, 220));
        }
        s.add(step(text(w, h, label, GOLD, 0), 1100));
        return s;
    }

    /** An expanding ring of sparks from (cx, cy), drawn over an optional background. */
    static List<Moments.Step> burst(int cx, int cy, int w, int h, int color, Frame bg, Random r) {
        List<Moments.Step> s = new ArrayList<>();
        for (int rad = 1; rad <= 7; rad += 2) {
            Frame f = bg == null ? new Frame(w, h) : copy(bg);
            for (int k = 0; k < 12; k++) {
                double a = k * Math.PI / 6;
                int x = cx + (int) Math.round(Math.cos(a) * rad * 1.6);
                int y = cy + (int) Math.round(Math.sin(a) * rad);
                dot(f, x, y, rad >= 5 ? dim(color, 60) : color);
                if (rad > 1) dot(f, cx + (int) Math.round(Math.cos(a) * (rad - 1) * 1.6), cy + (int) Math.round(Math.sin(a) * (rad - 1)), dim(color, 35));
            }
            s.add(step(f, 70));
        }
        return s;
    }

    // ------------------------------------------------------------------ 100-139: fireworks

    /** Three fireworks go off around the panel, then the score. */
    public static List<Moments.Step> fireworks(String label, int w, int h, Random r) {
        List<Moments.Step> s = new ArrayList<>();
        int[][] spots = {{w / 5, h / 3}, {w * 4 / 5, h / 2}, {w / 2, h / 3}};
        Frame bg = new Frame(w, h);
        for (int i = 0; i < spots.length; i++) {
            int c = PARTY[r.nextInt(PARTY.length)];
            // short launch streak
            for (int y = h; y > spots[i][1]; y -= 4) {
                Frame f = copy(bg);
                dot(f, spots[i][0], y, WHITE);
                dot(f, spots[i][0], y + 1, dim(WHITE, 40));
                s.add(step(f, 50));
            }
            List<Moments.Step> b = burst(spots[i][0], spots[i][1], w, h, c, bg, r);
            s.addAll(b);
            bg = fade(b.get(b.size() - 1).frame, 40);
        }
        for (int i = 0; i < 3; i++) {
            Frame f = text(w, h, label, i % 2 == 0 ? GOLD : WHITE, 0);
            sparks(f, r, 5);
            s.add(step(f, 250));
        }
        s.add(step(text(w, h, label, GOLD, 0), 1000));
        return s;
    }

    // ------------------------------------------------------------------ bull

    /** Rings spread out from the middle like a hit bull, then "BULL!". */
    public static List<Moments.Step> bull(int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        int cx = w / 2, cy = h / 2;
        for (int rad = 0; rad <= 9; rad++) {
            Frame f = new Frame(w, h);
            for (int k = rad; k >= 0; k -= 3) ring(f, cx, cy, k, k % 2 == 0 ? RED : GREEN);
            s.add(step(f, 55));
        }
        for (int i = 0; i < 2; i++) {
            s.add(step(text(w, h, "BULL!", i == 0 ? RED : GREEN, 0), 260));
        }
        return s;
    }

    static void ring(Frame f, int cx, int cy, int rad, int c) {
        if (rad == 0) { dot(f, cx, cy, c); return; }
        for (int a = 0; a < 360; a += 6) {
            double t = Math.toRadians(a);
            dot(f, cx + (int) Math.round(Math.cos(t) * rad * 1.4), cy + (int) Math.round(Math.sin(t) * rad), c);
        }
    }

    // ------------------------------------------------------------------ T20 ping

    public static List<Moments.Step> ping(String dart, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        s.add(step(text(w, h, dart + "!", WHITE, 0), 110));
        s.add(step(text(w, h, dart + "!", GOLD, 0), 160));
        s.add(step(text(w, h, dart + "!", WHITE, 0), 110));
        return s;
    }

    // ------------------------------------------------------------------ on fire

    /** Flames flicker along the bottom under "ON FIRE!". */
    public static List<Moments.Step> onFire(String name, int w, int h, Random r) {
        List<Moments.Step> s = new ArrayList<>();
        String n = Renderer.shortName(name);
        for (int i = 0; i < 12; i++) {
            Frame f = new Frame(w, h);
            flames(f, r, h / 2 + 1);
            String t = i < 6 ? "ON FIRE!" : n;
            PixelFont font = PixelFont.BIG;
            PixelFont use = font.width(t, 1) <= w ? font : PixelFont.SMALL;
            use.draw(f, t, (w - use.width(t, 1)) / 2, 0, 1, i % 2 == 0 ? GOLD : WHITE);
            s.add(step(f, 140));
        }
        return s;
    }

    /** Flames up to maxH pixels tall along the bottom edge. */
    static void flames(Frame f, Random r, int maxH) {
        for (int x = 0; x < f.w; x++) {
            int hh = 2 + r.nextInt(Math.max(1, maxH - 1));
            for (int k = 0; k < hh; k++) {
                int y = f.h - 1 - k;
                int c = k < hh / 3 ? GOLD : (k < hh * 2 / 3 ? ORANGE : RED);
                if (f.px[y * f.w + x] == 0) dot(f, x, y, k == hh - 1 ? dim(c, 50) : c);
            }
        }
    }

    // ------------------------------------------------------------------ best visit crown

    private static final String[] CROWN = {
            "#..#..#",
            "#.###.#",
            "#######",
            "#######",
            ".#####.",
    };

    /** A crown drops in next to "BEST" and the visit. */
    public static List<Moments.Step> bestVisit(int visit, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        PixelFont big = PixelFont.BIG;
        String l1 = "BEST", l2 = Integer.toString(visit);
        int crownW = CROWN[0].length();
        int textW = Math.max(big.width(l1, 1), big.width(l2, 1));
        int x0 = (w - crownW - 3 - textW) / 2;
        for (int dy = -6; dy <= 0; dy += 2) s.add(step(crownFrame(w, h, x0, dy, l1, l2, false), 70));
        for (int i = 0; i < 4; i++) s.add(step(crownFrame(w, h, x0, 0, l1, l2, i % 2 == 0), 250));
        s.add(step(crownFrame(w, h, x0, 0, l1, l2, false), 1200));
        return s;
    }

    private static Frame crownFrame(int w, int h, int x0, int dy, String l1, String l2, boolean shine) {
        Frame f = new Frame(w, h);
        int y0 = (h - CROWN.length) / 2 + dy;
        for (int y = 0; y < CROWN.length; y++)
            for (int x = 0; x < CROWN[0].length(); x++)
                if (CROWN[y].charAt(x) == '#') dot(f, x0 + x, y0 + y, shine && (x + y) % 3 == 0 ? WHITE : GOLD);
        PixelFont big = PixelFont.BIG;
        int tx = x0 + CROWN[0].length() + 3;
        if (h >= 16) {
            big.draw(f, l1, tx, (h - 16) / 2, 1, WHITE);
            big.draw(f, l2, tx, (h - 16) / 2 + 8, 1, GOLD);
        } else {
            big.draw(f, l2, tx, (h - big.h) / 2, 1, GOLD);
        }
        return f;
    }

    // ------------------------------------------------------------------ under 20: sad face

    private static final String[] SAD = {
            ".#####.",
            "#.....#",
            "#.#.#.#",
            "#.....#",
            "#..#..#",
            "#.#.#.#",
            ".#####.",
    };

    /** A sad face sheds a tear next to a short comment. */
    public static List<Moments.Step> sadFace(String line, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        PixelFont big = PixelFont.BIG;
        PixelFont font = big.width(line, 1) <= w - 11 ? big : PixelFont.SMALL;
        int faceX = (w - 7 - 3 - font.width(line, 1)) / 2;
        int faceY = (h - 7) / 2;
        for (int i = 0; i < 9; i++) {
            Frame f = new Frame(w, h);
            for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++)
                if (SAD[y].charAt(x) == '#') dot(f, faceX + x, faceY + y, GOLD);
            int tear = faceY + 3 + (i % 5);
            if (tear < h) dot(f, faceX + 2, tear, CYAN);
            font.draw(f, line, faceX + 10, (h - font.h) / 2, 1, i < 2 ? dim(WHITE, 40 + i * 30) : WHITE);
            s.add(step(f, i == 8 ? 1400 : 180));
        }
        return s;
    }

    // ------------------------------------------------------------------ shaking text

    /** Text that shakes left and right (WAKE UP!, SO CLOSE!). */
    public static List<Moments.Step> shake(String t, int c1, int c2, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        int[] dx = {-2, 2, -2, 2, -1, 1, -1, 1, 0};
        for (int i = 0; i < dx.length; i++) s.add(step(text(w, h, t, i % 2 == 0 ? c1 : c2, dx[i]), 70));
        s.add(step(text(w, h, t, c1, 0), 1300));
        return s;
    }

    // ------------------------------------------------------------------ long leg: Zzz

    /** "Z z z" float up from a sleepy face. */
    public static List<Moments.Step> zzz(int darts, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        String sub = darts + " DARTS";
        for (int i = 0; i < 14; i++) {
            Frame f = new Frame(w, h);
            PixelFont small = PixelFont.SMALL;
            small.draw(f, sub, 0, h - small.h, 1, dim(WHITE, 70));
            for (int k = 0; k < 3; k++) {
                int t = i - k * 3;
                if (t < 0) continue;
                int x = w / 2 + k * 6 + t;
                int y = h - 6 - t;
                if (y < -5) continue;
                PixelFont font = k == 0 ? PixelFont.SMALL : PixelFont.BIG;
                font.draw(f, "Z", x, y, 1, k == 2 ? CYAN : (k == 1 ? BLUE : WHITE));
            }
            s.add(step(f, 150));
        }
        return s;
    }

    // ------------------------------------------------------------------ one-dart finish

    /** A one-dart finish: the double pulses like a target. */
    public static List<Moments.Step> doubleTarget(String dart, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        int[] cs = {GREEN, WHITE, GREEN, WHITE, GREEN};
        for (int i = 0; i < cs.length; i++) {
            Frame f = text(w, h, dart, cs[i], 0);
            int pad = i % 2 == 0 ? 1 : 0;
            box(f, pad, pad, w - 2 * pad, h - 2 * pad, dim(GREEN, i % 2 == 0 ? 60 : 25));
            s.add(step(f, 200));
        }
        return s;
    }

    /** Bars down both sides (where a full box would touch two lines of text). */
    static void sides(Frame f, int c) {
        for (int y = 0; y < f.h; y++) { dot(f, 0, y, c); dot(f, 1, y, c); dot(f, f.w - 1, y, c); dot(f, f.w - 2, y, c); }
    }

    static void box(Frame f, int x, int y, int bw, int bh, int c) {
        for (int i = 0; i < bw; i++) { dot(f, x + i, y, c); dot(f, x + i, y + bh - 1, c); }
        for (int i = 0; i < bh; i++) { dot(f, x, y + i, c); dot(f, x + bw - 1, y + i, c); }
    }

    // ------------------------------------------------------------------ match darts

    /** Red frame pulses around "MATCH DARTS". */
    public static List<Moments.Step> matchDarts(String name, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Frame f = Moments.lines(w, h, "MATCH", i % 2 == 0 ? RED : WHITE, "DARTS!", i % 2 == 0 ? WHITE : RED);
            if (i % 2 == 0) sides(f, RED);
            s.add(step(f, 280));
        }
        s.add(step(Moments.lines(w, h, Renderer.shortName(name), WHITE, "FOR MATCH", RED), 1300));
        return s;
    }

    // ------------------------------------------------------------------ confetti

    /** Confetti falling over a picture, avoiding its lit pixels. */
    public static List<Moments.Step> confetti(Frame bg, int frames, Random r) {
        List<Moments.Step> s = new ArrayList<>();
        int n = Math.max(8, bg.w * bg.h / 60);
        int[][] p = new int[n][3];
        for (int[] q : p) { q[0] = r.nextInt(bg.w); q[1] = -r.nextInt(bg.h * 2); q[2] = PARTY[r.nextInt(PARTY.length)]; }
        for (int i = 0; i < frames; i++) {
            Frame f = copy(bg);
            for (int[] q : p) {
                q[1] += 1 + (q[0] % 2);
                q[0] += r.nextInt(3) - 1;
                if (q[1] >= bg.h) { q[1] = -r.nextInt(4); q[0] = r.nextInt(bg.w); }
                if (q[1] >= 0 && q[0] >= 0 && q[0] < bg.w && bg.px[q[1] * bg.w + q[0]] == 0) dot(f, q[0], q[1], q[2]);
            }
            s.add(step(f, 110));
        }
        return s;
    }

    // ------------------------------------------------------------------ scrolling text

    /** Text scrolls from right to left; parts are {text, colour} pairs. */
    public static List<Moments.Step> marquee(String[] parts, int[] colors, int w, int h) {
        List<Moments.Step> s = new ArrayList<>();
        PixelFont font = PixelFont.BIG;
        int total = 0;
        for (String t : parts) total += font.width(t, 1) + font.advance(1);
        int y = (h - font.h) / 2;
        for (int x = w; x > -total; x -= 3) {
            Frame f = new Frame(w, h);
            int cx = x;
            for (int i = 0; i < parts.length; i++) {
                font.draw(f, parts[i], cx, y, 1, colors[i % colors.length]);
                cx += font.width(parts[i], 1) + font.advance(1);
            }
            s.add(step(f, 60));
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    static Frame copy(Frame f) {
        Frame c = new Frame(f.w, f.h);
        System.arraycopy(f.px, 0, c.px, 0, f.px.length);
        return c;
    }

    static Frame fade(Frame f, int pct) {
        Frame c = new Frame(f.w, f.h);
        for (int i = 0; i < f.px.length; i++) c.px[i] = dim(f.px[i], pct);
        return c;
    }

    /** A few twinkles on empty pixels. */
    static void sparks(Frame f, Random r, int n) {
        for (int k = 0, tries = 0; k < n && tries < 100; tries++) {
            int x = r.nextInt(f.w), y = r.nextInt(f.h);
            boolean free = true;
            for (int yy = y - 1; yy <= y + 1 && free; yy++)
                for (int xx = x - 1; xx <= x + 1; xx++)
                    if (xx >= 0 && yy >= 0 && xx < f.w && yy < f.h && f.px[yy * f.w + xx] != 0) { free = false; break; }
            if (!free) continue;
            dot(f, x, y, r.nextBoolean() ? WHITE : GOLD);
            k++;
        }
    }
}
