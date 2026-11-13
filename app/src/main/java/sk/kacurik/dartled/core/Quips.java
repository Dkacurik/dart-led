package sk.kacurik.dartled.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Random comments ("hlášky") for the whole match, not just the big moments.
 * English lines are read by the recorded voice, Slovak ones by the tablet's Slovak voice.
 */
public final class Quips {

    public static final class Quip {
        public final String text;
        public final boolean slovak;

        Quip(String text, boolean slovak) {
            this.text = text;
            this.slovak = slovak;
        }

        @Override
        public String toString() {
            return (slovak ? "[sk] " : "[en] ") + text;
        }
    }

    /** "en", "sk" or "mix". */
    public volatile String language = "mix";
    /** Chance (0..1) of a comment after an ordinary visit. */
    public volatile double chance = 0.4;
    /** At least this long between ordinary comments. */
    public long cooldownMs = 12_000;

    // ------------------------------------------------------------------ lines

    static final String[] EN_LOW = {        // 10-19
            "Warming up, are we?", "The board is that way.", "Tough board tonight.",
            "Every point counts. Apparently.", "Small steps."};
    static final String[] SK_LOW = {
            "Rozcvička?", "Terč je tamto.", "Aspoň niečo.", "Pomaly, ale isto. Veľmi pomaly.", "Aj to sa počíta."};
    static final String[] EN_MID = {        // 20-39
            "Steady.", "Keeping it tidy.", "Room for improvement.", "Nothing fancy.", "It all adds up."};
    static final String[] SK_MID = {
            "Dá sa aj lepšie.", "Pomaly ale isto.", "Bez paniky.", "Nič extra.", "Kvapka do mora."};
    static final String[] EN_GOOD = {       // 40-59
            "Not bad.", "Solid visit.", "Decent.", "Nicely done.", "Keep it coming."};
    static final String[] SK_GOOD = {
            "Slušné.", "Nie zlé.", "Solídne.", "Ide to.", "Len tak ďalej."};
    static final String[] EN_GREAT = {      // 60-99
            "Good scoring!", "Now we're talking!", "That's more like it!", "Lovely darts!", "Found the rhythm!"};
    static final String[] SK_GREAT = {
            "No konečne!", "To už je ono!", "Pekne!", "Toto sa mi páči!", "Chytil rytmus!"};
    static final String[] EN_DOUBLE_TROUBLE = {
            "Double trouble!", "The double is playing hard to get.", "So close, yet so far.", "That double is shrinking."};
    static final String[] SK_DOUBLE_TROUBLE = {
            "Tá dvojka sa nedá.", "Dvojka si dáva načas.", "Tak blízko, a predsa tak ďaleko.", "Dvojka sa zmenšuje."};
    static final String[] EN_LONG_LEG = {
            "This leg is taking a while.", "Somebody check this one out, please.", "Grab a drink, we'll be here a while."};
    static final String[] SK_LONG_LEG = {
            "To bude dlhý leg.", "Dajte si pivo, potrvá to.", "Niekto to už zatvorte, prosím."};
    static final String[] EN_NEW_LEG = {"New leg, fresh start.", "Here we go again.", "Next leg. Reset the nerves."};
    static final String[] SK_NEW_LEG = {"Nový leg, nové šťastie!", "Ideme znova.", "Nový leg, nervy na nulu."};
    static final String EN_DECIDING = "It's the deciding leg!";
    static final String SK_DECIDING = "Rozhodujúci leg!";
    static final String EN_MATCH_DARTS = "Match darts!";
    static final String SK_MATCH_DARTS = "Šípky na zápas!";

