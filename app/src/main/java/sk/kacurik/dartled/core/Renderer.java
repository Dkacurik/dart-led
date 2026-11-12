package sk.kacurik.dartled.core;

import java.util.List;

/** Draws a Scoreboard onto a Frame of any panel size. */
public final class Renderer {

    private static final int[] PLAYER_COLORS = {
            0xFF5A28, // red-orange (like DartCounter's first card)
            0xFFA014, // orange
            0x28C8FF, // cyan
            0x50FF50, // green
    };
    private static final int NAME = 0xFFFFFF;
    private static final int LEGS = 0x40FF40;
    private static final int CHECKOUT = 0xFFE020;
    private static final int MARKER = 0xFFFFFF;
    private static final int FINISH = 0x40FF40;   // the finishing double
    private static final int GOLD = 0xFFC814;
    private static final int VISIT = 0x40FF40;     // "+60" shown after a visit
    private static final int DIST = 0x28C8FF;

    private static final String[] TROPHY = {
            "#.###.#",
            "#.###.#",
            ".#####.",
            "..###..",
            "...#...",
            "..###..",
            ".#####.",
    };

    private Renderer() {}

    public static Frame render(Scoreboard sb, int w, int h) {
        return render(sb, w, h, null);
    }

    /**
     * @param labels optional text shown instead of a player's name (e.g. "+60" right after
     *               their visit); null entries keep the name.
     */
    public static Frame render(Scoreboard sb, int w, int h, String[] labels) {
        Frame f = new Frame(w, h);
        List<Scoreboard.Player> ps = sb.players;
        if (sb.winner != null) {
            drawWinner(f, sb, w, h);
            return f;
        }
        if (sb.bullUp) {
            drawBullUp(f, sb, w, h);
            return f;
        }
        if (ps.isEmpty()) return f;

        // Short panels (e.g. 64x16): when the player at the oche can finish, show only
        // their score on top and the checkout darts underneath.
        if (h < 24) {
            for (int i = 0; i < ps.size(); i++) {
                Scoreboard.Player p = ps.get(i);
                if (p.active && !p.checkout.isEmpty()) {
                    drawCheckout(f, p, i, w, h, label(labels, i));
                    return f;
                }
            }
        }

        int maxRows = Math.max(1, h / PixelFont.SMALL.h);
        int n = Math.min(ps.size(), maxRows);
        int rowH = h / n;
        boolean anyActive = false;
        for (Scoreboard.Player p : ps) anyActive |= p.active;

        for (int i = 0; i < n; i++) {
            Scoreboard.Player p = ps.get(i);
            int y0 = i * rowH + (i == n - 1 ? h - n * rowH : 0); // give leftover pixels to last row
            boolean bright = !anyActive || p.active;
            drawRow(f, p, i, 0, y0, w, rowH, bright, label(labels, i));
        }
        return f;
    }

    private static String label(String[] labels, int i) {
        return labels != null && i < labels.length ? labels[i] : null;
    }

