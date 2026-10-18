package ru.local.sleepscreen;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Сброс e-ink панели в белое напрямую через драйвер /dev/ebc (доступен всем) утилитой /system/bin/ioctl:
 * берём свободный буфер кадров (ioctl 0x7000) и отправляем его с режимом 5 (RESET) на весь экран (ioctl 0x7001).
 * После такого сброса следующая картинка (обложка) рисуется на чистой белой панели, без остаточного изображения.
 * Содержимое буфера при режиме 5 не важно, поэтому данные в буфер не пишем.
 */
final class EbcReset {
    private static final String TAG = "SleepScreen";
    private static final String IOCTL = "/system/bin/ioctl";
    private static final String DEV = "/dev/ebc";
    private static final int MODE_RESET = 5;

    private EbcReset() {
    }

    static boolean available() {
        return new File(IOCTL).canExecute() && new File(DEV).exists();
    }

    /** Выполняет сброс. Возвращает true, если кадр отправлен. Блокирует вызывающий поток на время запуска двух утилит. */
    static synchronized boolean run() {
        long t0 = SystemClock.elapsedRealtime();
        int offset = -1;
        try {
            String out = exec(new String[]{IOCTL, "-l", "56", "-a", "4", DEV, "0x7000"});
            int at = out.indexOf("return buf:");
            if (at < 0) {
                Log.w(TAG, "сброс экрана: нет ответа драйвера: " + out);
                return false;
            }
            String[] b = out.substring(at + "return buf:".length()).trim().split("\\s+");
            if (b.length < 56) {
                Log.w(TAG, "сброс экрана: короткий ответ драйвера (" + b.length + " байт)");
                return false;
            }
            offset = (Integer.parseInt(b[3], 16) << 24) | (Integer.parseInt(b[2], 16) << 16)
                    | (Integer.parseInt(b[1], 16) << 8) | Integer.parseInt(b[0], 16);
            long t1 = SystemClock.elapsedRealtime();
            // слова 0..13 структуры: смещение буфера, режим, 758, 1024, 758, 1024, 1024, 758, 0, окно x1 y1 x2 y2 = 0 0 1024 758, поворот 90
            exec(new String[]{IOCTL, "-l", "56", "-a", "4", DEV, "0x7001", "0x" + Integer.toHexString(offset), String.valueOf(MODE_RESET),
                    "0x2f6", "0x400", "0x2f6", "0x400", "0x400", "0x2f6", "0", "0", "0", "0x400", "0x2f6", "0x5a"});
            offset = -1;
            Log.i(TAG, "сброс экрана отправлен за " + (SystemClock.elapsedRealtime() - t0) + " мс (получение буфера " + (t1 - t0) + " мс)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "сброс экрана не удался: " + t);
            if (offset >= 0) {
                // буфер уже взят у драйвера: возвращаем его крошечным частичным обновлением, чтобы не потерять из пула
                try {
                    exec(new String[]{IOCTL, "-l", "56", "-a", "4", DEV, "0x7001", "0x" + Integer.toHexString(offset), "3",
                            "0x2f6", "0x400", "0x2f6", "0x400", "0x400", "0x2f6", "0", "0", "0", "8", "8", "0x5a"});
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    private static String exec(String[] cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        p.waitFor();
        return sb.toString();
    }
}
