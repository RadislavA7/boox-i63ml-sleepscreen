package ru.local.sleepscreen;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Минимальный декодер base64 без зависимостей (пропускает пробелы и переводы строк). */
final class Base64Lite {
    private static final byte[] DEC = new byte[256];

    static {
        Arrays.fill(DEC, (byte) -1);
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < a.length(); i++) {
            DEC[a.charAt(i)] = (byte) i;
        }
        DEC['-'] = 62;
        DEC['_'] = 63;
    }

    private Base64Lite() {
    }

    static byte[] decode(byte[] src, int off, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len * 3 / 4 + 4);
        int acc = 0, bits = 0;
        for (int i = off; i < off + len; i++) {
            int c = src[i] & 0xFF;
            if (c == '=') {
                break;
            }
            int v = DEC[c];
            if (v < 0) {
                continue;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
