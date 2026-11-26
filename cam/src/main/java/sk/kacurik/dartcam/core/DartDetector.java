package sk.kacurik.dartcam.core;

import java.util.ArrayDeque;

/**
 * Finds new darts by comparing camera frames with a reference picture of the board.
 *
 * Plain Java (no Android), fed with grayscale frames of a fixed size. Works like this:
 *  1. Wait until the picture has been still for a few frames (the dart has landed, the board stopped shaking).
 *  2. Compare it with the last still picture. A small changed area = a new dart.
 *  3. If the picture now matches the empty board again, the darts were pulled out (takeout).
 *  4. The dart is a long blob: thin at the tip, wide at the flight. The tip is the thin end.
 *  5. The tip is mapped through the calibration homography onto the board and scored.
 */
public final class DartDetector {

    public enum Type { DART, TAKEOUT }

    public static final class Event {
        public final Type type;
        public final double imgX, imgY;    // tip in image pixels (DART only)
        public final double boardX, boardY; // tip in board millimetres (DART only)
        public final Hit hit;               // DART only
        public final int pixels;            // size of the changed area
        public final int dartInVisit;       // 1..3 for DART

        Event(Type type, double ix, double iy, double bx, double by, Hit hit, int pixels, int dartInVisit) {
            this.type = type; this.imgX = ix; this.imgY = iy; this.boardX = bx; this.boardY = by;
            this.hit = hit; this.pixels = pixels; this.dartInVisit = dartInVisit;
        }

        @Override public String toString() {
            return type == Type.TAKEOUT ? "TAKEOUT" : "DART#" + dartInVisit + " " + hit
                    + String.format(java.util.Locale.ROOT, " @(%.1f,%.1f)mm", boardX, boardY);
        }
    }

    /** Tuning knobs; defaults are for frames around 480x640. */
    public static final class Params {
        public int diffThreshold = 28;        // grey-level change that counts as "changed"
        public int stableFrames = 4;          // still frames needed before we evaluate
        public double motionFraction = 0.0015; // < this share of masked pixels moving = still
        public double minDartFraction = 0.0006; // a dart changes at least this share of the board area
        public double emptyFraction = 0.0010; // frame "matches the empty board" below this share
        public double occludedFraction = 0.12; // more than this changed = someone in front of the board
        public double maskRadiusMm = 260;     // area watched (board + surround, flights stick out)
        public int recoverFrames = 45;        // still frames after a big unexplained change before adopting it (~2 s)
    }

    private final int w, h;
    private final Params p;
    private Homography imgToBoard;
    private boolean[] mask;
    private int maskCount;

    private int[] prev, refVisit, refEmpty;
    private int still;
    private boolean moving, blocked;
    private int dartsInVisit;

    public DartDetector(int width, int height, Params params) {
        this.w = width; this.h = height; this.p = params == null ? new Params() : params;
    }

    public int width() { return w; }
    public int height() { return h; }
    public int dartsInVisit() { return dartsInVisit; }
    public boolean calibrated() { return imgToBoard != null; }

    /**
     * Where the camera stands, projected onto the board plane (board mm). Darts stick out of the
     * board towards the camera, so on the flattened board every dart's flight lies further from
     * this point than its tip. That tells the tip from the flight even when the flight is hard to see.
     */
    private double[] cameraFoot;

    public double[] cameraFoot() { return cameraFoot == null ? null : cameraFoot.clone(); }

