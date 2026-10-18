package ru.local.sleepscreen;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/** Чтение базы библиотеки Onyx (OnyxCmsProvider): какая книга открыта последней и где лежат миниатюры обложек. */
final class CmsReader {
    private static final String TAG = "SleepScreen";
    static final Uri META = Uri.parse("content://com.onyx.android.sdk.OnyxCmsProvider/library_metadata");
    static final Uri HISTORY = Uri.parse("content://com.onyx.android.sdk.OnyxCmsProvider/library_history");
    static final Uri THUMBS = Uri.parse("content://com.onyx.android.sdk.OnyxCmsProvider/library_thumbnail");

    private static final String[] BOOK_EXT = {"fb2", "epub", "mobi", "azw", "azw3", "prc", "pdf", "djvu", "djv", "txt", "rtf",
            "doc", "docx", "html", "htm", "chm", "lit", "pdb", "zip", "fb3", "odt"};

    static final class Book {
        String md5, title, authors, path;
        long lastAccess;
    }

    private CmsReader() {
    }

    private static boolean isBookPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ENGLISH);
        for (String e : BOOK_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Причина, по которой current() вернул null, и стоит ли повторить позже (карта не смонтирована, база ещё не поднялась). */
    static volatile String lastFailure;
    static volatile boolean lastRetry;

    /** Последняя открытая книга (картинки и служебные файлы пропускаются). */
    static Book current(Context c) {
        Cursor cur = null;
        lastFailure = null;
        lastRetry = false;
        boolean sawMissing = false;
        try {
            cur = c.getContentResolver().query(META,
                    new String[]{"MD5", "Title", "Authors", "NativeAbsolutePath", "LastAccess"},
                    null, null, "LastAccess DESC");
            if (cur == null) {
                Log.w(TAG, "провайдер Onyx не вернул курсор");
                lastFailure = "База Onyx пока недоступна.";
                lastRetry = true;
                return null;
            }
            while (cur.moveToNext()) {
                String path = cur.getString(3);
                long last = cur.getLong(4);
                if (path == null || last <= 0 || !isBookPath(path)) {
                    continue;
                }
                if (!new File(path).isFile()) {
                    sawMissing = true;
                    continue;
                }
                Book b = new Book();
                b.md5 = cur.getString(0);
                b.title = cur.getString(1);
                b.authors = cur.getString(2);
                b.path = path;
                b.lastAccess = last;
                if (b.title == null || b.title.length() == 0) {
                    String n = new File(path).getName();
                    int dot = n.lastIndexOf('.');
                    b.title = dot > 0 ? n.substring(0, dot) : n;
                }
                return b;
            }
            if (sawMissing) {
                lastFailure = "Файл открытой книги пока недоступен (карта памяти ещё не готова?).";
                lastRetry = true;
            } else {
                lastFailure = "Не найдена открытая книга. Откройте любую книгу в читалке.";
            }
        } catch (Throwable t) {
            Log.e(TAG, "чтение базы Onyx не удалось", t);
            lastFailure = "Не удалось прочитать базу Onyx: " + t;
            lastRetry = true;
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        return null;
    }

    /** Служебное: выводит в журнал, что Onyx знает о каждой книге (название, авторы, путь). */
    static void dump(Context c) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(META, new String[]{"MD5", "Title", "Authors", "NativeAbsolutePath", "LastAccess"},
                    null, null, "LastAccess DESC");
            while (cur != null && cur.moveToNext()) {
                Log.i(TAG, "БАЗА: title=[" + cur.getString(1) + "] authors=[" + cur.getString(2) + "] path=" + cur.getString(3)
                        + " last=" + cur.getLong(4));
            }
        } catch (Throwable t) {
            Log.w(TAG, "дамп базы не удался: " + t);
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
    }

    /** Миниатюра обложки из кэша Onyx (маленькая), либо null. */
    static File thumbnail(Context c, String md5) {
        if (md5 == null) {
            return null;
        }
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(THUMBS, new String[]{"_data"},
                    "Source_MD5=? AND Thumbnail_Kind=?", new String[]{md5, "Original"}, null);
            if (cur != null && cur.moveToFirst()) {
                String p = cur.getString(0);
                if (p != null) {
                    File f = new File(p);
                    if (f.isFile()) {
                        return f;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "миниатюра не найдена: " + t);
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        return null;
    }
}
