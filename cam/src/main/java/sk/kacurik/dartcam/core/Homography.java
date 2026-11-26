package sk.kacurik.dartcam.core;

/**
 * 3x3 planar homography. Maps points from a source plane to a destination plane.
 * Used to turn the tilted camera view of the board into flat board coordinates (millimetres).
 */
public final class Homography {
    private final double[] h; // row-major 3x3

    public Homography(double[] h) {
        if (h.length != 9) throw new IllegalArgumentException("need 9 values");
        this.h = h.clone();
    }

    /** Least-squares homography from n >= 4 point pairs (src[i] -> dst[i]), each as {x, y}. */
    public static Homography fromPoints(double[][] src, double[][] dst) {
        int n = src.length;
        if (n < 4 || dst.length != n) throw new IllegalArgumentException("need >= 4 matching points");
        // Normalise both point sets for numerical stability (Hartley).
        double[] ts = normalisation(src), td = normalisation(dst);
        double[][] ata = new double[8][8];
        double[] atb = new double[8];
        for (int i = 0; i < n; i++) {
            double x = (src[i][0] - ts[0]) * ts[2], y = (src[i][1] - ts[1]) * ts[2];
            double u = (dst[i][0] - td[0]) * td[2], v = (dst[i][1] - td[1]) * td[2];
            double[] r1 = {x, y, 1, 0, 0, 0, -u * x, -u * y};
            double[] r2 = {0, 0, 0, x, y, 1, -v * x, -v * y};
            accumulate(ata, atb, r1, u);
            accumulate(ata, atb, r2, v);
        }
        double[] p = solve(ata, atb);
        double[] hn = {p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], 1};
        // Undo normalisation: H = Td^-1 * Hn * Ts
        double[] tsM = {ts[2], 0, -ts[2] * ts[0], 0, ts[2], -ts[2] * ts[1], 0, 0, 1};
        double[] tdInv = {1 / td[2], 0, td[0], 0, 1 / td[2], td[1], 0, 0, 1};
        double[] out = mul(tdInv, mul(hn, tsM));
        double s = out[8];
        for (int i = 0; i < 9; i++) out[i] /= s;
        return new Homography(out);
    }

    private static double[] normalisation(double[][] pts) {
        double mx = 0, my = 0;
        for (double[] p : pts) { mx += p[0]; my += p[1]; }
        mx /= pts.length; my /= pts.length;
        double d = 0;
        for (double[] p : pts) d += Math.hypot(p[0] - mx, p[1] - my);
        d /= pts.length;
        double s = d < 1e-12 ? 1 : Math.sqrt(2) / d;
        return new double[]{mx, my, s};
    }

    private static void accumulate(double[][] ata, double[] atb, double[] r, double b) {
        for (int i = 0; i < 8; i++) {
            atb[i] += r[i] * b;
            for (int j = 0; j < 8; j++) ata[i][j] += r[i] * r[j];
        }
    }

    /** Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
            if (Math.abs(m[piv][c]) < 1e-12) throw new IllegalArgumentException("degenerate points");
            double[] t = m[c]; m[c] = m[piv]; m[piv] = t;
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                if (f == 0) continue;
                for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    private static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                c[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return c;
    }

    public double[] map(double x, double y) {
        double w = h[6] * x + h[7] * y + h[8];
        return new double[]{(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w};
    }

    public Homography inverse() {
        double a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7], l = h[8];
        double det = a * (e * l - f * k) - b * (d * l - f * g) + c * (d * k - e * g);
        if (Math.abs(det) < 1e-15) throw new IllegalStateException("singular homography");
        double[] inv = {
                (e * l - f * k) / det, (c * k - b * l) / det, (b * f - c * e) / det,
                (f * g - d * l) / det, (a * l - c * g) / det, (c * d - a * f) / det,
                (d * k - e * g) / det, (b * g - a * k) / det, (a * e - b * d) / det};
        return new Homography(inv);
    }

    public double[] values() { return h.clone(); }
}
