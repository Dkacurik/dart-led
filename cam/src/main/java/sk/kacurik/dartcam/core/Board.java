package sk.kacurik.dartcam.core;

/**
 * Standard steel-tip dartboard geometry (BDO/WDF/PDC regulation sizes, millimetres).
 * Board coordinates: origin at the bull, x to the right, y DOWN (same orientation as images),
 * so the 20 segment is at negative y.
 */
public final class Board {
    private Board() {}

    public static final double R_INNER_BULL = 6.35;
    public static final double R_OUTER_BULL = 15.9;
    public static final double R_TREBLE_IN = 99.0;
    public static final double R_TREBLE_OUT = 107.0;
    public static final double R_DOUBLE_IN = 162.0;
    public static final double R_DOUBLE_OUT = 170.0;

    private static final int[] ORDER = {20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 11, 14, 9, 12, 5, 8};

    /** Clockwise segment order from the top (20 entries). */
    public static int[] order() { return ORDER.clone(); }

    /**
     * Calibration targets: outer edge of the double ring at the middle of segments 20 (top),
     * 6 (right), 3 (bottom) and 11 (left). The user taps these four points in the camera image.
     */
    public static final double[][] CALIBRATION_POINTS = {
            {0, -R_DOUBLE_OUT}, {R_DOUBLE_OUT, 0}, {0, R_DOUBLE_OUT}, {-R_DOUBLE_OUT, 0}};
    public static final String[] CALIBRATION_LABELS = {"20 (top)", "6 (right)", "3 (bottom)", "11 (left)"};

    /** Score for a point in board coordinates (mm). */
    public static Hit score(double x, double y) {
        double r = Math.hypot(x, y);
        if (r <= R_INNER_BULL) return Hit.bull();
        if (r <= R_OUTER_BULL) return Hit.outerBull();
        if (r > R_DOUBLE_OUT) return Hit.miss();
        double angle = Math.toDegrees(Math.atan2(x, -y)); // 0 at top, clockwise positive
        if (angle < 0) angle += 360;
        int idx = (int) Math.floor((angle + 9.0) / 18.0) % 20;
        int number = ORDER[idx];
        int mult = 1;
        if (r >= R_TREBLE_IN && r <= R_TREBLE_OUT) mult = 3;
        else if (r >= R_DOUBLE_IN) mult = 2;
        return Hit.of(number, mult);
    }

    /** Centre of the given segment/ring in board coordinates, handy for tests and overlays. */
    public static double[] pointFor(int number, double radius) {
        int idx = -1;
        for (int i = 0; i < 20; i++) if (ORDER[i] == number) idx = i;
        if (idx < 0) throw new IllegalArgumentException("bad number " + number);
        double a = Math.toRadians(idx * 18.0);
        return new double[]{radius * Math.sin(a), -radius * Math.cos(a)};
    }
}
