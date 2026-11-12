package sk.kacurik.dartled;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sk.kacurik.dartled.core.Frame;
import sk.kacurik.dartled.core.ScreenParser;

@SuppressLint({"MissingPermission", "SetTextI18n"})
public final class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView readerStatus, displayStatus, boardText, debugText;
    private Button sizeButton;
    private ImageView preview;
    private Frame shownFrame;
    private boolean scanning;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DartApp app = DartApp.get();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);

        TextView title = text(col, "Dart LED", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text(col, "Shows DartCounter scores on your iPixel LED display.", 15);

        // Step 1 – reading DartCounter
        header(col, "1. Read scores from DartCounter");
        readerStatus = text(col, "", 15);
        button(col, "Open accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        text(col, "Find \"Dart LED\" (under Installed apps) and switch it on. If the switch is greyed out: "
                + "Settings › Apps › Dart LED › ⋮ (top right) › Allow restricted settings, then try again.", 13);

        // Step 2 – display
        header(col, "2. Connect the LED display");
        displayStatus = text(col, "", 15);
        text(col, "Close the iPixel Color app first – the display accepts only one connection.", 13);
        button(col, "Find display", v -> findDisplay());
        sizeButton = button(col, "", v -> {
            String cur = app.sizeSetting();
            int i = 0;
            for (int k = 0; k < DartApp.SIZES.length; k++) if (DartApp.SIZES[k].equals(cur)) i = k;
            app.setSizeSetting(DartApp.SIZES[(i + 1) % DartApp.SIZES.length]);
            refresh();
        });
        text(col, "Brightness", 15);
        SeekBar bright = new SeekBar(this);
        bright.setMin(1);
        bright.setMax(100);
        bright.setProgress(app.brightness());
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { app.setBrightness(s.getProgress()); }
        });
        col.addView(bright);

        // Step 3 – what's on the display
        header(col, "3. On the display now");
        preview = new ImageView(this);
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        col.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        boardText = text(col, "", 15);
        button(col, "Send test score", v -> app.showDemo());

        // Match moments
        header(col, "4. Match moments");
        text(col, "Short animations for 180, ton+, bust, no score, game shot – and jokes for 26, 41, 45, 67, 69 and visits under 10.", 13);
        Button momentsBtn = button(col, "", null);
        momentsBtn.setText(app.momentsOn() ? "Match moments: ON" : "Match moments: OFF");
        momentsBtn.setOnClickListener(v -> {
            app.setMomentsOn(!app.momentsOn());
            momentsBtn.setText(app.momentsOn() ? "Match moments: ON" : "Match moments: OFF");
        });
        LinearLayout tests = new LinearLayout(this);
        tests.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(tests);
        String[][] kinds = {{"180", "180"}, {"Ton+", "ton"}, {"26", "26"}, {"Bust", "bust"}, {"No score", "noscore"}, {"Game shot", "game"}};
        String[][] jokeKinds = {{"45", "45"}, {"41", "41"}, {"69", "69"}, {"67", "67"}, {"Under 10", "aim"}};
        for (String[] k : kinds) {
            Button b2 = new Button(this);
            b2.setText(k[0]);
            b2.setAllCaps(false);
            b2.setOnClickListener(v -> app.testMoment(k[1]));
            tests.addView(b2);
        }
        LinearLayout jokes = new LinearLayout(this);
        jokes.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(jokes);
        for (String[] k : jokeKinds) {
            Button b3 = new Button(this);
            b3.setText(k[0]);
            b3.setAllCaps(false);
            b3.setOnClickListener(v -> app.testMoment(k[1]));
            jokes.addView(b3);
        }

        // Idle clock
        header(col, "5. Idle clock");
        text(col, "When no match is on screen for 20 seconds, the display shows a clock, "
                + "and every 30 seconds a dart flies at a little dartboard.", 13);
        Button idleBtn = button(col, "", null);
        idleBtn.setText(app.idleOn() ? "Idle clock: ON" : "Idle clock: OFF");
        idleBtn.setOnClickListener(v -> {
            app.setIdleOn(!app.idleOn());
            idleBtn.setText(app.idleOn() ? "Idle clock: ON" : "Idle clock: OFF");
        });
        button(col, "Show clock now", v -> app.showIdleNow());

        // Troubleshooting
        header(col, "If scores don't appear");
        text(col, "Start a match in DartCounter, then come back here. Below is what Dart LED "
                + "can see on DartCounter's screen. Copy it and send it to whoever set this up.", 13);
        button(col, "Copy what Dart LED sees", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Dart LED", debugText.getText()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        debugText = text(col, "", 11);
        debugText.setTypeface(Typeface.MONOSPACE);
        debugText.setTextIsSelectable(true);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresher); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresher); }

    private void refresh() {
        DartApp app = DartApp.get();
        boolean on = DartReaderService.running || isReaderEnabled();
        readerStatus.setText(on ? "✅ Reading is ON" : "❌ Reading is OFF");

        LedLink link = app.link();
        String name = app.displayName();
        if (scanning) {
            displayStatus.setText("Searching for displays…");
        } else if (link.address() == null) {
            displayStatus.setText("❌ No display chosen");
        } else {
            displayStatus.setText((link.isReady() ? "✅ " : "⏳ ") + (name != null ? name : link.address())
                    + "\n" + link.status());
        }
        int[] s = app.size();
        String setting = app.sizeSetting();
        sizeButton.setText("Display size: " + ("auto".equals(setting) ? "auto (" + s[0] + "×" + s[1] + ")" : setting.replace("x", "\u00D7")));

        Frame f = app.frame();
        if (f != null && f != shownFrame) {
            shownFrame = f;
            int[] argb = new int[f.px.length];
            for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | f.px[i];
            Bitmap bm = Bitmap.createBitmap(argb, f.w, f.h, Bitmap.Config.ARGB_8888);
            Bitmap big = Bitmap.createScaledBitmap(bm, f.w * 12, f.h * 12, false);
            BitmapDrawable d = new BitmapDrawable(getResources(), big);
            d.setFilterBitmap(false);
            preview.setImageDrawable(d);
        }
        boardText.setText(app.board().isEmpty() ? "Waiting for a DartCounter match…" : app.board().toString());

        StringBuilder dbg = new StringBuilder();
        long ago = app.lastSeenAt() == 0 ? -1 : (System.currentTimeMillis() - app.lastSeenAt()) / 1000;
        dbg.append("app: ").append(app.lastPackage())
           .append(ago < 0 ? "  (DartCounter not seen yet)" : "  (" + ago + " s ago)").append('\n');
        dbg.append("parsed: ").append(app.board().toString().replace("\n", " | ")).append('\n');
        dbg.append("texts:\n");
        List<String> t = app.lastTexts();
        for (int i = 0; i < t.size() && i < 120; i++) dbg.append(i).append(": ").append(ScreenParser.visible(t.get(i).replace("\n", " / "))).append('\n');
        debugText.setText(dbg.toString());
    }

    private boolean isReaderEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    // ------------------------------------------------------------------ finding the display

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void findDisplay() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) { toast("This device has no Bluetooth"); return; }
        if (!adapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("Turn Bluetooth on and try again"); return; }

        Map<String, String> found = new LinkedHashMap<>();
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                String n = r.getDevice().getName();
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                if (n != null && !n.trim().isEmpty()) found.put(r.getDevice().getAddress(), n.trim());
            }
        };
        scanning = true;
        refresh();
        scanner.startScan(cb);
        handler.postDelayed(() -> {
            try { scanner.stopScan(cb); } catch (Exception ignored) { }
            scanning = false;
            showChoices(found);
        }, 6000);
    }

    private void showChoices(Map<String, String> found) {
        List<String> addrs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        // iPixel displays usually advertise a name containing "LED"; list those first.
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<String, String> e : found.entrySet()) {
                boolean led = e.getValue().toUpperCase().contains("LED");
                if ((pass == 0) == led) {
                    addrs.add(e.getKey());
                    labels.add(e.getValue() + (led ? "  ★" : "") + "\n" + e.getKey());
                }
            }
        }
        if (addrs.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No display found")
                    .setMessage("Make sure the display is switched on and the iPixel Color app is closed "
                            + "(swipe it away from recent apps), then try again.")
                    .setPositiveButton("OK", null).show();
            refresh();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose your display (★ = likely)")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    DartApp.get().chooseDisplay(addrs.get(which), found.get(addrs.get(which)));
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean all = results.length > 0;
        for (int r : results) all &= r == PackageManager.PERMISSION_GRANTED;
        if (all) findDisplay();
        else toast("Dart LED needs Bluetooth permission to talk to the display");
    }

    // ------------------------------------------------------------------ tiny UI helpers

    private TextView text(LinearLayout parent, String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t);
        return t;
    }

    private void header(LinearLayout parent, String s) {
        TextView t = text(parent, s, 19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(4));
    }

    private Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
