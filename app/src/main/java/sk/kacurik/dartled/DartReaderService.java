package sk.kacurik.dartled;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the texts shown by DartCounter (names, scores, legs) using Android's accessibility API.
 * It only looks at DartCounter's windows and never types or taps anything.
 */
public final class DartReaderService extends AccessibilityService {

    public static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable readNow = this::readScreen;
    private boolean scheduled;

    @Override
    protected void onServiceConnected() {
        running = true;
        DartApp.get().link().connectNow();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (!isDartCounter(pkg)) return;
        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(readNow, 250); // wait for the screen to settle
        }
    }

    private void readScreen() {
        scheduled = false;
        AccessibilityNodeInfo root = findDartCounterRoot();
        if (root == null) return;
        List<String> texts = new ArrayList<>();
        collect(root, texts, 0);
        DartApp.get().onScreenTexts(String.valueOf(root.getPackageName()), texts);
    }

    private AccessibilityNodeInfo findDartCounterRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && isDartCounter(root.getPackageName())) return root;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && isDartCounter(r.getPackageName())) return r;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 80 || out.size() > 600) return;
        if (!node.isVisibleToUser()) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) out.add(t.toString());
        if (d != null && d.length() > 0 && (t == null || !d.toString().equals(t.toString()))) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out, depth + 1);
        }
    }

    private static boolean isDartCounter(CharSequence pkg) {
        return pkg != null && pkg.toString().toLowerCase(Locale.ROOT).contains("dartcounter");
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
