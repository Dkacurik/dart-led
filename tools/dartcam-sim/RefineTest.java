import sk.kacurik.dartcam.core.*;

import java.util.*;

/**
 * Full-resolution re-check (TipRefiner) vs. the detector alone.
 *
 * Scenes are rendered at 2x (the camera picture); the detector gets the 2x2-averaged half-size copy
 * like on the phone. After each detected dart the window around the tip is cut from the 2x "before"
 * and "after" pictures and re-checked.
 *
 * Run from the repository root:
 *   javac -d /tmp/dartsim cam/src/main/java/sk/kacurik/dartcam/core/*.java tools/dartcam-sim/*.java
 *   java -cp /tmp/dartsim RefineTest
 */
public class RefineTest {
    static final int W = SimTest.W, H = SimTest.H, S = 2, FW = W * S, FH = H * S;
    static final Random rnd = new Random(Long.getLong("seed", 5));
    static int failures;

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAIL " + what); failures++; } }

    /** Camera for the full-res picture. */
    static void camera(double[] cam) {
        SimTest.camera(cam[0], cam[1], cam[2], cam[3] * S, cam[4]);
        SimTest.ppx = FW / 2.0;
        SimTest.ppy = FH / 2.0;
    }

    static int[] renderFull(Homography i2bFull) {
        int[] fr = new int[FW * FH];
        for (int y = 0; y < FH; y++) for (int x = 0; x < FW; x++) {
            double[] b = i2bFull.map(x + 0.5, y + 0.5);
            fr[y * FW + x] = SimTest.boardGray(b[0], b[1]);
        }
        return fr;
    }

    /** Like SimTest.paintDart, on the full-res picture. */
    static void paintDart(int[] fr, double bx, double by, double tiltX, double tiltY, int flightGray) {
        double[] dir = SimTest.norm(new double[]{tiltX, tiltY, -1});
        double[][] parts = {{0, 18, 1.0, 175}, {18, 58, 3.5, 150}, {58, 90, 2.0, 55}, {90, 125, 15, flightGray}};
        for (double[] part : parts) {
            for (double t = part[0]; t <= part[1]; t += 0.35) {
                double[] p = SimTest.project(bx + dir[0] * t, by + dir[1] * t, dir[2] * t);
                double hw = part[2];
                boolean flight = part[0] == 90;
                if (flight) hw *= 0.25 + 0.75 * (t - 90) / 35;
                double rpx = Math.max(0.5, hw * SimTest.f / p[2]);
                double ex = rpx, ey = flight ? rpx * 0.5 : rpx;
                for (double dy = -ey; dy <= ey; dy += 0.5)
                    for (double dx = -ex; dx <= ex; dx += 0.5) {
                        if (!flight && dx * dx + dy * dy > rpx * rpx) continue;
                        int x = (int) Math.floor(p[0] + dx), y = (int) Math.floor(p[1] + dy);
                        if (x >= 0 && y >= 0 && x < FW && y < FH) fr[y * FW + x] = (int) part[3];
                    }
            }
        }
    }

