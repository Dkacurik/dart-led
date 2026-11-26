package sk.kacurik.dartcam.core;

/**
 * Follows the board like a QR scanner follows a code: fed with an Auto result every few hundred
 * milliseconds, it keeps the calibration points (4 image points, see Board.CALIBRATION_POINTS) steady
 * and up to date.
 *
 *  - Small differences (jitter, slow drift) are smoothed: the points move part of the way.
 *  - A big difference (the phone moved) must be seen twice in a row before it is taken, so a single
 *    wrong result can't throw the calibration away.
 *  - Repeated failures are reported as "searching", with the reason.
 *
 * Not thread-safe: use from one thread.
 */
public final class BoardTracker {

    public enum State { NONE, LOCKED, FOLLOWING, SEARCHING }

    /** New points to use. jump = the board moved (detector references must be reset). */
    public static final class Update {
        public final double[][] points;
        public final boolean jump;
        public final double moved;
        Update(double[][] points, boolean jump, double moved) { this.points = points; this.jump = jump; this.moved = moved; }
    }

    public double smoothLimitPx = 1.5;   // below: smoothing
    public double smoothFactor = 0.2;    // part of the way per result
    public double minChangePx = 0.05;    // smaller smoothing steps are not reported
    public int failsForSearching = 3;

    private double[][] pts;
    private double[][] candidate;
    private int fails;
    private String reason;
    private State state = State.NONE;

    public State state() { return state; }
    /** Why the last results failed (while SEARCHING). */
    public String reason() { return reason; }
    public double[][] points() { return pts == null ? null : copy(pts); }

    /** Calibration set by hand (or loaded): start from these points. */
    public void set(double[][] points) {
        pts = points == null ? null : copy(points);
        candidate = null;
        fails = 0;
        state = pts == null ? State.NONE : State.LOCKED;
    }

    /** Auto failed on this frame. */
    public void fail(String why) {
        candidate = null;
        reason = why;
        if (++fails >= failsForSearching) state = State.SEARCHING;
    }

    /** Auto found the board at these points. Returns the change to apply, or null. */
    public Update found(double[][] p) {
        fails = 0;
        reason = null;
        if (pts == null) return confirm(p, Double.NaN);
        double d = maxDist(p, pts);
        if (d < smoothLimitPx) {
            candidate = null;
            state = State.LOCKED;
            double[][] n = new double[4][2];
            for (int i = 0; i < 4; i++)
                for (int k = 0; k < 2; k++) n[i][k] = pts[i][k] + smoothFactor * (p[i][k] - pts[i][k]);
            double step = maxDist(n, pts);
            if (step < minChangePx) return null;
            pts = n;
            return new Update(copy(n), false, step);
        }
        return confirm(p, d);
    }

    /** A big change: take it when the previous result agreed, else remember it. */
    private Update confirm(double[][] p, double moved) {
        if (candidate != null) {
            double agree = maxDist(p, candidate);
            double limit = Double.isNaN(moved) ? smoothLimitPx : Math.max(smoothLimitPx, 0.15 * moved);
            if (agree <= limit) {
                pts = copy(p);
                candidate = null;
                state = State.LOCKED;
                return new Update(copy(p), true, moved);
            }
        }
        candidate = copy(p);
        state = State.FOLLOWING;
        return null;
    }

    public static double maxDist(double[][] a, double[][] b) {
        double m = 0;
        for (int i = 0; i < 4; i++) m = Math.max(m, Math.hypot(a[i][0] - b[i][0], a[i][1] - b[i][1]));
        return m;
    }

    private static double[][] copy(double[][] p) {
        double[][] c = new double[p.length][];
        for (int i = 0; i < p.length; i++) c[i] = p[i].clone();
        return c;
    }
}
