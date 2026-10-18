package ru.local.sleepscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** Делает заставку из обложки книги, которую читаешь сейчас (последней открытой в читалке). */
final class CoverUpdater {
    private static final String TAG = "SleepScreen";
    private static final double MAX_UPSCALE = 3.4;   // во сколько раз можно растягивать обложку на весь экран
    /** Версия логики подбора обложки/названия: после её изменения заставка пересоздаётся один раз даже для той же книги. */
    private static final int LOGIC = 3;

    static final class Result {
        boolean unchanged, retry;
        String title, authors, source, error;
    }

    private CoverUpdater() {
    }

    static synchronized Result update(Context ctx, boolean force) {
        Result r = new Result();
        SharedPreferences p = Prefs.get(ctx);
        try {
            long t0 = System.currentTimeMillis();
            CmsReader.Book b = CmsReader.current(ctx);
            if (b == null) {
                r.error = CmsReader.lastFailure != null ? CmsReader.lastFailure : "Не найдена открытая книга. Откройте любую книгу в читалке.";
                r.retry = CmsReader.lastRetry;
                return r;
            }
            File bf = new File(b.path);
            // название и автор: из самой книги, потом из базы Onyx, потом (в крайнем случае) из имени файла
            BookMeta meta = BookMeta.resolve(bf, b.title, b.authors);
            String key = b.md5 + "|" + bf.length() + "|" + bf.lastModified() + "|v" + LOGIC;
            r.title = meta.title;
            r.authors = meta.authors;
            if (!force && key.equals(p.getString("auto_key", null)) && AdvtLink.target(ctx).exists() && AdvtLink.linked(ctx)) {
                r.unchanged = true;
                r.source = p.getString("auto_source", "");
                Log.d(TAG, "без изменений: " + meta.title);
                return r;
            }
            Log.i(TAG, "текущая книга: " + meta.title + " / " + meta.authors + " | " + b.path);

            Render rd = render(ctx, bf, b.md5, meta.title, meta.authors);
            byte[] portrait = rd.portrait;
            String source = rd.source;

            savePreview(ctx, portrait);
            byte[] png = ImageProc.encodePng(ImageProc.rotateCwToStored(portrait));
            AdvtLink.write(ctx, png);
            String err = AdvtLink.ensure(ctx);
            if (err != null) {
                r.error = err;
                return r;
            }
            p.edit().putString("auto_key", key).putString("auto_title", meta.title).putString("auto_authors", meta.authors)
                    .putString("auto_source", source).putLong("auto_time", System.currentTimeMillis()).remove("auto_error").commit();
            r.source = source;
            Log.i(TAG, "заставка обновлена: " + source + ", PNG " + png.length + " байт, всего " + (System.currentTimeMillis() - t0) + " мс");
        } catch (Throwable t) {
            Log.e(TAG, "обновление обложки не удалось", t);
            r.error = "Ошибка: " + t;
        }
        return r;
    }

    static final class Render {
        byte[] portrait;
        String source;
    }

    /** Строит портрет 758x1024 для книги: обложка из файла книги, иначе миниатюра Onyx, иначе карточка с названием. */
    static Render render(Context ctx, File bf, String md5, String title, String authors) {
        Render rd = new Render();
        long t0 = System.currentTimeMillis();
        byte[] img = BookCovers.find(bf);
        Log.i(TAG, "обложка из файла книги: " + (img == null ? "нет" : img.length + " байт") + " (" + (System.currentTimeMillis() - t0) + " мс)");
        if (img != null) {
            try {
                ImageProc.Gray g = ImageLoader.loadBytes(img, false);
                double scale = Math.min((double) ImageProc.PW / g.w, (double) ImageProc.PH / g.h);
                if (scale <= MAX_UPSCALE) {
                    rd.portrait = ImageProc.toPortrait(g, ImageProc.MODE_FIT_EDGE, true);
                    rd.source = "обложка из книги";
                } else {
                    Bitmap bm = BitmapFactory.decodeByteArray(img, 0, img.length);
                    rd.portrait = TitleCard.render(title, authors, bm);
                    rd.source = "карточка с маленькой обложкой";
                    if (bm != null) {
                        bm.recycle();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "обложку не удалось обработать: " + t);
                rd.portrait = null;
            }
        }
        if (rd.portrait == null) {
            File th = CmsReader.thumbnail(ctx, md5);
            Bitmap tb = null;
            if (th != null) {
                try {
                    tb = BitmapFactory.decodeFile(th.getPath());
                } catch (Throwable ignored) {
                    tb = null;
                }
            }
            if (tb != null && isBlank(tb)) {
                tb.recycle();
                tb = null;
            }
            rd.portrait = TitleCard.render(title, authors, tb);
            rd.source = tb != null ? "карточка с миниатюрой Onyx" : "карточка с названием";
            if (tb != null) {
                tb.recycle();
            }
        }
        return rd;
    }

    /** Проверка: строит заставку для произвольного файла и кладёт PNG в files/test.png (заставку системы не трогает). */
    static String test(Context ctx, File book, String title, String authors) {
        try {
            BookMeta meta = BookMeta.resolve(book, title, authors);
            Log.i(TAG, "ТЕСТ " + book.getName() + ": название [" + meta.title + "], автор [" + meta.authors + "]");
            Render rd = render(ctx, book, null, meta.title, meta.authors);
            byte[] png = ImageProc.encodePng(ImageProc.rotateCwToStored(rd.portrait));
            File out = new File(ctx.getFilesDir(), "test.png");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(png);
            } finally {
                fos.close();
            }
            out.setReadable(true, false);
            Log.i(TAG, "ТЕСТ " + book.getName() + ": " + rd.source + ", PNG " + png.length + " байт");
            return rd.source;
        } catch (Throwable t) {
            Log.e(TAG, "ТЕСТ не удался", t);
            return null;
        }
    }

    private static boolean isBlank(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int min = 255, max = 0;
        for (int i = 0; i < 400; i++) {
            int x = (int) ((long) i * 7919 % w);
            int y = (int) ((long) i * 104729 % h);
            int c = bm.getPixel(x, y);
            int g = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            min = Math.min(min, g);
            max = Math.max(max, g);
        }
        return max - min < 14;
    }

    /** Уменьшенная копия для экрана приложения (files/preview.png, портрет как в руках). */
    private static void savePreview(Context ctx, byte[] portrait) {
        try {
            int w = ImageProc.PW / 2, h = ImageProc.PH / 2;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (2 * y) * ImageProc.PW + 2 * x;
                    int v = ((portrait[i] & 0xFF) + (portrait[i + 1] & 0xFF) + (portrait[i + ImageProc.PW] & 0xFF) + (portrait[i + ImageProc.PW + 1] & 0xFF)) / 4;
                    px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }
            Bitmap bm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), "preview.png"));
            try {
                bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            bm.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "предпросмотр не сохранён: " + t);
        }
    }
}
