package ru.local.sleepscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** Общие настройки приложения (activity и служба работают в одном процессе). */
final class Prefs {
    static final String MODE_AUTO = "auto";       // обложка текущей книги, обновляется службой
    static final String MODE_MANUAL = "manual";   // своя картинка, выбранная вручную
    static final String MODE_OFF = "off";         // стандартные картинки

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("sleepscreen", Context.MODE_PRIVATE);
    }

    static String mode(Context c) {
        return get(c).getString("mode", MODE_OFF);
    }

    static boolean isAuto(Context c) {
        return MODE_AUTO.equals(mode(c));
    }

    static void setMode(Context c, String mode) {
        get(c).edit().putString("mode", mode).commit();
    }

    /** Сбрасывать e-ink в белое при нажатии кнопки питания, чтобы заставка не получала остаточное изображение (по умолчанию да). */
    static boolean cleanOnSleep(Context c) {
        return get(c).getBoolean("clean", true);
    }

    static void setCleanOnSleep(Context c, boolean on) {
        get(c).edit().putBoolean("clean", on).commit();
    }
}