    private static void drawRow(Frame f, Scoreboard.Player p, int index, int x0, int y0, int w, int rowH, boolean bright, String label) {
        String score = Integer.toString(p.score);
        String legs = Integer.toString(Math.min(p.legs, 99));
        PixelFont small = PixelFont.SMALL;

        int markerW = 2;                                   // 1px bar + 1px gap
        int legsW = small.width(legs, 1) + 1;              // 1px gap + digits, flush right
        boolean showLegs = rowH >= small.h;

        // Pick the biggest score font that fits.
        PixelFont sf = null;
        int ss = 1;
        int room = w - markerW - (showLegs ? legsW : 0);
        for (int s = 4; s >= 1 && sf == null; s--) {
            if (PixelFont.BIG.h * s <= rowH && PixelFont.BIG.width(score, s) <= room) { sf = PixelFont.BIG; ss = s; }
        }
        if (sf == null) { sf = small; ss = 1; }

        int scoreW = sf.width(score, ss);
        int scoreH = sf.h * ss;
        int scoreX = w - (showLegs ? legsW : 1) - scoreW + (showLegs ? 0 : 1);
        int scoreY = y0 + (rowH - scoreH) / 2;

        int color = dim(PLAYER_COLORS[index % PLAYER_COLORS.length], bright);
        sf.draw(f, score, scoreX, scoreY, ss, color);

        if (showLegs) {
            int lx = w - legsW + 1;
            int ly = y0 + (rowH - small.h) / 2;
            small.draw(f, legs, lx, ly, 1, dim(LEGS, bright));
        }

        // Name (and checkout hint for the player at the oche, if there's height for it).
        String name = label != null ? label : shortName(p.name);
        // The turn bar needs 2px; drop it if a long name wouldn't fit otherwise
        // (the waiting player's row is dimmed anyway).
        if (PixelFont.BIG.width(name, 1) + markerW + 2 > scoreX && PixelFont.BIG.width(name, 1) + 2 <= scoreX) markerW = 0;
        if (p.active && markerW > 0) {
            f.fillRect(x0, y0 + (rowH - scoreH) / 2, 1, scoreH, MARKER);
        }
        int nameRoom = scoreX - markerW - 2;
        String co = p.active ? p.checkout.toUpperCase() : "";

        PixelFont nf = null;
        int ns = 1;
        boolean withCheckout = !co.isEmpty() && rowH >= PixelFont.BIG.h + small.h + 1;
        int nameMaxH = withCheckout ? rowH - small.h - 1 : rowH;
        for (int s = 3; s >= 1 && nf == null; s--) {
            if (PixelFont.BIG.h * s <= Math.min(nameMaxH, scoreH) && PixelFont.BIG.advance(s) * 2 - s <= nameRoom) {
                nf = PixelFont.BIG; ns = s;
            }
        }
        if (nf == null && small.h <= nameMaxH && small.advance(1) * 2 - 1 <= nameRoom) nf = small;
        if (nf == null) return;

        String shown = fit(nf, name, ns, nameRoom);
        int blockH = nf.h * ns + (withCheckout ? small.h + 1 : 0);
        int ny = y0 + (rowH - blockH) / 2;
        nf.draw(f, shown, markerW, ny, ns, label != null ? VISIT : dim(NAME, bright));

        if (withCheckout) {
            String c = fit(small, co, 1, nameRoom);
            small.draw(f, c, markerW, ny + nf.h * ns + 1, 1, CHECKOUT);
        }
    }

    /** Top row: whose turn + remaining score. Bottom row: the checkout, e.g. "T20 T13 D16". */
    private static void drawCheckout(Frame f, Scoreboard.Player p, int index, int w, int h, String label) {
        PixelFont big = PixelFont.BIG;
        PixelFont small = PixelFont.SMALL;
        int rowH = h / 2;

        // Row 1
        String score = Integer.toString(p.score);
        int scoreW = big.width(score, 1);
        int scoreX = w - 1 - scoreW;
        int y1 = (rowH - big.h) / 2;
        f.fillRect(0, y1, 1, big.h, MARKER);
        big.draw(f, score, scoreX, y1, 1, PLAYER_COLORS[index % PLAYER_COLORS.length]);
        String name = fit(big, label != null ? label : shortName(p.name), 1, scoreX - 2 - 3);
        big.draw(f, name, 2, y1, 1, label != null ? VISIT : NAME);

        // Row 2: one group per dart, biggest font + widest gap that fits.
        String[] darts = p.checkout.trim().toUpperCase().split("\\s+");
        PixelFont[] fonts = {big, big, big, small, small};
        int[] gaps = {5, 4, 3, 4, 2};
        for (int k = 0; k < fonts.length; k++) {
            PixelFont font = fonts[k];
            int total = gaps[k] * (darts.length - 1);
            for (String d : darts) total += font.width(d, 1);
            if (total > w) continue;
            int x = (w - total) / 2;
            int y = rowH + (h - rowH - font.h + 1) / 2;
            for (int i = 0; i < darts.length; i++) {
                int color = i == darts.length - 1 ? FINISH : CHECKOUT;
                font.draw(f, darts[i], x, y, 1, color);
                x += font.width(darts[i], 1) + gaps[k];
            }
            return;
        }
        String s = fit(small, p.checkout.toUpperCase(), 1, w);
        small.draw(f, s, 0, rowH + (h - rowH - small.h + 1) / 2, 1, CHECKOUT);
    }

