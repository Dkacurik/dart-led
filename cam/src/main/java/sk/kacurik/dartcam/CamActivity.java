package sk.kacurik.dartcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sk.kacurik.dartcam.core.Board;
import sk.kacurik.dartcam.core.DartDetector;
import sk.kacurik.dartcam.core.Homography;

/**
 * DartCam: the phone at the board. Watches the board with the camera, recognises each dart
 * and sends it to Dart LED on the tablet, which enters it into DartCounter.
 */
@SuppressLint("SetTextI18n")
public final class CamActivity extends ComponentActivity implements TabletLink.Listener {

    /** Long side of the picture the detector works on (pixels). */
    private static final int ANALYSIS_LONG_SIDE = 640;

    private SharedPreferences prefs;
    private BoardView boardView;
    private TextView linkText, gameText, hintText;
    private LinearLayout playBar, calBar;
    private SeekBar zoomBar;
    private TabletLink link;
    private Camera camera;
    private final ExecutorService analysisThread = Executors.newSingleThreadExecutor();

    // Touched only on the analysis thread.
    private DartDetector detector;
    private volatile int frameW, frameH;

    // Set on the UI thread, picked up by the analysis thread.
    private volatile float[] pendingCal;      // 8 normalised values (x0,y0..x3,y3), or null
    private volatile boolean calChanged;
    private volatile boolean paused;
    private volatile Runnable detectorTask;   // small actions on the detector (undo, miss, board clear)

