package ru.local.sleepscreen;

import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;

/**
 * Ловит нажатие кнопки питания при включённом экране по записям системы в журнале (нужен READ_LOGS):
 * "WindowManager: interceptKeyTq keycode=26 screenIsOn=true ..." при нажатии и при отпускании, и
 * "jeffy: mPowerSleep ..." в момент, когда книга действительно начинает засыпать.
 * Заставку система рисует примерно через полсекунды после нажатия, и этого времени хватает, чтобы успеть сбросить
 * экран (см. EbcReset). Нажатие, которое будит книгу, пропускается: у него первая запись приходит с screenIsOn=false.
 */
final class PowerKeyWatcher extends Thread {
    interface Listener {
        /** Кнопка питания нажата при включённом экране; delayMs — через сколько запись из журнала дошла до приложения. */
        void onPowerKeyDown(long delayMs);

        /** Система начала засыпание (нажатие было «коротким» и дошло до конца). */
        void onSleepStarted();
    }

    private static final String TAG = "SleepScreen";
    private static final String MATCH = "interceptKeyTq keycode=26 screenIsOn=true";
    private static final String MATCH_OFF = "interceptKeyTq keycode=26 screenIsOn=false";

    private final Listener listener;
    private volatile boolean stop;

    PowerKeyWatcher(Listener l) {
        super("power-key-watcher");
        setDaemon(true);
        listener = l;
    }

    void shutdown() {
        stop = true;
    }

    @Override
    public void run() {
        long startSec = System.currentTimeMillis() / 1000 - 2;   // записи, накопленные до запуска, пропускаем
        long lastFire = 0;
        long lastWake = -100000;
        while (!stop) {
            FileInputStream in = null;
            try {
                in = new FileInputStream("/dev/log/main");
                Log.i(TAG, "слежение за кнопкой питания запущено");
                byte[] buf = new byte[5120];
                while (!stop) {
                    int n = in.read(buf);
                    if (n < 24) {
                        continue;
                    }
                    int sec = le32(buf, 12);
                    if (sec < startSec) {
                        continue;
                    }
                    int tagEnd = 21;
                    while (tagEnd < n && buf[tagEnd] != 0) {
                        tagEnd++;
                    }
                    String tag = new String(buf, 21, tagEnd - 21, "UTF-8");
                    boolean wm = "WindowManager".equals(tag);
                    boolean jeffy = "jeffy".equals(tag);
                    if (!wm && !jeffy) {
                        continue;
                    }
                    int msgEnd = tagEnd + 1;
                    while (msgEnd < n && buf[msgEnd] != 0) {
                        msgEnd++;
                    }
                    String msg = new String(buf, tagEnd + 1, Math.max(0, msgEnd - tagEnd - 1), "UTF-8");
                    long now = SystemClock.elapsedRealtime();
                    if (jeffy) {
                        if (msg.startsWith("mPowerSleep")) {
                            listener.onSleepStarted();
                        }
                        continue;
                    }
                    if (msg.startsWith(MATCH_OFF)) {
                        lastWake = now;   // нажатие, которое будит книгу: его «отпускание» уже придёт с включённым экраном
                        continue;
                    }
                    if (!msg.startsWith(MATCH)) {
                        continue;
                    }
                    if (now - lastWake < 700) {
                        continue;   // отпускание кнопки, разбудившей книгу
                    }
                    if (now - lastFire < 2500) {
                        continue;   // вторая запись (отпускание кнопки)
                    }
                    lastFire = now;
                    long entryMs = (long) sec * 1000L + le32(buf, 16) / 1000000L;
                    listener.onPowerKeyDown(System.currentTimeMillis() - entryMs);
                }
            } catch (Throwable t) {
                if (!stop) {
                    Log.w(TAG, "слежение за журналом прервалось: " + t);
                    SystemClock.sleep(5000);
                }
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
}
