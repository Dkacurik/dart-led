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

    /** Where to tap for a key: a real element, or a point worked out from the keypad's grid. */
    public static final class Key {
        public final int x, y;
        public final El el;           // null if estimated
        Key(El el) { this.el = el; this.x = el.cx(); this.y = el.cy(); }
        Key(int x, int y) { this.el = null; this.x = x; this.y = y; }
        @Override public String toString() { return el != null ? el.toString() : "(grid) @" + x + "," + y; }
    }

    private static final String[][] TAB_WORDS = {{"SINGLE"}, {"DOUBLE"}, {"TREBLE", "TRIPLE"}, {"BULL", "BULLSEYE"}, {"OUTER"}};

    public Key single, dbl, treble, bull, outer, miss, undo, submit;
    public final Key[] numbers = new Key[21];
    public int colPitch, rowPitch;

    /**
     * The keypad on this screen, or null if there is no tab row (Single/Double/Treble/Bull/Outer, at
     * least two of them). DartCounter is a web app: some elements report bounds far off screen (CSS
     * transforms), so only elements on the screen (screenW × screenH, 0 = don't check) are trusted and
     * the rest of the keypad is worked out from its grid: 5 columns shared by the tabs and the numbers,
     * 4 number rows, the MISS row below, the submit arrow above the last column.
     */
    public static Keypad find(List<El> all, int screenW, int screenH) {
        List<El> els = new ArrayList<>();
        for (El e : all) {
            if (screenW > 0 && (e.cx() < 0 || e.cx() >= screenW || e.cy() < 0 || e.cy() >= screenH)) continue;
            els.add(e);
        }
        // Tab row.
        El[] tab = new El[5];
        for (int c = 0; c < 5; c++) for (String w : TAB_WORDS[c]) if (tab[c] == null) tab[c] = lowestWord(els, w);
        int rowRef = -1;
        for (int c = 0; c < 5; c++) if (tab[c] != null && (rowRef < 0 || c == 1)) rowRef = c;
        if (rowRef < 0) return null;
        int tabY = tab[rowRef].cy(), tol = Math.max(tab[rowRef].h(), 40);
        int first = -1, last = -1;
        for (int c = 0; c < 5; c++) {
            if (tab[c] != null && Math.abs(tab[c].cy() - tabY) > tol) tab[c] = null;      // not in the row
            if (tab[c] != null) { if (first < 0) first = c; last = c; }
        }
        if (first < 0 || first == last) return null;
        Keypad k = new Keypad();
        k.colPitch = (tab[last].cx() - tab[first].cx()) / (last - first);
        if (k.colPitch < 20) return null;
        int[] colX = new int[5];
        for (int c = 0; c < 5; c++) colX[c] = tab[c] != null ? tab[c].cx() : tab[first].cx() + (c - first) * k.colPitch;
        Key[] tabs = new Key[5];
        for (int c = 0; c < 5; c++) tabs[c] = tab[c] != null ? new Key(tab[c]) : new Key(colX[c], tabY);
        k.single = tabs[0]; k.dbl = tabs[1]; k.treble = tabs[2]; k.bull = tabs[3]; k.outer = tabs[4];
        int left = colX[0] - k.colPitch / 2, right = colX[4] + k.colPitch / 2;

        // Numbers and MISS below the tabs.
        El[] num = new El[21];
        El missEl = null;
        for (El e : els) {
            if (e.cx() < left || e.cx() > right || e.cy() <= tabY + tol / 2) continue;
            String s = !e.text.isEmpty() ? e.text : e.desc;
            if (s.matches("\\d{1,2}")) {
                int n = Integer.parseInt(s);
                if (n >= 1 && n <= 20) num[n] = better(num[n], e);
            } else if (e.says("MISS")) {
                missEl = better(missEl, e);
            }
        }
        double[] rowSum = new double[4];
        int[] rowCnt = new int[4];
        for (int n = 1; n <= 20; n++) if (num[n] != null) { rowSum[(n - 1) / 5] += num[n].cy(); rowCnt[(n - 1) / 5]++; }
        int r0 = -1, r1 = -1;
        for (int r = 0; r < 4; r++) if (rowCnt[r] > 0) { if (r0 < 0) r0 = r; r1 = r; }
        if (r0 < 0) return null;
        double y0 = rowSum[r0] / rowCnt[r0];
        k.rowPitch = r1 > r0 ? (int) Math.round((rowSum[r1] / rowCnt[r1] - y0) / (r1 - r0))
                             : (int) Math.round((y0 - tabY) / (r0 + 1));
        if (k.rowPitch < 20) return null;
        for (int n = 1; n <= 20; n++) {
            int c = (n - 1) % 5, r = (n - 1) / 5;
            int y = rowCnt[r] > 0 ? (int) Math.round(rowSum[r] / rowCnt[r]) : (int) Math.round(y0 + (r - r0) * k.rowPitch);
            k.numbers[n] = num[n] != null ? new Key(num[n]) : new Key(colX[c], y);
        }
        int bottomY = (int) Math.round(y0 + (4 - r0) * k.rowPitch);
        k.miss = missEl != null ? new Key(missEl) : new Key((colX[2] + colX[3]) / 2, bottomY);

        // Undo: described as such, else the icon left of MISS in its row, else the grid (under columns 1–2).
        for (El e : els) {
            if (e.cy() > tabY && e.cx() >= left && e.cx() <= right
                    && (has(e.desc, "UNDO") || has(e.desc, "BACK") || has(e.desc, "DELETE") || has(e.desc, "REMOVE")))
                k.undo = new Key(e);
        }
        if (k.undo == null && missEl != null) {
            El m = missEl, best = null;
            int band = Math.max(m.h(), 20);
            for (El e : els) {
                if (!e.pressable || e.says("MISS") || e.contains(m.cx(), m.cy())) continue;
                if (Math.abs(e.cy() - m.cy()) > band || e.cx() >= m.cx() || e.cx() < left) continue;
                if (!e.text.isEmpty() && !e.text.matches("[^A-Z0-9]*")) continue;    // a labelled button, not an icon
                if (best == null || e.cx() > best.cx()) best = e;
            }
            if (best != null) k.undo = new Key(best);
        }
        if (k.undo == null) k.undo = new Key((colX[0] + colX[1]) / 2, k.miss.y);

        // Submit: described as such, else a button above the last column, else the grid: the green
        // arrow sits at the right end of the input bar, about 1.2 rows above the tabs.
        int bandTop = tabY - 2 * k.rowPitch;
        for (El e : els) {
            if (e.cy() >= tabY - tol / 2 || e.cy() < bandTop || e.cx() < left || e.cx() > right) continue;
            if (has(e.desc, "SUBMIT") || has(e.desc, "CONFIRM") || has(e.desc, "SEND") || e.says("OK") || e.says("SUBMIT")) {
                k.submit = new Key(e);
                break;
            }
        }
        if (k.submit == null) {
            El best = null;
            for (El e : els) {
                if (!e.pressable || e.cy() >= tabY - tol / 2 || e.cy() < bandTop) continue;
                if (e.cx() < colX[4] - k.colPitch / 2 || e.cx() > right) continue;      // above the last column
                if (e.r - e.l > k.colPitch) continue;                                   // the whole bar, not a button
                if (best == null || e.cx() > best.cx()) best = e;
            }
            k.submit = best != null ? new Key(best)
                    : new Key(colX[4] + (int) Math.round(0.17 * k.colPitch), tabY - (int) Math.round(1.17 * k.rowPitch));
        }
        return k;
    }

    /**
     * The element for one step of DartTyper's plan (a list of accepted labels), or null.
     * "TREBLE"/"T" → Treble tab, "16" → number 16, "25"/"OUTER" → Outer 25, "BULL"/"50" → Bull 50, ...
     */
    public Key target(String[] labels) {
        for (String raw : labels) {
            String l = plain(raw);
            Key e = null;
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
