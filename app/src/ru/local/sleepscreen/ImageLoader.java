package ru.local.sleepscreen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;

import java.io.IOException;

/** Загрузка картинки (из файла или из массива байт) в серый массив с ограничением памяти. */
final class ImageLoader {
    private ImageLoader() {
    }

    /** Из файла: fill=true - потом будет обрезка по центру, иначе вписывание целиком. С учётом EXIF-поворота. */
    static ImageProc.Gray load(String path, boolean fill) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            throw new IOException("не удалось прочитать картинку (формат не поддерживается?)");
        }
        int orient = exifOrientation(path);
        boolean swap = (orient == 6 || orient == 8);
        int ew = swap ? o.outHeight : o.outWidth;
        int eh = swap ? o.outWidth : o.outHeight;
        int sample = 1;
        while (sampleOk(ew, eh, sample * 2, fill)) {
            sample *= 2;
        }
        Bitmap bm = null;
        for (int attempt = 0; attempt < 4 && bm == null; attempt++) {
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                bm = BitmapFactory.decodeFile(path, d);
            } catch (OutOfMemoryError e) {
                bm = null;
            }
            if (bm == null) {
                sample *= 2;
            }
        }
        return finish(bm, orient);
    }

    /** Из массива байт (обложка из книги). */
    static ImageProc.Gray loadBytes(byte[] data, boolean fill) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            throw new IOException("не удалось прочитать обложку");
        }
        int sample = 1;
        while (sampleOk(o.outWidth, o.outHeight, sample * 2, fill)) {
            sample *= 2;
        }
        Bitmap bm = null;
        for (int attempt = 0; attempt < 4 && bm == null; attempt++) {
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                bm = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            } catch (OutOfMemoryError e) {
                bm = null;
            }
            if (bm == null) {
                sample *= 2;
            }
        }
        return finish(bm, 1);
    }

    private static ImageProc.Gray finish(Bitmap bm, int orient) throws IOException {
        if (bm == null) {
            throw new IOException("не удалось декодировать картинку (мало памяти)");
        }
        int w = bm.getWidth(), h = bm.getHeight();
        byte[] pix = new byte[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bm.getPixels(row, 0, w, 0, y, w, 1);
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int a = c >>> 24;
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (a < 255) {
                    r = (r * a + 255 * (255 - a)) / 255;
                    g = (g * a + 255 * (255 - a)) / 255;
                    b = (b * a + 255 * (255 - a)) / 255;
                }
                pix[base + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
        }
        bm.recycle();
        ImageProc.Gray gray = new ImageProc.Gray(pix, w, h);
        if (orient == 6) {
            gray = ImageProc.rotate(gray, 1);
        } else if (orient == 3) {
            gray = ImageProc.rotate(gray, 2);
        } else if (orient == 8) {
            gray = ImageProc.rotate(gray, 3);
        }
        return gray;
    }

    private static boolean sampleOk(int ew, int eh, int s, boolean fill) {
        int sw = ew / s, sh = eh / s;
        if (sw < 1 || sh < 1) {
            return false;
        }
        if (fill) {
            double target = (double) ImageProc.PW / ImageProc.PH;
            double cw, ch;
            if ((double) sw / sh > target) {
                ch = sh;
                cw = sh * target;
            } else {
                cw = sw;
                ch = sw / target;
            }
            return cw >= ImageProc.PW && ch >= ImageProc.PH;
        }
        return Math.min((double) ImageProc.PW / sw, (double) ImageProc.PH / sh) <= 1.0;
    }

    private static int exifOrientation(String path) {
        try {
            ExifInterface e = new ExifInterface(path);
            return e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (Throwable t) {
            return 1;
        }
    }
}
