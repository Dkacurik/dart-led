package sk.kacurik.dartcam.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the dartboard in a colour camera picture by itself (no tapping).
 *
 * Plain Java (no Android). Works like this:
 *  1. Mark the strongly coloured pixels: red or green. On a dartboard only the double and treble
 *     rings and the bull are red/green; the singles are black/cream, the number ring black.
 *  2. From the middle of those pixels, look along 720 rays for the treble ring followed by the double
 *     ring, and fit an ellipse through the outer edge of the double ring.
 *  3. The ellipse fixes the board up to its rotation. The rotation comes from matching the whole
 *     red/green pattern around the double ring (the double of 20 is red), and the copy of 20 that
 *     points most upwards in the picture wins (the phone stands roughly upright).
 *  4. Refine: measure the exact image positions of the ring edges and the segment wires near where
 *     the current guess predicts them, fit a homography through all of them, repeat.
 *
 * Input pixels are 0xAARRGGBB, as Android's Bitmap.getPixels returns them.
 */
public final class AutoCalibrator {
    private AutoCalibrator() {}

    public static final class Result {
        /** Board millimetres -> image pixels, or null if the board was not found. */
        public final Homography boardToImg;
        /** The 4 calibration points (Board.CALIBRATION_POINTS) in image pixels, or null. */
        public final double[][] calPoints;
        public final double rmsPx;
        public final int inliers;
        /** Share of the red/green pattern around the board that matches (0..1). */
        public final double patternMatch;
        /** Why it failed, or null on success. */
        public final String failReason;

        Result(Homography h, double rms, int inliers, double match, String fail) {
            this.boardToImg = fail == null ? h : null;
            this.rmsPx = rms; this.inliers = inliers; this.patternMatch = match; this.failReason = fail;
            if (fail == null) {
                calPoints = new double[4][];
                for (int i = 0; i < 4; i++) calPoints[i] = h.map(Board.CALIBRATION_POINTS[i][0], Board.CALIBRATION_POINTS[i][1]);
            } else calPoints = null;
        }

        public boolean ok() { return failReason == null; }

        static Result fail(String why) { return new Result(null, Double.NaN, 0, 0, why); }

        @Override public String toString() {
            return ok() ? String.format(java.util.Locale.ROOT, "OK rms %.2f px, %d points, pattern %.0f%%", rmsPx, inliers, patternMatch * 100)
                    : "FAILED: " + failReason;
        }
    }

    static final byte OTHER = 0, RED = 1, GREEN = 2;

    /** Pixel class from relative colour tests (survive dim light and white-balance shifts). */
    static byte classify(int argb) {
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        int chroma = mx - mn;
        if (chroma < 22 || chroma < 0.33 * mx) return OTHER;
        // Hue in degrees.
        double hue;
        if (mx == r) hue = 60.0 * (g - b) / chroma;
        else if (mx == g) hue = 60.0 * (b - r) / chroma + 120;
        else hue = 60.0 * (r - g) / chroma + 240;
        if (hue < 0) hue += 360;
        if (hue >= 335 || hue <= 22) return RED;
        if (hue >= 75 && hue <= 175) return GREEN;
        return OTHER;
    }

    private static final double[] EDGES = {Board.R_DOUBLE_OUT, Board.R_DOUBLE_IN, Board.R_TREBLE_OUT, Board.R_TREBLE_IN};
    /** true: coloured inside, plain outside when walking outwards. */
    private static final boolean[] EDGE_COLOURED_INSIDE = {true, false, true, false};

    /** Same as {@link #find(int[], int, int, double, double)} with "up" = top of the picture. */
    public static Result find(int[] argb, int w, int h) { return find(argb, w, h, 0, -1); }

    /**
     * @param upX,upY direction of "up" (against gravity) in the picture, e.g. from the phone's
     *                accelerometer. The board hangs with 20 at the top, so this decides which of the
     *                10 red segments is the 20 (works within ±18° and with the phone at any roll).
     */
    public static Result find(int[] argb, int w, int h, double upX, double upY) {
        return find(argb, w, h, upX, upY, Double.NaN, Double.NaN);
    }

