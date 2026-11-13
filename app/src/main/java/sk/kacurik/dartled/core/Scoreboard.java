package sk.kacurik.dartled.core;

import java.util.ArrayList;
import java.util.List;

/** What we read from DartCounter: one entry per player. */
public final class Scoreboard {

    public static final class Player {
        public final String name;
        public final int score;
        public final int legs;
        public final String checkout; // e.g. "T20 T13 D16", may be empty
        public final boolean active;
        /** Score of the player's last visit (-1 if unknown) and darts thrown this leg (-1 if unknown). */
        public int lastScore = -1;
        public int darts = -1;
        /** True if the last visit was shown as a bust. */
        public boolean lastBust;

        public Player(String name, int score, int legs, String checkout, boolean active) {
            this.name = name;
            this.score = score;
            this.legs = legs;
            this.checkout = checkout == null ? "" : checkout;
            this.active = active;
        }

        String key() {
            return name + "|" + score + "|" + legs + "|" + checkout + "|" + active + "|" + darts + "|" + lastScore;
        }
    }

    public final List<Player> players = new ArrayList<>();

    /** Legs needed to win the match ("FIRST TO 2 LEGS" -> 2, "BEST OF 3 LEGS" -> 2), 0 if unknown. */
    public int legsToWin;

    /** Set on DartCounter's end-of-match screen: who won and the final legs, e.g. 2 and 1. */
    public String winner;

    /** Cricket: raw mark icons per player (0 = first card, 1 = second) for 20,19,18,17,16,15,BULL. */
    public boolean cricket;

    /** Bull-up ("WHO THROWS FIRST?") screen: names, distance from the bull in mm (null = not thrown yet). */
    public boolean bullUp;
    public final List<String> bullNames = new ArrayList<>();
    public final List<Double> bullDist = new ArrayList<>();
    public int bullActive = -1;

    /** Darts of the visit currently being entered, e.g. ["T20", "S20"]. */
    public final List<String> entered = new ArrayList<>();
    public final String[][] markIcons = new String[2][7];
    public int winnerLegs, loserLegs;

    public boolean isEmpty() {
        return players.isEmpty() && winner == null && !bullUp;
    }

    /** Identity used to skip re-sending an unchanged picture. */
    public String key() {
        StringBuilder sb = new StringBuilder();
        if (winner != null) sb.append("W:").append(winner).append(winnerLegs).append('-').append(loserLegs).append(';');
        for (Player p : players) sb.append(p.key()).append(';');
        if (cricket) for (String[] row : markIcons) sb.append(java.util.Arrays.toString(row));
        sb.append("E:").append(entered);
        if (bullUp) sb.append("B:").append(bullNames).append(bullDist).append(bullActive);
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (winner != null) sb.append("Winner: ").append(winner).append("  ").append(winnerLegs).append('-').append(loserLegs).append('\n');
        for (Player p : players) {
            sb.append(p.active ? "> " : "  ")
              .append(p.name).append("  ").append(p.score)
              .append("  legs ").append(p.legs);
            if (!p.checkout.isEmpty()) sb.append("  (").append(p.checkout).append(')');
            sb.append('\n');
        }
        if (bullUp) {
            sb.append("Bull-up:");
            for (int i = 0; i < bullNames.size(); i++) {
                Double d = bullDist.get(i);
                sb.append("\n  ").append(i == bullActive ? "> " : "  ").append(bullNames.get(i))
                  .append("  ").append(d == null ? "-" : d + " mm");
            }
        }
        if (!entered.isEmpty()) sb.append("\nentering: ").append(String.join(" ", entered));
        if (cricket) {
            String[] names = {"20", "19", "18", "17", "16", "15", "B"};
            sb.append("\ncricket marks:");
            for (int k = 0; k < 7; k++) {
                sb.append("\n  ").append(names[k]).append(": ")
                  .append(markIcons[0][k] == null ? "-" : ScreenParser.visible(markIcons[0][k])).append(" | ")
                  .append(markIcons[1][k] == null ? "-" : ScreenParser.visible(markIcons[1][k]));
            }
        }
        return sb.toString().trim();
    }

    public static Scoreboard demo() {
        Scoreboard s = new Scoreboard();
        s.players.add(new Player("Dávid", 131, 0, "T20 T13 D16", true));
        s.players.add(new Player("Matej", 149, 1, "T20 T19 D16", false));
        return s;
    }
}
