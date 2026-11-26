import sk.kacurik.dartcam.core.*;

import java.util.*;

/**
 * BoardTracker: smoothing of jitter, following a moved phone, ignoring single outliers, reporting
 * failures.
 *
 *   javac -d /tmp/dartsim cam/src/main/java/sk/kacurik/dartcam/core/*.java tools/dartcam-sim/*.java
 *   java -cp /tmp/dartsim TrackerTest
 */
public class TrackerTest {
    static int failures;
    static final Random rnd = new Random(3);

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static double[][] pts(double dx, double dy) {
        return new double[][]{{240 + dx, 150 + dy}, {390 + dx, 330 + dy}, {240 + dx, 500 + dy}, {90 + dx, 330 + dy}};
    }

    static double[][] noisy(double[][] p, double sd) {
        double[][] n = new double[4][2];
        for (int i = 0; i < 4; i++) { n[i][0] = p[i][0] + rnd.nextGaussian() * sd; n[i][1] = p[i][1] + rnd.nextGaussian() * sd; }
        return n;
    }

    static double[][] apply(double[][] cur, BoardTracker.Update u) { return u == null ? cur : u.points; }

    public static void main(String[] args) {
        // 1. Jitter around a fixed board: stays close to the truth, never "jumps".
        BoardTracker t = new BoardTracker();
        double[][] truth = pts(0, 0);
        t.set(truth);
        double[][] cur = truth;
        double worst = 0, sq = 0;
        int jumps = 0;
        for (int i = 0; i < 300; i++) {
            BoardTracker.Update u = t.found(noisy(truth, 0.5)); // ~±1 px
            if (u != null && u.jump) jumps++;
            cur = apply(cur, u);
            double e = BoardTracker.maxDist(cur, truth);
            worst = Math.max(worst, e);
            sq += e * e;
        }
        double rms = Math.sqrt(sq / 300);
        check(jumps == 0, "jitter: no jumps (" + jumps + ")");
        check(rms < 0.35 && worst < 0.8, String.format(Locale.ROOT, "jitter ±1 px in, points off by %.2f px typically, %.2f px at worst", rms, worst));
        check(t.state() == BoardTracker.State.LOCKED, "jitter: state LOCKED");

        // 2. Phone moved 10 px: followed after two agreeing results.
        double[][] moved = pts(10, -4);
        BoardTracker.Update u1 = t.found(noisy(moved, 0.4));
        check(u1 == null && t.state() == BoardTracker.State.FOLLOWING, "moved: first result only a candidate (FOLLOWING)");
        BoardTracker.Update u2 = t.found(noisy(moved, 0.4));
        check(u2 != null && u2.jump && BoardTracker.maxDist(u2.points, moved) < 2, "moved: second agreeing result taken as a jump");
        check(t.state() == BoardTracker.State.LOCKED, "moved: LOCKED again");
        cur = apply(cur, u2);

        // 3. A single wrong result (36° off etc.) is ignored.
        BoardTracker.Update o1 = t.found(pts(80, 60));
        BoardTracker.Update o2 = t.found(noisy(moved, 0.4));
        cur = apply(apply(cur, o1), o2);
        check(o1 == null && BoardTracker.maxDist(cur, moved) < 2, "single outlier ignored");

        // 4. Failures: searching with the reason; a good result locks again.
        t.fail("board not found");
        t.fail("board not found");
        check(t.state() != BoardTracker.State.SEARCHING, "two failures: not yet searching");
        t.fail("board pattern unclear");
        check(t.state() == BoardTracker.State.SEARCHING && "board pattern unclear".equals(t.reason()), "three failures: SEARCHING with the reason");
        t.found(noisy(moved, 0.3));
        check(t.state() == BoardTracker.State.LOCKED, "found again: LOCKED");

        // 5. Never calibrated: the first lock needs two agreeing results.
        BoardTracker fresh = new BoardTracker();
        check(fresh.found(truth) == null, "fresh: first result only a candidate");
        BoardTracker.Update f2 = fresh.found(noisy(truth, 0.3));
        check(f2 != null && f2.jump, "fresh: second agreeing result locks");

        // 6. Slow drift (0.05 px per result) is followed without jumps.
        BoardTracker d = new BoardTracker();
        d.set(truth);
        double[][] dc = truth;
        int dj = 0;
        for (int i = 0; i < 200; i++) {
            BoardTracker.Update u = d.found(noisy(pts(i * 0.05, 0), 0.2));
            if (u != null && u.jump) dj++;
            dc = apply(dc, u);
        }
        check(dj == 0 && BoardTracker.maxDist(dc, pts(199 * 0.05, 0)) < 1.0, "slow drift of 10 px followed smoothly");

        if (failures > 0) { System.out.println(failures + " TRACKER FAILURES"); System.exit(1); }
        System.out.println("TRACKER: all checks passed");
    }
}
