import sk.kacurik.dartcam.core.*;

import java.util.*;

/**
 * Auto-calibration on simulated COLOUR camera pictures (pinhole camera from SimTest).
 * Dull, realistic ring colours, a brightness gradient, a colour cast, noise, wires, darts in the
 * board (one with a red flight). Several upright camera positions with up to ±15° roll.
 *
 * Run from the repository root:
 *   javac -d /tmp/dartsim cam/src/main/java/sk/kacurik/dartcam/core/*.java tools/dartcam-sim/*.java
 *   java -cp /tmp/dartsim AutoCalTest
 */
public class AutoCalTest {
    static final int W = SimTest.W, H = SimTest.H;
    static final Random rnd = new Random(Long.getLong("seed", 11));
    static int failures;

    static final int[] RED = {150, 30, 35}, GREEN = {20, 80, 45}, BLACK = {28, 28, 30}, CREAM = {215, 205, 170},
            NUMBER_RING = {22, 22, 24}, WIRE = {170, 170, 175}, WALL = {185, 175, 155};

    static int[] boardColour(double x, double y) {
        double r = Math.hypot(x, y);
        if (r > 225) return WALL;
        if (r > Board.R_DOUBLE_OUT + 0.5) return NUMBER_RING;
        double a = Math.toDegrees(Math.atan2(x, -y)); if (a < 0) a += 360;
        if (r <= Board.R_INNER_BULL) return RED;
        if (r <= Board.R_OUTER_BULL) return GREEN;
        // Wires: rings and segment boundaries, ~1 mm.
        for (double rr : new double[]{Board.R_OUTER_BULL, Board.R_TREBLE_IN, Board.R_TREBLE_OUT, Board.R_DOUBLE_IN, Board.R_DOUBLE_OUT})
            if (Math.abs(r - rr) < 0.5) return WIRE;
        double toWire = ((a + 9) % 18 + 18) % 18;
        double distMm = Math.min(toWire, 18 - toWire) * Math.PI / 180 * r;
        if (distMm < 0.5) return WIRE;
        int idx = (int) Math.floor((a + 9) / 18) % 20;
        boolean even = idx % 2 == 0;
        boolean ring = (r >= Board.R_TREBLE_IN && r <= Board.R_TREBLE_OUT) || r >= Board.R_DOUBLE_IN;
        if (ring) return even ? RED : GREEN;
        return even ? BLACK : CREAM;
    }