    /** imgToBoard maps image pixels to board millimetres. Resets the references. */
    public void setCalibration(Homography imgToBoard) {
        this.imgToBoard = imgToBoard;
        this.cameraFoot = estimateCameraFoot(imgToBoard.inverse(), w / 2.0, h / 2.0, Math.max(w, h));
        mask = new boolean[w * h];
        maskCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] b = imgToBoard.map(x, y);
                boolean in = Math.hypot(b[0], b[1]) < p.maskRadiusMm;
                mask[y * w + x] = in;
                if (in) maskCount++;
            }
        }
        reset();
    }

    /** Forget the references: the next still frame becomes the empty board. */
    public void reset() {
        prev = refVisit = refEmpty = null;
        still = 0;
        moving = false;
        blocked = false;
        dartsInVisit = 0;
    }

    /** A dart the camera could not see (bounce-out, missed the board) was entered by hand. */
    public void addManualDart() { dartsInVisit = Math.min(3, dartsInVisit + 1); }

    /** The last detected dart was wrong and was undone by hand. */
    public void forgetLastDart() { if (dartsInVisit > 0) dartsInVisit--; }

    /** Tell the detector the board is empty now (e.g. after a manual "next player"). */
    public void markEmpty() {
        if (prev != null) { refEmpty = prev.clone(); refVisit = prev.clone(); }
        dartsInVisit = 0;
    }

    /**
     * Feed one grayscale frame (w*h values 0..255, row-major).
     * Returns an event or null.
     */
    public Event feed(int[] gray) {
        if (gray.length != w * h) throw new IllegalArgumentException("frame size");
        if (imgToBoard == null) return null;
        int[] cur = gray.clone();
        if (prev == null) {
            prev = cur; refVisit = cur.clone(); refEmpty = cur.clone();
            return null;
        }
        int motion = countDiff(cur, prev, null);
        prev = cur;
        if (motion > p.motionFraction * maskCount) {
            moving = true;
            still = 0;
            return null;
        }
        still++;
        if (!moving && blocked && still > p.recoverFrames) {
            // The view changed a lot and stayed that way (light/exposure change, something moved
            // permanently): accept the new picture instead of waiting forever.
            blocked = false;
            refVisit = cur.clone();
            if (dartsInVisit == 0) refEmpty = cur.clone();
            return null;
        }
        if (!moving) {
            // Nothing happening: let the references follow slow light changes.
            if (countDiff(cur, refVisit, null) < p.emptyFraction * maskCount) blend(refVisit, cur);
            if (dartsInVisit == 0 && countDiff(cur, refEmpty, null) < p.emptyFraction * maskCount) blend(refEmpty, cur);
            return null;
        }
        if (still < p.stableFrames) return null;
        moving = false;
        return evaluate(cur);
    }

    private Event evaluate(int[] cur) {
        boolean[] changed = new boolean[w * h];
        int n = countDiff(cur, refVisit, changed);
        if (n < p.minDartFraction * maskCount) return null;               // nothing new (vibration, noise)
        blocked = false;
        if (n > p.occludedFraction * maskCount) {
            // Someone stands in front / big light change. Board empty again? -> takeout.
            if (countDiff(cur, refEmpty, null) < p.emptyFraction * maskCount * 3) return takeout(cur, n);
            blocked = true;                                               // wait for the view to clear
            return null;
        }
        int emptyDiff = countDiff(cur, refEmpty, null);
        if (dartsInVisit > 0 && emptyDiff < p.emptyFraction * maskCount) return takeout(cur, n);
        if (dartsInVisit >= 3) return takeout(cur, n); // after three darts, any change means darts were pulled

        int[] blob = dartPixels(changed);
        if (blob == null || blob.length < p.minDartFraction * maskCount) {
            refVisit = cur;
            return null;
        }
        double[] tip = findTip(blob);
        double[] b = imgToBoard.map(tip[0], tip[1]);
        Hit hit = Board.score(b[0], b[1]);
        dartsInVisit++;
        refVisit = cur;
        return new Event(Type.DART, tip[0], tip[1], b[0], b[1], hit, blob.length, dartsInVisit);
    }

    private Event takeout(int[] cur, int n) {
        refVisit = cur.clone();
        refEmpty = cur.clone();
        dartsInVisit = 0;
        return new Event(Type.TAKEOUT, 0, 0, 0, 0, null, n, 0);
    }

    private void blend(int[] ref, int[] cur) {
        for (int i = 0; i < ref.length; i++) ref[i] = (ref[i] * 7 + cur[i]) >> 3;
    }

    private int countDiff(int[] a, int[] b, boolean[] out) {
        int n = 0, t = p.diffThreshold;
        for (int i = 0; i < a.length; i++) {
            if (!mask[i]) continue;
            if (Math.abs(a[i] - b[i]) > t) {
                n++;
                if (out != null) out[i] = true;
            }
        }
        return n;
    }

    /** The changed pixels that belong to the new dart: all 8-connected pieces except noise specks. */
    private int[] dartPixels(boolean[] changed) {
        boolean[] dil = new boolean[w * h];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (changed[i]) {
                    dil[i] = dil[i - 1] = dil[i + 1] = dil[i - w] = dil[i + w] = true;
                }
            }
        int[] label = new int[w * h];
        int best = 0, bestLabel = 0, next = 1;
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < w * h; s++) {
            if (!dil[s] || label[s] != 0) continue;
            int lab = next++, size = 0;
            label[s] = lab;
            q.add(s);
            while (!q.isEmpty()) {
                int i = q.poll();
                if (changed[i]) size++;
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int j = ny * w + nx;
                        if (dil[j] && label[j] == 0) { label[j] = lab; q.add(j); }
                    }
            }
            sizes.add(size);
            if (size > best) { best = size; bestLabel = lab; }
        }
        if (best == 0) return null;
        // Only one dart lands per evaluation, so every real piece of the change belongs to it
        // (a dark shaft over a black segment can split a dart in two). Drop only noise specks.
        int total = 0;
        for (int sz : sizes) total += sz;
        int minPiece = Math.max(6, (int) (total * 0.04));
        boolean[] keep = new boolean[next];
        int n = 0;
        for (int l = 1; l < next; l++) if (sizes.get(l - 1) >= minPiece) { keep[l] = true; n += sizes.get(l - 1); }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < w * h; i++) if (changed[i] && keep[label[i]]) out[k++] = i;
        return out;
    }

    /**
     * The dart is elongated: find its main axis (PCA), then compare how wide the blob is near
     * each end. The flight is wide, the point is thin, so the tip is the narrower end.
     */
    double[] findTip(int[] blob) {
        if (cameraFoot != null && Math.hypot(cameraFoot[0], cameraFoot[1]) > 120) {
            double[] tip = tipNearestFoot(blob);
            if (tip != null) return tip;
        }
        return tipByShape(blob);
    }

    /**
     * Every part of a dart that sticks out of the board, flattened onto the board plane, lands
     * further from the camera's foot point than the tip does. So the tip is the blob pixel that
     * lies closest to the foot point (averaged over the last few millimetres for stability).
     * This does not care how big or visible the flight is.
     */
    private double[] tipNearestFoot(int[] blob) {
        double[] d = new double[blob.length];
        double min = Double.MAX_VALUE;
        for (int k = 0; k < blob.length; k++) {
            double[] b = imgToBoard.map(blob[k] % w + 0.5, blob[k] / w + 0.5);
            d[k] = Math.hypot(b[0] - cameraFoot[0], b[1] - cameraFoot[1]);
            min = Math.min(min, d[k]);
        }
        // Ignore a few stray pixels: use the 0.5th percentile as the end.
        double[] sorted = d.clone();
        java.util.Arrays.sort(sorted);
        double end = sorted[Math.min(sorted.length - 1, blob.length / 200)];
        double sx = 0, sy = 0; int n = 0;
        for (int k = 0; k < blob.length; k++) {
            if (d[k] <= end + 2.0) { sx += blob[k] % w + 0.5; sy += blob[k] / w + 0.5; n++; }
        }
        return n == 0 ? null : new double[]{sx / n, sy / n};
    }

    private double[] tipByShape(int[] blob) {
        double mx = 0, my = 0;
        for (int i : blob) { mx += i % w; my += i / w; }
        mx /= blob.length; my /= blob.length;
        double sxx = 0, syy = 0, sxy = 0;
        for (int i : blob) {
            double dx = i % w - mx, dy = i / w - my;
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double ux = Math.cos(theta), uy = Math.sin(theta);
        double tmin = Double.MAX_VALUE, tmax = -Double.MAX_VALUE;
        double[] t = new double[blob.length], s = new double[blob.length];
        for (int k = 0; k < blob.length; k++) {
            double dx = blob[k] % w - mx, dy = blob[k] / w - my;
            t[k] = dx * ux + dy * uy;
            s[k] = -dx * uy + dy * ux;
            tmin = Math.min(tmin, t[k]);
            tmax = Math.max(tmax, t[k]);
        }
        double len = tmax - tmin;
        double slice = Math.max(2, len * 0.18);
        double widthLo = spread(t, s, tmin, tmin + slice), widthHi = spread(t, s, tmax - slice, tmax);
        boolean tipAtMin = widthLo <= widthHi;
        double edge = tipAtMin ? tmin : tmax;
        // Tip = mean of the few pixels at the very end of that side.
        double end = Math.max(1.5, len * 0.03);
        double sx = 0, sy = 0; int n = 0;
        for (int k = 0; k < blob.length; k++) {
            if (Math.abs(t[k] - edge) <= end) { sx += blob[k] % w; sy += blob[k] / w; n++; }
        }
        return new double[]{sx / n, sy / n};
    }

    /**
     * Camera position from the board->image homography, assuming square pixels and the optical
     * centre in the middle of the image. The focal length is solved from the homography itself
     * (the board's x and y axes must be perpendicular and equally long in 3D); if that is not
     * possible (camera almost straight in front) a typical phone focal length is used.
     * Returns {x, y} of the camera in board millimetres, or null.
     */
    static double[] estimateCameraFoot(Homography boardToImg, double cx, double cy, double fallbackF) {
        double[] H = boardToImg.values();
        // Shift image origin to the optical centre: H' = T * H, T = [1 0 -cx; 0 1 -cy; 0 0 1]
        double[] m = new double[9];
        for (int j = 0; j < 3; j++) {
            m[j] = H[j] - cx * H[6 + j];
            m[3 + j] = H[3 + j] - cy * H[6 + j];
            m[6 + j] = H[6 + j];
        }
        double h11 = m[0], h12 = m[1], h21 = m[3], h22 = m[4], h31 = m[6], h32 = m[7];
        double sum = 0; int n = 0;
        double den1 = h31 * h32;
        if (Math.abs(den1) > 1e-14) {
            double f2 = -(h11 * h12 + h21 * h22) / den1;
            if (f2 > 0) { sum += Math.sqrt(f2); n++; }
        }
        double den2 = h31 * h31 - h32 * h32;
        if (Math.abs(den2) > 1e-14) {
            double f2 = -(h11 * h11 + h21 * h21 - h12 * h12 - h22 * h22) / den2;
            if (f2 > 0) { sum += Math.sqrt(f2); n++; }
        }
        double f = n > 0 ? sum / n : fallbackF;
        if (!(f > fallbackF * 0.3 && f < fallbackF * 4)) f = fallbackF;
        // K^-1 * H'
        double[] c1 = {m[0] / f, m[3] / f, m[6]}, c2 = {m[1] / f, m[4] / f, m[7]}, c3 = {m[2] / f, m[5] / f, m[8]};
        double l1 = Math.sqrt(c1[0] * c1[0] + c1[1] * c1[1] + c1[2] * c1[2]);
        double l2 = Math.sqrt(c2[0] * c2[0] + c2[1] * c2[1] + c2[2] * c2[2]);
        double lam = 2 / (l1 + l2);
        double[] r1 = {c1[0] * lam, c1[1] * lam, c1[2] * lam}, r2 = {c2[0] * lam, c2[1] * lam, c2[2] * lam};
        double[] t = {c3[0] * lam, c3[1] * lam, c3[2] * lam};
        // C = -R^T t; only the in-plane part is needed and it does not depend on the sign of lam.
        double x = -(r1[0] * t[0] + r1[1] * t[1] + r1[2] * t[2]);
        double y = -(r2[0] * t[0] + r2[1] * t[1] + r2[2] * t[2]);
        if (Double.isNaN(x) || Double.isNaN(y)) return null;
        return new double[]{x, y};
    }

    private static double spread(double[] t, double[] s, double from, double to) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE; int n = 0;
        for (int k = 0; k < t.length; k++) {
            if (t[k] >= from && t[k] <= to) { lo = Math.min(lo, s[k]); hi = Math.max(hi, s[k]); n++; }
        }
        return n == 0 ? 0 : hi - lo;
    }
}
