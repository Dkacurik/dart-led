package sk.kacurik.dartled;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sk.kacurik.dartled.core.Scoreboard;

/**
 * Enters darts coming from the DartCam phone into DartCounter's per-dart keypad, by pressing
 * its buttons through the accessibility service: e.g. "T20" = press TRIPLE, then 20.
 * SUBMIT finishes the visit (only when DartCounter shows entered darts), UNDO removes the last one.
 *
 * DartCounter's button labels are matched loosely (several spellings), and when the same label
 * appears more than once the one lowest on screen wins: the keypad sits at the bottom, the
 * player cards (which also show numbers) at the top.
 */
public final class DartTyper {

    public interface Result { void done(String line); }

    private static final long TAP_GAP_MS = 220;     // between two button presses
    private static final long SETTLE_MS = 450;      // before reading back what DartCounter shows

    private static final String[] MOD_SINGLE = {"SINGLE", "S"};
    private static final String[] MOD_DOUBLE = {"DOUBLE", "D"};
    private static final String[] MOD_TRIPLE = {"TRIPLE", "TREBLE", "T"};
    private static final String[] SUBMIT = {"SUBMIT", "OK", "ENTER", "DONE", "CONFIRM", "NEXT"};
    private static final String[] UNDO = {"UNDO", "DELETE", "BACKSPACE", "⌫", "CLEAR LAST"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean busy;

    // ---------------------------------------------------------------- public API (main thread)

    public void enterDart(String label, Result cb) {
        enqueue(() -> {
            String err = precheck();
            if (err != null) { finish(cb, "ERR " + err); return; }
            List<String[]> steps = plan(label);
            if (steps == null) { finish(cb, "ERR unknown dart " + label); return; }
            int before = DartApp.get().board().entered.size();
            press(steps, 0, label, cb, before);
        });
    }

    public void submit(Result cb) {
        enqueue(() -> {
            String err = precheck();
            if (err != null) { finish(cb, "ERR " + err); return; }
            if (DartApp.get().board().entered.isEmpty()) {
                // DartCounter already moved on by itself (e.g. after the 3rd dart): nothing to do,
                // and pressing SUBMIT now would enter an empty visit for the next player.
                finish(cb, "OK SUBMIT (nothing entered)");
                return;
            }
            String miss = tap(SUBMIT, false);
            finishLater(cb, miss == null ? "OK SUBMIT" : "ERR no " + miss + " button");
        });
    }

    public void undo(Result cb) {
        enqueue(() -> {
            String err = precheck();
            if (err != null) { finish(cb, "ERR " + err); return; }
            String miss = tap(UNDO, true);
            finishLater(cb, miss == null ? "OK UNDO" : "ERR no undo button");
        });
    }

    // ---------------------------------------------------------------- planning

    /** Buttons to press for a dart, each step a list of accepted labels. null = unknown dart. */
    static List<String[]> plan(String label) {
        String l = label.trim().toUpperCase(Locale.ROOT);
        List<String[]> s = new ArrayList<>();
        switch (l) {
            case "MISS": case "M": case "0": case "S0":
                s.add(new String[]{"MISS", "0", "M", "NO SCORE"});
                return s;
            case "25": case "S25": case "OUTER":
                s.add(opt(MOD_SINGLE));
                s.add(new String[]{"25", "OUTER BULL", "OUTER", "S25", "SBULL"});
                return s;
            case "BULL": case "D25": case "50": case "DBULL":
                s.add(new String[]{"BULL", "BULLSEYE", "50", "D25", "DBULL", "INNER BULL"});
                return s;
        }
        if (l.length() < 2) return null;
        char m = l.charAt(0);
        int n;
        try { n = Integer.parseInt(l.substring(1)); } catch (NumberFormatException e) { return null; }
        if (n < 1 || n > 20) return null;
        if (m == 'T') s.add(MOD_TRIPLE);
        else if (m == 'D') s.add(MOD_DOUBLE);
        else if (m == 'S') s.add(opt(MOD_SINGLE));
        else return null;
        s.add(new String[]{String.valueOf(n)});
        return s;
    }

    /** Marks a step as optional: pressed if such a button exists, skipped otherwise. */
    private static String[] opt(String[] labels) {
        String[] o = new String[labels.length + 1];
        o[0] = "?";
        System.arraycopy(labels, 0, o, 1, labels.length);
        return o;
    }

    // ---------------------------------------------------------------- execution

    private void press(List<String[]> steps, int i, String label, Result cb, int before) {
        if (i == steps.size()) {
            handler.postDelayed(() -> {
                Scoreboard b = DartApp.get().board();
                String shown = String.join(",", b.entered);
                boolean grew = b.entered.size() > before || (before == 2 && b.entered.isEmpty());
                cb.done((grew ? "OK DART " : "WARN DART ") + label + " (DartCounter shows: " + (shown.isEmpty() ? "-" : shown) + ")");
                next();
            }, SETTLE_MS);
            return;
        }
        String[] want = steps.get(i);
        boolean optional = want.length > 0 && "?".equals(want[0]);
        String[] labels = optional ? java.util.Arrays.copyOfRange(want, 1, want.length) : want;
        String missing = tap(labels, false);
        if (missing != null && !optional) {
            finish(cb, "ERR no button " + missing + " for " + label + " – is DartCounter in single-dart input?");
            return;
        }
        handler.postDelayed(() -> press(steps, i + 1, label, cb, before), missing == null ? TAP_GAP_MS : 0);
    }

    private String precheck() {
        if (!DartApp.get().typingOn()) return "entering darts is switched off in Dart LED";
        DartReaderService svc = DartReaderService.instance;
        if (svc == null) return "Dart LED accessibility service is off";
        if (svc.dartCounterRoot() == null) return "DartCounter is not on screen";
        if (System.currentTimeMillis() - DartApp.get().lastSeenAt() > 60_000 && DartApp.get().board().players.isEmpty())
            return "no DartCounter match on screen";
        return null;
    }

    /**
     * Press the button with one of the labels. Returns null on success, else the label tried.
     * descContains: also accept content descriptions that merely contain the label (icon buttons).
     */
    private String tap(String[] labels, boolean descContains) {
        DartReaderService svc = DartReaderService.instance;
        AccessibilityNodeInfo root = svc == null ? null : svc.dartCounterRoot();
        if (root == null) return labels[0];
        for (String want : labels) {
            AccessibilityNodeInfo node = find(root, plain(want), descContains);
            if (node != null && click(svc, node)) return null;
        }
        return labels[0];
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String want, boolean descContains) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        AccessibilityNodeInfo best = null;
        int bestY = Integer.MIN_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all) {
            String t = n.getText() == null ? "" : plain(n.getText().toString());
            String d = n.getContentDescription() == null ? "" : plain(n.getContentDescription().toString());
            boolean match = t.equals(want) || d.equals(want) || (descContains && want.length() > 2 && d.contains(want));
            if (!match) continue;
            // Pressable elements first, then the lowest on screen (the keypad is at the bottom).
            n.getBoundsInScreen(r);
            int rank = (clickTarget(n) != null ? 100_000 : 0) + r.centerY();
            if (rank > bestY) { bestY = rank; best = n; }
        }
        return best;
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 80 || out.size() > 1500) return;
        if (!n.isVisibleToUser()) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable() && cur.isEnabled()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean click(AccessibilityService svc, AccessibilityNodeInfo node) {
        AccessibilityNodeInfo t = clickTarget(node);
        if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        // Fallback: a real tap in the middle of the label (needs canPerformGestures).
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        try {
            return svc.dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    static String plain(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ---------------------------------------------------------------- queue

    private void enqueue(Runnable r) {
        queue.add(r);
        if (!busy) next();
    }

    private void next() {
        Runnable r = queue.poll();
        busy = r != null;
        if (r != null) r.run();
    }

    private void finish(Result cb, String line) {
        cb.done(line);
        next();
    }

    private void finishLater(Result cb, String line) {
        handler.postDelayed(() -> finish(cb, line), SETTLE_MS);
    }

    // ---------------------------------------------------------------- debugging

    /** Every visible DartCounter element with text, whether it can be pressed, and where. */
    public static String describeButtons() {
        DartReaderService svc = DartReaderService.instance;
        AccessibilityNodeInfo root = svc == null ? null : svc.dartCounterRoot();
        if (root == null) return "DartCounter not on screen (or accessibility service off)";
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        StringBuilder sb = new StringBuilder();
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText(), d = n.getContentDescription();
            if ((t == null || t.length() == 0) && (d == null || d.length() == 0)) continue;
            n.getBoundsInScreen(r);
            sb.append(clickTarget(n) != null ? "[btn] " : "      ")
                    .append(t == null ? "" : t).append(d == null ? "" : " {" + d + "}")
                    .append("  @").append(r.centerX()).append(',').append(r.centerY())
                    .append(' ').append(n.getClassName()).append('\n');
        }
        return sb.toString();
    }
}
