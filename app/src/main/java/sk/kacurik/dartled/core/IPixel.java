package sk.kacurik.dartled.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Bluetooth commands understood by "iPixel Color" LED panels.
 * Based on the open-source pypixelcolor project (MIT), which reverse-engineered the protocol.
 */
public final class IPixel {
    private IPixel() {}

    public static final UUID WRITE_UUID = UUID.fromString("0000fa02-0000-1000-8000-00805f9b34fb");
    public static final UUID NOTIFY_UUID = UUID.fromString("0000fa03-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static byte[] deviceInfo(int hour, int minute, int second) {
        return new byte[]{8, 0, 1, (byte) 0x80, (byte) hour, (byte) minute, (byte) second, 0};
    }

    public static byte[] brightness(int level) {
        level = Math.max(1, Math.min(100, level));
        return new byte[]{5, 0, 4, (byte) 0x80, (byte) level};
    }

    /** Panel size from the device-info reply, or null if unknown. */
    public static int[] sizeFromInfo(byte[] reply) {
        if (reply == null || reply.length < 5) return null;
        switch (reply[4] & 0xFF) {
            case 128: return new int[]{64, 64};
            case 129: return new int[]{32, 32};
            case 130: return new int[]{32, 16};
            case 131: return new int[]{64, 16};
            case 132: return new int[]{96, 16};
            case 133: return new int[]{64, 20};
            case 134: return new int[]{128, 32};
            case 135: return new int[]{144, 16};
            case 136: return new int[]{192, 16};
            case 137: return new int[]{48, 24};
            case 138: return new int[]{64, 32};
            case 139: return new int[]{96, 32};
            case 140: return new int[]{128, 32};
            case 141: return new int[]{96, 32};
            case 142: return new int[]{160, 32};
            case 143: return new int[]{192, 32};
            case 144: return new int[]{256, 32};
            case 145: return new int[]{320, 32};
            case 146: return new int[]{384, 32};
            case 147: return new int[]{448, 32};
            default: return null;
        }
    }

    /** True if a notification acknowledges a transfer window. */
    public static boolean isAck(byte[] n) {
        return n != null && n.length >= 5 && n[0] == 0x05 && ((n[4] & 0xFF) <= 3);
    }

    /** Splits a PNG into protocol "windows" (each must be acknowledged before the next). */
    public static List<byte[]> imageWindows(byte[] png) {
        CRC32 crc = new CRC32();
        crc.update(png);
        long c = crc.getValue();
        int total = png.length;

        List<byte[]> windows = new ArrayList<>();
        int windowSize = 12 * 1024;
        for (int pos = 0, idx = 0; pos < total; pos += windowSize, idx++) {
            int len = Math.min(windowSize, total - pos);
            int frameLen = 13 + len;          // header (13) + payload
            int msgLen = 2 + frameLen;        // length prefix counts itself
            byte[] m = new byte[msgLen];
            int o = 0;
            m[o++] = (byte) msgLen;
            m[o++] = (byte) (msgLen >> 8);
            m[o++] = 0x02;                    // static image
            m[o++] = 0x00;
            m[o++] = (byte) (idx == 0 ? 0x00 : 0x02);
            m[o++] = (byte) total;
            m[o++] = (byte) (total >> 8);
            m[o++] = (byte) (total >> 16);
            m[o++] = (byte) (total >> 24);
            m[o++] = (byte) c;
            m[o++] = (byte) (c >> 8);
            m[o++] = (byte) (c >> 16);
            m[o++] = (byte) (c >> 24);
            m[o++] = 0x00;
            m[o++] = 0x00;                    // save slot 0 = show only, don't store
            System.arraycopy(png, pos, m, o, len);
            windows.add(m);
        }
        return windows;
    }
}