    /**
     * @param hintX,hintY where the board centre probably is (a tap on the screen, or the bull of the
     *                    tracked calibration), or NaN. The centre is looked for around it first, so other
     *                    round or red/green things in the picture can't pull Auto away.
     */
    public static Result find(int[] argb, int w, int h, double upX, double upY, double hintX, double hintY) {
        double un = Math.hypot(upX, upY);
        if (un < 1e-9) { upX = 0; upY = -1; } else { upX /= un; upY /= un; }
        byte[] cls = new byte[w * h];
        int n = 0;
        for (int i = 0; i < cls.length; i++) { cls[i] = classify(argb[i]); if (cls[i] != OTHER) n++; }
        if (n < 0.01 * w * h) return Result.fail("board not found (no red/green rings visible – more light?)");

        double[] c = null;
        if (!Double.isNaN(hintX) && !Double.isNaN(hintY)) c = centreNear(cls, w, h, hintX, hintY);
        if (c == null) c = findCentre(cls, w, h);
        double[] ell = null;
        double[] centre = c;
        // Ray casting from a point inside the bull area; repeat once from the fitted ellipse centre.
        for (int pass = 0; pass < 2; pass++) {
            List<double[]> outer = castRays(cls, w, h, centre[0], centre[1]);
            if (outer.size() < 120) return Result.fail("board not found (rings not recognised)");
            ell = fitEllipse(outer);
            if (ell == null) return Result.fail("board not found (ring shape)");
            centre = new double[]{ell[0], ell[1]};
            if (centre[0] < 0 || centre[1] < 0 || centre[0] >= w || centre[1] >= h)
                return Result.fail("board not found (centre outside picture)");
        }

        // Affine board->image map for rotation 0: b (mm) -> centre + A * b.
        double[] A = {ell[2] / Board.R_DOUBLE_OUT, ell[3] / Board.R_DOUBLE_OUT, ell[4] / Board.R_DOUBLE_OUT, ell[5] / Board.R_DOUBLE_OUT};
        // Segments from the red/green runs just inside the outer edge of the double ring. Perspective makes
        // them uneven in width, so they are read as runs, not matched against an even 18° pattern.
        int steps = 720;
        byte[] dbl = new byte[steps];
        for (int s = 0; s < steps; s++) {
            double g = Math.toRadians(s * 360.0 / steps);
            dbl[s] = sampleAffine(cls, w, h, centre, A, 166 * Math.sin(g), -166 * Math.cos(g));
        }
        List<int[]> runs = colourRuns(dbl);
        // Clean boundaries: two long-enough runs of different colour with only a wire between them.
        int nr = runs.size();
        if (nr < 6) return Result.fail("board pattern unclear (segments not seen)");
        int[] lens = new int[nr];
        for (int i = 0; i < nr; i++) lens[i] = runs.get(i)[3];
        int[] sl = lens.clone();
        Arrays.sort(sl);
        double med = sl[nr / 2];
        boolean[] good = new boolean[nr]; // boundary after run i
        for (int i = 0; i < nr; i++) {
            int[] a = runs.get(i), b = runs.get((i + 1) % nr);
            int gap = cyclicLen(a[2], b[1], steps) - 2;
            good[i] = a[0] != b[0] && gap <= 4 && runOk(lens, i) && runOk(lens, (i + 1) % nr) && lens[i] >= 0.3 * med;
        }
        // Longest cyclic chain of consecutive clean boundaries.
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < nr; i++) {
            if (!good[i] || good[(i - 1 + nr) % nr] && bestLen < nr) {
                if (!good[i]) continue;
                if (good[(i - 1 + nr) % nr]) continue; // not a chain start
            }
            int len = 0;
            while (len < nr && good[(i + len) % nr]) len++;
            if (len > bestLen) { bestLen = len; bestStart = i; }
        }
        if (bestStart < 0) { // every boundary is clean (no chain start found)
            boolean all = true;
            for (boolean g : good) all &= g;
            if (all) { bestStart = 0; bestLen = nr; }
        }
        if (bestLen < 8 || nr > 20 && bestLen > 20) return Result.fail("board pattern unclear – pull the darts out and try again");
        bestLen = Math.min(bestLen, 20);
        double[][] iInit = new double[bestLen][];
        for (int m = 0; m < bestLen; m++) {
            int[] a = runs.get((bestStart + m) % nr), b = runs.get((bestStart + m + 1) % nr);
            double g = Math.toRadians((a[2] + (cyclicLen(a[2], b[1], steps) - 1) / 2.0) * 360.0 / steps);
            double bx = 166 * Math.sin(g), by = -166 * Math.cos(g);
            iInit[m] = new double[]{centre[0] + A[0] * bx + A[1] * by, centre[1] + A[2] * bx + A[3] * by};
        }
        // Segment index (from 20, clockwise) of the run before the chain: parity from its colour; of the
        // 10 possible labelings take the one whose 20 points most upwards from the bull.
        int parity = runs.get(bestStart)[0] == RED ? 0 : 1;
        Homography H = null;
        double bestUp = -2;
        for (int s0 = parity; s0 < 20; s0 += 2) {
            double[][] bInit = new double[bestLen][];
            for (int m = 0; m < bestLen; m++) {
                double beta = Math.toRadians(18 * (s0 + m) + 9);
                bInit[m] = new double[]{166 * Math.sin(beta), -166 * Math.cos(beta)};
            }
            Homography cand;
            try { cand = Homography.fromPoints(bInit, iInit); } catch (RuntimeException e) { continue; }
            // Board angle of "up" seen from the bull: the closer to 0° (the middle of 20) the better.
            double[] bull = cand.map(0, 0);
            double up;
            try {
                double[] u = cand.inverse().map(bull[0] + 20 * upX, bull[1] + 20 * upY);
                up = Math.cos(Math.atan2(u[0], -u[1]));
            } catch (RuntimeException e) { continue; }
            if (up > bestUp) { bestUp = up; H = cand; }
        }
        if (H == null) return Result.fail("board not found (segments)");

