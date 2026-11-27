import sk.kacurik.dartled.core.Keypad;
import sk.kacurik.dartled.core.Keypad.El;

import java.util.*;

/**
 * Keypad: on DartCounter's match screen (tablet screenshot, 2000×1250) every dart presses the right
 * buttons; submit = the green arrow icon, undo = the arrow icon left of MISS.
 * Two styles of accessibility tree: text in a child of a pressable cell (native views) and pressable
 * nodes carrying only a description (Flutter/Compose-like).
 *
 *   javac -d /tmp/typersim app/src/main/java/sk/kacurik/dartled/core/Keypad.java tools/typer-sim/*.java
 *   java -cp /tmp/typersim KeypadTest
 */
public class KeypadTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static final class Screen {
        final List<El> els = new ArrayList<>();
        final boolean descOnly;
        Screen(boolean descOnly) { this.descOnly = descOnly; }

        int add(String text, String desc, int l, int t, int r, int b, boolean pressable) {
            els.add(new El(text, desc, l, t, r, b, pressable, els.size()));
            return els.size() - 1;
        }
        /** A labelled button: returns the index that must be pressed (cell or its text). */
        int button(String label, int l, int t, int r, int b) {
            if (descOnly) return add("", label, l, t, r, b, true);
            add("", "", l, t, r, b, true);                                 // pressable cell
            int cx = (l + r) / 2, cy = (t + b) / 2;
            String[] lines = label.split("\n");
            int first = -1;
            for (int i = 0; i < lines.length; i++) {
                int y = cy - 14 * lines.length + 28 * i;
                int idx = add(lines[i], "", cx - 30, y, cx + 30, y + 26, false);
                if (first < 0) first = idx;
            }
            return first;                                                   // DartTyper clicks via the parent
        }
        int icon(int l, int t, int r, int b) {
            int cell = add("", "", l, t, r, b, true);
            add("", "", l + 20, t + 20, r - 20, b - 20, false);             // the image inside
            return cell;
        }
    }

    static final Map<String, Integer> idx = new HashMap<>();

    static Screen build(boolean descOnly) {
        idx.clear();
        Screen s = new Screen(descOnly);
        // Top bar and player cards (numbers that must NOT be pressed).
        s.icon(25, 65, 75, 115);
        s.add("FIRST TO 2 LEGS", "", 870, 60, 1130, 115, false);
        s.icon(1880, 65, 1930, 110);
        s.icon(1930, 65, 1980, 110);
        s.add("", "", 28, 163, 455, 680, true);                              // player card (pressable)
        s.add("Samuel", "", 160, 215, 290, 260, false);
        s.add("501", "", 60, 320, 200, 440, false);
        s.add("0", "", 362, 392, 416, 446, false);
        s.add("3-dart avg.", "", 64, 490, 215, 525, false);
        s.add("0.00", "", 350, 485, 410, 530, false);
        s.add("Darts thrown", "", 64, 590, 240, 625, false);
        s.add("0", "", 395, 585, 410, 630, false);
        s.add("Dávid", "", 160, 735, 260, 775, false);
        s.add("501", "", 60, 835, 200, 955, false);
        s.add("0", "", 362, 910, 416, 965, false);
        s.add("0", "", 395, 1100, 410, 1150, false);
        s.add("SAMUEL'S TURN TO THROW!", "", 490, 195, 840, 240, false);
        s.icon(1905, 190, 1965, 250);                                        // layout button
        s.add("#1", "", 1657, 297, 1702, 352, false);
        s.icon(1910, 1137, 1970, 1197);                                      // camera
        // Input bar: whole bar, grid icon, 3 empty dart slots, green arrow.
        s.add("", "", 483, 274, 1358, 390, true);
        s.icon(497, 290, 572, 375);
        for (int i = 0; i < 3; i++) s.add("", "", 573 + 224 * i, 274, 796 + 224 * i, 390, true);
        idx.put("SUBMIT", s.icon(1259, 289, 1344, 375));
        // Tabs.
        String[] tabs = {"Single", "Double", "Treble", "Bull\n50", "Outer\n25"};
        String[] keys = {"SINGLE", "DOUBLE", "TREBLE", "BULL", "OUTER"};
        for (int i = 0; i < 5; i++) idx.put(keys[i], s.button(tabs[i], 483 + 175 * i, 420, 658 + 175 * i, 548));
        // Numbers 1..20.
        for (int n = 1; n <= 20; n++) {
            int c = (n - 1) % 5, r = (n - 1) / 5;
            idx.put(String.valueOf(n), s.button(String.valueOf(n), 483 + 175 * c, 548 + 130 * r, 658 + 175 * c, 678 + 130 * r));
        }
        // Bottom row: undo icon, MISS, empty cell.
        idx.put("UNDO", s.icon(483, 1070, 833, 1198));
        idx.put("MISS", s.button("MISS", 833, 1070, 1183, 1198));
        s.add("", "", 1183, 1070, 1358, 1198, false);
        return s;
    }

    /** Which buttons a dart presses: the keys of idx, in order ("?" steps skipped if missing). */
    static List<String> presses(Keypad k, String dart) {
        List<String> out = new ArrayList<>();
        List<String[]> steps = Keypad.plan(dart);
        if (steps == null) { out.add("UNKNOWN"); return out; }
        for (String[] st : steps) {
            boolean optional = st.length > 0 && "?".equals(st[0]);
            String[] labels = optional ? Arrays.copyOfRange(st, 1, st.length) : st;
            Keypad.Key key = k.target(labels);
            if (key == null) { if (!optional) out.add("MISSING"); continue; }
            String name = "?" + key;
            for (Map.Entry<String, Integer> m : idx.entrySet()) if (key.el != null && m.getValue() == key.el.index) name = m.getKey();
            out.add(name);
        }
        return out;
    }

    static void run(boolean descOnly) {
        String style = descOnly ? "[description-only] " : "[text in cells] ";
        Screen s = build(descOnly);
        Keypad k = Keypad.find(s.els, 2000, 1250);
        check(k != null, style + "keypad found");
        if (k == null) return;
        Map<String, List<String>> want = new LinkedHashMap<>();
        want.put("T20", List.of("TREBLE", "20"));
        want.put("D16", List.of("DOUBLE", "16"));
        want.put("S5", List.of("SINGLE", "5"));
        want.put("S1", List.of("SINGLE", "1"));
        want.put("T19", List.of("TREBLE", "19"));
        want.put("D1", List.of("DOUBLE", "1"));
        want.put("25", List.of("SINGLE", "OUTER"));
        want.put("BULL", List.of("BULL"));
        want.put("MISS", List.of("MISS"));
        for (Map.Entry<String, List<String>> w : want.entrySet()) {
            List<String> got = presses(k, w.getKey());
            check(got.equals(w.getValue()), style + w.getKey() + " → " + got);
        }
        int bad = 0;
        for (int n = 1; n <= 20; n++) if (!presses(k, "S" + n).equals(List.of("SINGLE", String.valueOf(n)))) bad++;
        check(bad == 0, style + "S1..S20 all on the keypad (" + bad + " wrong)");
        Keypad.Key sub = k.target(Keypad.SUBMIT), undo = k.target(Keypad.UNDO);
        check(sub != null && sub.el != null && sub.el.index == idx.get("SUBMIT"), style + "submit = green arrow (" + sub + ")");
        check(undo != null && undo.el != null && undo.el.index == idx.get("UNDO"), style + "undo = arrow left of MISS (" + undo + ")");
    }

    /**
     * The real tablet (Galaxy Tab S10 Lite, 2112×1320), from Dart LED's "Copy DartCounter buttons":
     * DartCounter is a web app and reports some bounds far off screen (player cards, input bar icons,
     * even the selected "Single" tab at x=2392). Expected tap points come from the screenshot
     * (2000×1250) scaled by 2112/2000.
     */
    static void realTablet() {
        List<El> els = new ArrayList<>();
        int[] n = {0};
        java.util.function.BiConsumer<String, int[]> at = (label, c) ->
                els.add(new El(label, "", c[0] - 40, c[1] - 25, c[0] + 40, c[1] + 25, true, n[0]++));
        at.accept("", new int[]{3664, 149});
        at.accept("", new int[]{3710, 149});
        els.add(new El("Samuel", "", 2000, 270, 2120, 310, false, n[0]++));
        els.add(new El("501", "", 1900, 380, 2036, 474, false, n[0]++));
        els.add(new El("0", "", 2200, 440, 2236, 488, false, n[0]++));
        els.add(new El("SAMUEL'S TURN TO THROW!", "", 2800, 250, 3176, 292, false, n[0]++));
        els.add(new El("", "", 3663, 243, 3723, 302, true, n[0]++));
        els.add(new El("", "", 2322, 344, 2393, 416, true, n[0]++));
        els.add(new El("", "", 3048, 337, 3132, 424, true, n[0]++));
        at.accept("Single", new int[]{2392, 524});
        at.accept("Double", new int[]{788, 510});
        at.accept("Treble", new int[]{974, 510});
        at.accept("Bull 50", new int[]{1159, 510});
        at.accept("Outer 25", new int[]{1344, 510});
        int[] xs = {605, 788, 974, 1160, 1344}, ys = {648, 786, 923, 1059};
        for (int i = 1; i <= 20; i++) at.accept(String.valueOf(i), new int[]{xs[(i - 1) % 5], ys[(i - 1) / 5]});
        at.accept("", new int[]{698, 1196});
        at.accept("MISS", new int[]{1067, 1196});
        els.add(new El("...", "", 1600, 330, 1640, 354, false, n[0]++));
        els.add(new El("#1", "", 1750, 320, 1798, 364, false, n[0]++));
        at.accept("", new int[]{2048, 1230});

        Keypad k = Keypad.find(els, 2112, 1320);
        check(k != null, "[real tablet] keypad found");
        if (k == null) return;
        System.out.println(k);
        double sc = 2112.0 / 2000;
        near(k.single, (int) (570 * sc), (int) (484 * sc), 40, "Single (reported off screen) worked out from the grid");
        near(k.treble, 974, 510, 5, "Treble");
        near(k.numbers[1], 605, 648, 5, "1");
        near(k.numbers[16], 605, 1059, 5, "16");
        near(k.numbers[20], 1344, 1059, 5, "20");
        near(k.miss, 1067, 1196, 5, "MISS");
        near(k.undo, 698, 1196, 5, "undo (icon left of MISS)");
        near(k.submit, (int) (1301 * sc), (int) (332 * sc), 30, "submit = green arrow (icons reported off screen)");
    }

    static void near(Keypad.Key key, int x, int y, int tol, String what) {
        boolean ok = key != null && Math.hypot(key.x - x, key.y - y) <= tol;
        check(ok, "[real tablet] " + what + ": " + key + " (want ~" + x + "," + y + ")");
    }

    public static void main(String[] args) {
        run(false);
        run(true);
        realTablet();
        check(Keypad.find(new ArrayList<>(), 0, 0) == null, "no keypad on an empty screen");
        if (failures > 0) { System.out.println(failures + " KEYPAD FAILURES"); System.exit(1); }
        System.out.println("KEYPAD: all checks passed");
    }
}
