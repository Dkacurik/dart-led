package sk.kacurik.dartcam.core;

/** One dart result. number: 1-20, 25 for bull, 0 for miss. multiplier: 1-3 (bull: 1 = 25, 2 = 50). */
public final class Hit {
    public final int number;
    public final int multiplier;

    private Hit(int number, int multiplier) {
        this.number = number;
        this.multiplier = multiplier;
    }

    public static Hit of(int number, int multiplier) {
        if (number == 0) return miss();
        if (number == 25) {
            if (multiplier < 1 || multiplier > 2) throw new IllegalArgumentException("bull is 25 or 50");
        } else if (number < 1 || number > 20 || multiplier < 1 || multiplier > 3) {
            throw new IllegalArgumentException("bad hit " + number + "x" + multiplier);
        }
        return new Hit(number, multiplier);
    }

    public static Hit miss() { return new Hit(0, 0); }
    public static Hit outerBull() { return new Hit(25, 1); }
    public static Hit bull() { return new Hit(25, 2); }

    public int points() { return number * multiplier; }
    public boolean isDouble() { return multiplier == 2; }

    public String label() {
        if (number == 0) return "MISS";
        if (number == 25) return multiplier == 2 ? "BULL" : "25";
        return (multiplier == 3 ? "T" : multiplier == 2 ? "D" : "S") + number;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Hit && ((Hit) o).number == number && ((Hit) o).multiplier == multiplier;
    }
    @Override public int hashCode() { return number * 4 + multiplier; }
    @Override public String toString() { return label(); }
}
