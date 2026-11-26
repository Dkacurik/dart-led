import sk.kacurik.dartcam.core.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the auto-calibration on real pictures and draws what it found.
 *
 *   javac -d /tmp/dartsim cam/src/main/java/sk/kacurik/dartcam/core/*.java tools/dartcam-sim/*.java
 *   java -cp /tmp/dartsim AutoCalPhoto board.jpg out.png [rollDegrees]
 *   java -cp /tmp/dartsim AutoCalPhoto dartcam-debug-....zip outDir     (zip from DartCam's "Send debug")
 *
 * Single pictures are scaled like on the phone (long side 640 px). rollDegrees = how far the phone was
 * turned clockwise (default 0 = upright), standing in for the accelerometer. For debug zips the
 * phone's own gravity reading (info.txt) is used, and the phone's result is printed next to ours.
 */
public class AutoCalPhoto {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: AutoCalPhoto in.jpg out.png [rollDegrees]   |   AutoCalPhoto debug.zip outDir");
            System.exit(2);
        }
        if (args[0].endsWith(".zip")) { debugZip(new File(args[0]), new File(args[1])); return; }

        BufferedImage src = ImageIO.read(new File(args[0]));
        if (src == null) { System.out.println("cannot read " + args[0]); System.exit(2); }
        if (src.getWidth() > src.getHeight())
            System.out.println("note: picture is landscape (" + src.getWidth() + "x" + src.getHeight() + "). Phone JPGs often store a portrait"
                    + " photo sideways with an EXIF rotation tag that is ignored here: pass rollDegrees 90 or 270 if 20 is not at the top.");
        double roll = args.length > 2 ? Math.toRadians(Double.parseDouble(args[2])) : 0;
        BufferedImage img = scaled(src, 640);
        String r = process(img, -Math.sin(roll), -Math.cos(roll), new File(args[1]));
        System.out.println(r);
        System.out.println("wrote " + args[1]);
    }

    /** Every Auto picture in a DartCam debug zip: our result vs. the phone's, plus an overlay each. */
    static void debugZip(File zip, File outDir) throws IOException {
        outDir.mkdirs();
        Map<String, BufferedImage> pics = new TreeMap<>();
        String info = "";
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                byte[] data = z.readAllBytes();
                if (e.getName().equals("info.txt")) info = new String(data, StandardCharsets.UTF_8);
                else if (e.getName().endsWith(".png") || e.getName().endsWith(".jpg")) pics.put(e.getName(), ImageIO.read(new ByteArrayInputStream(data)));
            }
        }
        Map<String, String> lines = new HashMap<>();
        for (String l : info.split("\n")) {
            int t = l.indexOf('\t');
            if (t > 0) lines.put(l.substring(0, t), l.substring(t + 1));
            else if (!l.isBlank()) System.out.println("info: " + l);
        }
        for (Map.Entry<String, BufferedImage> e : pics.entrySet()) {
            String name = e.getKey();
            BufferedImage img = e.getValue();
            if (img.getWidth() > 700 || img.getHeight() > 700) img = scaled(img, 640);
            String meta = lines.getOrDefault(name, "");
            double gx = num(meta, "gx", 0), gy = num(meta, "gy", 9.81);
            File out = new File(outDir, name.replaceAll("\\.(png|jpg)$", "") + "-overlay.png");
            String ours = process(img, gx, -gy, out);
            System.out.println(name + "\n   phone: " + (meta.isEmpty() ? "(no info)" : meta) + "\n   mac:   " + ours);
        }
        System.out.println("overlays in " + outDir);
    }

    static double num(String meta, String key, double def) {
        for (String part : meta.split(" ")) if (part.startsWith(key + "=")) {
            try { return Double.parseDouble(part.substring(key.length() + 1)); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    static BufferedImage scaled(BufferedImage src, int longSide) {
        double scale = (double) longSide / Math.max(src.getWidth(), src.getHeight());
        int w = (int) Math.round(src.getWidth() * scale), h = (int) Math.round(src.getHeight() * scale);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return img;
    }

    /** Run Auto with "up" = (upX, upY) in picture axes, draw the result into outFile. */
    static String process(BufferedImage img, double upX, double upY, File outFile) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        long t0 = System.nanoTime();
        AutoCalibrator.Result r = AutoCalibrator.find(px, w, h, upX, upY);
        String summary = String.format(Locale.ROOT, "%s (%d ms, %dx%d)", r, (System.nanoTime() - t0) / 1_000_000, w, h);

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
        // "Up" arrow from the gravity reading.
        o.setColor(Color.MAGENTA);
        double un = Math.hypot(upX, upY);
        if (un > 1e-9) o.drawLine(w / 2, h / 2, (int) (w / 2 + 40 * upX / un), (int) (h / 2 + 40 * upY / un));
        o.setColor(Color.WHITE);
        o.drawString(r.toString(), 8, 16);
        o.dispose();
        ImageIO.write(out, "png", outFile);
        return summary;
    }
}
