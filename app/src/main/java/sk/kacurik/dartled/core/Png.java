package sk.kacurik.dartled.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Minimal RGB PNG encoder (the display accepts PNG images). */
public final class Png {
    private Png() {}

    public static byte[] encode(Frame f) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DeflaterOutputStream z = new DeflaterOutputStream(raw);
            for (int y = 0; y < f.h; y++) {
                z.write(0); // filter: none
                for (int x = 0; x < f.w; x++) {
                    int c = f.px[y * f.w + x];
                    z.write((c >> 16) & 0xFF);
                    z.write((c >> 8) & 0xFF);
                    z.write(c & 0xFF);
                }
            }
            z.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            byte[] ihdr = new byte[13];
            be32(ihdr, 0, f.w);
            be32(ihdr, 4, f.h);
            ihdr[8] = 8;  // bit depth
            ihdr[9] = 2;  // colour type: RGB
            chunk(out, "IHDR", ihdr);
            chunk(out, "IDAT", raw.toByteArray());
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        be32(len, 0, data.length);
        out.write(len, 0, 4);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        be32(c, 0, (int) crc.getValue());
        out.write(c, 0, 4);
    }

    private static void be32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }
}