    /**
     * Testing on a screen: no darts, but rolling brightness bands (refresh beating with the exposure) and
     * 1-px shifts of the whole picture (moiré moving with focus). Whatever the detector reports must be
     * dropped by DartGate, as in the app.
     */
    static void screenFlicker() {
        camera(new double[]{0, 330, -380, 520, 0});
        Homography b2iFull = SimTest.boardToImg();
        Homography i2bFull = b2iFull.inverse();
        double[][] img = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] p = b2iFull.map(Board.CALIBRATION_POINTS[i][0], Board.CALIBRATION_POINTS[i][1]);
            img[i] = new double[]{p[0] / S, p[1] / S};
        }
        DartDetector det = new DartDetector(W, H, null);
        det.setCalibration(Homography.fromPoints(img, Board.CALIBRATION_POINTS));
        int[] emptyFull = renderFull(i2bFull);
        int[] beforeFull = null;
        int raw = 0, scored = 0;
        for (int i = 0; i < 8; i++) { int[] f = noisy(emptyFull, 3); det.feed(down(f)); if (det.idle()) beforeFull = f; }
        for (int burst = 0; burst < 40; burst++) {
            double phase = rnd.nextDouble() * 2 * Math.PI, period = 40 + rnd.nextInt(80), amp = 0.10 + rnd.nextDouble() * 0.08;
            int shift = rnd.nextInt(3) - 1;
            int[] fl = new int[FW * FH];
            for (int y = 0; y < FH; y++) {
                double k = 1 + amp * Math.sin(2 * Math.PI * y / period + phase);
                for (int x = 0; x < FW; x++) {
                    int sx = Math.max(0, Math.min(FW - 1, x + shift));
                    fl[y * FW + x] = Math.min(255, (int) (emptyFull[y * FW + sx] * k));
                }
            }
            for (int i = 0; i < 14; i++) {
                int[] f = noisy(i < 12 ? fl : emptyFull, 3);
                DartDetector.Event ev = det.feed(down(f));
                if (ev != null && ev.type == DartDetector.Type.DART) {
                    raw++;
                    if (Boolean.getBoolean("feat")) System.out.printf(Locale.ROOT, "  FEAT phantom expl %.2f %s px %d burst %d frame %d amp %.2f period %.0f shift %d tip %.0f,%.0f%n", det.lastExplained, ev.hit, ev.pixels, burst, i, amp, period, shift, ev.imgX, ev.imgY);
                    TipRefiner.Result rr = null;
                    if (beforeFull != null) {
                        double[] win = TipRefiner.window(ev.imgX, ev.imgY, det.imgToBoard(), W, H);
                        int cx0 = (int) Math.floor(win[0] * S), cy0 = (int) Math.floor(win[1] * S);
                        int cw = Math.min(FW, (int) Math.ceil(win[2] * S)) - cx0, ch = Math.min(FH, (int) Math.ceil(win[3] * S)) - cy0;
                        rr = TipRefiner.refine(crop(beforeFull, cx0, cy0, cw, ch), crop(f, cx0, cy0, cw, ch), cw, ch,
                                (double) cx0 / S, (double) cy0 / S, S, ev.imgX, ev.imgY, ev.hit, det.imgToBoard(), det.cameraFoot(), 28);
                    }
                    String why = DartGate.reject(ev.hit, rr);
                    if (why == null) scored++;
                    else det.forgetLastDart();
                }
                if (det.idle()) beforeFull = f;
            }
        }
        System.out.printf(Locale.ROOT, "  screen flicker, no darts: detector saw %d \"darts\", %d scored after DartGate%n", raw, scored);
        // Before the "explained by the old picture" check this gave 26 of 40 bursts as darts.
        check(scored <= 2, "screen flicker: at most 2 phantom darts in 40 harsh flicker bursts");
    }

    static int[] down(int[] full) {
        int[] o = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = 2 * y * FW + 2 * x;
            o[y * W + x] = (full[i] + full[i + 1] + full[i + FW] + full[i + FW + 1] + 2) >> 2;
        }
        return o;
    }

    static int[] noisy(int[] fr, double sd) {
        int[] o = new int[fr.length];
        for (int i = 0; i < fr.length; i++) o[i] = Math.max(0, Math.min(255, fr[i] + (int) Math.round(rnd.nextGaussian() * sd)));
        return o;
    }

    static int[] crop(int[] full, int cx0, int cy0, int cw, int ch) {
        int[] o = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(full, (cy0 + y) * FW + cx0, o, y * cw, cw);
        return o;
    }

    public static void main(String[] args) {
        // Board.wireDistance basics.
        check(Math.abs(Board.wireDistance(0, -103) - 4) < 1e-9, "wireDistance middle of T20 = 4 mm");
        check(Board.wireDistance(0, -170) < 1e-9, "wireDistance on the double edge = 0");
        double[] onWire = {130 * Math.sin(Math.toRadians(9)), -130 * Math.cos(Math.toRadians(9))};
        check(Board.wireDistance(onWire[0], onWire[1]) < 1e-9, "wireDistance on the 20/1 wire = 0");
        check(Math.abs(Board.wireDistance(0, -130) - 130 * Math.sin(Math.toRadians(9))) < 1e-9, "wireDistance middle of S20");

        double[][] cams = {
                {-180, 260, -420, 520, 0}, {0, 330, -380, 520, 0}, {320, 40, -380, 520, 0},
                {-60, -300, -420, 520, 0}, {200, 220, -500, 650, 90}};
        String[] names = {"below-left", "below", "right side", "above", "below-right rotated"};
        int total = 0, coarseOk = 0, refinedOk = 0, refinedRan = 0, changedToRight = 0, changedToWrong = 0;
        int wrongFlagged = 0, wrongUnflagged = 0, coarseWrong = 0, flagged = 0;
        int dcTotal = 0, dcMeasured = 0, dcOk = 0, dcFixed = 0, dcBroke = 0;
        List<Double> cErr = new ArrayList<>(), rErr = new ArrayList<>();
        long refineNanos = 0;
        for (int ci = 0; ci < cams.length; ci++) {
            camera(cams[ci]);
            Homography b2iFull = SimTest.boardToImg();
            Homography i2bFull = b2iFull.inverse();
            // Calibration like Auto gives it (~0.3 px), in analysis pixels.
            double[][] img = new double[4][];
            for (int i = 0; i < 4; i++) {
                double[] p = b2iFull.map(Board.CALIBRATION_POINTS[i][0], Board.CALIBRATION_POINTS[i][1]);
                img[i] = new double[]{p[0] / S + rnd.nextGaussian() * 0.3, p[1] / S + rnd.nextGaussian() * 0.3};
            }
            DartDetector det = new DartDetector(W, H, null);
            det.setCalibration(Homography.fromPoints(img, Board.CALIBRATION_POINTS));

            int[] emptyFull = renderFull(i2bFull);
            int[] emptyDown = down(emptyFull);
            int camTotal = 0, camCoarse = 0, camRefined = 0;
            for (int v = 0; v < 12; v++) {
                int[] scene = emptyFull.clone();
                int[] sceneDown = emptyDown;
                for (int i = 0; i < 6; i++) det.feed(noisy(sceneDown, 3));
                List<Object[]> visitShots = new ArrayList<>();   // per dart: before, after, window, ev, first result, truth
                for (int d = 0; d < 3; d++) {
                    int[] beforeFull = noisy(scene, 3);
                    double r = Math.sqrt(rnd.nextDouble()) * 168, a = rnd.nextDouble() * 2 * Math.PI;
                    if (rnd.nextDouble() < 0.4) r = rnd.nextBoolean() ? 103 + rnd.nextGaussian() * 2 : 166 + rnd.nextGaussian() * 1.5;
                    double bx = r * Math.sin(a), by = -r * Math.cos(a);
                    Hit expected = Board.score(bx, by);
                    int flightGray = rnd.nextDouble() < 0.4 ? 215 : 40 + rnd.nextInt(200);
                    int[] fl = scene.clone();
                    paintDart(fl, bx * 0.6, by * 0.6 + 40, 0, 0.2, flightGray);
                    int[] flDown = down(fl);
                    det.feed(noisy(flDown, 3)); det.feed(noisy(flDown, 3));
                    paintDart(scene, bx, by, rnd.nextGaussian() * 0.12, 0.15 + rnd.nextGaussian() * 0.12, flightGray);
                    sceneDown = down(scene);
                    DartDetector.Event ev = null;
                    for (int i = 0; i < 10; i++) { DartDetector.Event e = det.feed(noisy(sceneDown, 3)); if (ev == null) ev = e; }
                    if (ev == null || ev.type != DartDetector.Type.DART) continue;
                    total++; camTotal++;
                    if (Boolean.getBoolean("feat")) System.out.printf(Locale.ROOT, "  FEAT dart expl %.2f%n", det.lastExplained);
                    boolean cOk = ev.hit.equals(expected);
                    if (cOk) { coarseOk++; camCoarse++; } else coarseWrong++;
                    cErr.add(Math.hypot(ev.boardX - bx, ev.boardY - by));

                    // Zoom: same window from the full-res before/after pictures.
                    double[] win = TipRefiner.window(ev.imgX, ev.imgY, det.imgToBoard(), W, H);
                    int cx0 = (int) Math.floor(win[0] * S), cy0 = (int) Math.floor(win[1] * S);
                    int cx1 = Math.min(FW, (int) Math.ceil(win[2] * S)), cy1 = Math.min(FH, (int) Math.ceil(win[3] * S));
                    int cw = cx1 - cx0, ch = cy1 - cy0;
                    int[] afterFull = noisy(scene, 3);
                    long t0 = System.nanoTime();
                    TipRefiner.Result rr = TipRefiner.refine(crop(beforeFull, cx0, cy0, cw, ch), crop(afterFull, cx0, cy0, cw, ch),
                            cw, ch, (double) cx0 / S, (double) cy0 / S, S, ev.imgX, ev.imgY, ev.hit,
                            det.imgToBoard(), det.cameraFoot(), 28);
                    refineNanos += System.nanoTime() - t0;
                    Hit finalHit = ev.hit;
                    boolean close = false;
                    if (rr.ok) {
                        refinedRan++;
                        finalHit = rr.hit;
                        close = rr.closeCall;
                        rErr.add(Math.hypot(rr.boardX - bx, rr.boardY - by));
                        if (rr.changed) { if (rr.hit.equals(expected)) changedToRight++; else changedToWrong++; }
                    } else rErr.add(Math.hypot(ev.boardX - bx, ev.boardY - by));
                    visitShots.add(new Object[]{beforeFull, afterFull, new int[]{cx0, cy0, cw, ch}, ev, rr, expected, finalHit});
                    if (close) flagged++;
                    if (finalHit.equals(expected)) { refinedOk++; camRefined++; }
                    else if (close) wrongFlagged++;
                    else {
                        wrongUnflagged++;
                        if (Boolean.getBoolean("dbg")) System.out.printf(Locale.ROOT, "  silent miss: true %s (%.1f,%.1f) wire %.2f | det %s err %.2f | zoom %s err %.2f d=(%.2f,%.2f)%n",
                                expected, bx, by, Board.wireDistance(bx, by), ev.hit, Math.hypot(ev.boardX - bx, ev.boardY - by),
                                rr, rr.ok ? Math.hypot(rr.boardX - bx, rr.boardY - by) : -1, rr.boardX - bx, rr.boardY - by);
                    }
                }
                // Double check of every dart on the last still picture (all darts in), later darts masked.
                int[] finalFull = noisy(scene, 3);
                for (int k = 0; k < visitShots.size(); k++) {
                    Object[] sh = visitShots.get(k);
                    int[] wv = (int[]) sh[2];
                    DartDetector.Event e0 = (DartDetector.Event) sh[3];
                    TipRefiner.Result first = (TipRefiner.Result) sh[4];
                    Hit truth = (Hit) sh[5], entered = (Hit) sh[6];
                    int[] settled = k < visitShots.size() - 1 ? crop((int[]) sh[1], wv[0], wv[1], wv[2], wv[3]) : null;
                    double cx = first.ok ? first.tipX : e0.imgX, cy = first.ok ? first.tipY : e0.imgY;
                    TipRefiner.Result second = TipRefiner.refine(crop((int[]) sh[0], wv[0], wv[1], wv[2], wv[3]),
                            crop(finalFull, wv[0], wv[1], wv[2], wv[3]), settled, wv[2], wv[3], (double) wv[0] / S, (double) wv[1] / S, S,
                            cx, cy, entered, det.imgToBoard(), det.cameraFoot(), 28);
                    DoubleCheck.Verdict vd = DoubleCheck.decide(entered, first, second, e0.boardX, e0.boardY);
                    dcTotal++;
                    if (vd.measured) dcMeasured++;
                    if (vd.hit.equals(truth)) dcOk++;
                    if (!vd.hit.equals(entered)) { if (vd.hit.equals(truth)) dcFixed++; else dcBroke++; }
                    if (Boolean.getBoolean("dc") && (!vd.hit.equals(entered) || !vd.hit.equals(truth)))
                        System.out.printf(Locale.ROOT, "  DC dart %d/%d truth %s entered %s -> %s | first %s | second %s | %s%n",
                                k + 1, visitShots.size(), truth, entered, vd.hit, first, second, vd.note);
                }
                // Takeout.
                int[] hand = scene.clone();
                for (int y = 360; y < 960; y++) for (int x = 200; x < 800; x++) hand[y * FW + x] = 160;
                int[] handDown = down(hand);
                for (int i = 0; i < 3; i++) det.feed(noisy(handDown, 3));
                for (int i = 0; i < 10; i++) det.feed(noisy(emptyDown, 3));
            }
            System.out.printf(Locale.ROOT, "%-20s darts %2d: detector %2d correct, with zoom re-check %2d correct%n",
                    names[ci], camTotal, camCoarse, camRefined);
        }
        Collections.sort(cErr); Collections.sort(rErr);
        System.out.printf(Locale.ROOT, "%nZOOM: detector %d/%d (%.0f%%) -> re-checked %d/%d (%.0f%%); re-check ran on %d%n",
                coarseOk, total, 100.0 * coarseOk / total, refinedOk, total, 100.0 * refinedOk / total, refinedRan);
        System.out.printf(Locale.ROOT, "  tip error median %.2f -> %.2f mm, 90%% %.2f -> %.2f mm%n",
                cErr.get(cErr.size() / 2), rErr.get(rErr.size() / 2), cErr.get((int) (cErr.size() * 0.9)), rErr.get((int) (rErr.size() * 0.9)));
        System.out.printf(Locale.ROOT, "  number changed by re-check: %d to the right one, %d to a wrong one%n", changedToRight, changedToWrong);
        System.out.printf(Locale.ROOT, "  flagged 'close to wire': %d; wrong after re-check: %d flagged, %d NOT flagged (detector alone: %d wrong)%n",
                flagged, wrongFlagged, wrongUnflagged, coarseWrong);
        System.out.printf(Locale.ROOT, "  re-check time %.1f ms per dart%n", refineNanos / 1e6 / Math.max(1, total));
        System.out.printf(Locale.ROOT, "%nDOUBLE CHECK (every dart again on the last picture, later darts masked): %d/%d correct (%.0f%%),"
                        + " measured %d; corrected %d to the right number, %d to a wrong one%n",
                dcOk, dcTotal, 100.0 * dcOk / Math.max(1, dcTotal), dcMeasured, dcFixed, dcBroke);
        check(dcOk >= refinedOk, "double check must not lose correct darts overall");
        check(dcMeasured >= 0.85 * dcTotal, "double check measures at least 85% of darts");
        screenFlicker();
        check(refinedOk >= coarseOk, "re-check must not lose correct darts overall");
        check(wrongUnflagged < coarseWrong, "fewer silent mistakes than the detector alone");
        check(refinedRan >= 0.9 * total, "re-check runs on at least 90% of darts");
        if (failures > 0) { System.out.println(failures + " ZOOM FAILURES"); System.exit(1); }
    }
}
