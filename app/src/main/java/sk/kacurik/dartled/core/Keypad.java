package sk.kacurik.dartled.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DartCounter's per-dart keypad, found from the elements on screen (plain Java, testable):
 *
 * <pre>
 *   [grid] [dart] [dart] [dart] (→)          ← input bar, (→) = submit (an icon without text)
 *   Single  Double  Treble  Bull 50  Outer 25   ← tab row
 *     1   2   3   4   5
 *    ...
 *    16  17  18  19  20
 *     (↶)     MISS                             ← (↶) = undo (an icon without text)
 * </pre>
 *
 * The tab row (Single / Double / Treble|Triple) defines the keypad: numbers are only taken inside it
 * (the player cards also show numbers), MISS below it, undo = the button left of MISS in its row,
 * submit = the rightmost button in the band just above the tab row.
 */
public final class Keypad {

    /** One element on screen. pressable = can be pressed itself (not just through a parent). */
    public static final class El {
        public final String text, desc;
        public final int l, t, r, b;
        public final boolean pressable;
        public final int index;          // caller's own index (e.g. into its node list)

        public El(String text, String desc, int l, int t, int r, int b, boolean pressable, int index) {
            this.text = plain(text);
            this.desc = plain(desc);
            this.l = l; this.t = t; this.r = r; this.b = b;
            this.pressable = pressable;
            this.index = index;
        }
        int cx() { return (l + r) / 2; }
        int cy() { return (t + b) / 2; }
        int h() { return b - t; }
        boolean contains(int x, int y) { return x >= l && x < r && y >= t && y < b; }
        /** First word of the text, else of the description ("Bull 50" → "BULL"). */
        String word() {
            String s = !text.isEmpty() ? text : desc;
            int sp = s.indexOf(' ');
            return sp < 0 ? s : s.substring(0, sp);
        }
        boolean says(String w) { return text.equals(w) || desc.equals(w); }
        @Override public String toString() {
            return (text.isEmpty() ? "" : text) + (desc.isEmpty() ? "" : " {" + desc + "}") + " @" + cx() + "," + cy();
        }
    }

    public static final String[] MOD_SINGLE = {"SINGLE", "S"};
    public static final String[] MOD_DOUBLE = {"DOUBLE", "D"};
    public static final String[] MOD_TRIPLE = {"TRIPLE", "TREBLE", "T"};
    public static final String[] SUBMIT = {"SUBMIT", "OK", "ENTER", "DONE", "CONFIRM", "NEXT"};
    public static final String[] UNDO = {"UNDO", "DELETE", "BACKSPACE", "⌫", "CLEAR LAST"};