    /** Every fixed English line (for the voice-clip generator). */
    public static String[] allEnglish() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String[] a : new String[][]{EN_LOW, EN_MID, EN_GOOD, EN_GREAT, EN_DOUBLE_TROUBLE, EN_LONG_LEG, EN_NEW_LEG}) {
            java.util.Collections.addAll(l, a);
        }
        l.add(EN_DECIDING);
        l.add(EN_MATCH_DARTS);
        l.add("is running away with it!");
        return l.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ state

    private final Random rnd;
    private long lastOrdinaryAt;
    private final Map<String, Integer> missedFinish = new HashMap<>();
    private final Map<String, Boolean> saidThisLeg = new HashMap<>();
    private final Map<String, Integer> lastPick = new HashMap<>();

    public Quips(Random rnd) {
        this.rnd = rnd;
    }

    /** A comment after an ordinary visit (no big moment played), or null. */
    public synchronized Quip afterVisit(Scoreboard before, Scoreboard now, long nowMs) {
        int[] v = Moments.lastVisit(before, now);
        if (v == null || now.cricket) return null;
        Scoreboard.Player a = before.players.get(v[0]), b = now.players.get(v[0]);
        int visit = v[1];

        // On a finish and still not out: count, then tease on the second miss in a row.
        if (onFinish(a.score) && b.score > 0 && b.legs == a.legs) {
            int n = missedFinish.merge(b.name, 1, Integer::sum);
            if (n >= 2) {
                missedFinish.put(b.name, 0);
                return pick("double", EN_DOUBLE_TROUBLE, SK_DOUBLE_TROUBLE);
            }
        }
        // Running away with it: 150+ ahead after this visit (once per leg).
        for (Scoreboard.Player other : now.players) {
            if (other != b && b.score + 150 <= other.score && once("run:" + b.name)) {
                return lang(b.name + " is running away with it!", b.name + " uteká súperovi!");
            }
        }
        // A long leg (once per leg).
        if (b.darts >= 30 && once("long")) return pick("long", EN_LONG_LEG, SK_LONG_LEG);

        // Ordinary visit: sometimes, never too often.
        if (nowMs - lastOrdinaryAt < cooldownMs || rnd.nextDouble() >= chance) return null;
        Quip q = null;
        if (visit >= 10 && visit <= 19) q = pick("low", EN_LOW, SK_LOW);
        else if (visit >= 20 && visit <= 39) q = pick("mid", EN_MID, SK_MID);
        else if (visit >= 40 && visit <= 59) q = pick("good", EN_GOOD, SK_GOOD);
        else if (visit >= 60 && visit <= 99) q = pick("great", EN_GREAT, SK_GREAT);
        if (q != null) lastOrdinaryAt = nowMs;
        return q;
    }

    /** After a leg is won: "deciding leg", or sometimes "new leg". */
    public synchronized Quip afterLeg(Scoreboard now) {
        saidThisLeg.clear();
        missedFinish.clear();
        if (now.legsToWin > 1 && !now.players.isEmpty()) {
            boolean deciding = true;
            for (Scoreboard.Player p : now.players) deciding &= p.legs == now.legsToWin - 1;
            if (deciding) return lang(EN_DECIDING, SK_DECIDING);
        }
        return rnd.nextBoolean() ? pick("newleg", EN_NEW_LEG, SK_NEW_LEG) : null;
    }

    /** A player steps up able to win the match with this visit. */
    public synchronized Quip matchDarts(Scoreboard now, Scoreboard.Player p) {
        if (now.legsToWin > 0 && p.legs == now.legsToWin - 1 && once("match:" + p.name)) {
            return lang(EN_MATCH_DARTS, SK_MATCH_DARTS);
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Finishable with one double: 2-40 even, or the bull (50). */
    static boolean onFinish(int score) {
        return (score >= 2 && score <= 40 && score % 2 == 0) || score == 50;
    }

    private boolean once(String key) {
        return saidThisLeg.put(key, true) == null;
    }

    private boolean slovakNow() {
        return "sk".equals(language) || ("mix".equals(language) && rnd.nextBoolean());
    }

    private Quip lang(String en, String sk) {
        boolean slovak = slovakNow();
        return new Quip(slovak ? sk : en, slovak);
    }

    private Quip pick(String key, String[] en, String[] sk) {
        boolean slovak = slovakNow();
        String[] list = slovak ? sk : en;
        String k = key + (slovak ? ":sk" : ":en");
        int i = rnd.nextInt(list.length);
        Integer prev = lastPick.get(k);
        if (prev != null && prev == i && list.length > 1) i = (i + 1) % list.length;
        lastPick.put(k, i);
        return new Quip(list[i], slovak);
    }
}
