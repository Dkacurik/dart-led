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
                String t = line.trim().replace(' ', ' ');
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
        if (avgIdx.isEmpty()) return sb;

        String turnName = findTurnName(t);

        int start = 0;
        for (int p = 0; p < avgIdx.size(); p++) {
            int end = avgIdx.get(p); // exclusive
            if (p > 0) start = skipStats(t, avgIdx.get(p - 1), end);

            int scoreAt = -1;
            for (int i = start; i < end; i++) {
                if (isInt(t.get(i))) { scoreAt = i; break; }
            }
            if (scoreAt < 0) continue;

            String name = null;
            for (int i = scoreAt - 1; i >= start; i--) {
                if (isNameLike(t.get(i))) { name = t.get(i); break; }
            }
            if (name == null) name = "P" + (sb.players.size() + 1);

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
                    if (CHECKOUT_PART.matcher(part).matches() && !isInt(part)) {
                        if (co.length() > 0) co.append(' ');
                        co.append(part.toUpperCase(Locale.ROOT));
                    } else if (isInt(part) && co.length() > 0) {
                        co.append(' ').append(part); // e.g. "T20 20 D10"
                    }
                }
            }

            sb.players.add(new Scoreboard.Player(name, score, legs, co.toString(), false));
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
                marked.add(new Scoreboard.Player(pl.name, pl.score, pl.legs, pl.checkout, active));
            }
            sb.players.clear();
            sb.players.addAll(marked);
        }
        return sb;
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
            String p = plain(s).replace('’', '\'');
            int k = p.indexOf("'S TURN");
            if (k <= 0) k = p.indexOf("S TURN");
            if (k > 0 && p.contains("TURN")) return p.substring(0, k).trim();
        }
        return null;
    }

    static boolean isAvgLabel(String s) {
        String p = plain(s);
        return p.startsWith("3-DART AVG") || p.startsWith("3 DART AVG");
    }

    static boolean isInt(String s) {
        return INT.matcher(s).matches();
    }

    private static boolean isNameLike(String s) {
        if (s.length() > 24 || isInt(s)) return false;
        if (!s.matches(".*\\p{L}.*")) return false;
        String p = plain(s);
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
