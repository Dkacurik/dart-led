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

    public interface TapListener {
        /** Tap on the camera picture while playing, in frame pixels. */
        void onTap(float fx, float fy);
    }

    public static final class Mark {
        final float x, y; final String label;
        public Mark(float x, float y, String label) { this.x = x; this.y = y; this.label = label; }
    }

    /**
     * Close-up of the last dart: a full-resolution crop of the camera picture around the tip.
     * Crop pixel (cx, cy) covers frame [x0 + cx / scale, ...).
     */
    public static final class Zoom {
        final Bitmap crop; final float x0, y0, scale, tipX, tipY; final String label; final boolean close;
        public Zoom(Bitmap crop, float x0, float y0, float scale, float tipX, float tipY, String label, boolean close) {
            this.crop = crop; this.x0 = x0; this.y0 = y0; this.scale = scale;
            this.tipX = tipX; this.tipY = tipY; this.label = label; this.close = close;
        }
    }

    private Zoom zoom;
    private final RectF zoomRect = new RectF();
    private final Paint zoomWire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Bitmap frame;
    private final Matrix toView = new Matrix(), toFrame = new Matrix();
    private Homography boardToImg;
    private final List<float[]> cal = new ArrayList<>();
    private final List<Mark> marks = new ArrayList<>();
    private boolean calibrating;
    private int dragging = -1;
    private CalibrationListener calListener;
    private TapListener tapListener;
    private float ringX, ringY;      // focus ring after a tap (frame pixels)
    private long ringUntil;

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
        zoomWire.setStyle(Paint.Style.STROKE);
        zoomWire.setStrokeWidth(1.2f * dp);
        zoomWire.setColor(Color.argb(200, 64, 255, 64));
        zoomFrame.setStyle(Paint.Style.STROKE);
    }

    public void setCalibrationListener(CalibrationListener l) { calListener = l; }

    public void setTapListener(TapListener l) { tapListener = l; }

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

    /** Show the close-up of the last dart (null hides it). */
    public void setZoom(Zoom z) { zoom = z; invalidate(); }

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
        if (zoom != null && !calibrating) drawZoom(c, zoom);
        if (System.currentTimeMillis() < ringUntil) {
            float[] v = view(ringX, ringY);
            handle.setColor(Color.WHITE);
            c.drawCircle(v[0], v[1], 34 * dp, handle);
            c.drawCircle(v[0], v[1], 4 * dp, handle);
            handle.setColor(Color.YELLOW);
        }
    }

    /** The close-up, on the side of the screen away from the dart. */
    private void drawZoom(Canvas c, Zoom z) {
        float size = Math.min(getWidth() * 0.46f, getHeight() * 0.34f);
        float[] tipView = view(z.tipX, z.tipY);
        float left = tipView[0] > getWidth() / 2f ? 8 * dp : getWidth() - size - 8 * dp;
        float top = getHeight() * 0.22f;
        float k = size / Math.max(z.crop.getWidth(), z.crop.getHeight()); // inset px per crop px
        float w = z.crop.getWidth() * k, h = z.crop.getHeight() * k;
        zoomRect.set(left, top, left + w, top + h);

        c.save();
        c.clipRect(zoomRect);
        c.drawBitmap(z.crop, null, zoomRect, bitmapPaint);
        if (boardToImg != null) {
            // Rings: many short pieces (curved by perspective); segment wires are straight lines.
            double[] rings = {Board.R_INNER_BULL, Board.R_OUTER_BULL, Board.R_TREBLE_IN, Board.R_TREBLE_OUT, Board.R_DOUBLE_IN, Board.R_DOUBLE_OUT};
            for (double r : rings) {
                Path p = new Path();
                for (int i = 0; i <= 720; i++) {
                    double a = i * 2 * Math.PI / 720;
                    float[] v = boardToZoom(z, r * Math.sin(a), -r * Math.cos(a));
                    if (i == 0) p.moveTo(v[0], v[1]); else p.lineTo(v[0], v[1]);
                }
                c.drawPath(p, zoomWire);
            }
            for (int i = 0; i < 20; i++) {
                double a = Math.toRadians(i * 18 - 9);
                float[] a1 = boardToZoom(z, Board.R_OUTER_BULL * Math.sin(a), -Board.R_OUTER_BULL * Math.cos(a));
                float[] a2 = boardToZoom(z, Board.R_DOUBLE_OUT * Math.sin(a), -Board.R_DOUBLE_OUT * Math.cos(a));
                c.drawLine(a1[0], a1[1], a2[0], a2[1], zoomWire);
            }
        }
        float tx = left + (z.tipX - z.x0) * z.scale * k, ty = top + (z.tipY - z.y0) * z.scale * k;
        c.drawCircle(tx, ty, 7 * dp, markPaint);
        c.drawLine(tx - 12 * dp, ty, tx - 4 * dp, ty, markPaint);
        c.drawLine(tx + 4 * dp, ty, tx + 12 * dp, ty, markPaint);
        c.drawLine(tx, ty - 12 * dp, tx, ty - 4 * dp, markPaint);
        c.drawLine(tx, ty + 4 * dp, tx, ty + 12 * dp, markPaint);
        c.restore();

        zoomFrame.setColor(z.close ? Color.YELLOW : Color.WHITE);
        zoomFrame.setStrokeWidth((z.close ? 4 : 2) * dp);
        c.drawRect(zoomRect, zoomFrame);
        String s = z.close ? z.label + "  close – check" : z.label;
        label(c, s, left + 6 * dp, top + h - 8 * dp);
    }

    private float[] boardToZoom(Zoom z, double bx, double by) {
        double[] p = boardToImg.map(bx, by);
        return new float[]{zoomRect.left + (float) ((p[0] - z.x0) * z.scale) * (zoomRect.width() / z.crop.getWidth()),
                zoomRect.top + (float) ((p[1] - z.y0) * z.scale) * (zoomRect.height() / z.crop.getHeight())};
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
        if (!calibrating && zoom != null && e.getActionMasked() == MotionEvent.ACTION_DOWN
                && zoomRect.contains(e.getX(), e.getY())) {
            zoom = null; // tap the close-up to hide it
            invalidate();
            return true;
        }
        if (!calibrating) {
            // Playing: a tap on the picture = "focus here, the board is here".
            if (frame == null || tapListener == null) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float[] f = toFramePoint(e.getX(), e.getY());
                if (f[0] < 0 || f[1] < 0 || f[0] >= frame.getWidth() || f[1] >= frame.getHeight()) return true;
                ringX = f[0]; ringY = f[1];
                ringUntil = System.currentTimeMillis() + 1200;
                invalidate();
                postDelayed(this::invalidate, 1300);
                tapListener.onTap(f[0], f[1]);
            }
            return true;
        }
        if (frame == null) return false;
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
