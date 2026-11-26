package sk.kacurik.dartcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
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
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import sk.kacurik.dartcam.core.AutoCalibrator;
import sk.kacurik.dartcam.core.Board;
import sk.kacurik.dartcam.core.DartDetector;
import sk.kacurik.dartcam.core.Homography;
import sk.kacurik.dartcam.core.TipRefiner;

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
    // Full-resolution camera picture (sensor orientation) of the last still frame = "before" the next dart.
    private Bitmap beforeFull;
    private int beforeRot;
    private volatile int frameW, frameH;

    // Set on the UI thread, picked up by the analysis thread.
    private volatile float[] pendingCal;      // 8 normalised values (x0,y0..x3,y3), or null
    private volatile boolean calChanged;
    private volatile boolean paused;
    private volatile Runnable detectorTask;   // small actions on the detector (undo, miss, board clear)
    private volatile boolean autoCalRequested; // run AutoCalibrator on the next camera frame
    private volatile float gravX, gravY = 9.81f; // gravity in device axes (portrait: x right, y up)

    /** Note about the last dart's zoom re-check (corrected / close to a wire), shown in the hint. */
    private String zoomNote;

    // Keeping the calibration up to date while playing (analysis thread, except keepCalibrated).
    private volatile boolean keepCalibrated = true;
    private long lastRecheckMs, lastFailRecordMs;
    private double[][] recheckCandidate;  // last found points (frame px), adopted when the next run agrees
    private static final long RECHECK_MS = 3000, RECHECK_CONFIRM_MS = 1000;

    // ---- diagnostics: what Auto saw and decided (last few runs), for "Send debug" ----
    private static final class AutoRecord {
        final Bitmap frame; final String trigger; final AutoCalibrator.Result r;
        final float gx, gy, zoom; final long time; final String outcome;
        AutoRecord(Bitmap frame, String trigger, AutoCalibrator.Result r, float gx, float gy, float zoom, String outcome) {
            this.frame = frame; this.trigger = trigger; this.r = r; this.gx = gx; this.gy = gy; this.zoom = zoom;
            this.time = System.currentTimeMillis(); this.outcome = outcome;
        }
    }
    private final ArrayDeque<AutoRecord> records = new ArrayDeque<>();
    private volatile Bitmap lastFull, lastUpright; // newest camera frame (full-res, sensor orientation) and its analysis copy
    private volatile int lastFullRot;

    private void record(Bitmap frame, String trigger, AutoCalibrator.Result r, String outcome) {
        ZoomState zs = zoomState();
        AutoRecord rec = new AutoRecord(frame, trigger, r, gravX, gravY, zs == null ? Float.NaN : zs.getZoomRatio(), outcome);
        synchronized (records) {
            records.addLast(rec);
            while (records.size() > 12) records.removeFirst();
        }
    }

    /** "Calibration updated" note and until when to show it (UI thread). */
    private String calNote;
    private long calNoteUntil;

    /** Message from the last Auto run, shown while calibrating; null = normal hints. */
    private String autoMsg;
    private boolean autoRunning;

    private SensorManager sensors;
    private final SensorEventListener gravityListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            // Light smoothing, enough for a phone standing still on a tripod.
            gravX += 0.2f * (e.values[0] - gravX);
            gravY += 0.2f * (e.values[1] - gravY);
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

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
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) {
            Sensor g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (g == null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (g != null) sensors.registerListener(gravityListener, g, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onStop() {
        link.stop();
        if (sensors != null) sensors.unregisterListener(gravityListener);
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
        boardView.setCalibrationListener(pts -> {
            // Tapping by hand after a failed Auto: back to the step-by-step hints.
            if (autoMsg != null && autoMsg.startsWith("Auto:")) autoMsg = null;
            onCalibrationPoints(pts);
        });
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
        TextView zl = label(calBar, "Zoom (Auto sets it by itself – or set it, then tap the 4 points)", 13);
        zl.setPadding(dp(4), 0, 0, 0);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setProgress(prefs.getInt("zoom", 0));
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && camera != null) camera.getCameraControl().setLinearZoom(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("zoom", s.getProgress()).remove("zoomRatio").apply();
            }
        });
        calBar.addView(zoomBar);
        CheckBox keep = new CheckBox(this);
        keep.setText("Keep calibrated automatically (follows the phone if it moves)");
        keep.setTextColor(Color.WHITE);
        keepCalibrated = prefs.getBoolean("autoKeep", true);
        keep.setChecked(keepCalibrated);
        keep.setOnCheckedChangeListener((b, on) -> {
            keepCalibrated = on;
            prefs.edit().putBoolean("autoKeep", on).apply();
        });
        calBar.addView(keep);
        LinearLayout cr = row(calBar);
        button(cr, "Auto", v -> requestAutoCalibration());
        button(cr, "Reset points", v -> { autoMsg = null; boardView.clearCalibration(); });
        button(cr, "Done", v -> finishCalibration());
        LinearLayout cr2 = row(calBar);
        button(cr2, "Tablet address", v -> askTabletAddress());
        button(cr2, "Send debug", v -> sendDebug());

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
            if (autoRunning) { hintText.setText("Looking for the board…"); return; }
            if (autoMsg != null) { hintText.setText(autoMsg); return; }
            if (n < 4) hintText.setText("Tap the OUTER edge of the double ring in the middle of segment "
                    + Board.CALIBRATION_LABELS[n] + ". (" + (n + 1) + "/4)");
            else hintText.setText("Drag the yellow circles until the green lines sit on the wires, then tap Done.");
        } else if (pendingCal == null) {
            hintText.setText("Not calibrated yet – tap Calibrate.");
        } else if (paused) {
            hintText.setText("Paused");
        } else {
            String v = visit.isEmpty() ? "Ready – throw!" : "Visit: " + String.join("  ", visit);
            if (zoomNote != null && !visit.isEmpty()) v += "\n" + zoomNote;
            if (calNote != null && System.currentTimeMillis() < calNoteUntil) v += "\n" + calNote;
            hintText.setText(v);
        }
    }

    // ---------------- calibration ----------------

    private int calPointCount;

    private void startCalibration() {
        boardView.setCalibrating(true);
        paused = true;
        autoMsg = null;
        boardView.setZoom(null);
        List<float[]> pts = currentCalPointsInFrame();
        boardView.setCalibrationPoints(pts);
        onCalibrationPoints(pts);
        showMode();
        // First time: try to find the board right away.
        if (pendingCal == null) requestAutoCalibration();
    }

    /** Find the board in the next camera frame (AutoCalibrator), then show the result for checking. */
    private void requestAutoCalibration() {
        if (autoRunning) return;
        autoRunning = true;
        autoMsg = null;
        autoRound = 0;
        autoZoomedOut = false;
        autoCalRequested = true;
        updateHint();
    }

    // Auto-zoom: rounds of "find the board, set the zoom so it fills the picture, look again".
    private volatile int autoRound;
    private boolean autoZoomedOut;
    private static final double BOARD_VIEW_MM = 225; // number ring + flights must stay in the picture
    private static final double BOARD_FILL = 0.92;

    private ZoomState zoomState() {
        return camera == null ? null : camera.getCameraInfo().getZoomState().getValue();
    }

    /** Set the zoom, then look for the board again once the picture has settled. */
    private void zoomAndRetry(float ratio, String hint) {
        camera.getCameraControl().setZoomRatio(ratio);
        autoMsg = null;
        hintText.setText(hint);
        hintText.postDelayed(() -> { if (autoRunning && boardView.calibrating()) autoCalRequested = true; }, 900);
    }

    /** Runs on the analysis thread with the upright frame. */
    private void runAutoCalibration(Bitmap frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[] px = new int[w * h];
        frame.getPixels(px, 0, w, 0, 0, w, h);
        // The frame is upright in the phone's portrait axes (x right, y down); gravity is measured
        // with y up, and the sensor reports the push against gravity, i.e. "up".
        AutoCalibrator.Result r = AutoCalibrator.find(px, w, h, gravX, -gravY);
        record(frame, "auto-round" + autoRound, r, "");
        runOnUiThread(() -> onAutoCalibration(r));
    }

    private void onAutoCalibration(AutoCalibrator.Result r) {
        if (!boardView.calibrating()) { autoRunning = false; return; }
        ZoomState zs = zoomState();
        if (zs != null && r.ok() && autoRound < 3) {
            double f = AutoCalibrator.zoomFactor(r.boardToImg, frameW, frameH, BOARD_VIEW_MM, BOARD_FILL);
            float cur = zs.getZoomRatio();
            float target = (float) Math.max(zs.getMinZoomRatio(), Math.min(zs.getMaxZoomRatio(), cur * f));
            if (Math.abs(target / cur - 1) > 0.06) {
                autoRound++;
                zoomAndRetry(target, String.format(Locale.ROOT, "Zooming to the board (%.1f× → %.1f×)…", cur, target));
                return;
            }
        }
        if (zs != null && !r.ok() && !autoZoomedOut && zs.getZoomRatio() > zs.getMinZoomRatio() * 1.05f) {
            // Maybe zoomed in too far (board cut off): look again with the widest view.
            autoZoomedOut = true;
            zoomAndRetry(zs.getMinZoomRatio(), "Board not found – zooming out and looking again…");
            return;
        }
        autoRunning = false;
        if (zs != null) {
            // Keep exactly this zoom for the next start; move the slider without touching the camera.
            prefs.edit().putFloat("zoomRatio", zs.getZoomRatio()).putInt("zoom", Math.round(zs.getLinearZoom() * 100)).apply();
            zoomBar.setProgress(Math.round(zs.getLinearZoom() * 100));
        }
        if (r.ok()) {
            List<float[]> pts = new ArrayList<>();
            for (double[] p : r.calPoints) pts.add(new float[]{(float) p[0], (float) p[1]});
            boardView.setCalibrationPoints(pts);
            autoMsg = (zs == null ? "Board found. " : String.format(Locale.ROOT, "Board found, zoom %.1f×. ", zs.getZoomRatio()))
                    + "Check that 20 is at the top and the green lines sit on the wires "
                    + "(drag the yellow circles if not), then tap Done.";
            onCalibrationPoints(pts);
            buzz(25);
        } else {
            autoMsg = "Auto: " + r.failReason
                    + (r.inliers > 0 ? String.format(Locale.ROOT, " [%d points, rms %.1f px, pattern %.0f%%]", r.inliers, r.rmsPx, r.patternMatch * 100) : "")
                    + ". Tap Auto again, or tap the 4 points by hand. (Send debug helps me fix it.)";
            updateHint();
        }
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
        saveCalibration(lastCalPoints);
        boardView.setCalibrating(false);
        paused = false;
        autoMsg = null;
        autoRunning = false; // a zoom round still waiting ends here
        visit.clear();
        boardView.clearMarks();
        showMode();
    }

    /** Store 4 points (frame pixels) and hand them to the detector. */
    private void saveCalibration(List<float[]> pts) {
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
    }

    /**
     * Analysis thread, while playing: every few seconds, when nothing moves, look for the board again.
     * A new position is taken only when two runs in a row agree, and (to leave the visit alone)
     * small shifts only while the board is empty.
     */
    private void recheckCalibration(Bitmap frame) {
        long now = System.currentTimeMillis();
        if (now - lastRecheckMs < (recheckCandidate != null ? RECHECK_CONFIRM_MS : RECHECK_MS)) return;
        lastRecheckMs = now;
        float[] c = pendingCal;
        if (c == null) return;
        int w = frame.getWidth(), h = frame.getHeight();
        int[] px = new int[w * h];
        frame.getPixels(px, 0, w, 0, 0, w, h);
        AutoCalibrator.Result r = AutoCalibrator.find(px, w, h, gravX, -gravY);
        if (!r.ok()) {
            recheckCandidate = null;
            if (now - lastFailRecordMs > 30000) { lastFailRecordMs = now; record(frame, "recheck", r, "failed"); }
            return;
        }
        double shift = 0, agree = recheckCandidate == null ? Double.MAX_VALUE : 0;
        for (int i = 0; i < 4; i++) {
            shift = Math.max(shift, Math.hypot(r.calPoints[i][0] - c[i * 2] * w, r.calPoints[i][1] - c[i * 2 + 1] * h));
            if (recheckCandidate != null)
                agree = Math.max(agree, Math.hypot(r.calPoints[i][0] - recheckCandidate[i][0], r.calPoints[i][1] - recheckCandidate[i][1]));
        }
        boolean worth = shift > 3 || shift > 1.0 && detector.dartsInVisit() == 0;
        if (!worth) { recheckCandidate = null; return; }
        if (agree > 0.7) { // confirm with the next run
            recheckCandidate = r.calPoints;
            record(frame, "recheck", r, String.format(Locale.ROOT, "candidate, shift %.1f px", shift));
            return;
        }
        recheckCandidate = null;
        record(frame, "recheck", r, String.format(Locale.ROOT, "adopted, shift %.1f px, darts in board %d", shift, detector.dartsInVisit()));
        List<float[]> pts = new ArrayList<>();
        for (double[] p : r.calPoints) pts.add(new float[]{(float) p[0], (float) p[1]});
        double moved = shift;
        runOnUiThread(() -> {
            if (boardView.calibrating() || !keepCalibrated) return;
            saveCalibration(pts);
            if (moved <= 3) return; // small drift: fixed silently
            boardView.clearMarks(); // positions of darts already in the board are no longer valid
            boardView.setZoom(null);
            calNote = String.format(Locale.ROOT, "Calibration updated (phone moved %.0f px).", moved);
            calNoteUntil = System.currentTimeMillis() + 5000;
            hintText.postDelayed(this::updateHint, 5100);
            buzz(20);
            updateHint();
        });
    }

    /**
     * Pack the last Auto runs (pictures + what Auto decided) and the current camera picture into one
     * zip in Downloads/DartCam and open the share sheet, so the pictures can be sent for tuning.
     */
    private void sendDebug() {
        List<AutoRecord> recs;
        synchronized (records) { recs = new ArrayList<>(records); }
        Bitmap full = lastFull, cur = lastUpright;
        int fullRot = lastFullRot;
        ZoomState zs = zoomState();
        float[] cal = pendingCal;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        String name = "dartcam-debug-" + stamp + ".zip";
        hintText.setText("Packing debug pictures…");
        new Thread(() -> {
            try {
                Uri uri = null;
                File file = null;
                OutputStream os;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DartCam");
                    ContentResolver cr = getContentResolver();
                    uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IllegalStateException("cannot create the file in Downloads");
                    os = cr.openOutputStream(uri);
                } else {
                    file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                    os = new FileOutputStream(file);
                }
                StringBuilder info = new StringBuilder();
                info.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
                if (full != null) info.append("stream=").append(full.getWidth()).append('x').append(full.getHeight()).append(" rotation=").append(fullRot).append('\n');
                if (zs != null) info.append(String.format(Locale.ROOT, "zoom=%.3f min=%.3f max=%.3f linear=%.3f%n",
                        zs.getZoomRatio(), zs.getMinZoomRatio(), zs.getMaxZoomRatio(), zs.getLinearZoom()));
                info.append(String.format(Locale.ROOT, "gravity=%.3f,%.3f keepCalibrated=%b%n", gravX, gravY, keepCalibrated));
                if (cal != null) {
                    info.append("calibration(normalised)=");
                    for (float f : cal) info.append(String.format(Locale.ROOT, "%.5f,", f));
                    info.append('\n');
                }
                try (ZipOutputStream z = new ZipOutputStream(os)) {
                    int i = 0;
                    for (AutoRecord rec : recs) {
                        String fn = String.format(Locale.ROOT, "%02d-%s.png", i++, rec.trigger);
                        z.putNextEntry(new ZipEntry(fn));
                        rec.frame.compress(Bitmap.CompressFormat.PNG, 100, z);
                        z.closeEntry();
                        AutoCalibrator.Result r = rec.r;
                        info.append(fn).append('\t')
                                .append(String.format(Locale.ROOT, "time=%tT gx=%.3f gy=%.3f zoom=%.3f size=%dx%d ok=%b",
                                        rec.time, rec.gx, rec.gy, rec.zoom, rec.frame.getWidth(), rec.frame.getHeight(), r.ok()))
                                .append(String.format(Locale.ROOT, " rms=%.3f inliers=%d pattern=%.3f", r.rmsPx, r.inliers, r.patternMatch))
                                .append(" reason=").append(r.failReason == null ? "-" : r.failReason.replace(' ', '_'))
                                .append(" outcome=").append(rec.outcome.isEmpty() ? "-" : rec.outcome.replace(' ', '_'));
                        if (r.calPoints != null) {
                            info.append(" points=");
                            for (double[] p : r.calPoints) info.append(String.format(Locale.ROOT, "%.2f:%.2f;", p[0], p[1]));
                        }
                        info.append('\n');
                    }
                    if (cur != null) {
                        z.putNextEntry(new ZipEntry("current.png"));
                        cur.compress(Bitmap.CompressFormat.PNG, 100, z);
                        z.closeEntry();
                    }
                    if (full != null) {
                        z.putNextEntry(new ZipEntry("current-full.jpg"));
                        uprightFull(full, fullRot).compress(Bitmap.CompressFormat.JPEG, 92, z);
                        z.closeEntry();
                    }
                    z.putNextEntry(new ZipEntry("info.txt"));
                    z.write(info.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    z.closeEntry();
                }
                Uri shareUri = uri;
                File saved = file;
                int n = recs.size();
                runOnUiThread(() -> {
                    hintText.setText("Saved " + n + " Auto pictures to " + (shareUri != null ? "Downloads/DartCam/" + name : saved.getPath()));
                    if (shareUri != null) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, shareUri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "Send DartCam debug"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> hintText.setText("Send debug failed: " + e.getMessage()));
            }
        }, "debug-zip").start();
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
                float zr = prefs.getFloat("zoomRatio", -1);
                if (zr > 0) camera.getCameraControl().setZoomRatio(zr); // exactly the zoom Auto calibrated with
                else camera.getCameraControl().setLinearZoom(prefs.getInt("zoom", 0) / 100f);
            } catch (Exception e) {
                hintText.setText("Camera error: " + e.getMessage());
            }
        }, this::runOnUiThread);
    }

    /** Runs on the analysis thread for every camera frame. */
    private void analyze(ImageProxy image) {
        Bitmap upright, src;
        int rot;
        float scale;
        try {
            src = image.toBitmap();
            rot = image.getImageInfo().getRotationDegrees();
            lastFull = src;
            lastFullRot = rot;
            Matrix m = new Matrix();
            m.postRotate(rot);
            scale = (float) ANALYSIS_LONG_SIDE / Math.max(src.getWidth(), src.getHeight());
            m.postScale(scale, scale);
            upright = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Exception e) {
            return;
        } finally {
            image.close();
        }
        lastUpright = upright;
        int w = upright.getWidth(), h = upright.getHeight();
        if (detector == null || w != frameW || h != frameH) {
            frameW = w;
            frameH = h;
            detector = new DartDetector(w, h, null);
            calChanged = true;
        }
        if (calChanged) {
            calChanged = false;
            beforeFull = null;
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
        if (autoCalRequested) {
            autoCalRequested = false;
            runAutoCalibration(upright);
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
            if (ev == null && keepCalibrated && detector.idle()) recheckCalibration(upright);
            else if (ev != null) recheckCandidate = null;
        }
        ZoomShot zoom = null;
        if (ev != null && ev.type == DartDetector.Type.DART && beforeFull != null) {
            zoom = zoomRecheck(ev, beforeFull, beforeRot, src, rot, 1f / scale, w, h);
        }
        // The last still picture becomes the "before" of the next dart (also right after a dart).
        if (!paused && detector.calibrated() && detector.idle()) { beforeFull = src; beforeRot = rot; }
        else if (paused) beforeFull = null;
        DartDetector.Event event = ev;
        ZoomShot z = zoom;
        runOnUiThread(() -> {
            boardView.setFrame(upright);
            if (event != null) onDetectorEvent(event, z);
        });
    }

    /** Result of the zoom: the re-check (may be null / not ok) and the colour crop for the close-up. */
    private static final class ZoomShot {
        final TipRefiner.Result result; final Bitmap crop; final float x0, y0, scale;
        ZoomShot(TipRefiner.Result r, Bitmap crop, float x0, float y0, float scale) {
            this.result = r; this.crop = crop; this.x0 = x0; this.y0 = y0; this.scale = scale;
        }
    }

    /**
     * Digital zoom: cut the window around the detected tip out of the full-resolution pictures
     * before and after the dart and measure the tip again there (analysis thread, a few ms).
     * @param full crop pixels per analysis pixel
     */
    private ZoomShot zoomRecheck(DartDetector.Event ev, Bitmap before, int beforeRotation,
                                          Bitmap after, int afterRotation, float full, int aw, int ah) {
        try {
            if (beforeRotation != afterRotation || before.getWidth() != after.getWidth()
                    || before.getHeight() != after.getHeight()) return null;
            double[] win = TipRefiner.window(ev.imgX, ev.imgY, detector.imgToBoard(), aw, ah);
            Bitmap b = uprightFull(before, beforeRotation), a = uprightFull(after, afterRotation);
            int x0 = (int) Math.floor(win[0] * full), y0 = (int) Math.floor(win[1] * full);
            int x1 = Math.min(a.getWidth(), (int) Math.ceil(win[2] * full));
            int y1 = Math.min(a.getHeight(), (int) Math.ceil(win[3] * full));
            int cw = x1 - x0, ch = y1 - y0;
            if (cw < 8 || ch < 8) return null;
            int[] gb = grayCrop(b, x0, y0, cw, ch), ga = grayCrop(a, x0, y0, cw, ch);
            TipRefiner.Result r = TipRefiner.refine(gb, ga, cw, ch, x0 / full, y0 / full, full,
                    ev.imgX, ev.imgY, ev.hit, detector.imgToBoard(), detector.cameraFoot(), 28);
            return new ZoomShot(r, Bitmap.createBitmap(a, x0, y0, cw, ch), x0 / full, y0 / full, full);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap uprightFull(Bitmap src, int rotation) {
        if (rotation == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(rotation);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
    }

    private static int[] grayCrop(Bitmap b, int x0, int y0, int cw, int ch) {
        int[] px = new int[cw * ch];
        b.getPixels(px, 0, cw, x0, y0, cw, ch);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
        }
        return px;
    }

    // ---------------- game flow ----------------

    private void onDetectorEvent(DartDetector.Event e, ZoomShot shot) {
        TipRefiner.Result zoom = shot == null ? null : shot.result;
        if (e.type == DartDetector.Type.DART) {
            String label = e.hit.label();
            float mx = (float) e.imgX, my = (float) e.imgY;
            zoomNote = null;
            if (zoom != null && zoom.ok) {
                if (zoom.changed) zoomNote = label + " → " + zoom.hit.label() + " (zoom re-check)";
                label = zoom.hit.label();
                mx = (float) zoom.tipX; my = (float) zoom.tipY;
                if (zoom.closeCall) zoomNote = (zoomNote == null ? label : zoomNote)
                        + String.format(Locale.ROOT, " – close to a wire (%.1f mm), please check", zoom.wireMm);
            }
            visit.add(label);
            boardView.addMark(mx, my, label);
            boardView.setZoom(shot == null ? null : new BoardView.Zoom(shot.crop, shot.x0, shot.y0, shot.scale,
                    mx, my, label, zoom != null && zoom.ok && zoom.closeCall));
            link.send("DART " + label);
            buzz(35);
        } else {
            zoomNote = null;
            boardView.setZoom(null);
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
        zoomNote = null;
        boardView.setZoom(null);
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
        zoomNote = null;
        boardView.setZoom(null);
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
