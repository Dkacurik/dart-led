package sk.kacurik.dartcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import sk.kacurik.dartcam.core.Board;
import sk.kacurik.dartcam.core.Homography;

/**
 * Shows the camera frame (the exact picture the detector sees), the board wireframe from the
 * calibration, the darts of the current visit, and lets the user place / drag the 4 calibration
 * points. All positions are in frame pixels; this view only scales them to the screen.
 */
public final class BoardView extends View {

    public interface CalibrationListener {
        /** Called when the points change; pts has 0..4 entries (frame pixels). */
        void onCalibrationChanged(List<float[]> pts);
    }

    public static final class Mark {
        final float x, y; final String label;
        public Mark(float x, float y, String label) { this.x = x; this.y = y; this.label = label; }
    }

    private Bitmap frame;
    private final Matrix toView = new Matrix(), toFrame = new Matrix();
    private Homography boardToImg;
    private final List<float[]> cal = new ArrayList<>();
    private final List<Mark> marks = new ArrayList<>();
    private boolean calibrating;
    private int dragging = -1;
    private CalibrationListener calListener;

    private final Paint wire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    public BoardView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        wire.setStyle(Paint.Style.STROKE);
        wire.setStrokeWidth(1.5f * dp);
        wire.setColor(Color.argb(170, 64, 255, 64));
        handle.setStyle(Paint.Style.STROKE);
        handle.setStrokeWidth(2.5f * dp);
        handle.setColor(Color.YELLOW);
        text.setColor(Color.WHITE);
        text.setTextSize(15 * dp);
        text.setFakeBoldText(true);
        shadow.setColor(Color.argb(160, 0, 0, 0));
        markPaint.setColor(Color.rgb(255, 64, 160));
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(3 * dp);
        setBackgroundColor(Color.BLACK);
    }

    public void setCalibrationListener(CalibrationListener l) { calListener = l; }

    public void setFrame(Bitmap b) {
        frame = b;
        updateMatrix();
        invalidate();
    }

    public void setBoardToImage(Homography h) { boardToImg = h; invalidate(); }

    public void setCalibrating(boolean on) { calibrating = on; invalidate(); }

    public boolean calibrating() { return calibrating; }

    public void setCalibrationPoints(List<float[]> pts) {
        cal.clear();
        for (float[] p : pts) cal.add(p.clone());
        invalidate();
    }

    public void clearCalibration() {
        cal.clear();
        boardToImg = null;
        notifyCal();
        invalidate();
    }

    public void addMark(float x, float y, String label) { marks.add(new Mark(x, y, label)); invalidate(); }

    public void removeLastMark() { if (!marks.isEmpty()) marks.remove(marks.size() - 1); invalidate(); }

    public void clearMarks() { marks.clear(); invalidate(); }

    private void updateMatrix() {
        if (frame == null || getWidth() == 0) return;
        RectF src = new RectF(0, 0, frame.getWidth(), frame.getHeight());
        RectF dst = new RectF(0, 0, getWidth(), getHeight());
        toView.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        toView.invert(toFrame);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { updateMatrix(); }

    @Override
    protected void onDraw(Canvas c) {
        if (frame == null) {
            c.drawText("Starting camera…", 20 * dp, 40 * dp, text);
            return;
        }
        c.drawBitmap(frame, toView, null);
        if (boardToImg != null) drawBoard(c);
        for (int i = 0; i < cal.size() && calibrating; i++) {
            float[] v = view(cal.get(i)[0], cal.get(i)[1]);
            c.drawCircle(v[0], v[1], 14 * dp, handle);
            c.drawLine(v[0] - 6 * dp, v[1], v[0] + 6 * dp, v[1], handle);
            c.drawLine(v[0], v[1] - 6 * dp, v[0], v[1] + 6 * dp, handle);
            label(c, String.valueOf(Board.CALIBRATION_LABELS[i].split(" ")[0]), v[0] + 16 * dp, v[1] - 10 * dp);
        }
        for (Mark m : marks) {
            float[] v = view(m.x, m.y);
            c.drawCircle(v[0], v[1], 9 * dp, markPaint);
            label(c, m.label, v[0] + 12 * dp, v[1] + 6 * dp);
        }
    }

    private void drawBoard(Canvas c) {
        double[] rings = {Board.R_INNER_BULL, Board.R_OUTER_BULL, Board.R_TREBLE_IN, Board.R_TREBLE_OUT, Board.R_DOUBLE_IN, Board.R_DOUBLE_OUT};
        for (double r : rings) {
            Path p = new Path();
            for (int i = 0; i <= 90; i++) {
                double a = i * 2 * Math.PI / 90;
                float[] v = boardToView(r * Math.sin(a), -r * Math.cos(a));
                if (i == 0) p.moveTo(v[0], v[1]); else p.lineTo(v[0], v[1]);
            }
            c.drawPath(p, wire);
        }
        for (int i = 0; i < 20; i++) {
            double a = Math.toRadians(i * 18 - 9);
            float[] a1 = boardToView(Board.R_OUTER_BULL * Math.sin(a), -Board.R_OUTER_BULL * Math.cos(a));
            float[] a2 = boardToView(Board.R_DOUBLE_OUT * Math.sin(a), -Board.R_DOUBLE_OUT * Math.cos(a));
            c.drawLine(a1[0], a1[1], a2[0], a2[1], wire);
        }
        int[] order = Board.order();
        for (int i = 0; i < 20; i++) {
            double a = Math.toRadians(i * 18);
            float[] v = boardToView(190 * Math.sin(a), -190 * Math.cos(a));
            String s = String.valueOf(order[i]);
            c.drawText(s, v[0] - text.measureText(s) / 2, v[1] + 5 * dp, text);
        }
    }

    private void label(Canvas c, String s, float x, float y) {
        float w = text.measureText(s);
        c.drawRoundRect(x - 4 * dp, y - 16 * dp, x + w + 4 * dp, y + 5 * dp, 4 * dp, 4 * dp, shadow);
        c.drawText(s, x, y, text);
    }

    private float[] boardToView(double bx, double by) {
        double[] p = boardToImg.map(bx, by);
        return view((float) p[0], (float) p[1]);
    }

    private float[] view(float fx, float fy) {
        float[] p = {fx, fy};
        toView.mapPoints(p);
        return p;
    }

    private float[] toFramePoint(float vx, float vy) {
        float[] p = {vx, vy};
        toFrame.mapPoints(p);
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!calibrating || frame == null) return false;
        float[] f = toFramePoint(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = -1;
                float best = 36 * dp;
                for (int i = 0; i < cal.size(); i++) {
                    float[] v = view(cal.get(i)[0], cal.get(i)[1]);
                    float d = (float) Math.hypot(v[0] - e.getX(), v[1] - e.getY());
                    if (d < best) { best = d; dragging = i; }
                }
                if (dragging < 0 && cal.size() < 4) {
                    cal.add(new float[]{f[0], f[1]});
                    dragging = cal.size() - 1;
                    notifyCal();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging >= 0) {
                    cal.get(dragging)[0] = clamp(f[0], 0, frame.getWidth() - 1);
                    cal.get(dragging)[1] = clamp(f[1], 0, frame.getHeight() - 1);
                    notifyCal();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                return true;
        }
        return true;
    }

    private void notifyCal() {
        if (calListener == null) return;
        List<float[]> copy = new ArrayList<>();
        for (float[] p : cal) copy.add(p.clone());
        calListener.onCalibrationChanged(copy);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