    /** 2x2 supersampled colour picture with light gradient, colour cast and noise (0xAARRGGBB). */
    static int[] render(Homography imgToBoard, double gradient, double[] cast) {
        int[] out = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double r = 0, g = 0, b = 0;
            for (int sy = 0; sy < 2; sy++) for (int sx = 0; sx < 2; sx++) {
                double[] p = imgToBoard.map(x + 0.25 + 0.5 * sx, y + 0.25 + 0.5 * sy);
                int[] c = boardColour(p[0], p[1]);
                r += c[0]; g += c[1]; b += c[2];
            }
            double light = 1 + gradient * ((double) x / W - 0.5) + 0.5 * gradient * ((double) y / H - 0.5);
            out[y * W + x] = pack(r / 4 * light * cast[0], g / 4 * light * cast[1], b / 4 * light * cast[2]);
        }
        return out;
    }

    static int pack(double r, double g, double b) {
        r += rnd.nextGaussian() * 4; g += rnd.nextGaussian() * 4; b += rnd.nextGaussian() * 4;
        return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    /** Dart stuck in at (bx,by) mm, sticking out towards the camera; drawn as discs along its length. */
    static void paintDart(int[] fr, double bx, double by, int[] flight) {
        double[] dir = {rnd.nextGaussian() * 0.12, 0.15 + rnd.nextGaussian() * 0.1, -1};
        double n = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + 1);
        double[][] parts = {{0, 18, 1.0}, {18, 58, 3.5}, {58, 90, 2.0}, {90, 125, 14}};
        int[][] cols = {{175, 175, 175}, {150, 150, 155}, {55, 55, 60}, flight};
        for (int pi = 0; pi < parts.length; pi++) {
            double[] part = parts[pi];
            for (double t = part[0]; t <= part[1]; t += 0.7) {
                double[] p = SimTest.project(bx + dir[0] / n * t, by + dir[1] / n * t, dir[2] / n * t);
                double rpx = Math.max(0.6, part[2] * SimTest.f / p[2]);
                for (double dy = -rpx; dy <= rpx; dy += 0.5) for (double dx = -rpx; dx <= rpx; dx += 0.5) {
                    if (dx * dx + dy * dy > rpx * rpx) continue;
                    int x = (int) Math.round(p[0] + dx), y = (int) Math.round(p[1] + dy);
                    if (x >= 0 && y >= 0 && x < W && y < H) fr[y * W + x] = pack(cols[pi][0], cols[pi][1], cols[pi][2]);
                }
            }
        }
    }

    public static void main(String[] args) {
        // Upright camera positions (the "20 points up" rule needs the phone roughly upright).
        double[][] cams = {
                {-180, 260, -420, 520}, {0, 330, -380, 520}, {320, 40, -380, 520}, {-60, -300, -420, 520},
                {150, 200, -650, 480}, // further away, smaller board
        };
        String[] names = {"below-left", "below", "right side", "above", "far below-right"};
        double[] rolls = {0, 15, -15, 90};
        int cases = 0, ok = 0;
        double worst = 0;
        List<Double> allErr = new ArrayList<>();
        for (int ci = 0; ci < cams.length; ci++) {
            for (double roll : rolls) {
                double[] cam = cams[ci];
                SimTest.camera(cam[0], cam[1], cam[2], cam[3], roll);
                Homography b2i = SimTest.boardToImg();
                Homography i2b = b2i.inverse();
                double gradient = 0.3 + rnd.nextDouble() * 0.5;
                double[] cast = {0.9 + rnd.nextDouble() * 0.2, 0.95 + rnd.nextDouble() * 0.1, 0.85 + rnd.nextDouble() * 0.2};
                double dim = 0.55 + rnd.nextDouble() * 0.5; // overall exposure
                cast = new double[]{cast[0] * dim, cast[1] * dim, cast[2] * dim};
                int[] img = render(i2b, gradient, cast);
                // Coloured clutter beside the board (red cabinet, green plant) in some pictures.
                if (rnd.nextBoolean()) {
                    for (int k = 0; k < 2; k++) {
                        int[] col = k == 0 ? new int[]{160, 35, 30} : new int[]{40, 110, 45};
                        int bx0 = k == 0 ? 0 : W - 70, by0 = rnd.nextInt(H - 140);
                        for (int y = by0; y < by0 + 140; y++) for (int x = bx0; x < bx0 + 70; x++) {
                            double[] bp = i2b.map(x + 0.5, y + 0.5);
                            if (Math.hypot(bp[0], bp[1]) > 240) img[y * W + x] = pack(col[0] * dim, col[1] * dim, col[2] * dim);
                        }
                    }
                }
                int darts = 1 + rnd.nextInt(3);
                for (int d = 0; d < darts; d++) {
                    double r = Math.sqrt(rnd.nextDouble()) * 165, a = rnd.nextDouble() * 2 * Math.PI;
                    int[] fl = d == 0 ? new int[]{170, 25, 30} : new int[]{30 + rnd.nextInt(200), 30 + rnd.nextInt(200), 30 + rnd.nextInt(200)};
                    paintDart(img, r * Math.sin(a), -r * Math.cos(a), fl);
                }
                cases++;
                long t0 = System.nanoTime();
                // What the phone's accelerometer would say: world "up" (board -y) in camera axes.
                double[] upCam = {-SimTest.R[0][1], -SimTest.R[1][1]};
                AutoCalibrator.Result res = AutoCalibrator.find(img, W, H, upCam[0], upCam[1]);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String line = String.format(Locale.ROOT, "%-16s roll %+3.0f° light %.2f: %s (%d ms)", names[ci], roll, dim, res, ms);
                if (!res.ok()) { System.out.println("FAIL " + line); failures++; continue; }
                double maxErr = 0;
                for (int i = 0; i < 4; i++) {
                    double[] t = b2i.map(Board.CALIBRATION_POINTS[i][0], Board.CALIBRATION_POINTS[i][1]);
                    double e = Math.hypot(t[0] - res.calPoints[i][0], t[1] - res.calPoints[i][1]);
                    maxErr = Math.max(maxErr, e);
                    allErr.add(e);
                }
                // Same score with the found calibration as with the true one?
                Homography found = Homography.fromPoints(res.calPoints, Board.CALIBRATION_POINTS);
                int same = 0, tries = 300;
                for (int i = 0; i < tries; i++) {
                    double r = Math.sqrt(rnd.nextDouble()) * 172, a = rnd.nextDouble() * 2 * Math.PI;
                    double[] ip = b2i.map(r * Math.sin(a), -r * Math.cos(a));
                    double[] bp = found.map(ip[0], ip[1]);
                    if (Board.score(bp[0], bp[1]).equals(Board.score(r * Math.sin(a), -r * Math.cos(a)))) same++;
                }
                double sameShare = (double) same / tries;
                worst = Math.max(worst, maxErr);
                boolean good = maxErr <= 3.0 && sameShare >= 0.98;
                System.out.printf(Locale.ROOT, "%s %s | cal points max err %.2f px, same score %.1f%%%n",
                        good ? "ok  " : "FAIL", line, maxErr, sameShare * 100);
                if (good) ok++; else failures++;
            }
        }

        // Must not invent a board.
        int[] wall = new int[W * H];
        for (int i = 0; i < wall.length; i++) wall[i] = pack(WALL[0], WALL[1], WALL[2]);
        AutoCalibrator.Result r1 = AutoCalibrator.find(wall, W, H);
        System.out.println("blank wall: " + r1);
        if (r1.ok()) { failures++; System.out.println("FAIL blank wall must not calibrate"); }
        int[] blobs = wall.clone();
        for (int k = 0; k < 6; k++) {
            int cx = rnd.nextInt(W), cy = rnd.nextInt(H), rad = 20 + rnd.nextInt(40);
            int[] col = k % 2 == 0 ? RED : GREEN;
            for (int y = Math.max(0, cy - rad); y < Math.min(H, cy + rad); y++)
                for (int x = Math.max(0, cx - rad); x < Math.min(W, cx + rad); x++)
                    if (Math.hypot(x - cx, y - cy) < rad) blobs[y * W + x] = pack(col[0], col[1], col[2]);
        }
        AutoCalibrator.Result r2 = AutoCalibrator.find(blobs, W, H);
        System.out.println("red/green blobs, no board: " + r2);
        if (r2.ok()) { failures++; System.out.println("FAIL random blobs must not calibrate"); }

        Collections.sort(allErr);
        System.out.printf(Locale.ROOT, "%nAUTOCAL: %d/%d found correctly; cal point error median %.2f px, worst %.2f px%n",
                ok, cases, allErr.isEmpty() ? Double.NaN : allErr.get(allErr.size() / 2), worst);
        if (failures > 0) { System.out.println(failures + " AUTOCAL FAILURES"); System.exit(1); }
    }
}
