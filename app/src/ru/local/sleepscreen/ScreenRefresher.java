package ru.local.sleepscreen;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Заставляет систему заново перерисовать весь экран: на долю секунды добавляется прозрачное окно на весь экран.
 * Нужно, если экран сбросили в белое, а книга так и не заснула (например, отменили меню выключения):
 * системе не о чем «догадаться», и без этого белый экран остался бы до первого действия в читалке.
 */
final class ScreenRefresher {
    private static final String TAG = "SleepScreen";

    private ScreenRefresher() {
    }

    static void refresh(final Context ctx) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    final View v = new View(ctx);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    wm.addView(v, lp);
                    Log.i(TAG, "перерисовка экрана: прозрачное окно добавлено");
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        public void run() {
                            try {
                                wm.removeView(v);
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 400);
                } catch (Throwable t) {
                    Log.w(TAG, "перерисовка экрана не удалась: " + t);
                }
            }
        });
    }
}
