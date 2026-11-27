package sk.kacurik.dartcam.core;

/**
 * Last check before a detected dart is scored: things that are not darts are dropped here.
 *
 *  - If the full-resolution zoom sees no change at the tip, the picture didn't really change there
 *    (noise or flicker in the small analysis copy), so there is no object.
 *  - A MISS (tip outside the double ring: number ring, board edge, surround) is only scored when the
 *    zoom confirms an object there: flicker, a screen's moiré or a shadow at the board edge must not
 *    become a miss. Without a zoom picture a MISS is dropped (enter it with the MISS button).
 *    Changes further out than DartDetector.Params.missRadiusMm never reach this point.
 */
public final class DartGate {
    private DartGate() {}

    private static final String NO_CHANGE = "no change in the zoom";

    /** Why this detection is not a dart, or null if it should be scored. */
    public static String reject(Hit coarseHit, TipRefiner.Result zoom) {
        if (zoom != null && !zoom.ok && NO_CHANGE.equals(zoom.note)) return "no change at full resolution";
        Hit hit = zoom != null && zoom.ok ? zoom.hit : coarseHit;
        if (hit == null) return "no score";
        if (hit.label().equals("MISS") && zoom == null) return "miss without a zoom check";
        return null;
    }
}
