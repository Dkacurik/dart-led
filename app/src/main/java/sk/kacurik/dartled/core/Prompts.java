package sk.kacurik.dartled.core;

import java.util.ArrayList;
import java.util.List;

/**
 * DartCounter's questions at the end of a visit (plain Java, testable):
 *
 * <pre>
 *   YOU HAVE BUST YOUR SCORE        ← under the input bar after a bust
 *        [Confirm]
 *
 *   ┌ DARTS USED ON A DOUBLE ┐      ← dialog after a bust / checkout attempt
 *   │  [0]   [1]   [2]       │
 *   │       [Confirm]        │
 *   └────────────────────────┘
 * </pre>
 *
 * The dialog is answered from the darts of the visit: a dart counts as "used on a double" when the
 * score before it could be finished with one double (2–40 even, or 50 for the bull). A question about
 * darts used for the checkout (if DartCounter asks it) is answered with the number of darts.
 */
public final class Prompts {
    private Prompts() {}

    /** One tap to make, with what it is for the log. */
    public static final class Tap {
        public final int x, y;
        public final String what;
        Tap(int x, int y, String what) { this.x = x; this.y = y; this.what = what; }
        @Override public String toString() { return what + " @" + x + "," + y; }
    }

    /** Is a bust banner or a "darts used" dialog on screen (darts must not be entered now)? */
    public static boolean blocking(List<Keypad.El> all, int screenW, int screenH) {
        List<Keypad.El> els = onScreen(all, screenW, screenH);
        return bustText(els) != null || !titles(els).isEmpty();
    }

    /**
     * Taps answering what is on screen: the dialog (option + Confirm) if open, else the bust
     * banner's Confirm. Empty list = nothing to answer. startScore = remaining score before the
     * visit (-1 unknown), darts = the visit's darts ("S1", "D10", ...).
     */
    public static List<Tap> answer(List<Keypad.El> all, int screenW, int screenH, int startScore, List<String> darts) {
        List<Keypad.El> els = onScreen(all, screenW, screenH);
        List<Tap> taps = new ArrayList<>();
        List<Keypad.El> titles = titles(els);
        if (!titles.isEmpty()) {
            int lowest = titles.get(titles.size() - 1).cy();
            for (int i = 0; i < titles.size(); i++) {
                Keypad.El t = titles.get(i);
                int limit = i + 1 < titles.size() ? titles.get(i + 1).cy() : Integer.MAX_VALUE;
                List<Keypad.El> row = optionRow(els, t.cy(), limit);
                if (row.isEmpty()) continue;
                lowest = Math.max(lowest, row.get(0).cy());
                boolean checkout = t.text.contains("CHECKOUT") || t.text.contains("FINISH");
                int want = checkout ? Math.max(1, darts.size()) : doublesTried(startScore, darts);
                Keypad.El pick = null;
                for (Keypad.El o : row) {
                    int v = Integer.parseInt(o.text.isEmpty() ? o.desc : o.text);
                    if (v == want) pick = o;
                }
                if (pick == null) {                 // not offered: the nearest offered value
                    for (Keypad.El o : row) {
                        int v = Integer.parseInt(o.text.isEmpty() ? o.desc : o.text);
                        if (pick == null || Math.abs(v - want) < Math.abs(Integer.parseInt(pick.text.isEmpty() ? pick.desc : pick.text) - want)) pick = o;
                    }
                }
                taps.add(new Tap(pick.cx(), pick.cy(), (checkout ? "darts for the checkout: " : "darts on a double: ")
                        + (pick.text.isEmpty() ? pick.desc : pick.text)));
            }
            // The dialog's Confirm: the nearest below its options (a bust banner's Confirm may sit
            // behind the dialog, under the title).
            Keypad.El confirm = null;
            for (Keypad.El e : els)
                if (e.says("CONFIRM") && e.cy() > lowest && (confirm == null || e.cy() < confirm.cy())) confirm = e;
            if (confirm != null) taps.add(new Tap(confirm.cx(), confirm.cy(), "Confirm (dialog)"));
            return taps;
        }
        Keypad.El bust = bustText(els);
        if (bust != null) {
            Keypad.El confirm = null;
            for (Keypad.El e : els)
                if (e.says("CONFIRM") && e.cy() > bust.cy() && (confirm == null || e.cy() < confirm.cy())) confirm = e;
            if (confirm != null) taps.add(new Tap(confirm.cx(), confirm.cy(), "Confirm (bust)"));
        }
        return taps;
    }

    /** Darts thrown while the score left could be finished with one double. */
    public static int doublesTried(int startScore, List<String> darts) {
        if (startScore < 0) return 0;
        int rem = startScore, n = 0;
        for (String d : darts) {
            if (rem == 50 || (rem >= 2 && rem <= 40 && rem % 2 == 0)) n++;
            int v = ScreenParser.dartValue(d);
            if (v < 0) break;
            rem -= v;
            if (rem <= 1) break;      // bust or finished
        }
        return n;
    }

    private static List<Keypad.El> onScreen(List<Keypad.El> all, int w, int h) {
        List<Keypad.El> els = new ArrayList<>();
        for (Keypad.El e : all)
            if (w <= 0 || (e.cx() >= 0 && e.cx() < w && e.cy() >= 0 && e.cy() < h)) els.add(e);
        return els;
    }

    private static Keypad.El bustText(List<Keypad.El> els) {
        for (Keypad.El e : els) if (e.text.contains("BUST") || e.desc.contains("BUST")) return e;
        return null;
    }

    private static List<Keypad.El> titles(List<Keypad.El> els) {
        List<Keypad.El> t = new ArrayList<>();
        for (Keypad.El e : els) {
            String s = e.text.isEmpty() ? e.desc : e.text;
            if (s.contains("DARTS USED") || s.startsWith("DARTS ON") || s.contains("DARTS AT A DOUBLE")) t.add(e);
        }
        t.sort((a, b) -> Integer.compare(a.cy(), b.cy()));
        return t;
    }

    /** The row of 0–3 buttons nearest below a title (the keypad numbers behind the dialog are further down). */
    private static List<Keypad.El> optionRow(List<Keypad.El> els, int titleY, int limitY) {
        Keypad.El first = null;
        for (Keypad.El e : els) {
            String s = e.text.isEmpty() ? e.desc : e.text;
            if (!s.matches("[0-3]") || e.cy() <= titleY || e.cy() >= limitY) continue;
            if (first == null || e.cy() < first.cy()) first = e;
        }
        List<Keypad.El> row = new ArrayList<>();
        if (first == null) return row;
        int band = Math.max(20, first.h());
        for (Keypad.El e : els) {
            String s = e.text.isEmpty() ? e.desc : e.text;
            if (s.matches("[0-3]") && Math.abs(e.cy() - first.cy()) <= band) row.add(e);
        }
        return row;
    }
}
