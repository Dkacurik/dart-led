import sk.kacurik.dartcam.core.*;

import java.util.*;

/**
 * End-to-end test of the detector with a real pinhole camera model: the board is seen in
 * perspective, darts stick ~12 cm out of the board towards the camera, frames are noisy,
 * darts fly in, a hand pulls them out. Several camera positions.
 */
/*
 * Run from the repository root:
 *   javac -d /tmp/dartsim cam/src/main/java/sk/kacurik/dartcam/core/*.java tools/dartcam-sim/SimTest.java
 *   java -cp /tmp/dartsim SimTest
 */
public class SimTest {
    static final int W = 480, H = 640;
    static Random rnd = new Random(7);

    static double f, ppx = W / 2.0, ppy = H / 2.0;
    static double[][] R; static double[] C;

    /** Camera at (cx,cy,cz) board mm (z<0 = in front of the board), looking at the bull. */
    static void camera(double cx, double cy, double cz, double focal, double rollDeg) {
        f = focal; C = new double[]{cx, cy, cz};
        double[] fw = norm(new double[]{-cx, -cy, -cz});
        double[] x = norm(cross(new double[]{0, 1, 0}, fw));
        double[] y = cross(fw, x);
        double r = Math.toRadians(rollDeg), c = Math.cos(r), s = Math.sin(r);
        double[] xr = {c * x[0] + s * y[0], c * x[1] + s * y[1], c * x[2] + s * y[2]};
        double[] yr = {-s * x[0] + c * y[0], -s * x[1] + c * y[1], -s * x[2] + c * y[2]};
        R = new double[][]{xr, yr, fw};
    }

    static double[] project(double X, double Y, double Z) {
        double[] d = {X - C[0], Y - C[1], Z - C[2]};
        double xc = dot(R[0], d), yc = dot(R[1], d), zc = dot(R[2], d);
        return new double[]{f * xc / zc + ppx, f * yc / zc + ppy, zc};
    }

    static Homography boardToImg() {
        double[][] src = {{-150, -150}, {150, -150}, {150, 150}, {-150, 150}, {0, 0}};
        double[][] dst = new double[src.length][];
        for (int i = 0; i < src.length; i++) { double[] p = project(src[i][0], src[i][1], 0); dst[i] = new double[]{p[0], p[1]}; }
        return Homography.fromPoints(src, dst);
    }

    static int boardGray(double x, double y) {
        double r = Math.hypot(x, y);
        if (r > 225) return 70;
        if (r > Board.R_DOUBLE_OUT) return 18;
        if (r <= Board.R_INNER_BULL) return 90;
        if (r <= Board.R_OUTER_BULL) return 120;
        double a = Math.toDegrees(Math.atan2(x, -y)); if (a < 0) a += 360;
        int idx = (int) Math.floor((a + 9) / 18) % 20;
        boolean dark = idx % 2 == 0;
        boolean ring = (r >= Board.R_TREBLE_IN && r <= Board.R_TREBLE_OUT) || r >= Board.R_DOUBLE_IN;
        if (ring) return dark ? 90 : 120;
        return dark ? 28 : 215;
    }

