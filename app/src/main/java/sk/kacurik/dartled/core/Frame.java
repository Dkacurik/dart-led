package sk.kacurik.dartled.core;

/** An RGB picture the size of the LED panel (0xRRGGBB per pixel). */
public final class Frame {
    public final int w, h;
    public final int[] px;

    public Frame(int w, int h) {
        this.w = w;
        this.h = h;
        this.px = new int[w * h];
    }

    public void fillRect(int x, int y, int rw, int rh, int rgb) {
        for (int yy = Math.max(0, y); yy < Math.min(h, y + rh); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(w, x + rw); xx++) {
                px[yy * w + xx] = rgb & 0xFFFFFF;
            }
        }
    }
}
