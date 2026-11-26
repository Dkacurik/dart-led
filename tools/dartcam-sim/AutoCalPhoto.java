import sk.kacurik.dartcam.core.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Runs the auto-calibration on a real photo and draws what it found.
 *
 *   javac -d /tmp/dartsim cam/src/main/java/sk/kacurik/dartcam/core/*.java tools/dartcam-sim/*.java
 *   java -cp /tmp/dartsim AutoCalPhoto board.jpg out.png [rollDegrees]
 *
 * The photo is scaled like on the phone (long side 640 px). rollDegrees = how far the phone was
 * turned clockwise when taking the photo (default 0 = upright), standing in for the accelerometer.
 */
public class AutoCalPhoto {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("usage: AutoCalPhoto in.jpg out.png [rollDegrees]"); System.exit(2); }
        BufferedImage src = ImageIO.read(new File(args[0]));
        if (src == null) { System.out.println("cannot read " + args[0]); System.exit(2); }
        if (src.getWidth() > src.getHeight())
            System.out.println("note: picture is landscape (" + src.getWidth() + "x" + src.getHeight() + "). Phone JPGs often store a portrait"
                    + " photo sideways with an EXIF rotation tag that is ignored here: pass rollDegrees 90 or 270 if 20 is not at the top.");
        double scale = 640.0 / Math.max(src.getWidth(), src.getHeight());
        int w = (int) Math.round(src.getWidth() * scale), h = (int) Math.round(src.getHeight() * scale);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        double roll = args.length > 2 ? Math.toRadians(Double.parseDouble(args[2])) : 0;
        long t0 = System.nanoTime();
        AutoCalibrator.Result r = AutoCalibrator.find(px, w, h, -Math.sin(roll), -Math.cos(roll));
        System.out.printf("%s (%d ms, %dx%d)%n", r, (System.nanoTime() - t0) / 1_000_000, w, h);

        // Overlay: red/green pixel classes faintly, and the found board wireframe.
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        Graphics2D o = out.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (r.ok()) {
            Homography b2i = r.boardToImg;
            o.setColor(Color.CYAN);
            o.setStroke(new BasicStroke(1f));
            for (double rad : new double[]{Board.R_OUTER_BULL, Board.R_TREBLE_IN, Board.R_TREBLE_OUT, Board.R_DOUBLE_IN, Board.R_DOUBLE_OUT}) {
                double[] prev = null;
                for (int a = 0; a <= 360; a += 3) {
                    double t = Math.toRadians(a);
                    double[] p = b2i.map(rad * Math.sin(t), -rad * Math.cos(t));
                    if (prev != null) o.drawLine((int) prev[0], (int) prev[1], (int) p[0], (int) p[1]);
                    prev = p;
                }
            }
            int[] order = Board.order();
            for (int k = 0; k < 20; k++) {
                double t = Math.toRadians(18 * k + 9);
                double[] p0 = b2i.map(Board.R_OUTER_BULL * Math.sin(t), -Board.R_OUTER_BULL * Math.cos(t));
                double[] p1 = b2i.map(Board.R_DOUBLE_OUT * Math.sin(t), -Board.R_DOUBLE_OUT * Math.cos(t));
                o.drawLine((int) p0[0], (int) p0[1], (int) p1[0], (int) p1[1]);
                double c = Math.toRadians(18 * k);
                double[] lp = b2i.map(190 * Math.sin(c), -190 * Math.cos(c));
                o.setColor(Color.YELLOW);
                o.drawString(String.valueOf(order[k]), (int) lp[0] - 6, (int) lp[1] + 5);
                o.setColor(Color.CYAN);
            }
            o.setColor(Color.YELLOW);
            for (double[] p : r.calPoints) o.drawOval((int) p[0] - 6, (int) p[1] - 6, 12, 12);
        }
        o.setColor(Color.WHITE);
        o.drawString(r.toString(), 8, 16);
        o.dispose();
        ImageIO.write(out, "png", new File(args[1]));
        System.out.println("wrote " + args[1]);
    }
}