    static int[] render(Homography imgToBoard) {
        int[] fr = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double[] b = imgToBoard.map(x + 0.5, y + 0.5);
            fr[y * W + x] = boardGray(b[0], b[1]);
        }
        return fr;
    }

    /**
     * Dart stuck in at (bx,by), body coming out of the board (negative z), slightly tilted.
     * Parts (mm along the dart, radius): point 0-18 r1, barrel 18-58 r3.5, shaft 58-90 r2,
     * flight 90-125 half-width up to 15. flightGray is sometimes the same as the board (hard case).
     */
    static void paintDart(int[] fr, double bx, double by, double tiltX, double tiltY, int flightGray) {
        double[] dir = norm(new double[]{tiltX, tiltY, -1});
        double[][] parts = {{0, 18, 1.0, 175}, {18, 58, 3.5, 150}, {58, 90, 2.0, 55}, {90, 125, 15, flightGray}};
        for (double[] part : parts) {
            for (double t = part[0]; t <= part[1]; t += 0.7) {
                double[] p = project(bx + dir[0] * t, by + dir[1] * t, dir[2] * t);
                double hw = part[2];
                boolean flight = part[0] == 90;
                if (flight) hw *= 0.25 + 0.75 * (t - 90) / 35;
                double rpx = Math.max(0.5, hw * f / p[2]);
                double ex = rpx, ey = flight ? rpx * 0.5 : rpx;
                for (double dy = -ey; dy <= ey; dy += 0.5)
                    for (double dx = -ex; dx <= ex; dx += 0.5) {
                        if (!flight && dx * dx + dy * dy > rpx * rpx) continue;
                        int x = (int) Math.round(p[0] + dx), y = (int) Math.round(p[1] + dy);
                        if (x >= 0 && y >= 0 && x < W && y < H) fr[y * W + x] = (int) part[3];
                    }
            }
        }
    }

    static int[] noisy(int[] fr) {
        int[] o = new int[fr.length];
        for (int i = 0; i < fr.length; i++) o[i] = Math.max(0, Math.min(255, fr[i] + (int) Math.round(rnd.nextGaussian() * 3)));
        return o;
    }

    public static void main(String[] args) {
        check(Board.score(0, -103).label().equals("T20"), "T20 scoring");
        check(Board.score(166, 0).label().equals("D6"), "D6 scoring");
        check(Board.score(0, 3).label().equals("BULL"), "bull scoring");
        check(Board.score(10, 0).label().equals("25"), "25 scoring");
        check(Board.score(0, 175).label().equals("MISS"), "miss scoring");
        double[] d16 = Board.pointFor(16, 166);
        check(Board.score(d16[0], d16[1]).label().equals("D16"), "D16 via pointFor");

        double[][] cams = {
                {-180, 260, -420, 520, 0},
                {0, 330, -380, 520, 0},
                {320, 40, -380, 520, 0},
                {-60, -300, -420, 520, 0},
                {200, 220, -500, 650, 90},
        };
        String[] names = {"below-left", "below", "right side", "above", "below-right rotated"};

        int allTotal = 0, allCorrect = 0;
        List<Double> allErr = new ArrayList<>();
        for (int ci = 0; ci < cams.length; ci++) {
            double[] cam = cams[ci];
            camera(cam[0], cam[1], cam[2], cam[3], cam[4]);
            Homography b2i = boardToImg();
            Homography trueI2B = b2i.inverse();
            double[][] taps = new double[4][];
            for (int i = 0; i < 4; i++) {
                double[] p = b2i.map(Board.CALIBRATION_POINTS[i][0], Board.CALIBRATION_POINTS[i][1]);
                taps[i] = new double[]{p[0] + rnd.nextGaussian() * 1.5, p[1] + rnd.nextGaussian() * 1.5};
            }
            DartDetector det = new DartDetector(W, H, null);
            det.setCalibration(Homography.fromPoints(taps, Board.CALIBRATION_POINTS));
            double[] foot = det.cameraFoot();

            int[] empty = render(trueI2B);
            int total = 0, correct = 0, takeouts = 0, visits = 12, wrongEnd = 0, spurious = 0, missed = 0;
            for (int v = 0; v < visits; v++) {
                int[] scene = empty.clone();
                for (int i = 0; i < 6; i++) if (det.feed(noisy(scene)) != null) spurious++;
                for (int d = 0; d < 3; d++) {
                    double r = Math.sqrt(rnd.nextDouble()) * 168, a = rnd.nextDouble() * 2 * Math.PI;
                    if (rnd.nextDouble() < 0.4) r = rnd.nextBoolean() ? 103 + rnd.nextGaussian() * 2 : 166 + rnd.nextGaussian() * 1.5;
                    double bx = r * Math.sin(a), by = -r * Math.cos(a);
                    String expected = Board.score(bx, by).label();
                    int flightGray = rnd.nextDouble() < 0.4 ? 215 : 40 + rnd.nextInt(200);
                    int[] fl = scene.clone();
                    paintDart(fl, bx * 0.6, by * 0.6 + 40, 0, 0.2, flightGray);
                    det.feed(noisy(fl)); det.feed(noisy(fl));
                    paintDart(scene, bx, by, rnd.nextGaussian() * 0.12, 0.15 + rnd.nextGaussian() * 0.12, flightGray);
                    DartDetector.Event ev = null;
                    for (int i = 0; i < 10; i++) { DartDetector.Event e = det.feed(noisy(scene)); if (ev == null) ev = e; }
                    total++;
                    if (ev != null && ev.type == DartDetector.Type.DART) {
                        double err = Math.hypot(ev.boardX - bx, ev.boardY - by);
                        allErr.add(err);
                        if (err > 25) wrongEnd++;
                        if (ev.hit.label().equals(expected)) correct++;
                    } else missed++;
                }
                int[] hand = scene.clone();
                for (int y = 180; y < 480; y++) for (int x = 100; x < 400; x++) hand[y * W + x] = 160;
                for (int i = 0; i < 3; i++) det.feed(noisy(hand));
                DartDetector.Event ev = null;
                for (int i = 0; i < 10; i++) { DartDetector.Event e = det.feed(noisy(empty)); if (ev == null) ev = e; }
                if (ev != null && ev.type == DartDetector.Type.TAKEOUT) takeouts++;
            }
            // Exposure jump: whole picture 25 levels brighter, then a dart must still be found.
            int[] bright = empty.clone();
            for (int i = 0; i < bright.length; i++) bright[i] = Math.min(255, bright[i] + 40);
            for (int i = 0; i < 3; i++) det.feed(noisy(empty));
            det.feed(noisy(bright)); det.feed(noisy(bright));
            for (int i = 0; i < 60; i++) if (det.feed(noisy(bright)) != null) spurious++;
            int[] withDart = bright.clone();
            double[] tp = Board.pointFor(20, 103);
            paintDart(withDart, tp[0], tp[1], 0, 0.15, 60);
            det.feed(noisy(withDart)); det.feed(noisy(withDart));
            DartDetector.Event le = null;
            for (int i = 0; i < 10; i++) { DartDetector.Event e = det.feed(noisy(withDart)); if (le == null) le = e; }
            String light = le != null && le.type == DartDetector.Type.DART ? le.hit.label() : String.valueOf(le);
            System.out.println("  after exposure change, T20 detected as: " + light);
            if (!"T20".equals(light)) failures++;
            allTotal += total; allCorrect += correct;
            System.out.printf("%-20s camera foot est (%4.0f,%4.0f) true (%4.0f,%4.0f) | darts %2d/%2d, wrong end %d, missed %d | takeouts %2d/%2d | spurious %d%n",
                    names[ci], foot == null ? Double.NaN : foot[0], foot == null ? Double.NaN : foot[1], cam[0], cam[1],
                    correct, total, wrongEnd, missed, takeouts, visits, spurious);
        }
        Collections.sort(allErr);
        System.out.printf("%nALL: %d/%d darts correct (%.0f%%); tip error median %.1f mm, 90%% %.1f mm%n",
                allCorrect, allTotal, 100.0 * allCorrect / allTotal,
                allErr.get(allErr.size() / 2), allErr.get((int) (allErr.size() * 0.9)));
        if (failures > 0) { System.out.println(failures + " CHECK FAILURES"); System.exit(1); }
    }

    static int failures;
    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAIL " + what); failures++; } }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double[] norm(double[] a) { double n = Math.sqrt(dot(a, a)); return new double[]{a[0] / n, a[1] / n, a[2] / n}; }
}
