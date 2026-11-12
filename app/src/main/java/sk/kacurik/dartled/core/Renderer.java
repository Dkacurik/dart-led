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

    private Renderer() {}

    public static Frame render(Scoreboard sb, int w, int h) {
        Frame f = new Frame(w, h);
        List<Scoreboard.Player> ps = sb.players;
        if (ps.isEmpty()) return f;

        // Short panels (e.g. 64x16): when the player at the oche can finish, show only
        // their score on top and the checkout darts underneath.
        if (h < 24) {
            for (int i = 0; i < ps.size(); i++) {
                Scoreboard.Player p = ps.get(i);
                if (p.active && !p.checkout.isEmpty()) {
                    drawCheckout(f, p, i, w, h);
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
            drawRow(f, p, i, 0, y0, w, rowH, bright);
        }
        return f;
    }

    private static void drawRow(Frame f, Scoreboard.Player p, int index, int x0, int y0, int w, int rowH, boolean bright) {
        String score = Integer.toString(p.score);
        String legs = Integer.toString(Math.min(p.legs, 99));
        PixelFont small = PixelFont.SMALL;

        int markerW = 2;                                   // 1px bar + 1px gap
        int legsW = small.width(legs, 1) + 3;              // 2px gap + digits + 1px right margin
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
            int lx = w - legsW + 2;
            int ly = y0 + (rowH - small.h) / 2;
            small.draw(f, legs, lx, ly, 1, dim(LEGS, bright));
        }

        if (p.active) {
            f.fillRect(x0, y0 + (rowH - scoreH) / 2, 1, scoreH, MARKER);
        }

        // Name (and checkout hint for the player at the oche, if there's height for it).
        int nameRoom = scoreX - markerW - 2;
        String name = ScreenParser.plain(p.name);
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
        nf.draw(f, shown, markerW, ny, ns, dim(NAME, bright));

        if (withCheckout) {
            String c = fit(small, co, 1, nameRoom);
            small.draw(f, c, markerW, ny + nf.h * ns + 1, 1, CHECKOUT);
        }
    }

    /** Top row: whose turn + remaining score. Bottom row: the checkout, e.g. "T20 T13 D16". */
    private static void drawCheckout(Frame f, Scoreboard.Player p, int index, int w, int h) {
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
        String name = fit(big, ScreenParser.plain(p.name), 1, scoreX - 2 - 3);
        big.draw(f, name, 2, y1, 1, NAME);

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
