package sk.kacurik.dartled.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Idle screen: a big clock, and now and then a dart thrown at a little dartboard. */
public final class IdleClock {

    private static final int GOLD = 0xFFC814;
    private static final int RED = 0xE02020;
    private static final int GREEN = 0x10A030;
    private static final int CREAM = 0xC8B478;
    private static final int DARK = 0x1C1C1C;
    private static final int CLOCK = 0x28C8FF;

    /** Dartboard numbers clockwise from the top. */
    private static final int[] ORDER = {20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5};

    private IdleClock() {}

    /** "21:07" as large as fits; the colon blinks with {@code colon}. */
    public static Frame clock(int w, int h, int hour, int minute, boolean colon) {
        Frame f = new Frame(w, h);
        String text = hour + (colon ? ":" : " ") + (minute < 10 ? "0" : "") + minute;
        PixelFont font = PixelFont.BIG;
        String widest = hour + ":" + "00";
        int scale = 1;
        for (int k = 4; k >= 1; k--) {
            if (font.h * k <= h && font.width(widest, k) <= w) { scale = k; break; }
        }
        int x = (w - font.width(text, scale)) / 2;
        int y = (h - font.h * scale) / 2;
        font.draw(f, text, x, y, scale, CLOCK);
        return f;
    }

    /** One throw: the dart flies in, sticks, the result blinks, then the clock comes back. */
    public static List<Moments.Step> dartThrow(int w, int h, Random rnd) {
        int d = Math.min(h, 32);                 // board diameter in pixels
        double r = d / 2.0;
        double cx = w - r - 0.5, cy = h / 2.0;

        // Pick where it lands.
        String text;
        double tx, ty;
        int roll = rnd.nextInt(4);
        if (roll == 0) {
            text = "BULL!"; tx = cx; ty = cy;
        } else if (roll == 1) {
            text = "T20!"; double[] p = at(cx, cy, r * 0.60, 20); tx = p[0]; ty = p[1];
        } else if (roll == 2) {
            text = "D16!"; double[] p = at(cx, cy, r * 0.93, 16); tx = p[0]; ty = p[1];
        } else {
            text = "WIDE!"; tx = w + 4; ty = cy - r * 0.9;
        }

        List<Moments.Step> s = new ArrayList<>();
        s.add(new Moments.Step(board(w, h, cx, cy, r), 400));
        int n = 7;
        for (int k = 1; k <= n; k++) {
            double t = k / (double) n;
            double x = -3 + (tx + 3) * t;
            double y = ty - Math.sin(Math.PI * t) * Math.min(4, h / 4.0);
            Frame f = board(w, h, cx, cy, r);
            dart(f, (int) Math.round(x), (int) Math.round(y));
            s.add(new Moments.Step(f, 110));
        }
        int room = (int) Math.min(w - d - 1, tx < w ? tx - 10 : w);
        for (int i = 0; i < 3; i++) {
            Frame on = board(w, h, cx, cy, r);
            if (tx < w) dart(on, (int) Math.round(tx), (int) Math.round(ty));
            label(on, text, room, GOLD);
            s.add(new Moments.Step(on, 450));
            Frame off = board(w, h, cx, cy, r);
            if (tx < w) dart(off, (int) Math.round(tx), (int) Math.round(ty));
            s.add(new Moments.Step(off, 200));
        }
        Frame end = board(w, h, cx, cy, r);
        if (tx < w) dart(end, (int) Math.round(tx), (int) Math.round(ty));
        label(end, text, room, GOLD);
        s.add(new Moments.Step(end, 1200));
        return s;
    }

    // ------------------------------------------------------------------ drawing

    /** Position of a number's segment at distance {@code dist} from the centre. */
    private static double[] at(double cx, double cy, double dist, int number) {
        int idx = 0;
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == number) idx = i;
        double ang = Math.toRadians(90 - idx * 18);
        return new double[]{cx + Math.cos(ang) * dist, cy - Math.sin(ang) * dist};
    }

    static Frame board(int w, int h, double cx, double cy, double r) {
        Frame f = new Frame(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                double dist = Math.sqrt(dx * dx + dy * dy) / r;    // 0 at centre, 1 at edge
                if (dist > 1.0) continue;
                double ang = Math.toDegrees(Math.atan2(-dy, dx));
                int seg = (int) Math.floor(((90 - ang + 9) % 360 + 360) % 360 / 18) % 20;
                boolean even = seg % 2 == 0;
                int c;
                if (dist < 0.13) c = RED;                         // bull
                else if (dist < 0.27) c = GREEN;                  // outer bull
                else if (dist < 0.56) c = even ? DARK : CREAM;
                else if (dist < 0.67) c = even ? RED : GREEN;     // treble ring
                else if (dist < 0.87) c = even ? DARK : CREAM;
                else c = even ? RED : GREEN;                      // double ring
                f.px[y * w + x] = c;
            }
        }
        return f;
    }

    /** A dart pointing right with its tip at (x, y). */
    static void dart(Frame f, int x, int y) {
        f.fillRect(x, y, 1, 1, 0xD0D0D0);          // tip
        f.fillRect(x - 4, y, 4, 1, GOLD);          // barrel
        f.fillRect(x - 6, y, 2, 1, 0x808080);      // shaft
        f.fillRect(x - 9, y - 1, 3, 1, CLOCK);     // flights
        f.fillRect(x - 9, y, 3, 1, 0x1890C0);
        f.fillRect(x - 9, y + 1, 3, 1, CLOCK);
    }

    private static void label(Frame f, String text, int room, int color) {
        PixelFont font = PixelFont.BIG.width(text, 1) <= room ? PixelFont.BIG : PixelFont.SMALL;
        int x = Math.max(0, (room - font.width(text, 1)) / 2);
        font.draw(f, text, x, (f.h - font.h) / 2, 1, color);
    }
}
