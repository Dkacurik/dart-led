package sk.kacurik.dartled;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Speaks match moments with the tablet's built-in text-to-speech voice
 * (English for calls, Slovak for Slovak jokes when a Slovak voice is installed).
 */
public final class Narrator implements TextToSpeech.OnInitListener {

    private static final Locale ENGLISH = Locale.UK;
    private static final Locale SLOVAK = new Locale("sk", "SK");

    private final TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean slovakAvailable;
    private int counter;

    public Narrator(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        tts.setLanguage(ENGLISH);
        slovakAvailable = tts.isLanguageAvailable(SLOVAK) >= TextToSpeech.LANG_AVAILABLE;
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * @param interrupt true to cut off whatever is being said (a new moment started);
     *                  false to queue after it.
     */
    public synchronized void say(String text, boolean slovak, boolean interrupt) {
        if (!ready || text == null || text.trim().isEmpty()) return;
        tts.setLanguage(slovak && slovakAvailable ? SLOVAK : ENGLISH);
        tts.speak(text, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "dartled-" + (counter++));
    }

    public void stop() {
        if (ready) tts.stop();
    }
}
