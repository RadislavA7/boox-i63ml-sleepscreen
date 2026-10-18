package ru.local.sleepscreen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Конвейер обработки картинки без классов Android (чтобы его можно было проверить на ПК).
 *
 * Картинка сна рисуется системой в фреймбуфер 1024x758 БЕЗ поворота, поэтому в файле картинка хранится как
 * обычная портретная (758x1024, как её видно в руках), повёрнутая на 90 градусов ПО ЧАСОВОЙ. Формат файла как у
 * заводских: PNG, RGB 8 бит, без альфы, без интерлейса.
 */
public final class ImageProc {
    public static final int PW = 758, PH = 1024;   // портрет: то, что видно в руках
    public static final int SW = 1024, SH = 758;   // как хранится в файле

    public static final class Gray {
        public final byte[] pix;
        public final int w, h;

        public Gray(byte[] pix, int w, int h) {
            this.pix = pix;
            this.w = w;
            this.h = h;
        }
    }

    private ImageProc() {
    }

    /** Поворот по часовой стрелке на quarterTurns * 90 градусов. */
    public static Gray rotate(Gray g, int quarterTurns) {
        quarterTurns = ((quarterTurns % 4) + 4) % 4;
        if (quarterTurns == 0) {
            return g;
        }
        int w = g.w, h = g.h;
        byte[] src = g.pix;
        byte[] dst = new byte[w * h];
        if (quarterTurns == 2) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x];
                }
            }
            return new Gray(dst, w, h);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int di = (quarterTurns == 1) ? (x * h + (h - 1 - y)) : ((w - 1 - x) * h + y);
                dst[di] = src[y * w + x];
            }
        }
        return new Gray(dst, h, w);
    }

    public static final int MODE_FILL = 0;        // обрезка по центру без искажений
    public static final int MODE_FIT_WHITE = 1;   // вписать целиком, поля белые
    public static final int MODE_FIT_EDGE = 2;    // вписать целиком, поля цвета краёв картинки (для обложек)

    /** Исходник (серый) -> портрет 758x1024: fill = обрезка по центру без искажений, иначе вписать целиком на белом. */
    public static byte[] toPortrait(Gray g, boolean fill, boolean autoContrast) {
        return toPortrait(g, fill ? MODE_FILL : MODE_FIT_WHITE, autoContrast);
    }

    /** Средний серый цвет по рамке картинки (для заливки полей). */
    private static int edgeGray(Gray g) {
        int w = g.w, h = g.h;
        int ring = Math.max(1, Math.min(w, h) / 100);
        long sum = 0;
        long cnt = 0;
        for (int y = 0; y < h; y++) {
            boolean fullRow = y < ring || y >= h - ring;
            for (int x = 0; x < w; x++) {
                if (fullRow || x < ring || x >= w - ring) {
                    sum += g.pix[y * w + x] & 0xFF;
                    cnt++;
                } else if (x == ring) {
                    x = w - ring - 1;   // пропустить середину строки
                }
            }
        }
        return cnt == 0 ? 255 : (int) (sum / cnt);
    }

    public static byte[] toPortrait(Gray g, int mode, boolean autoContrast) {
        byte[] out = new byte[PW * PH];
        int w = g.w, h = g.h;
        boolean fill = (mode == MODE_FILL);
        if (fill) {
            double target = (double) PW / PH;
            int cw, ch;
            if ((double) w / h > target) {
                ch = h;
                cw = (int) Math.round(h * target);
            } else {
                cw = w;
                ch = (int) Math.round(w / target);
            }
            if (cw > w) cw = w;
            if (ch > h) ch = h;
            if (cw < 1) cw = 1;
            if (ch < 1) ch = 1;
            resizeArea(g.pix, w, (w - cw) / 2, (h - ch) / 2, cw, ch, out, PW, 0, 0, PW, PH);
        } else {
            java.util.Arrays.fill(out, (byte) (mode == MODE_FIT_EDGE ? edgeGray(g) : 255));
            double s = Math.min((double) PW / w, (double) PH / h);
            int dw = Math.max(1, (int) Math.round(w * s));
            int dh = Math.max(1, (int) Math.round(h * s));
            if (dw > PW) dw = PW;
            if (dh > PH) dh = PH;
            resizeArea(g.pix, w, 0, 0, w, h, out, PW, (PW - dw) / 2, (PH - dh) / 2, dw, dh);
        }
        if (autoContrast) {
            autoContrast(out);
        }
        return out;
    }

    /** Масштабирование усреднением по площади (раздельное, два прохода). */
    private static void resizeArea(byte[] src, int srcStride, int sx, int sy, int sw, int sh,
                                   byte[] dst, int dstStride, int dx, int dy, int dw, int dh) {
        double scaleX = (double) sw / dw;
        double scaleY = (double) sh / dh;
        float[] tmp = new float[dw * sh];
        for (int i = 0; i < dw; i++) {
            double x0 = i * scaleX, x1 = x0 + scaleX;
            int k0 = (int) Math.floor(x0);
            int k1 = (int) Math.ceil(x1);
            if (k1 > sw) k1 = sw;
            if (k0 >= k1) k0 = k1 - 1;
            float[] wt = new float[k1 - k0];
            for (int k = k0; k < k1; k++) {
                double a = Math.max(k, x0), b = Math.min(k + 1, x1);
                wt[k - k0] = (float) (Math.max(0.0, b - a) / scaleX);
            }
            for (int r = 0; r < sh; r++) {
                int base = (sy + r) * srcStride + sx;
                float acc = 0f;
                for (int k = k0; k < k1; k++) {
                    acc += (src[base + k] & 0xFF) * wt[k - k0];
                }
                tmp[r * dw + i] = acc;
            }
        }
        for (int j = 0; j < dh; j++) {
            double y0 = j * scaleY, y1 = y0 + scaleY;
            int k0 = (int) Math.floor(y0);
            int k1 = (int) Math.ceil(y1);
            if (k1 > sh) k1 = sh;
            if (k0 >= k1) k0 = k1 - 1;
            float[] wt = new float[k1 - k0];
            for (int k = k0; k < k1; k++) {
                double a = Math.max(k, y0), b = Math.min(k + 1, y1);
                wt[k - k0] = (float) (Math.max(0.0, b - a) / scaleY);
            }
            for (int i = 0; i < dw; i++) {
                float acc = 0f;
                for (int k = k0; k < k1; k++) {
                    acc += tmp[k * dw + i] * wt[k - k0];
                }
                int v = Math.round(acc);
                dst[(dy + j) * dstStride + dx + i] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
            }
        }
    }

    /** Растяжка контраста с отсечением 1% самых тёмных и светлых пикселей. */
    private static void autoContrast(byte[] p) {
        int[] hist = new int[256];
        for (int i = 0; i < p.length; i++) {
            hist[p[i] & 0xFF]++;
        }
        int cut = p.length / 100;
        int lo = 0, acc = 0;
        for (lo = 0; lo < 255; lo++) {
            acc += hist[lo];
            if (acc > cut) break;
        }
        int hi = 255;
        acc = 0;
        for (hi = 255; hi > 0; hi--) {
            acc += hist[hi];
            if (acc > cut) break;
        }
        if (hi <= lo) {
            return;
        }
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            int t = (v - lo) * 255 / (hi - lo);
            lut[v] = t < 0 ? 0 : (t > 255 ? 255 : t);
        }
        for (int i = 0; i < p.length; i++) {
            p[i] = (byte) lut[p[i] & 0xFF];
        }
    }

    /** Портрет 758x1024 -> вид для файла 1024x758 (поворот на 90 градусов по часовой). */
    public static byte[] rotateCwToStored(byte[] portrait) {
        byte[] stored = new byte[SW * SH];
        for (int py = 0; py < PH; py++) {
            for (int px = 0; px < PW; px++) {
                stored[px * SW + (PH - 1 - py)] = portrait[py * PW + px];
            }
        }
        return stored;
    }

    /** Серый 1024x758 -> PNG RGB 8 бит (фильтр Sub, zlib). */
    public static byte[] encodePng(byte[] stored) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512 * 1024);
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, SW);
        putInt(ihdr, 4, SH);
        ihdr[8] = 8;   // глубина
        ihdr[9] = 2;   // RGB
        writeChunk(out, "IHDR", ihdr);

        ByteArrayOutputStream z = new ByteArrayOutputStream(512 * 1024);
        Deflater def = new Deflater(6);
        DeflaterOutputStream dos = new DeflaterOutputStream(z, def, 32 * 1024);
        byte[] cur = new byte[SW * 3];
        byte[] row = new byte[1 + SW * 3];
        row[0] = 1;    // фильтр Sub
        for (int y = 0; y < SH; y++) {
            int base = y * SW;
            for (int x = 0; x < SW; x++) {
                byte v = stored[base + x];
                cur[3 * x] = v;
                cur[3 * x + 1] = v;
                cur[3 * x + 2] = v;
            }
            for (int i = 0; i < cur.length; i++) {
                row[1 + i] = (byte) (cur[i] - (i >= 3 ? cur[i - 3] : 0));
            }
            dos.write(row, 0, row.length);
        }
        dos.finish();
        dos.close();
        def.end();
        writeChunk(out, "IDAT", z.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes("US-ASCII");
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
