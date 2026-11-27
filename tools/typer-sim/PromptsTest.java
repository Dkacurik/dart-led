import sk.kacurik.dartled.core.Keypad.El;
import sk.kacurik.dartled.core.Prompts;

import java.util.*;

/**
 * Prompts: DartCounter's bust banner and "DARTS USED ON A DOUBLE" dialog (tablet screenshots
 * 1389×868 scaled to the real 2112×1320 screen), and the number of darts used on a double.
 *
 *   javac -d /tmp/typersim app/src/main/java/sk/kacurik/dartled/core/*.java tools/typer-sim/*.java
 *   java -cp /tmp/typersim PromptsTest
 */
public class PromptsTest {
    static int failures;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static final double S = 2112.0 / 1389;
    static int n;
    static El el(String text, double x, double y, double hw, double hh, boolean pressable) {
        return new El(text, "", (int) ((x - hw) * S), (int) ((y - hh) * S), (int) ((x + hw) * S), (int) ((y + hh) * S), pressable, n++);
    }

    /** Keypad and input bar as on the bust screenshot (a dialog may cover it, the elements stay). */
    static List<El> base() {
        List<El> e = new ArrayList<>();
        e.add(new El("Matej", "", 2950, 230, 3100, 270, false, n++));                 // player card, reported off screen
        e.add(el("MATEJ'S TURN TO THROW!", 455, 152, 115, 18, false));
        e.add(el("1", 475, 220, 15, 22, false));
        e.add(el("S1", 475, 252, 15, 8, false));
        e.add(el("Single", 396, 433, 60, 12, true));
        e.add(el("Double", 517, 433, 60, 12, true));
        e.add(el("Treble", 639, 433, 60, 12, true));
        for (int i = 1; i <= 20; i++) e.add(el(String.valueOf(i), 396 + 121.7 * ((i - 1) % 5), 505 + 72.5 * ((i - 1) / 5), 50, 30, true));
        e.add(el("MISS", 700, 795, 60, 30, true));
        return e;
    }

    public static void main(String[] a) {
        List<String> visit = List.of("S1", "S5", "S20");
        check(Prompts.doublesTried(21, visit) == 1, "21: S1 (21 odd), S5 at 20 (a double), S20 at 15 → 1 dart on a double");
        check(Prompts.doublesTried(40, List.of("S20", "D10")) == 2, "40: S20 at 40, D10 at 20 → 2");
        check(Prompts.doublesTried(32, List.of("S16", "S8", "D4")) == 3, "32 → 16 → 8: all three at a double → 3");
        check(Prompts.doublesTried(101, List.of("T20", "S1", "D20")) == 1, "101: T20, S1, D20 at 40 → 1");
        check(Prompts.doublesTried(50, List.of("BULL")) == 1, "50: bull → 1");
        check(Prompts.doublesTried(-1, visit) == 0, "unknown score → 0");

        // 1. Bust banner: press its Confirm.
        List<El> bust = base();
        bust.add(el("YOU HAVE BUST YOUR SCORE", 640, 318, 95, 12, false));
        bust.add(el("Confirm", 640, 362, 60, 18, true));
        check(Prompts.blocking(bust, 2112, 1320), "bust banner blocks entering darts");
        List<Prompts.Tap> t1 = Prompts.answer(bust, 2112, 1320, 21, visit);
        System.out.println("  bust: " + t1);
        check(t1.size() == 1 && near(t1.get(0), 640, 362), "bust: Confirm under the banner");

        // 2. The dialog that follows (banner still in the tree behind it).
        List<El> dlg = new ArrayList<>(bust);
        dlg.add(el("DARTS USED ON A DOUBLE", 694, 347, 115, 15, false));
        dlg.add(el("0", 464, 417, 105, 38, true));
        dlg.add(el("1", 694, 417, 105, 38, true));
        dlg.add(el("2", 924, 417, 105, 38, true));
        dlg.add(el("Confirm", 694, 528, 335, 30, true));
        List<Prompts.Tap> t2 = Prompts.answer(dlg, 2112, 1320, 21, visit);
        System.out.println("  dialog: " + t2);
        check(t2.size() == 2 && near(t2.get(0), 694, 417) && near(t2.get(1), 694, 528),
                "dialog: option 1 (not the keypad's 1 behind it), then the dialog's Confirm");
        List<Prompts.Tap> t3 = Prompts.answer(dlg, 2112, 1320, 101, List.of("T20", "T20", "T20"));
        check(t3.size() == 2 && near(t3.get(0), 464, 417), "dialog: no dart at a double → option 0");

        // 3. Normal keypad: nothing to answer.
        check(!Prompts.blocking(base(), 2112, 1320) && Prompts.answer(base(), 2112, 1320, 21, visit).isEmpty(),
                "normal keypad: nothing to answer");

        if (failures > 0) { System.out.println(failures + " PROMPT FAILURES"); System.exit(1); }
        System.out.println("PROMPTS: all checks passed");
    }

    static boolean near(Prompts.Tap t, double x, double y) { return Math.hypot(t.x - x * S, t.y - y * S) < 12; }
}
