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
        public double missRadiusMm = 250;     // tips up to here (number ring, board edge, surround) = MISS; further out ignored
        public double explainedReject = 0.94; // change explained this well by the old picture = not a dart
        public double vanishedShare = 0.85;
        public double maxSpreadMm = 60;
        public boolean blurTolerant = true;   // a refocus / 2 px shift (3x3 means agree) is explained too
        public double minFill = 0.15;         // a dart fills its extent (length × width); outlines from focus/shake don't       // changed pixels spread wider than this (std. dev. along the main axis) = not one dart   // changed area explained this well by the clean board = a dart was pulled out
    }

    private final int w, h;
    private final Params p;
    private Homography imgToBoard;
    private boolean[] mask;
    private int maskCount;

    private int[] prev, refVisit, refEmpty;
    private int[] refClean;     // the board seen without darts (start, and every takeout that left it empty)
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

    /** Image pixels -> board millimetres, or null before calibration. */
    public Homography imgToBoard() { return imgToBoard; }

    /** True while nothing moves in front of the board (the picture is a good "before" for the next dart). */
    public boolean idle() { return prev != null && !moving && !blocked; }

    /**
     * Where the camera stands, projected onto the board plane (board mm). Darts stick out of the
     * board towards the camera, so on the flattened board every dart's flight lies further from
     * this point than its tip. That tells the tip from the flight even when the flight is hard to see.
     */
    private double[] cameraFoot;

    public double[] cameraFoot() { return cameraFoot == null ? null : cameraFoot.clone(); }

    /** Nothing is moving in front of the camera right now (also true while "blocked" after a big change). */
    public boolean still() { return prev != null && !moving; }

    /**
     * A slightly better calibration of the same view (tracking smoothing): swap it in but keep the
     * reference pictures and the darts of the visit.
     */
    public void updateCalibration(Homography imgToBoard) {
        if (this.imgToBoard == null) { setCalibration(imgToBoard); return; }
        int[] pv = prev, rv = refVisit, re = refEmpty, rc = refClean;
        int still0 = still, darts = dartsInVisit;
        boolean mv = moving, bl = blocked;
        setCalibration(imgToBoard);
        prev = pv; refVisit = rv; refEmpty = re; refClean = rc;
        still = still0; dartsInVisit = darts; moving = mv; blocked = bl;
    }

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
        prev = refVisit = refEmpty = refClean = null;
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
            prev = cur; refVisit = cur.clone(); refEmpty = cur.clone(); refClean = cur.clone();
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
            if (dartsInVisit == 0) { refEmpty = cur.clone(); refClean = cur.clone(); }
            return null;
        }
        if (!moving) {
            // Nothing happening: let the references follow slow light changes.
            if (countDiff(cur, refVisit, null) < p.emptyFraction * maskCount) blend(refVisit, cur);
            if (dartsInVisit == 0 && countDiff(cur, refEmpty, null) < p.emptyFraction * maskCount) blend(refEmpty, cur);
            if (dartsInVisit == 0 && refClean != null && countDiff(cur, refClean, null) < p.emptyFraction * maskCount) blend(refClean, cur);
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
            if (looksLike(cur, refEmpty, p.emptyFraction * maskCount * 3)
                    || looksLike(cur, refClean, p.emptyFraction * maskCount * 3)) return takeout(cur, n);
            blocked = true;                                               // wait for the view to clear
            return null;
        }
        if (dartsInVisit > 0 && looksLike(cur, refEmpty, p.emptyFraction * maskCount)) return takeout(cur, n);
        if (dartsInVisit >= 3) return takeout(cur, n); // after three darts, any change means darts were pulled

        int[] blob = dartPixels(changed);
        boolean vanished = false;
        if (blob == null || blob.length < p.minDartFraction * maskCount) {
            refVisit = cur;
            return null;
        }
        lastExplained = explained(blob, cur, refVisit);
        if (refClean != null && lastExplained < p.explainedReject) {
            double clean = explained(blob, cur, refClean);
            if (clean >= p.vanishedShare && clean - lastExplained >= 0.12) vanished = true;
        }
        if (vanished) {
            // Where the picture changed it looks like the clean board again: a dart was pulled out
            // (also with a light/exposure change from someone standing at the board), not thrown.
            vanishedChanges++;
            if (dartsInVisit > 0) return takeout(cur, n);
            refVisit = cur.clone();
            refEmpty = cur.clone();
            return null;
        }
        lastSpreadMm = spreadMm(blob);
        if (lastSpreadMm > p.maxSpreadMm) {
            // Spread over the board: a dart plus something else (a hand at the edge), or no dart at
            // all (focus, a tiny shift of the camera: wires and edges change everywhere). A dart is
            // one piece: keep the largest piece if that is compact, else it is not a dart.
            int[] piece = largestPiece(blob);
            double sp = spreadMm(piece);
            lastPieceShare = (double) piece.length / blob.length;
            if (sp > p.maxSpreadMm || piece.length < p.minDartFraction * maskCount) {
                lastSpreadMm = sp;
                ignoredChanges++;
                refVisit = cur;
                return null;
            }
            blob = piece;
            lastSpreadMm = sp;
            lastExplained = explained(blob, cur, refVisit);
        }
        if (lastFill < p.minFill) {
            // Scattered thin lines (wire and edge outlines from focus or a camera shake), not a solid object.
            ignoredChanges++;
            refVisit = cur;
            return null;
        }
        if (lastExplained >= p.explainedReject) {
            ignoredChanges++;
            // The old picture, a bit brighter/darker or shifted by a pixel, explains the change:
            // flicker, exposure, moiré, camera shake – not a new object.
            refVisit = cur;
            return null;
        }
        double[] tip = findTip(blob);
        double[] b = imgToBoard.map(tip[0], tip[1]);
        if (Math.hypot(b[0], b[1]) > p.missRadiusMm) {
            // Beyond the board and its surround: something on the wall, a person – not a dart.
            refVisit = cur;
            return null;
        }
        if (Math.hypot(b[0], b[1]) > Board.R_DOUBLE_OUT && !dartLike(blob)) {
            // Outside the double ring only a dart-shaped change counts as a miss; light flicker on
            // the number ring, a shadow at the board edge… are taken into the reference.
            ignoredChanges++;
            refVisit = cur;
            return null;
        }
        Hit hit = Board.score(b[0], b[1]);
        dartsInVisit++;
        refVisit = cur;
        return new Event(Type.DART, tip[0], tip[1], b[0], b[1], hit, blob.length, dartsInVisit);
    }

    /** Spread of the last change on the board (mm, std. dev. along its main axis); for tuning. */
    public double lastSpreadMm = Double.NaN, lastPieceShare = Double.NaN, lastWidthMm = Double.NaN, lastFill = Double.NaN;

    /** The largest 8-connected piece of the changed pixels (1-pixel gaps bridged). */
    int[] largestPiece(int[] blob) {
        boolean[] in = new boolean[w * h];
        for (int i : blob) in[i] = true;
        int[] label = new int[w * h];
        int best = 0, bestSize = 0, next = 1;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s0 : blob) {
            if (label[s0] != 0) continue;
            int lab = next++, size = 0;
            label[s0] = lab;
            q.add(s0);
            while (!q.isEmpty()) {
                int i = q.poll();
                size++;
                int x = i % w, y = i / w;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int j = ny * w + nx;
                        if (in[j] && label[j] == 0) { label[j] = lab; q.add(j); }
                    }
            }
            if (size > bestSize) { bestSize = size; best = lab; }
        }
        int[] out = new int[bestSize];
        int k = 0;
        for (int i : blob) if (label[i] == best) out[k++] = i;
        return out;
    }

    /** How far the changed pixels spread on the board: std. dev. along their main axis, in mm. */
    double spreadMm(int[] blob) {
        double mx = 0, my = 0, sxx = 0, syy = 0, sxy = 0;
        double[][] bp = new double[blob.length][];
        for (int k = 0; k < blob.length; k++) {
            bp[k] = imgToBoard.map(blob[k] % w + 0.5, blob[k] / w + 0.5);
            mx += bp[k][0]; my += bp[k][1];
        }
        mx /= blob.length; my /= blob.length;
        for (double[] q : bp) {
            double dx = q[0] - mx, dy = q[1] - my;
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        sxx /= blob.length; syy /= blob.length; sxy /= blob.length;
        double tr = sxx + syy, det = sxx * syy - sxy * sxy;
        double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        lastWidthMm = Math.sqrt(Math.max(0, tr / 2 - disc) * 12);   // full width of an even strip
        double major = Math.sqrt(tr / 2 + disc);
        // Share of the change's extent (length × width) actually changed: a dart is solid, focus or
        // camera-shake changes are thin lines scattered over an area.
        double[] c0 = imgToBoard.inverse().map(mx, my);
        double[] a = imgToBoard.map(c0[0], c0[1]), bx = imgToBoard.map(c0[0] + 1, c0[1]), by = imgToBoard.map(c0[0], c0[1] + 1);
        double pxArea = Math.abs((bx[0] - a[0]) * (by[1] - a[1]) - (bx[1] - a[1]) * (by[0] - a[0]));
        lastFill = blob.length * pxArea / Math.max(1, major * Math.sqrt(12) * Math.max(lastWidthMm, 1));
        return major;
    }

    /** Shape of the last change outside the double ring (elongation, alignment with the camera); for tuning. */
    public double lastElongation = Double.NaN, lastAlignment = Double.NaN;
    public double minElongation = 1.4, minAlignment = 0.8;
    public double lastAreaMm2 = Double.NaN, maxCompactAreaMm2 = 1500, maxDartAreaMm2 = 3500;

    /**
     * Is this change shaped like a dart? On the flattened board a dart is a long streak from its tip
     * away from the camera's foot point (it sticks out towards the camera): elongated, and its long
     * axis points at the foot point. Flicker patches on the number ring run along the ring instead.
     */
    boolean dartLike(int[] blob) {
        double mx = 0, my = 0;
        double[][] bp = new double[blob.length][];
        for (int k = 0; k < blob.length; k++) {
            bp[k] = imgToBoard.map(blob[k] % w + 0.5, blob[k] / w + 0.5);
            mx += bp[k][0]; my += bp[k][1];
        }
        mx /= blob.length; my /= blob.length;
        double sxx = 0, syy = 0, sxy = 0;
        for (double[] q : bp) {
            double dx = q[0] - mx, dy = q[1] - my;
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        double tr = sxx + syy, det = sxx * syy - sxy * sxy;
        double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double l1 = tr / 2 + disc, l2 = Math.max(1e-9, tr / 2 - disc);
        lastElongation = Math.sqrt(l1 / l2);
        double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double ux = Math.cos(theta), uy = Math.sin(theta);
        lastAlignment = 1;
        if (cameraFoot != null) {
            double fx = mx - cameraFoot[0], fy = my - cameraFoot[1], fl = Math.hypot(fx, fy);
            if (fl > 1e-6) lastAlignment = Math.abs(ux * fx + uy * fy) / fl;
        }
        // Area on the board (mm²) from the pixel size at the centre of the change.
        double[] c0 = imgToBoard.inverse().map(mx, my);
        double[] a = imgToBoard.map(c0[0], c0[1]), bx = imgToBoard.map(c0[0] + 1, c0[1]), by = imgToBoard.map(c0[0], c0[1] + 1);
        double pxArea = Math.abs((bx[0] - a[0]) * (by[1] - a[1]) - (bx[1] - a[1]) * (by[0] - a[0]));
        lastAreaMm2 = blob.length * pxArea;
        if (lastAreaMm2 > maxDartAreaMm2) return false;             // far bigger than a dart: a light patch
        if (lastAreaMm2 <= maxCompactAreaMm2) return true;          // small: a dart seen (nearly) end-on
        return lastElongation >= minElongation && lastAlignment >= minAlignment;
    }

    /** Share of the last evaluated change explained by the old picture (see explained()); for tests/tuning. */
    public double lastExplained = Double.NaN;
    /** How many changes were ignored as "not a new object" (for the debug record). */
    public int ignoredChanges;
    /** How many changes were darts being pulled out (for tests and the debug record). */
    public int vanishedChanges;

    /**
     * cur matches ref apart from light changes: fewer than maxPixels changed pixels that a locally
     * brighter/darker ref doesn't explain (someone standing at the board changes the exposure).
     */
    boolean looksLike(int[] cur, int[] ref, double maxPixels) {
        if (ref == null) return false;
        boolean[] ch = new boolean[w * h];
        int n = countDiff(cur, ref, ch);
        if (n < maxPixels) return true;
        if (n > p.occludedFraction * maskCount * 2) return false;    // far too different
        int[] px = new int[n];
        int k = 0;
        for (int i = 0; i < ch.length; i++) if (ch[i]) px[k++] = i;
        return n * (1 - explained(px, cur, ref)) < maxPixels;
    }

    /**
     * Share of the changed pixels that the old picture explains: shifted by at most 1 px and/or with a
     * locally different brightness (factor fitted in a 9x9 window). Close to 1 for flicker bands,
     * exposure steps, moiré or a tiny camera shake; clearly lower when a new object (a dart) appeared.
     */
    double explained(int[] blob, int[] cur, int[] ref) {
        int explained = 0;
        double tol = p.diffThreshold * 0.6;
        for (int i : blob) {
            int x = i % w, y = i / w;
            double lxy = 0, lxx = 0;
            for (int yy = Math.max(0, y - 4); yy <= Math.min(h - 1, y + 4); yy++)
                for (int xx = Math.max(0, x - 4); xx <= Math.min(w - 1, x + 4); xx++) {
                    int j = yy * w + xx;
                    lxy += (double) cur[j] * ref[j]; lxx += (double) ref[j] * ref[j];
                }
            double kl = lxx > 0 ? lxy / lxx : 1;
            boolean ok = false;
            for (int dy = -1; dy <= 1 && !ok; dy++)
                for (int dx = -1; dx <= 1 && !ok; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int r = ref[ny * w + nx];
                    ok = Math.abs(cur[i] - r) <= tol || Math.abs(cur[i] - kl * r) <= tol;
                }
            if (!ok && p.blurTolerant) {
                // Refocus / a shift of up to 2 px: the 3x3 means of both pictures still agree, while a
                // new object (wider than a couple of pixels) changes them.
                double bc = mean3(cur, x, y);
                for (int dy = -2; dy <= 2 && !ok; dy++)
                    for (int dx = -2; dx <= 2 && !ok; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue;
                        double br = mean3(ref, nx, ny);
                        ok = Math.abs(bc - br) <= tol;
                    }
            }
            if (ok) explained++;
        }
        return (double) explained / blob.length;
    }

    private double mean3(int[] a, int x, int y) {
        int sum = 0, cnt = 0;
        for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++)
            for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) { sum += a[yy * w + xx]; cnt++; }
        return (double) sum / cnt;
    }

    private Event takeout(int[] cur, int n) {
        if (looksLike(cur, refClean, p.emptyFraction * maskCount)) refClean = cur.clone();
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
