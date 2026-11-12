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

    private Renderer() {}

    public static Frame render(Scoreboard sb, int w, int h) {
        Frame f = new Frame(w, h);
        List<Scoreboard.Player> ps = sb.players;
        if (ps.isEmpty()) return f;

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
