package sk.kacurik.dartled.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a narrator line into the recorded clips that say it, e.g.
 * "Dávid, you require 40." -> [name_david, p_you_require, n_40].
 * Returns null when a piece is missing, so the app falls back to the tablet's voice.
 */
public final class VoiceScript {

    private static final Pattern GAME_ON = Pattern.compile("^Game on! (.+) to throw first\\.$");
    private static final Pattern THROWS_FIRST = Pattern.compile("^(.+) throws first\\.$");
    private static final Pattern REQUIRE = Pattern.compile("^(.+), you require (\\d{1,3})\\.$");
    private static final Pattern FINISH = Pattern.compile("^What a finish! (\\d{1,3})!$");
    private static final Pattern LEG = Pattern.compile("^Game shot, and the leg, (.+)!$");
    private static final Pattern MATCH = Pattern.compile("^Game shot, and the match, (.+)!$");
    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,3})!?$");

    private VoiceScript() {}

    public static List<String> clipsFor(String text, Set<String> available) {
        if (text == null) return null;
        String t = text.trim();
        List<String> keys;
        Matcher m;
        if ((m = GAME_ON.matcher(t)).matches()) keys = Arrays.asList("p_game_on", name(m.group(1)), "p_to_throw_first");
        else if ((m = REQUIRE.matcher(t)).matches()) keys = Arrays.asList(name(m.group(1)), "p_you_require", "n_" + m.group(2));
        else if ((m = FINISH.matcher(t)).matches()) keys = Arrays.asList("p_what_a_finish", "n_" + m.group(1));
        else if ((m = LEG.matcher(t)).matches()) keys = Arrays.asList("p_game_shot_leg", name(m.group(1)));
        else if ((m = MATCH.matcher(t)).matches()) keys = Arrays.asList("p_game_shot_match", name(m.group(1)));
        else if ((m = NUMBER.matcher(t)).matches()) keys = Arrays.asList("n_" + Integer.parseInt(m.group(1)));
        else if (t.equals("One hundred and eighty!")) keys = Arrays.asList("p_one_eighty");
        else if (t.equals("The big fish! One hundred and seventy!")) keys = Arrays.asList("p_big_fish");
        else if (t.equals("Bust!")) keys = Arrays.asList("p_bust");
        else if (t.equals("No score.")) keys = Arrays.asList("p_no_score");
        else if (t.equals("Six... seven!")) keys = Arrays.asList("p_six_seven_intro");
        else if ((m = THROWS_FIRST.matcher(t)).matches()) keys = Arrays.asList(name(m.group(1)), "p_throws_first");
        else keys = Arrays.asList("j_" + slug(t));
        for (String k : keys) if (!available.contains(k)) return null;
        return new ArrayList<>(keys);
    }

    static String name(String n) {
        return "name_" + slug(n);
    }

    /** Same rule as the generator script: "Dávid" -> "david", "Wide left!" -> "wide_left". */
    public static String slug(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9]+", "_");
        return t.replaceAll("^_+|_+$", "");
    }
}