    /** Bull-up: each player's name and distance from the bull; the closest one stays bright. */
    private static void drawBullUp(Frame f, Scoreboard sb, int w, int h) {
        int n = sb.bullNames.size();
        if (n == 0) return;
        PixelFont big = PixelFont.BIG;
        int rows = Math.min(n, Math.max(1, h / big.h));
        int rowH = h / rows;
        boolean allThrown = true;
        int best = -1;
        for (int i = 0; i < n; i++) {
            Double d = sb.bullDist.get(i);
            if (d == null) allThrown = false;
            else if (best < 0 || d < sb.bullDist.get(best)) best = i;
        }
        for (int i = 0; i < rows; i++) {
            Double d = sb.bullDist.get(i);
            String dist = d == null ? (i == sb.bullActive ? "..." : "-")
                    : (d == Math.floor(d) ? Integer.toString(d.intValue()) : String.format(java.util.Locale.ROOT, "%.1f", d));
            boolean bright = !allThrown || i == best;
            int y = i * rowH + (rowH - big.h) / 2;
            int dw = big.width(dist, 1);
            big.draw(f, dist, w - dw, y, 1, dim(allThrown && i == best ? GOLD : DIST, bright));
            if (i == sb.bullActive && !allThrown) f.fillRect(0, y, 1, big.h, MARKER);
            String name = fit(big, shortName(sb.bullNames.get(i)), 1, w - dw - 2 - 3);
            big.draw(f, name, 2, y, 1, dim(allThrown && i == best ? GOLD : NAME, bright));
        }
    }

    /** End of match: trophy + winner's name, and "WINS 2-1" underneath. */
    private static void drawWinner(Frame f, Scoreboard sb, int w, int h) {
        PixelFont big = PixelFont.BIG;
        String result = "WINS " + sb.winnerLegs + "-" + sb.loserLegs;
        int trophyW = TROPHY[0].length();

        // Biggest scale where both lines fit.
        int s = 1;
        for (int k = 4; k >= 1; k--) {
            if (big.h * k * 2 + k <= h && big.width(result, k) <= w && (trophyW + 2) * k + big.width("ABC", k) <= w) { s = k; break; }
        }
        int lineH = big.h * s;
        int gap = Math.max(1, (h - 2 * lineH) / 3);
        int y1 = gap;
        int y2 = h - gap - lineH;
        if (h < 2 * lineH + 2) { y1 = 0; y2 = h - lineH; }

        String name = fit(big, shortName(sb.winner), s, w - (trophyW + 2) * s);
        int total = (trophyW + 2) * s + big.width(name, s);
        int x = Math.max(0, (w - total) / 2);
        for (int ty = 0; ty < TROPHY.length; ty++) {
            for (int tx = 0; tx < trophyW; tx++) {
                if (TROPHY[ty].charAt(tx) == '#') f.fillRect(x + tx * s, y1 + ty * s, s, s, GOLD);
            }
        }
        big.draw(f, name, x + (trophyW + 2) * s, y1, s, GOLD);
        big.draw(f, result, (w - big.width(result, s)) / 2, y2, s, NAME);
    }

    /** Names are shown in capitals without accents, at most 7 letters: "Dávid" -> "DAVID". */
    static final int MAX_NAME = 7;

    static String shortName(String name) {
        String n = ScreenParser.plain(name).trim();
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).trim() : n;
    }

    private static String fit(PixelFont font, String s, int scale, int room) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!font.has(c)) continue;
            if (font.width(out.toString() + c, scale) > room) break;
            out.append(c);
        }
        return out.toString().trim();
    }

    private static int dim(int rgb, boolean bright) {
        if (bright) return rgb;
        int r = ((rgb >> 16) & 0xFF) * 30 / 100;
        int g = ((rgb >> 8) & 0xFF) * 30 / 100;
        int b = (rgb & 0xFF) * 30 / 100;
        return (r << 16) | (g << 8) | b;
    }
}
