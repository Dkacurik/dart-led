package sk.kacurik.dartled;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sk.kacurik.dartled.core.VoiceScript;

/** Plays the recorded voice clips in assets/voice/ one after another. */
final class VoicePack {

    private final Context ctx;
    private final Set<String> available = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private MediaPlayer current;

    VoicePack(Context c) {
        ctx = c.getApplicationContext();
        try {
            String[] files = ctx.getAssets().list("voice");
            if (files != null) {
                for (String f : files) if (f.endsWith(".mp3")) available.add(f.substring(0, f.length() - 4));
            }
        } catch (Exception ignored) { }
    }

    boolean isAvailable() {
        return !available.isEmpty();
    }

    /** @return false if some piece of the line isn't recorded (use the tablet's voice instead). */
    boolean play(String text, boolean interrupt) {
        List<String> keys = VoiceScript.clipsFor(text, available);
        if (keys == null) return false;
        main.post(() -> {
            if (interrupt) stopNow();
            queue.addAll(keys);
            if (current == null) next();
        });
        return true;
    }

    void stop() {
        main.post(this::stopNow);
    }

    private void stopNow() {
        queue.clear();
        if (current != null) {
            try { current.stop(); } catch (Exception ignored) { }
            current.release();
            current = null;
        }
    }

    private void next() {
        String key = queue.poll();
        if (key == null) {
            current = null;
            return;
        }
        try (AssetFileDescriptor afd = ctx.getAssets().openFd("voice/" + key + ".mp3")) {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setOnCompletionListener(p -> {
                p.release();
                if (current == p) current = null;
                next();
            });
            mp.prepare();
            current = mp;
            mp.start();
        } catch (Exception e) {
            current = null;
            next();
        }
    }
}
