package sk.kacurik.dartcam.core;

/**
 * Second opinion on a dart at the end of the visit. The tip was measured at full resolution right
 * after the dart landed (first) and again on the last still picture with all darts in (second, later
 * darts masked out). Two measurements of the same tip: their mean is more precise than either, so the
 * number is taken from the mean (or from the one that worked).
 */
public final class DoubleCheck {
    private DoubleCheck() {}

    /** Two measurements of one tip further apart than this don't agree. */
    public static final double MAX_APART_MM = 3.0;

    public static final class Verdict {
        public final Hit hit;              // number after the double check
        public final double boardX, boardY;
        public final boolean measured;     // false: nothing to check with (keep what was entered)
        public final String note;
        Verdict(Hit hit, double bx, double by, boolean measured, String note) {
            this.hit = hit; this.boardX = bx; this.boardY = by; this.measured = measured; this.note = note;
        }
    }

    /**
     * @param entered what was entered for this dart
     * @param first   zoom result right after the dart (may be null / not ok)
     * @param second  zoom result on the final picture (may be null / not ok)
     * @param coarseX,coarseY detector tip in board mm (used when neither zoom worked)
     */
    public static Verdict decide(Hit entered, TipRefiner.Result first, TipRefiner.Result second,
                                 double coarseX, double coarseY) {
        boolean a = first != null && first.ok, b = second != null && second.ok;
        if (!b) return new Verdict(entered, coarseX, coarseY, false,
                "no second measurement (" + (second == null ? "no picture" : second.note) + ")");
        double x, y;
        String how;
        if (a) {
            double apart = Math.hypot(first.boardX - second.boardX, first.boardY - second.boardY);
            if (apart > MAX_APART_MM) {
                // The two measurements don't agree (a later dart covers part of this one, the dart
                // moved…): no better answer – keep what was entered, but say it is uncertain.
                return new Verdict(entered, first.boardX, first.boardY, false,
                        String.format(java.util.Locale.ROOT, "measurements %.1f mm apart – kept %s", apart, entered.label()));
            }
            x = (first.boardX + second.boardX) / 2;
            y = (first.boardY + second.boardY) / 2;
            how = String.format(java.util.Locale.ROOT, "mean of 2 measurements %.1f mm apart",
                    Math.hypot(first.boardX - second.boardX, first.boardY - second.boardY));
        } else {
            x = second.boardX; y = second.boardY;
            how = "second measurement only";
        }
        Hit hit = Board.score(x, y);
        return new Verdict(hit, x, y, true, how + (hit.equals(entered) ? "" : ": " + entered.label() + " → " + hit.label()));
    }
}
