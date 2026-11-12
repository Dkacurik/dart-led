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

    /** Set on DartCounter's end-of-match screen: who won and the final legs, e.g. 2 and 1. */
    public String winner;
    public int winnerLegs, loserLegs;

    public boolean isEmpty() {
        return players.isEmpty() && winner == null;
    }

    /** Identity used to skip re-sending an unchanged picture. */
    public String key() {
        StringBuilder sb = new StringBuilder();
        if (winner != null) sb.append("W:").append(winner).append(winnerLegs).append('-').append(loserLegs).append(';');
        for (Player p : players) sb.append(p.key()).append(';');
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
        return sb.toString().trim();
    }

    public static Scoreboard demo() {
        Scoreboard s = new Scoreboard();
        s.players.add(new Player("Dávid", 131, 0, "T20 T13 D16", true));
        s.players.add(new Player("Matej", 149, 1, "T20 T19 D16", false));
        return s;
    }
}
