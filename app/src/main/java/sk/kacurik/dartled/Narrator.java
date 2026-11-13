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
    private final VoicePack pack;
    private volatile boolean usePack = true;
    private volatile boolean ready;
    private volatile boolean slovakAvailable;
    private int counter;

    public Narrator(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), this);
        pack = new VoicePack(ctx);
    }

    /** Use the recorded "Ryan" clips where possible (otherwise the tablet's own voice). */
    public void setUseRecordedVoice(boolean on) {
        usePack = on;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        tts.setLanguage(ENGLISH);
        slovakAvailable = tts.isLanguageAvailable(SLOVAK) >= TextToSpeech.LANG_AVAILABLE;
        ready = true;
    }

    public boolean isReady() {
        return ready || (usePack && pack.isAvailable());
    }

    /**
     * @param interrupt true to cut off whatever is being said (a new moment started);
     *                  false to queue after it.
     */
    public synchronized void say(String text, boolean slovak, boolean interrupt) {
        if (text == null || text.trim().isEmpty()) return;
        if (usePack && !slovak && pack.play(text, interrupt)) {
            if (interrupt && ready) tts.stop();
            return;
        }
        if (interrupt) pack.stop();
        if (!ready) return;
        tts.setLanguage(slovak && slovakAvailable ? SLOVAK : ENGLISH);
        tts.speak(text, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "dartled-" + (counter++));
    }

    public void stop() {
        pack.stop();
        if (ready) tts.stop();
    }
}