    private final List<String> visit = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("dartcam", MODE_PRIVATE);
        buildUi();
        link = new TabletLink(this, this);
        link.setFixedHost(prefs.getString("host", null));
        loadCalibration();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
    }

    @Override
    protected void onStart() {
        super.onStart();
        link.start();
    }

    @Override
    protected void onStop() {
        link.stop();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else hintText.setText("DartCam needs the camera to see the board. Allow it in Settings › Apps › DartCam › Permissions.");
    }

    // ---------------- UI ----------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        boardView = new BoardView(this);
        boardView.setCalibrationListener(this::onCalibrationPoints);
        root.addView(boardView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(36), dp(12), dp(8));
        top.setBackgroundColor(Color.argb(150, 0, 0, 0));
        linkText = label(top, "", 13);
        linkText.setOnClickListener(v -> askTabletAddress());
        gameText = label(top, "", 22);
        gameText.setTypeface(gameText.getTypeface(), android.graphics.Typeface.BOLD);
        hintText = label(top, "", 14);
        hintText.setTextColor(Color.YELLOW);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(24));
        bottom.setBackgroundColor(Color.argb(150, 0, 0, 0));

        playBar = row(bottom);
        button(playBar, "Undo", v -> undo());
        button(playBar, "Miss", v -> manualMiss());
        button(playBar, "Board clear ▶", v -> boardClear());
        button(playBar, "Calibrate", v -> startCalibration());

        calBar = new LinearLayout(this);
        calBar.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(calBar);
        TextView zl = label(calBar, "Zoom (set this first, then place the 4 points)", 13);
        zl.setPadding(dp(4), 0, 0, 0);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setProgress(prefs.getInt("zoom", 0));
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (camera != null) camera.getCameraControl().setLinearZoom(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { prefs.edit().putInt("zoom", s.getProgress()).apply(); }
        });
        calBar.addView(zoomBar);
        LinearLayout cr = row(calBar);
        button(cr, "Reset points", v -> boardView.clearCalibration());
        button(cr, "Tablet address", v -> askTabletAddress());
        button(cr, "Done", v -> finishCalibration());

        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        showMode();
        linkText.setText("Looking for Dart LED on the Wi-Fi…");
    }

    private void showMode() {
        boolean cal = boardView.calibrating();
        playBar.setVisibility(cal ? View.GONE : View.VISIBLE);
        calBar.setVisibility(cal ? View.VISIBLE : View.GONE);
        updateHint();
    }

    private void updateHint() {
        if (boardView.calibrating()) {
            int n = calPointCount;
            if (n < 4) hintText.setText("Tap the OUTER edge of the double ring in the middle of segment "
                    + Board.CALIBRATION_LABELS[n] + ". (" + (n + 1) + "/4)");
            else hintText.setText("Drag the yellow circles until the green lines sit on the wires, then tap Done.");
        } else if (pendingCal == null) {
            hintText.setText("Not calibrated yet – tap Calibrate.");
        } else if (paused) {
            hintText.setText("Paused");
        } else {
            hintText.setText(visit.isEmpty() ? "Ready – throw!" : "Visit: " + String.join("  ", visit));
        }
    }

    // ---------------- calibration ----------------

    private int calPointCount;

    private void startCalibration() {
        boardView.setCalibrating(true);
        paused = true;
        List<float[]> pts = currentCalPointsInFrame();
        boardView.setCalibrationPoints(pts);
        onCalibrationPoints(pts);
        showMode();
    }

    private void onCalibrationPoints(List<float[]> pts) {
        lastCalPoints = pts;
        calPointCount = pts.size();
        if (pts.size() == 4 && frameW > 0) {
            double[][] img = new double[4][];
            for (int i = 0; i < 4; i++) img[i] = new double[]{pts.get(i)[0], pts.get(i)[1]};
            try {
                Homography b2i = Homography.fromPoints(Board.CALIBRATION_POINTS, img);
                boardView.setBoardToImage(b2i);
            } catch (Exception e) {
                boardView.setBoardToImage(null);
            }
        } else {
            boardView.setBoardToImage(null);
        }
        updateHint();
    }

    private List<float[]> currentCalPointsInFrame() {
        List<float[]> out = new ArrayList<>();
        float[] c = pendingCal;
        if (c == null || frameW == 0) return out;
        for (int i = 0; i < 4; i++) out.add(new float[]{c[i * 2] * frameW, c[i * 2 + 1] * frameH});
        return out;
    }

    private void finishCalibration() {
        if (calPointCount < 4) {
            hintText.setText("Place all 4 points first (" + calPointCount + "/4).");
            return;
        }
        // BoardView owns the dragged positions; read them back through the listener copy.
        List<float[]> pts = lastCalPoints;
        float[] norm = new float[8];
        for (int i = 0; i < 4; i++) {
            norm[i * 2] = pts.get(i)[0] / frameW;
            norm[i * 2 + 1] = pts.get(i)[1] / frameH;
        }
        StringBuilder sb = new StringBuilder();
        for (float v : norm) sb.append(String.format(Locale.ROOT, "%.5f,", v));
        prefs.edit().putString("cal", sb.toString()).apply();
        pendingCal = norm;
        calChanged = true;
        boardView.setCalibrating(false);
        paused = false;
        visit.clear();
        boardView.clearMarks();
        showMode();
    }

    /** Latest calibration points (frame pixels) as placed/dragged in the BoardView. */
    private List<float[]> lastCalPoints = new ArrayList<>();

    private void loadCalibration() {
        String s = prefs.getString("cal", null);
        if (s == null) return;
        String[] p = s.split(",");
        if (p.length < 8) return;
        float[] v = new float[8];
        try {
            for (int i = 0; i < 8; i++) v[i] = Float.parseFloat(p[i]);
        } catch (NumberFormatException e) {
            return;
        }
        pendingCal = v;
        calChanged = true;
    }

    // ---------------- camera ----------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                ResolutionSelector rs = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(rs)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(analysisThread, this::analyze);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis);
                camera.getCameraControl().setLinearZoom(prefs.getInt("zoom", 0) / 100f);
            } catch (Exception e) {
                hintText.setText("Camera error: " + e.getMessage());
            }
        }, this::runOnUiThread);
    }

    /** Runs on the analysis thread for every camera frame. */
    private void analyze(ImageProxy image) {
        Bitmap upright;
        try {
            Bitmap src = image.toBitmap();
            int rot = image.getImageInfo().getRotationDegrees();
            Matrix m = new Matrix();
            m.postRotate(rot);
            float scale = (float) ANALYSIS_LONG_SIDE / Math.max(src.getWidth(), src.getHeight());
            m.postScale(scale, scale);
            upright = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Exception e) {
            return;
        } finally {
            image.close();
        }
        int w = upright.getWidth(), h = upright.getHeight();
        if (detector == null || w != frameW || h != frameH) {
            frameW = w;
            frameH = h;
            detector = new DartDetector(w, h, null);
            calChanged = true;
        }
        if (calChanged) {
            calChanged = false;
            float[] c = pendingCal;
            if (c != null) {
                double[][] img = new double[4][];
                for (int i = 0; i < 4; i++) img[i] = new double[]{c[i * 2] * w, c[i * 2 + 1] * h};
                try {
                    Homography imgToBoard = Homography.fromPoints(img, Board.CALIBRATION_POINTS);
                    detector.setCalibration(imgToBoard);
                    Homography b2i = imgToBoard.inverse();
                    runOnUiThread(() -> { if (!boardView.calibrating()) boardView.setBoardToImage(b2i); });
                } catch (Exception ignored) { }
            }
        }
        Runnable task = detectorTask;
        if (task != null) { detectorTask = null; task.run(); }

        DartDetector.Event ev = null;
        if (!paused && detector.calibrated()) {
            int[] px = new int[w * h];
            upright.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                px[i] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
            }
            ev = detector.feed(px);
        }
        DartDetector.Event event = ev;
        runOnUiThread(() -> {
            boardView.setFrame(upright);
            if (event != null) onDetectorEvent(event);
        });
    }

    // ---------------- game flow ----------------

    private void onDetectorEvent(DartDetector.Event e) {
        if (e.type == DartDetector.Type.DART) {
            String label = e.hit.label();
            visit.add(label);
            boardView.addMark((float) e.imgX, (float) e.imgY, label);
            link.send("DART " + label);
            buzz(35);
        } else {
            // Darts pulled out: the visit is over.
            link.send("SUBMIT");
            visit.clear();
            boardView.clearMarks();
            buzz(15);
        }
        updateHint();
    }

    private void undo() {
        if (visit.isEmpty()) return;
        visit.remove(visit.size() - 1);
        boardView.removeLastMark();
        link.send("UNDO");
        detectorTask = () -> { if (detector != null) detector.forgetLastDart(); };
        updateHint();
    }

    private void manualMiss() {
        if (visit.size() >= 3) return;
        visit.add("MISS");
        link.send("DART MISS");
        detectorTask = () -> { if (detector != null) detector.addManualDart(); };
        updateHint();
    }

    /** The darts are out but the camera did not notice: finish the visit by hand. */
    private void boardClear() {
        link.send("SUBMIT");
        visit.clear();
        boardView.clearMarks();
        detectorTask = () -> { if (detector != null) detector.markEmpty(); };
        updateHint();
    }

    @SuppressWarnings("deprecation")
    private void buzz(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    // ---------------- tablet link ----------------

    @Override
    public void onStatus(String status, boolean connected) {
        linkText.setText((connected ? "● " : "○ ") + status + "   (tap to set address)");
        linkText.setTextColor(connected ? Color.rgb(120, 255, 120) : Color.rgb(255, 170, 120));
    }

    @Override
    public void onLine(String line) {
        if (line.startsWith("STATE|")) {
            // STATE|name|remaining|entered darts (comma separated)|typer status
            String[] p = line.split("\\|", -1);
            String name = p.length > 1 ? p[1] : "";
            String score = p.length > 2 ? p[2] : "";
            String typer = p.length > 4 ? p[4] : "";
            gameText.setText(name.isEmpty() ? "DartCounter: no match on screen" : name + "  " + score);
            if (typer.startsWith("off")) {
                hintText.setText("Dart LED: entering darts into DartCounter is switched off on the tablet.");
            }
        } else if (line.startsWith("ERR")) {
            hintText.setText("Tablet: " + line.substring(3).trim());
        }
    }

    private void askTabletAddress() {
        EditText in = new EditText(this);
        in.setHint("auto (leave empty)  or  192.168.1.20");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(prefs.getString("host", ""));
        new AlertDialog.Builder(this)
                .setTitle("Tablet address")
                .setMessage("Leave empty to find Dart LED automatically on the same Wi-Fi. "
                        + "If that does not work, type the address Dart LED shows on the tablet.")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String h = in.getText().toString().trim();
                    prefs.edit().putString("host", h).apply();
                    link.setFixedHost(h);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------- helpers ----------------

    private TextView label(LinearLayout parent, String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        parent.addView(t);
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
