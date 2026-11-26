package sk.kacurik.dartcam.core;

/**
 * Last check before a detected dart is scored: things that are not darts are dropped here.
 *
 *  - The camera never scores a MISS by itself: a "dart" outside the double ring is almost always a
 *    change that isn't a dart (light flicker, a screen's moiré at the board edge, a shadow). Real
 *    misses are entered with the MISS button.
 *  - If the full-resolution zoom sees no change at the tip, the picture didn't really change there
 *    (noise or flicker in the small analysis copy), so there is no object.
 */
public final class DartGate {
    private DartGate() {}

    /** Why this detection is not a dart, or null if it should be scored. */
    public static String reject(Hit coarseHit, TipRefiner.Result zoom) {
        Hit hit = zoom != null && zoom.ok ? zoom.hit : coarseHit;
        if (hit == null || hit.label().equals("MISS")) return "outside the board";
        if (zoom != null && !zoom.ok && "no change in the zoom".equals(zoom.note)) return "no change at full resolution";
        return null;
    }
}
