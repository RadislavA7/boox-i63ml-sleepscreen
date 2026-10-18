package ru.local.sleepscreen;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Служба приложения. Делает две независимые вещи:
 * 1) авторежим: держит заставку в соответствии с книгой, которую читаешь (при смене книги, при включении/выключении
 *    экрана и по таймеру раз в полчаса);
 * 2) очистка экрана: при нажатии кнопки питания сразу сбрасывает e-ink в белое, чтобы на заставке не проступало
 *    остаточное изображение прежней страницы (см. EbcReset, PowerKeyWatcher).
 */
public class CoverService extends Service {
    static final String ACT_ENABLE = "ru.local.sleepscreen.ENABLE_AUTO";
    static final String ACT_DISABLE = "ru.local.sleepscreen.DISABLE_AUTO";
    static final String ACT_REFRESH = "ru.local.sleepscreen.REFRESH";
    static final String ACT_TICK = "ru.local.sleepscreen.TICK";
    static final String ACT_TEST = "ru.local.sleepscreen.TEST_BOOK";
    /** Перечитать настройки (после смены галочки «Очищать экран» в приложении). */
    static final String ACT_SYNC = "ru.local.sleepscreen.SYNC";
    private static final String TAG = "SleepScreen";
    /** Если через столько миллисекунд после сброса книга не заснула (например, отменили меню выключения), экран перерисовывается. */
    private static final long SAFETY_MS = 2200;

    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    private BroadcastReceiver screenReceiver;
    private PowerManager.WakeLock wakeLock;
    private PowerKeyWatcher keyWatcher;
    private volatile boolean forceNext;
    private volatile boolean sleepStarted;
    private int retries;

    private final Runnable refreshTask = new Runnable() {
        public void run() {
            doRefresh();
        }
    };

