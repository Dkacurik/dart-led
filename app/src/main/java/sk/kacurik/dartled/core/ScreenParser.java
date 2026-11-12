package sk.kacurik.dartled.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the list of texts visible on the DartCounter match screen into a Scoreboard.
 *
 * Each player card reads, in order:
 *   name, remaining score, legs won, [checkout hint], "3-dart avg.", avg, "Last score", n, "Darts thrown", n
 * and somewhere on screen there is "DAVID'S TURN TO THROW!".
 */
public final class ScreenParser {

    private static final Pattern INT = Pattern.compile("^\\d{1,4}$");
    private static final Pattern CHECKOUT_PART = Pattern.compile("^(?:[TDS]?\\d{1,2}|BULL|BULLSEYE|25|50)$", Pattern.CASE_INSENSITIVE);

    private ScreenParser() {}

    /** Split raw node texts into clean tokens (one per line). */
    public static List<String> tokens(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            if (r == null) continue;
            for (String line : r.split("[\\r\\n]+")) {
                String t = line.trim().replace('\u00A0', ' ');
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    public static Scoreboard parse(List<String> raw) {
        List<String> t = tokens(raw);
        Scoreboard sb = new Scoreboard();

        List<Integer> avgIdx = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (isAvgLabel(t.get(i))) avgIdx.add(i);
        }
        if (avgIdx.isEmpty()) return parseWinner(t);

        String turnName = findTurnName(t);

        int start = 0;
        for (int p = 0; p < avgIdx.size(); p++) {
            int end = avgIdx.get(p); // exclusive
            if (p > 0) start = skipStats(t, avgIdx.get(p - 1), end);

            // The remaining score is the first whole number that directly follows a player's
            // name. Numbers that follow a stat label ("Best leg 34", "Darts thrown 3") are skipped.
            int scoreAt = -1;
            String name = null;
            for (int i = start; i < end && scoreAt < 0; i++) {
                if (!isInt(t.get(i))) continue;
                for (int j = i - 1; j >= start; j--) {
                    String prev = t.get(j);
                    if (isStatLabel(plain(prev)) || isInt(prev) || prev.matches("^[\\d.,%/\\-]+$")) break;
                    if (isNameLike(prev)) { scoreAt = i; name = prev; break; }
                }
            }
            if (scoreAt < 0) continue;

            int score = Integer.parseInt(t.get(scoreAt));
            if (score > 1001) continue;

            int legs = 0;
            int after = scoreAt + 1;
            if (after < end && isInt(t.get(after)) && t.get(after).length() <= 2) {
                legs = Integer.parseInt(t.get(after));
                after++;
            }

            StringBuilder co = new StringBuilder();
            for (int i = after; i < end; i++) {
                String s = t.get(i);
                for (String part : s.split("\\s+")) {
                    if (isDart(part)) {
                        if (co.length() > 0) co.append(' ');
                        co.append(part.toUpperCase(Locale.ROOT)); // "T20", "D16", "BULL", or a single like "16"
                    }
                }
            }

            Scoreboard.Player pl = new Scoreboard.Player(name, score, legs, co.toString(), false);
            // Stats under this card: "Last score" x, "Darts thrown" y (up to the next card).
            int statsEnd = p + 1 < avgIdx.size() ? avgIdx.get(p + 1) : t.size();
            for (int i = end + 1; i + 1 < statsEnd + 1 && i + 1 < t.size(); i++) {
                String label = plain(t.get(i));
                String val = t.get(i + 1);
                if (label.startsWith("LAST SCORE") && pl.lastScore < 0) {
                    if (isInt(val)) pl.lastScore = Integer.parseInt(val);
                    else if (plain(val).contains("BUST")) { pl.lastScore = 0; pl.lastBust = true; }
                } else if (label.startsWith("DARTS THROWN") && pl.darts < 0 && isInt(val)) {
                    pl.darts = Integer.parseInt(val);
                }
                if (pl.lastScore >= 0 && pl.darts >= 0) break;
            }
            sb.players.add(pl);
        }

        // Mark whose turn it is.
        if (turnName != null && !sb.players.isEmpty()) {
            String want = plain(turnName);
            List<Scoreboard.Player> marked = new ArrayList<>();
            boolean any = false;
            for (Scoreboard.Player pl : sb.players) {
                String have = plain(pl.name);
                boolean active = !any && !have.isEmpty() && (have.startsWith(want) || want.startsWith(have));
                any |= active;
                Scoreboard.Player np = new Scoreboard.Player(pl.name, pl.score, pl.legs, pl.checkout, active);
                np.lastScore = pl.lastScore;
                np.darts = pl.darts;
                np.lastBust = pl.lastBust;
                marked.add(np);
            }
            sb.players.clear();
            sb.players.addAll(marked);
        }
        return sb;
    }

    /**
     * DartCounter's end-of-match "STATISTICS" screen: player names and the legs each won
     * (e.g. "Matej", "2", "1", "Dávid"), followed by stats such as "3-dart average".
     */
    static Scoreboard parseWinner(List<String> t) {
        Scoreboard sb = new Scoreboard();
        int start = -1;
        for (int i = 0; i < t.size(); i++) {
            if (plain(t.get(i)).equals("STATISTICS")) { start = i + 1; break; }
        }
        if (start < 0) return sb;
        List<String> names = new ArrayList<>();
        List<Integer> legs = new ArrayList<>();
        for (int i = start; i < t.size(); i++) {
            String s = t.get(i);
            String p = plain(s);
            if (p.startsWith("3-DART") || p.startsWith("3 DART") || p.startsWith("FIRST 9")
                    || p.startsWith("CHECKOUT") || p.startsWith("REMATCH")) break;
            if (isInt(s) && s.length() <= 2) {
                legs.add(Integer.parseInt(s));
            } else if (isNameLike(s) && !isHeaderWord(p)
                    && (names.isEmpty() || !names.get(names.size() - 1).equals(s))) {
                names.add(s);
            }
        }
        if (names.size() < 2 || legs.size() < 2) return sb;
        int a = legs.get(0), b = legs.get(1);
        if (a == b) return sb;
        sb.winner = a > b ? names.get(0) : names.get(1);
        sb.winnerLegs = Math.max(a, b);
        sb.loserLegs = Math.min(a, b);
        return sb;
    }

    private static boolean isHeaderWord(String p) {
        return p.startsWith("STRAIGHT") || p.startsWith("DOUBLE") || p.startsWith("MASTER")
                || p.startsWith("SINGLE") || p.equals("CLOSE") || p.equals("STATISTICS")
                || p.startsWith("VIEW DETAILS") || p.contains(" IN") || p.contains(" OUT");
    }

    /** After "3-dart avg." of the previous card, skip its value, "Last score" x, "Darts thrown" y. */
    private static int skipStats(List<String> t, int prevAvg, int end) {
        for (int i = prevAvg + 1; i < end; i++) {
            if (plain(t.get(i)).startsWith("DARTS THROWN")) {
                int j = i + 1;
                if (j < end && (isInt(t.get(j)) || t.get(j).equals("-"))) j++;
                return j;
            }
        }
        // No "Darts thrown" label: skip number-ish stat tokens and known labels.
        int i = prevAvg + 1;
        while (i < end && isStatToken(t.get(i))) i++;
        return i;
    }

    private static boolean isStatToken(String s) {
        String p = plain(s);
        return s.matches("^[\\d.,\\-]+$") || p.startsWith("LAST SCORE") || p.startsWith("DARTS THROWN");
    }

    private static String findTurnName(List<String> t) {
        for (String s : t) {
            String p = plain(s).replace('\u2019', '\'');
            int k = p.indexOf("'S TURN");
            if (k <= 0) k = p.indexOf("S TURN");
            if (k > 0 && p.contains("TURN")) return p.substring(0, k).trim();
        }
        return null;
    }

    /** One dart of a checkout: T1-T20, D1-D20, S1-S20, 1-20, 25, 50, BULL. */
    static boolean isDart(String part) {
        if (!CHECKOUT_PART.matcher(part).matches()) return false;
        String u = part.toUpperCase(Locale.ROOT);
        if (u.startsWith("BULL")) return true;
        String digits = u.replaceAll("^[TDS]", "");
        int v = Integer.parseInt(digits);
        if (u.equals(digits)) return (v >= 1 && v <= 20) || v == 25 || v == 50;
        return v >= 1 && v <= 20 || (v == 25 && !u.startsWith("T"));
    }

    static boolean isAvgLabel(String s) {
        String p = plain(s);
        return p.startsWith("3-DART AVG") || p.startsWith("3 DART AVG");
    }

    static boolean isInt(String s) {
        return INT.matcher(s).matches();
    }

    /** Labels of the per-player stats under the score: "Last score", "Best leg", "3-dart avg."... */
    static boolean isStatLabel(String p) {
        return STAT_WORDS.matcher(p).find() || p.startsWith("FIRST 9");
    }

    private static final Pattern STAT_WORDS = Pattern.compile(
            "\\b(AVG|AVERAGE|SCORE|THROWN|BEST|WORST|CHECKOUTS?|HIGHEST|LEGS?|DARTS|FINISH|RATE|TONS|180S?)\\b");

    private static boolean isNameLike(String s) {
        if (s.length() > 24 || isInt(s)) return false;
        if (!s.matches(".*\\p{L}.*")) return false;
        String p = plain(s);
        if (isStatLabel(p)) return false;
        if (p.contains("TURN") || p.startsWith("FIRST TO") || p.startsWith("BEST OF")
                || p.contains("LEGS") || p.contains("SETS") || p.startsWith("LAST SCORE")
                || p.startsWith("DARTS THROWN") || p.startsWith("SINGLE") || p.startsWith("DOUBLE")
                || p.startsWith("TREBLE") || p.startsWith("BULL") || p.startsWith("OUTER")
                || p.equals("MISS") || p.startsWith("#")) return false;
        for (String part : s.split("\\s+")) {
            if (!CHECKOUT_PART.matcher(part).matches()) return true;
        }
        return false; // looks like a checkout hint, not a name
    }

    /** Uppercase, accents removed: "Dávid" -> "DAVID". */
    public static String plain(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toUpperCase(Locale.ROOT).trim();
    }
}
