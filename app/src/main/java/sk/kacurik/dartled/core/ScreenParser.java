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
        for (String tok : t) {
            if (isMprLabel(plain(tok))) { parseCricketMarks(t, sb); break; }
        }
        parseEnteredDarts(t, avgIdx.get(avgIdx.size() - 1), sb);
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
        return p.startsWith("3-DART AVG") || p.startsWith("3 DART AVG") || isMprLabel(p);
    }

    /** Cricket cards show "Avg. MPR" (marks per round) instead of the 3-dart average. */
    static boolean isMprLabel(String p) {
        return p.startsWith("AVG. MPR") || p.startsWith("AVG MPR") || p.equals("MPR");
    }

    static final String[] CRICKET_TARGETS = {"20", "19", "18", "17", "16", "15", "BULL"};

    private static final Pattern DART_LABEL = Pattern.compile(
            "^(?:[SDT]\\d{1,2}|[SD]?BULL|[SD]25|OUTER|MISS|M|S0)$", Pattern.CASE_INSENSITIVE);

    /**
     * The darts of the visit being entered, shown as chips in DartCounter's input bar
     * (e.g. "60" "T20", "25" "S25"). They come after the player cards and the
     * "...'S TURN TO THROW!" line, and before the keypad.
     */
    static void parseEnteredDarts(List<String> t, int lastAvg, Scoreboard sb) {
        int start = lastAvg + 1;
        for (int i = 0; i < t.size(); i++) {
            if (plain(t.get(i)).contains("TURN TO THROW")) { start = Math.max(start, i + 1); break; }
        }
        for (int i = start; i < t.size(); i++) {
            String s = strip(t.get(i)).trim();
            String p = plain(s);
            if (p.equals("SINGLE") || p.equals("SUBMIT") || TARGET_BUTTON.matcher(s).matches()) break;
            if (DART_LABEL.matcher(s).matches()) sb.entered.add(p);
            if (sb.entered.size() == 3) break;
        }
    }

    /** Points for one dart label: T20 = 60, D16 = 32, S5 = 5, BULL = 50, S25 = 25, MISS = 0. */
    public static int dartValue(String label) {
        String p = plain(label);
        if (p.equals("MISS") || p.equals("M") || p.equals("S0")) return 0;
        if (p.equals("BULL") || p.equals("DBULL") || p.equals("D25")) return 50;
        if (p.equals("SBULL") || p.equals("S25") || p.equals("OUTER")) return 25;
        try {
            int n = Integer.parseInt(p.substring(1));
            switch (p.charAt(0)) {
                case 'T': return 3 * n;
                case 'D': return 2 * n;
                default: return n;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Separator between a token and its on-screen position ("x,y"). */
    public static final char POS = '\u0001';
    private static final Pattern TARGET_BUTTON = Pattern.compile("^(\\d{1,2}|BULL) \\.{1,3}$", Pattern.CASE_INSENSITIVE);

    /** Should the reader record where this text is on screen? */
    public static boolean needsPosition(String text) {
        String s = text.trim();
        return isIcon(s) || TARGET_BUTTON.matcher(s).matches();
    }

    static String strip(String s) {
        int k = s.indexOf(POS);
        return k < 0 ? s : s.substring(0, k);
    }

    static int[] position(String s) {
        int k = s.indexOf(POS);
        if (k < 0) return null;
        try {
            String[] p = s.substring(k + 1).split(",");
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cricket marks. Each target row has input buttons "20 .", "20 ..", "20 ..." and, when a
     * player has marks on it, an icon left of the buttons (first player) or right of them
     * (second player). Icons are matched to rows and sides by their position on screen.
     */
    static void parseCricketMarks(List<String> t, Scoreboard sb) {
        sb.cricket = true;
        int[] rowY = new int[7], minX = new int[7], maxX = new int[7], count = new int[7];
        java.util.Arrays.fill(minX, Integer.MAX_VALUE);
        java.util.Arrays.fill(maxX, Integer.MIN_VALUE);
        for (String tok : t) {
            String s = strip(tok).trim();
            int[] pos = position(tok);
            if (pos == null || !TARGET_BUTTON.matcher(s).matches()) continue;
            String target = plain(s.substring(0, s.indexOf(' ')));
            for (int k = 0; k < CRICKET_TARGETS.length; k++) {
                if (CRICKET_TARGETS[k].equals(target)) {
                    rowY[k] += pos[1];
                    count[k]++;
                    minX[k] = Math.min(minX[k], pos[0]);
                    maxX[k] = Math.max(maxX[k], pos[0]);
                }
            }
        }
        int rows = 0, spacing = Integer.MAX_VALUE;
        for (int k = 0; k < 7; k++) if (count[k] > 0) { rowY[k] /= count[k]; rows++; }
        for (int k = 1; k < 7; k++) {
            if (count[k] > 0 && count[k - 1] > 0) spacing = Math.min(spacing, Math.abs(rowY[k] - rowY[k - 1]));
        }
        if (rows == 0) return;
        if (spacing == Integer.MAX_VALUE) spacing = 100;
        // Horizontal gap between the S/D/T buttons; marks sit within ~2.5 gaps of them.
        int gap = 0;
        for (int k = 0; k < 7; k++) if (count[k] >= 3) { gap = (maxX[k] - minX[k]) / (count[k] - 1); break; }
        int reach = gap > 0 ? gap * 5 / 2 : Integer.MAX_VALUE;

        for (String tok : t) {
            String s = strip(tok);
            int[] pos = position(tok);
            if (pos == null || !isIcon(s.trim())) continue;
            int best = -1, bestDy = Integer.MAX_VALUE;
            for (int k = 0; k < 7; k++) {
                if (count[k] == 0) continue;
                int dy = Math.abs(pos[1] - rowY[k]);
                if (dy < bestDy) { bestDy = dy; best = k; }
            }
            if (best < 0 || bestDy > spacing / 2) continue;      // not on the board (avatar, undo...)
            if (pos[0] < minX[best] && minX[best] - pos[0] <= reach) sb.markIcons[0][best] = s.trim();
            else if (pos[0] > maxX[best] && pos[0] - maxX[best] <= reach) sb.markIcons[1][best] = s.trim();
        }
    }

    /** A token with no letters or digits, e.g. an icon-font symbol. */
    static boolean isIcon(String s) {
        return !s.isEmpty() && !s.matches(".*[\\p{L}\\p{N}].*");
    }

    /** Shows invisible / icon characters as [U+XXXX] so they can be read in the debug view. */
    public static String visible(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            boolean plainChar = (cp >= 0x21 && cp < 0x7F) || Character.isLetterOrDigit(cp);
            if (cp == POS) b.append(" @");
            else if (plainChar) b.appendCodePoint(cp);
            else if (cp == ' ') b.append(' ');
            else b.append(String.format(Locale.ROOT, "[U+%04X]", cp));
            i += Character.charCount(cp);
        }
        return b.toString();
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