    /** Страховка после сброса: если сон не начался, возвращаем на экран прежнюю картинку. */
    private final Runnable safetyTask = new Runnable() {
        public void run() {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!sleepStarted && pm.isScreenOn()) {
                Log.i(TAG, "после сброса книга не заснула, перерисовываю экран");
                ScreenRefresher.refresh(CoverService.this);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("cover-updater");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepScreenCover");
        wakeLock.setReferenceCounted(false);

        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                schedule(1200, false);
            }
        };
        getContentResolver().registerContentObserver(CmsReader.META, true, observer);
        getContentResolver().registerContentObserver(CmsReader.HISTORY, true, observer);

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    sleepStarted = true;
                }
                schedule(1500, false);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
        IntentFilter media = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);   // после загрузки карта монтируется не сразу
        media.addDataScheme("file");
        registerReceiver(screenReceiver, media);
        scheduleAlarm(this, Prefs.isAuto(this));
        syncKeyWatcher();
        Log.i(TAG, "служба запущена (обложка: " + Prefs.isAuto(this) + ", очистка экрана: " + Prefs.cleanOnSleep(this) + ")");
    }

    /** Нужна ли служба вообще: обновлять обложку (авторежим) и/или очищать экран при нажатии кнопки питания. */
    private boolean needService() {
        return Prefs.isAuto(this) || (Prefs.cleanOnSleep(this) && EbcReset.available());
    }

    /** Включает или выключает слежение за кнопкой питания в соответствии с настройкой «очищать экран». */
    private void syncKeyWatcher() {
        if (Prefs.cleanOnSleep(this) && EbcReset.available()) {
            if (keyWatcher == null) {
                keyWatcher = new PowerKeyWatcher(new PowerKeyWatcher.Listener() {
                    public void onPowerKeyDown(long delayMs) {
                        if (!Prefs.cleanOnSleep(CoverService.this)) {
                            return;
                        }
                        // защита от рассогласования состояния системы: если по данным питания экран уже выключен, ничего не сбрасываем
                        if (!((PowerManager) getSystemService(Context.POWER_SERVICE)).isScreenOn()) {
                            Log.w(TAG, "кнопка питания: система считает экран выключенным, сброс пропущен");
                            return;
                        }
                        sleepStarted = false;
                        Log.i(TAG, "кнопка питания (запись в журнале с задержкой " + delayMs + " мс): сбрасываю экран");
                        EbcReset.run();
                        handler.removeCallbacks(safetyTask);
                        handler.postDelayed(safetyTask, SAFETY_MS);
                    }

                    public void onSleepStarted() {
                        sleepStarted = true;
                    }
                });
                keyWatcher.start();
            }
        } else if (keyWatcher != null) {
            keyWatcher.shutdown();
            keyWatcher = null;
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACT_TEST.equals(a)) {
            // служебная проверка: am broadcast -a ru.local.sleepscreen.TEST_BOOK --es path /путь/к/книге.fb2
            handler.post(new Runnable() {
                public void run() {
                    if (intent.getBooleanExtra("dump", false)) {
                        CmsReader.dump(CoverService.this);
                    }
                    String path = intent.getStringExtra("path");
                    if (path != null) {
                        CoverUpdater.test(CoverService.this, new java.io.File(path), intent.getStringExtra("title"), intent.getStringExtra("authors"));
                    }
                }
            });
            handler.post(new Runnable() {
                public void run() {
                    if (!needService()) {
                        stopSelf();
                    }
                }
            });
            return needService() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACT_DISABLE.equals(a)) {
            if (Prefs.isAuto(this)) {
                Prefs.setMode(this, Prefs.MODE_MANUAL);
            }
            scheduleAlarm(this, false);
            return afterSettingsChange();
        }
        if (ACT_SYNC.equals(a)) {
            return afterSettingsChange();
        }
        if (ACT_ENABLE.equals(a)) {
            Prefs.setMode(this, Prefs.MODE_AUTO);
            scheduleAlarm(this, true);
        }
        syncKeyWatcher();
        if (!Prefs.isAuto(this)) {
            if (!needService()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            return START_STICKY;   // обложку не обновляем, служба нужна только для очистки экрана
        }
        boolean force = ACT_ENABLE.equals(a) || ACT_REFRESH.equals(a);
        schedule(force ? 0 : 800, force);
        return START_STICKY;
    }

    private int afterSettingsChange() {
        syncKeyWatcher();
        if (!needService()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void schedule(long delayMs, boolean force) {
        if (force) {
            forceNext = true;
        }
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, delayMs);
    }

    private void doRefresh() {
        if (!Prefs.isAuto(this)) {
            return;
        }
        boolean force = forceNext;
        forceNext = false;
        wakeLock.acquire(120000);
        try {
            CoverUpdater.Result r = CoverUpdater.update(this, force);
            if (r.error != null) {
                Prefs.get(this).edit().putString("auto_error", r.error).commit();
                Log.w(TAG, "служба: " + r.error);
                if (r.retry && retries < 10) {
                    retries++;
                    schedule(Math.min(60000L, 6000L * retries), false);   // повторить позже: карта/база ещё не готовы
                }
            } else {
                retries = 0;
                if (Prefs.get(this).contains("auto_error")) {
                    Prefs.get(this).edit().remove("auto_error").commit();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "служба: сбой обновления", t);
        } finally {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    static void scheduleAlarm(Context c, boolean on) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, BootReceiver.class).setAction(ACT_TICK), 0);
        if (on) {
            am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_HOUR,
                    AlarmManager.INTERVAL_HALF_HOUR, pi);
        } else {
            am.cancel(pi);
        }
    }

    @Override
    public void onDestroy() {
        try {
            getContentResolver().unregisterContentObserver(observer);
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        scheduleAlarm(this, false);
        if (keyWatcher != null) {
            keyWatcher.shutdown();
            keyWatcher = null;
        }
        thread.quit();
        Log.i(TAG, "служба остановлена");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