        // Refinement.
        double rms = Double.NaN;
        int inl = 0;
        for (int it = 0; it < 6; it++) {
            double window = it == 0 ? 7 : it < 3 ? 5 : 3.5;
            List<double[]> bpts = new ArrayList<>(), ipts = new ArrayList<>();
            measure(cls, w, h, H, window, bpts, ipts);
            if (bpts.size() < 12) return Result.fail("board not found (edges not measured)");
            Homography fit = robustFit(bpts, ipts, 2.5);
            if (fit == null) return Result.fail("board not found (fit)");
            H = fit;
            double[] st = stats(H, bpts, ipts, 2.5);
            rms = st[0]; inl = (int) st[1];
        }
        double pm = patternMatch(cls, w, h, H);
        if (inl < 150) return new Result(H, rms, inl, pm, "board not found reliably (" + inl + " points)");
        if (rms > 2.0) return new Result(H, rms, inl, pm, String.format(java.util.Locale.ROOT, "board fit too loose (%.1f px)", rms));
        if (pm < 0.9) return new Result(H, rms, inl, pm, String.format(java.util.Locale.ROOT, "board pattern unclear (%.0f%%)", pm * 100));
        return new Result(H, rms, inl, pm, null);
    }

    /** A run about as long as its neighbours (perspective changes widths only gradually). */
    private static boolean runOk(int[] lens, int j) {
        int n = lens.length;
        double nb = (lens[(j - 1 + n) % n] + lens[(j + 1) % n]) / 2.0;
        return lens[j] >= 3 && lens[j] >= 0.6 * nb && lens[j] <= 1.6 * nb;
    }

    /** Length of the cyclic index range from a to b inclusive. */
    private static int cyclicLen(int a, int b, int n) { return ((b - a) % n + n) % n + 1; }

    /**
     * Runs of one colour around the ring: {colour, firstIndex, lastIndex}. Gaps (wires, flights) are
     * skipped; runs much shorter than the typical segment (noise, a coloured flight) are dropped and
     * their neighbours joined.
     */
    static List<int[]> colourRuns(byte[] ring) {
        int n = ring.length;
        // Start right after a colour change so no run wraps around the start.
        int start = -1;
        byte last = OTHER;
        for (int i = 0; i < 2 * n && start < 0; i++) {
            byte c = ring[i % n];
            if (c == OTHER) continue;
            if (last != OTHER && c != last) start = i % n;
            last = c;
        }
        List<int[]> runs = new ArrayList<>();
        if (start < 0) return runs;
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            byte c = ring[i];
            if (c == OTHER) continue;
            int[] cur = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (cur != null && cur[0] == c) { cur[2] = i; cur[3]++; }
            else runs.add(new int[]{c, i, i, 1});
        }
        // Drop short runs, then join neighbours of equal colour; repeat until stable.
        while (runs.size() > 1) {
            int[] counts = new int[runs.size()];
            for (int i = 0; i < counts.length; i++) counts[i] = runs.get(i)[3];
            int[] sorted = counts.clone();
            Arrays.sort(sorted);
            int med = sorted[sorted.length / 2];
            int minIdx = 0;
            for (int i = 1; i < counts.length; i++) if (counts[i] < counts[minIdx]) minIdx = i;
            if (counts[minIdx] >= 3) break;
            runs.remove(minIdx);
            int m = runs.size();
            if (m < 2) break;
            int prev = (minIdx - 1 + m) % m, next = minIdx % m;
            int[] a = runs.get(prev), b = runs.get(next);
            if (a[0] == b[0]) { a[2] = b[2]; a[3] += b[3]; runs.remove(next); }
        }
        return runs;
    }

    /**
     * How much the camera zoom may change so that the board out to {@code radiusMm} (number ring and
     * flights) fills {@code fill} of the picture. Digital zoom enlarges about the picture centre, so
     * every point moves away from the centre by the factor; the factor is limited by the point that
     * would leave the picture first. &gt; 1 = zoom in, &lt; 1 = zoom out.
     */
    public static double zoomFactor(Homography boardToImg, int w, int h, double radiusMm, double fill) {
        double cx = w / 2.0, cy = h / 2.0, f = Double.MAX_VALUE;
        for (int i = 0; i < 72; i++) {
            double a = Math.toRadians(i * 5);
            double[] p = boardToImg.map(radiusMm * Math.sin(a), -radiusMm * Math.cos(a));
            double dx = Math.abs(p[0] - cx), dy = Math.abs(p[1] - cy);
            if (dx > 1e-6) f = Math.min(f, cx * fill / dx);
            if (dy > 1e-6) f = Math.min(f, cy * fill / dy);
        }
        return f == Double.MAX_VALUE ? 1 : f;
    }

    /** Red for segments with an even index from the top (20, 18, 13, ...), green for odd. */
    static byte expectedColour(double boardAngleDeg) {
        double a = ((boardAngleDeg % 360) + 360) % 360;
        int idx = (int) Math.floor((a + 9.0) / 18.0) % 20;
        return idx % 2 == 0 ? RED : GREEN;
    }

    private static byte at(byte[] cls, int w, int h, double x, double y) {
        // Pixel i covers [i, i+1), like the camera picture drawn in BoardView.
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        if (xi < 0 || yi < 0 || xi >= w || yi >= h) return OTHER;
        return cls[yi * w + xi];
    }

    private static byte sampleAffine(byte[] cls, int w, int h, double[] c, double[] A, double bx, double by) {
        return at(cls, w, h, c[0] + A[0] * bx + A[1] * by, c[1] + A[2] * bx + A[3] * by);
    }

    /** Robust middle of the coloured pixels: median, then trimmed mean around it. */
    private static double[] roughCentre(byte[] cls, int w, int h) {
        int[] hx = new int[w], hy = new int[h];
        int n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (cls[y * w + x] != OTHER) { hx[x]++; hy[y]++; n++; }
        double mx = median(hx, n), my = median(hy, n);
        for (int it = 0; it < 3; it++) {
            // Mean distance as spread estimate, keep pixels within 1.6x of it.
            double sd = 0; int k = 0;
            for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2)
                if (cls[y * w + x] != OTHER) { sd += Math.hypot(x - mx, y - my); k++; }
            if (k == 0) break;
            double lim = 1.6 * sd / k, sx = 0, sy = 0; int m = 0;
            for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2)
                if (cls[y * w + x] != OTHER && Math.hypot(x - mx, y - my) < lim) { sx += x; sy += y; m++; }
            if (m == 0) break;
            mx = sx / m; my = sy / m;
        }
        return new double[]{mx, my};
    }

    private static double median(int[] hist, int n) {
        int acc = 0;
        for (int i = 0; i < hist.length; i++) { acc += hist[i]; if (acc * 2 >= n) return i; }
        return hist.length / 2.0;
    }

    /**
     * Along each ray, find coloured runs; the treble ring followed directly by the double ring has a
     * characteristic radius ratio (~103/166). Returns the outer edge points of the double ring.
     */
    private static List<double[]> castRays(byte[] cls, int w, int h, double cx, double cy) {
        return castRays(cls, w, h, cx, cy, 720);
    }

    /**
     * The board centre: of a coarse grid of candidates (plus the colour centroid), the one from which
     * the most rays see "treble ring, then double ring". Coloured things beside the board don't fool it.
     */
    /**
     * Best board centre within ~20 % of the picture around a hint, or null if nothing there looks like
     * a board (then the whole picture is searched).
     */
    private static double[] centreNear(byte[] cls, int w, int h, double hx, double hy) {
        double radius = 0.2 * Math.max(w, h);
        int step = Math.max(4, Math.min(w, h) / 48);
        double[] best = null;
        int bestHits = 0;
        for (double y = hy - radius; y <= hy + radius; y += step)
            for (double x = hx - radius; x <= hx + radius; x += step) {
                if (x < 0 || y < 0 || x >= w || y >= h || Math.hypot(x - hx, y - hy) > radius) continue;
                int hits = castRays(cls, w, h, x, y, 48).size();
                if (hits > bestHits) { bestHits = hits; best = new double[]{x, y}; }
            }
        return bestHits >= 16 ? best : null; // a third of the rays must see "treble, then double"
    }

    private static double[] findCentre(byte[] cls, int w, int h) {
        double[] best = roughCentre(cls, w, h);
        int bestHits = castRays(cls, w, h, best[0], best[1], 48).size();
        int step = Math.max(8, Math.min(w, h) / 24);
        for (int y = step / 2; y < h; y += step)
            for (int x = step / 2; x < w; x += step) {
                int hits = castRays(cls, w, h, x, y, 48).size();
                if (hits > bestHits) { bestHits = hits; best = new double[]{x, y}; }
            }
        return best;
    }

    private static List<double[]> castRays(byte[] cls, int w, int h, double cx, double cy, int nRays) {
        List<double[]> out = new ArrayList<>();
        int maxR = (int) Math.hypot(w, h);
        int[] rs = new int[64], re = new int[64];
        for (int k = 0; k < nRays; k++) {
            double a = 2 * Math.PI * k / nRays, dx = Math.cos(a), dy = Math.sin(a);
            int runs = 0, gap = 99;
            boolean inRun = false;
            for (int r = 2; r < maxR; r++) {
                double x = cx + dx * r, y = cy + dy * r;
                if (x < 0 || y < 0 || x >= w || y >= h) break;
                boolean col = cls[(int) y * w + (int) x] != OTHER;
                if (col) {
                    if (!inRun) {
                        if (runs > 0 && gap <= 2) { inRun = true; } // bridge a wire
                        else { if (runs == rs.length) break; rs[runs] = r; runs++; inRun = true; }
                    }
                    re[runs - 1] = r;
                    gap = 0;
                } else {
                    if (inRun) { inRun = false; gap = 0; }
                    gap++;
                }
            }
            for (int i = 0; i + 1 < runs; i++) {
                double wt = re[i] - rs[i] + 1, wd = re[i + 1] - rs[i + 1] + 1;
                if (wt < 2 || wd < 2) continue;
                double mt = (rs[i] + re[i]) / 2.0, md = (rs[i + 1] + re[i + 1]) / 2.0;
                double ratio = mt / md, wr = wd / wt;
                if (ratio > 0.45 && ratio < 0.8 && wr > 0.33 && wr < 3 && wd < 0.2 * md) {
                    out.add(new double[]{cx + dx * (re[i + 1] + 0.5), cy + dy * (re[i + 1] + 0.5)});
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Ellipse through points, robust (refits without outliers).
     * Returns {cx, cy, a11, a12, a21, a22}: image = centre + [[a11,a12],[a21,a22]] * unitCircle, det > 0.
     */
    static double[] fitEllipse(List<double[]> pts) {
        List<double[]> use = new ArrayList<>(pts);
        double[] res = null;
        for (int it = 0; it < 5; it++) {
            if (use.size() < 20) return null;
            double mx = 0, my = 0;
            for (double[] p : use) { mx += p[0]; my += p[1]; }
            mx /= use.size(); my /= use.size();
            double s = 0;
            for (double[] p : use) s += Math.hypot(p[0] - mx, p[1] - my);
            s = s / use.size();
            // A x² + B xy + C y² + D x + E y = 1 in normalised coords.
            double[][] ata = new double[5][5];
            double[] atb = new double[5];
            for (double[] p : use) {
                double x = (p[0] - mx) / s, y = (p[1] - my) / s;
                double[] r = {x * x, x * y, y * y, x, y};
                for (int i = 0; i < 5; i++) { atb[i] += r[i]; for (int j = 0; j < 5; j++) ata[i][j] += r[i] * r[j]; }
            }
            double[] q;
            try { q = Homography.solve(ata, atb); } catch (IllegalArgumentException e) { return null; }
            double A = q[0], B = q[1], C = q[2], D = q[3], E = q[4];
            double det = 4 * A * C - B * B;
            if (det <= 0) return null;
            double x0 = (B * E - 2 * C * D) / det, y0 = (B * D - 2 * A * E) / det;
            double F = -1 + (D * x0 + E * y0) / 2; // constant after moving to the centre
            // Eigen-decomposition of [[A, B/2],[B/2, C]].
            double tr = A + C, dif = Math.sqrt((A - C) * (A - C) + B * B);
            double l1 = (tr + dif) / 2, l2 = (tr - dif) / 2;
            if (l1 <= 0 || l2 <= 0 || F >= 0) return null;
            double th = 0.5 * Math.atan2(B, A - C); // direction of the l1 eigenvector
            double a1 = Math.sqrt(-F / l1) * s, a2 = Math.sqrt(-F / l2) * s;
            double e1x = Math.cos(th), e1y = Math.sin(th), e2x = -Math.sin(th), e2y = Math.cos(th);
            res = new double[]{mx + x0 * s, my + y0 * s, a1 * e1x, a2 * e2x, a1 * e1y, a2 * e2y};
            if (res[2] * res[5] - res[3] * res[4] < 0) { res[3] = -res[3]; res[5] = -res[5]; }
            // Residual: distance of the point from the ellipse along the ray from its centre.
            List<double[]> keep = new ArrayList<>();
            double[] errs = new double[use.size()];
            double[] inv = inv2(res[2], res[3], res[4], res[5]);
            for (int i = 0; i < use.size(); i++) errs[i] = ellipseErr(res, inv, use.get(i));
            double[] sorted = errs.clone();
            Arrays.sort(sorted);
            double lim = Math.max(1.5, 3 * sorted[sorted.length / 2]);
            for (int i = 0; i < use.size(); i++) if (errs[i] <= lim) keep.add(use.get(i));
            if (keep.size() == use.size()) break;
            use = keep;
        }
        return res;
    }

    private static double[] inv2(double a, double b, double c, double d) {
        double det = a * d - b * c;
        return new double[]{d / det, -b / det, -c / det, a / det};
    }

    private static double ellipseErr(double[] e, double[] inv, double[] p) {
        double dx = p[0] - e[0], dy = p[1] - e[1];
        double u = inv[0] * dx + inv[1] * dy, v = inv[2] * dx + inv[3] * dy;
        double rho = Math.hypot(u, v);
        return Math.abs(rho - 1) * Math.hypot(dx, dy) / Math.max(rho, 1e-9);
    }

    /**
     * Measure ring edges (along rays at known board angles) and segment wires (along arcs at known
     * radii) close to where H predicts them. Adds board points and their measured image points.
     */
    private static void measure(byte[] cls, int w, int h, Homography H, double window,
                                List<double[]> bpts, List<double[]> ipts) {
        // Ring edges: 3 rays per segment.
        for (int k = 0; k < 20; k++) {
            for (int o = -1; o <= 1; o++) {
                double a = Math.toRadians(18 * k + 5 * o), sa = Math.sin(a), ca = -Math.cos(a);
                for (int e = 0; e < EDGES.length; e++) {
                    double R = EDGES[e];
                    double best = Double.NaN, bestD = 1e9;
                    double step = 0.25;
                    boolean prev = colouredAt(cls, w, h, H, (R - window) * sa, (R - window) * ca);
                    for (double r = R - window + step; r <= R + window; r += step) {
                        boolean cur = colouredAt(cls, w, h, H, r * sa, r * ca);
                        if (prev != cur && prev == EDGE_COLOURED_INSIDE[e]) {
                            double rr = r - step / 2;
                            if (Math.abs(rr - R) < bestD) { bestD = Math.abs(rr - R); best = rr; }
                        }
                        prev = cur;
                    }
                    if (!Double.isNaN(best)) {
                        bpts.add(new double[]{R * sa, R * ca});
                        ipts.add(H.map(best * sa, best * ca));
                    }
                }
            }
        }
        // Segment wires on the double and treble rings.
        double[] radii = {166, 103};
        for (int k = 0; k < 20; k++) {
            double beta = 18 * k + 9;
            byte first = expectedColour(beta - 4), second = expectedColour(beta + 4);
            for (double R : radii) {
                double span = Math.toDegrees(window / R) * 1.6 + 1.5;
                double lastFirst = Double.NaN, best = Double.NaN, bestD = 1e9;
                for (double d = -span; d <= span; d += 0.1) {
                    double a = Math.toRadians(beta + d);
                    byte c = at(cls, w, h, H, R * Math.sin(a), -R * Math.cos(a));
                    if (c == first) lastFirst = d;
                    else if (c == second && !Double.isNaN(lastFirst) && d - lastFirst < 2.5) {
                        double mid = (lastFirst + d) / 2;
                        if (Math.abs(mid) < bestD) { bestD = Math.abs(mid); best = mid; }
                        lastFirst = Double.NaN;
                    }
                }
                if (!Double.isNaN(best)) {
                    double a0 = Math.toRadians(beta), a1 = Math.toRadians(beta + best);
                    bpts.add(new double[]{R * Math.sin(a0), -R * Math.cos(a0)});
                    ipts.add(H.map(R * Math.sin(a1), -R * Math.cos(a1)));
                }
            }
        }
    }

    private static byte at(byte[] cls, int w, int h, Homography H, double bx, double by) {
        double[] p = H.map(bx, by);
        return at(cls, w, h, p[0], p[1]);
    }

    private static boolean colouredAt(byte[] cls, int w, int h, Homography H, double bx, double by) {
        return at(cls, w, h, H, bx, by) != OTHER;
    }

    /** Least-squares homography, dropping points with residual above maxErr (and gross outliers first). */
    private static Homography robustFit(List<double[]> b, List<double[]> img, double maxErr) {
        List<double[]> ub = new ArrayList<>(b), ui = new ArrayList<>(img);
        Homography H = null;
        for (int it = 0; it < 6; it++) {
            if (ub.size() < 8) return H;
            try {
                H = Homography.fromPoints(ub.toArray(new double[0][]), ui.toArray(new double[0][]));
            } catch (RuntimeException e) { return H; }
            double[] errs = new double[ub.size()];
            for (int i = 0; i < ub.size(); i++) {
                double[] p = H.map(ub.get(i)[0], ub.get(i)[1]);
                errs[i] = Math.hypot(p[0] - ui.get(i)[0], p[1] - ui.get(i)[1]);
            }
            double[] s = errs.clone();
            Arrays.sort(s);
            double lim = Math.max(maxErr, 3 * s[s.length / 2]);
            if (it >= 3) lim = maxErr;
            List<double[]> nb = new ArrayList<>(), ni = new ArrayList<>();
            for (int i = 0; i < ub.size(); i++) if (errs[i] <= lim) { nb.add(ub.get(i)); ni.add(ui.get(i)); }
            if (nb.size() == ub.size()) break;
            ub = nb; ui = ni;
        }
        return H;
    }

    /** {rms of inliers, inlier count}. */
    private static double[] stats(Homography H, List<double[]> b, List<double[]> img, double maxErr) {
        double s = 0; int n = 0;
        for (int i = 0; i < b.size(); i++) {
            double[] p = H.map(b.get(i)[0], b.get(i)[1]);
            double e = Math.hypot(p[0] - img.get(i)[0], p[1] - img.get(i)[1]);
            if (e <= maxErr) { s += e * e; n++; }
        }
        return new double[]{n == 0 ? Double.NaN : Math.sqrt(s / n), n};
    }

    /** Share of coloured samples on the double/treble ring centres that have the expected colour. */
    static double patternMatch(byte[] cls, int w, int h, Homography H) {
        int good = 0, all = 0;
        for (int s = 0; s < 720; s++) {
            double deg = s * 0.5;
            // Skip samples right on a wire.
            double inSeg = ((deg + 9) % 18 + 18) % 18;
            if (inSeg < 2 || inSeg > 16) continue;
            byte want = expectedColour(deg);
            double a = Math.toRadians(deg);
            for (double R : new double[]{166, 103}) {
                byte c = at(cls, w, h, H, R * Math.sin(a), -R * Math.cos(a));
                if (c == OTHER) continue;
                all++;
                if (c == want) good++;
            }
        }
        return all < 200 ? 0 : (double) good / all;
    }
}
