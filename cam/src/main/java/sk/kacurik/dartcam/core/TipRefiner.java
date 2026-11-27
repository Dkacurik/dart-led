package sk.kacurik.dartcam.core;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Re-checks a detected dart at full camera resolution ("digital zoom").
 *
 * The detector works on a half-size copy of the camera picture. After it reports a dart, the caller
 * cuts the same small window around the tip out of the full-resolution picture before and after the
 * dart. Here the dart is found again in that window and its tip measured with the same rule as the
 * detector (the point of the dart nearest the camera's foot point on the board), just with twice the
 * pixels. The result says whether the number changed and whether the tip is so close to a wire that
 * a person should look.
 *
 * Coordinates: "analysis" = the detector's picture; crop pixel (cx, cy) covers analysis
 * [x0 + cx / scale, x0 + (cx + 1) / scale).
 */
public final class TipRefiner {
    private TipRefiner() {}

    /** Half size of the window around the tip, in board millimetres. */
    public static final double WINDOW_MM = 40;

    public static final class Result {
        public final boolean ok;
        /** Refined tip in analysis pixels. */
        public final double tipX, tipY;
        /** Refined tip in board millimetres. */
        public final double boardX, boardY;
        public final Hit hit;
        /** Millimetres from the tip to the nearest wire. */
        public final double wireMm;
        /** Too close to a wire to be sure from the picture. */
        public final boolean closeCall;
        /** The re-check gave a different number than the detector. */
        public final boolean changed;
        public final String note;

        Result(boolean ok, double tx, double ty, double bx, double by, Hit hit, double wireMm, boolean close, boolean changed, String note) {
            this.ok = ok; this.tipX = tx; this.tipY = ty; this.boardX = bx; this.boardY = by; this.hit = hit;
            this.wireMm = wireMm; this.closeCall = close; this.changed = changed; this.note = note;
        }

        public static Result fail(String why) { return new Result(false, 0, 0, 0, 0, null, Double.NaN, false, false, why); }

        @Override public String toString() {
            return ok ? hit + (changed ? " (changed)" : "") + String.format(java.util.Locale.ROOT, " wire %.1f mm", wireMm) + (closeCall ? " CLOSE" : "")
                    : "not refined: " + note;
        }
    }

    /**
     * Window to cut around the coarse tip: {x0, y0, x1, y1} in analysis pixels (clamped to the picture),
     * covering ±WINDOW_MM of board around the tip.
     */
    public static double[] window(double tipX, double tipY, Homography imgToBoard, int analysisW, int analysisH) {
        double half = WINDOW_MM / mmPerPixel(imgToBoard, tipX, tipY);
        half = Math.max(16, Math.min(half, Math.max(analysisW, analysisH) / 4.0));
        return new double[]{Math.max(0, tipX - half), Math.max(0, tipY - half),
                Math.min(analysisW, tipX + half), Math.min(analysisH, tipY + half)};
    }

    /** Board millimetres per analysis pixel around (x, y). */
    static double mmPerPixel(Homography imgToBoard, double x, double y) {
        double[] a = imgToBoard.map(x, y), b = imgToBoard.map(x + 1, y), c = imgToBoard.map(x, y + 1);
        return Math.max(1e-6, (Math.hypot(b[0] - a[0], b[1] - a[1]) + Math.hypot(c[0] - a[0], c[1] - a[1])) / 2);
    }

    /**
     * @param before,after grayscale crops (cw*ch, 0..255) of the full-res picture before and after the dart
     * @param x0,y0        crop origin in analysis pixels
     * @param scale        crop pixels per analysis pixel (e.g. 2)
     * @param coarseX,coarseY,coarseHit what the detector found
     * @param cameraFoot   DartDetector.cameraFoot() (may be null)
     */
    public static Result refine(int[] before, int[] after, int cw, int ch, double x0, double y0, double scale,
                                double coarseX, double coarseY, Hit coarseHit,
                                Homography imgToBoard, double[] cameraFoot, int diffThreshold) {
        return refine(before, after, null, cw, ch, x0, y0, scale, coarseX, coarseY, coarseHit, imgToBoard, cameraFoot, diffThreshold);
    }

    /**
     * Double check at the end of a visit: "after" is the last still picture (all darts in), "settled"
     * the picture right after this dart (null for the last dart). Pixels where after and settled
     * differ were changed by later darts and are left out, so only this dart is measured again.
     */
    public static Result refine(int[] before, int[] after, int[] settled, int cw, int ch, double x0, double y0, double scale,
                                double coarseX, double coarseY, Hit coarseHit,
                                Homography imgToBoard, double[] cameraFoot, int diffThreshold) {
        if (before == null || after == null || before.length != cw * ch || after.length != cw * ch || cw < 8 || ch < 8)
            return Result.fail("no crop");
        if (settled != null && settled.length != cw * ch) settled = null;
        // 1. Changed pixels; a little smoothing against sensor noise (3x3 mean of the difference).
        boolean[] changed = new boolean[cw * ch];
        int n = 0;
        for (int y = 1; y < ch - 1; y++)
            for (int x = 1; x < cw - 1; x++) {
                int s = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int j = (y + dy) * cw + x + dx;
                        s += Math.abs(after[j] - before[j]);
                    }
                if (s <= 9 * diffThreshold * 0.75) continue;
                if (settled != null) {
                    int l = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = (y + dy) * cw + x + dx;
                            l += Math.abs(after[j] - settled[j]);
                        }
                    if (l > 9 * diffThreshold * 0.75) continue;      // a later dart, not this one
                }
                changed[y * cw + x] = true; n++;
            }
        if (n < 10) return Result.fail("no change in the zoom");
        if (n > 0.6 * cw * ch) return Result.fail("whole zoom changed");

        // 2. Pieces of the change.
        int[] label = new int[cw * ch];
        int next = 1;
        int[] sizes = new int[16];
        double[] near = new double[16]; // closest distance of each piece to the coarse tip (crop px)
        double ctx = (coarseX - x0) * scale, cty = (coarseY - y0) * scale;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < cw * ch; s++) {
            if (!changed[s] || label[s] != 0) continue;
            if (next == sizes.length) { sizes = Arrays.copyOf(sizes, next * 2); near = Arrays.copyOf(near, next * 2); }
            int lab = next++;
            label[s] = lab;
            q.add(s);
            int size = 0;
            double nd = Double.MAX_VALUE;
            while (!q.isEmpty()) {
                int i = q.poll();
                size++;
                int x = i % cw, y = i / cw;
                nd = Math.min(nd, Math.hypot(x + 0.5 - ctx, y + 0.5 - cty));
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) { // bridge 1-pixel gaps (thin point over a similar colour)
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue;
                        int j = ny * cw + nx;
                        if (changed[j] && label[j] == 0) { label[j] = lab; q.add(j); }
                    }
            }
            sizes[lab] = size;
            near[lab] = nd;
        }
        // Darts already in the board are in both pictures, so every real piece of change in the window
        // is the new dart (a dark shaft over a black segment can split it). Drop only noise specks, and
        // pieces far from the coarse tip that are tiny.
        int total = 0;
        for (int l = 1; l < next; l++) total += sizes[l];
        int minPiece = Math.max(6, (int) (total * 0.03));
        double nearLimit = 8 * scale;
        boolean[] keep = new boolean[next];
        int kept = 0;
        for (int l = 1; l < next; l++)
            if (sizes[l] >= minPiece || sizes[l] >= 6 && near[l] <= nearLimit) { keep[l] = true; kept += sizes[l]; }
        if (kept < 10) return Result.fail("dart not found in the zoom");

        // 3. Tip: the kept pixel nearest the camera foot on the board (as in DartDetector), averaged
        //    over the last millimetre. Without a usable foot point: the pixel nearest the coarse tip end.
        double[] ax = new double[kept], ay = new double[kept], d = new double[kept];
        int k = 0;
        boolean useFoot = cameraFoot != null && Math.hypot(cameraFoot[0], cameraFoot[1]) > 120;
        for (int i = 0; i < cw * ch; i++) {
            if (!changed[i] || !keep[label[i]]) continue;
            double px = x0 + (i % cw + 0.5) / scale, py = y0 + (i / cw + 0.5) / scale;
            ax[k] = px; ay[k] = py;
            if (useFoot) {
                double[] b = imgToBoard.map(px, py);
                d[k] = Math.hypot(b[0] - cameraFoot[0], b[1] - cameraFoot[1]);
            } else {
                d[k] = Math.hypot(px - coarseX, py - coarseY);
            }
            k++;
        }
        double[] sorted = d.clone();
        Arrays.sort(sorted);
        double end = sorted[Math.min(sorted.length - 1, kept / 200)];
        double band = useFoot ? 1.0 : 1.0 / scale;
        double sx = 0, sy = 0;
        int m = 0;
        for (int i = 0; i < kept; i++) if (d[i] <= end + band) { sx += ax[i]; sy += ay[i]; m++; }
        double tx = sx / m, ty = sy / m;
        double[] b = imgToBoard.map(tx, ty);
        double[] cb = imgToBoard.map(coarseX, coarseY);
        if (Math.hypot(b[0] - cb[0], b[1] - cb[1]) > 30) return Result.fail("zoom disagrees too much");

        Hit hit = Board.score(b[0], b[1]);
        double wire = Board.wireDistance(b[0], b[1]);
        // Uncertainty: about one full-res pixel plus a little for the calibration.
        double mmPerCropPx = mmPerPixel(imgToBoard, tx, ty) / scale;
        boolean close = wire < Math.max(1.0, 1.5 * mmPerCropPx + 0.5);
        return new Result(true, tx, ty, b[0], b[1], hit, wire, close, !hit.equals(coarseHit), null);
    }
}