    /** Buttons to press for a dart, each step a list of accepted labels. null = unknown dart. */
    public static List<String[]> plan(String label) {
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

    public El single, dbl, treble, bull, outer, miss, undo, submit;
    public final El[] numbers = new El[21];
    int left, right, tabTop, tabH;

    /** The keypad on this screen, or null if there is no Single/Double/Treble tab row. */
    public static Keypad find(List<El> els) {
        Keypad k = new Keypad();
        k.single = lowestWord(els, "SINGLE");
        k.dbl = lowestWord(els, "DOUBLE");
        k.treble = lowestWord(els, "TREBLE");
        if (k.treble == null) k.treble = lowestWord(els, "TRIPLE");
        if (k.dbl == null || k.treble == null) return null;
        El ref = k.dbl;
        if (Math.abs(k.treble.cy() - ref.cy()) > Math.max(ref.h(), 20)) return null;   // not one row
        k.tabTop = Math.min(ref.t, k.treble.t);
        k.tabH = Math.max(Math.max(ref.h(), k.treble.h()), 20);
        k.left = Math.min(ref.l, k.treble.l);
        k.right = Math.max(ref.r, k.treble.r);
        for (El e : els) {
            if (Math.abs(e.cy() - ref.cy()) > k.tabH) continue;
            String w = e.word();
            if (w.equals("BULL") || w.equals("BULLSEYE")) k.bull = better(k.bull, e);
            else if (w.equals("OUTER")) k.outer = better(k.outer, e);
            else if (w.equals("SINGLE")) k.single = better(k.single, e);
            else continue;
            k.left = Math.min(k.left, e.l);
            k.right = Math.max(k.right, e.r);
        }
        int slack = (k.right - k.left) / 20;
        int tabMid = ref.cy();

        for (El e : els) {
            if (e.cx() < k.left - slack || e.cx() > k.right + slack || e.cy() <= tabMid + k.tabH / 2) continue;
            String s = !e.text.isEmpty() ? e.text : e.desc;
            if (s.matches("\\d{1,2}")) {
                int n = Integer.parseInt(s);
                if (n >= 1 && n <= 20) k.numbers[n] = better(k.numbers[n], e);
            } else if (e.says("MISS")) {
                k.miss = better(k.miss, e);
            }
        }

        // Undo: described as such, else the button left of MISS in MISS's row.
        for (El e : els) {
            if (e.cy() > tabMid && e.cx() >= k.left - slack && e.cx() <= k.right + slack
                    && (has(e.desc, "UNDO") || has(e.desc, "BACK") || has(e.desc, "DELETE") || has(e.desc, "REMOVE")))
                k.undo = better(k.undo, e);
        }
        if (k.undo == null && k.miss != null) {
            El m = k.miss;
            int band = Math.max(m.h(), 20);
            for (El e : els) {
                if (!e.pressable || e.says("MISS") || e.contains(m.cx(), m.cy())) continue;
                if (Math.abs(e.cy() - m.cy()) > band || e.cx() >= m.cx() || e.cx() < k.left - slack) continue;
                if (!e.text.isEmpty() && !e.text.matches("[^A-Z0-9]*")) continue;    // a labelled button, not an icon
                if (k.undo == null || e.cx() > k.undo.cx()) k.undo = e;
            }
        }

        // Submit: described as such, else the rightmost button in the band above the tab row.
        int bandTop = k.tabTop - Math.max(3 * k.tabH, (k.right - k.left) / 3);   // tab text can be short
        for (El e : els) {
            if (e.cy() >= k.tabTop || e.cy() < bandTop || e.cx() < k.left - slack || e.cx() > k.right + slack) continue;
            if (has(e.desc, "SUBMIT") || has(e.desc, "CONFIRM") || has(e.desc, "SEND") || e.says("OK") || e.says("SUBMIT")) {
                k.submit = e;
                break;
            }
        }
        if (k.submit == null) {
            for (El e : els) {
                if (!e.pressable || e.cy() >= k.tabTop || e.cy() < bandTop) continue;
                if (e.cx() < k.left - slack || e.cx() > k.right + slack) continue;
                if (e.r - e.l > (k.right - k.left) / 2) continue;                    // the whole bar, not a button
                if (k.submit == null || e.cx() > k.submit.cx()) k.submit = e;
            }
        }
        return k;
    }

    /**
     * The element for one step of DartTyper's plan (a list of accepted labels), or null.
     * "TREBLE"/"T" → Treble tab, "16" → number 16, "25"/"OUTER" → Outer 25, "BULL"/"50" → Bull 50, ...
     */
    public El target(String[] labels) {
        for (String raw : labels) {
            String l = plain(raw);
            El e = null;
            switch (l) {
                case "SINGLE": case "S": e = single; break;
                case "DOUBLE": case "D": e = dbl; break;
                case "TREBLE": case "TRIPLE": case "T": e = treble; break;
                case "BULL": case "BULLSEYE": case "50": case "D25": case "DBULL": case "INNER BULL": e = bull; break;
                case "25": case "OUTER": case "OUTER BULL": case "S25": case "SBULL": e = outer; break;
                case "MISS": case "M": case "NO SCORE": e = miss; break;
                case "SUBMIT": case "OK": case "ENTER": case "DONE": case "CONFIRM": case "NEXT": e = submit; break;
                case "UNDO": case "DELETE": case "BACKSPACE": e = undo; break;
                default:
                    if (l.matches("\\d{1,2}")) {
                        int n = Integer.parseInt(l);
                        if (n >= 1 && n <= 20) e = numbers[n];
                    }
            }
            if (e != null) return e;
        }
        return null;
    }

    private static El lowestWord(List<El> els, String w) {
        El best = null;
        for (El e : els) if (e.word().equals(w)) best = better(best, e);
        return best;
    }

    /** Pressable first, then the lower one on screen. */
    private static El better(El a, El b) {
        if (a == null) return b;
        if (a.pressable != b.pressable) return a.pressable ? a : b;
        return b.cy() > a.cy() ? b : a;
    }

    private static boolean has(String s, String w) { return s.contains(w); }

    public static String plain(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("single=").append(single).append("\ndouble=").append(dbl).append("\ntreble=").append(treble)
                .append("\nbull=").append(bull).append("\nouter=").append(outer).append("\nmiss=").append(miss)
                .append("\nundo=").append(undo).append("\nsubmit=").append(submit).append("\nnumbers:");
        for (int n = 1; n <= 20; n++) sb.append(' ').append(numbers[n] == null ? "-" : String.valueOf(n));
        return sb.toString();
    }
}
