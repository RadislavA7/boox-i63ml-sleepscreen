package ru.local.sleepscreen;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Файл заставки лежит в каталоге приложения (files/advt.png, права 644), а /data/misc/advt.png - символическая ссылка на него.
 * Ссылка создаётся один раз через root. После этого менять и удалять заставку можно без root: если целевого файла нет,
 * система не может декодировать advt.png и показывает штатные картинки.
 */
final class AdvtLink {
    static final String ADVT = "/data/misc/advt.png";
    private static final String TAG = "SleepScreen";

    private AdvtLink() {
    }

    static File target(Context c) {
        return new File(c.getFilesDir(), "advt.png");
    }

    /** Атомарно записывает PNG заставки (во временный файл, затем rename). */
    static void write(Context c, byte[] png) throws IOException {
        File t = target(c);
        File tmp = new File(t.getParentFile(), "advt.png.tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(png);
            fos.flush();
        } finally {
            fos.close();
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(t)) {
            t.delete();
            if (!tmp.renameTo(t)) {
                throw new IOException("не удалось записать " + t);
            }
        }
        t.setReadable(true, false);
    }

    /** true, если /data/misc/advt.png - это наша ссылка (или любой путь к нашему файлу). */
    static boolean linked(Context c) {
        try {
            File a = new File(ADVT);
            return a.getCanonicalPath().equals(target(c).getCanonicalPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Создаёт ссылку через root, если её ещё нет. Возвращает null при успехе или текст ошибки. */
    static String ensure(Context c) {
        if (linked(c)) {
            return null;
        }
        String script = "BB=/system/bin/busybox\n"
                + "[ -x \"$BB\" ] || BB=/system/xbin/busybox\n"
                + "[ -x \"$BB\" ] || { echo ERR:nobusybox; exit 1; }\n"
                + "$BB rm -f " + ADVT + " " + ADVT + ".tmp\n"
                + "$BB ln -s \"" + target(c).getAbsolutePath() + "\" " + ADVT + " || { echo ERR:ln; exit 1; }\n"
                + "echo LINK=$($BB readlink " + ADVT + ")\n"
                + "echo SLEEPSCREEN_OK\n";
        RootShell.Result r = RootShell.run(script, 120000);
        String err = interpret(r);
        if (err == null && !linked(c)) {
            Log.w(TAG, "ссылка создана, но не распознана: " + r.out);
        }
        return err;
    }

    /** Возвращает штатные картинки: удаляет файл заставки (root нужен только если остался старый обычный файл). */
    static String remove(Context c) {
        target(c).delete();
        new File(target(c).getParentFile(), "advt.png.tmp").delete();
        File a = new File(ADVT);
        if (a.exists() && !linked(c)) {
            String script = "BB=/system/bin/busybox\n"
                    + "[ -x \"$BB\" ] || BB=/system/xbin/busybox\n"
                    + "$BB rm -f " + ADVT + " " + ADVT + ".tmp\n"
                    + "[ -e " + ADVT + " ] && { echo ERR:still-exists; exit 1; }\n"
                    + "echo SLEEPSCREEN_OK\n";
            return interpret(RootShell.run(script, 120000));
        }
        return null;
    }

    /** true, если сейчас на экране сна будет наша картинка. */
    static boolean active(Context c) {
        File t = target(c);
        return t.exists() && new File(ADVT).exists();
    }

    static String interpret(RootShell.Result r) {
        if (r.failedToStart) {
            return "Не найден su (root-доступ): " + r.error;
        }
        if (r.timedOut) {
            return "Нет ответа от Superuser. Разрешите доступ в окне Superuser (с галочкой «Запомнить») и повторите.";
        }
        if (r.out.indexOf("SLEEPSCREEN_OK") < 0) {
            String detail = (r.err + r.out).trim();
            if (detail.length() == 0) {
                return "Нет root-доступа (код " + r.exit + "). Разрешите приложению доступ в Superuser и повторите.";
            }
            return "Команда не выполнена (код " + r.exit + "): " + detail;
        }
        return null;
    }
}
