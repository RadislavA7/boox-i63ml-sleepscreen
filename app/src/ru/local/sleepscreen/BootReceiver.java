package ru.local.sleepscreen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Запускает службу обложки после загрузки книги и по таймеру; принимает служебные команды ENABLE/DISABLE/REFRESH/TEST_BOOK. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) {
            return;
        }
        boolean command = CoverService.ACT_ENABLE.equals(a) || CoverService.ACT_DISABLE.equals(a)
                || CoverService.ACT_REFRESH.equals(a) || CoverService.ACT_TEST.equals(a) || CoverService.ACT_SYNC.equals(a);
        if (command || Prefs.isAuto(c) || Prefs.cleanOnSleep(c)) {
            Intent out = new Intent(c, CoverService.class).setAction(a);
            if (i.getExtras() != null) {
                out.putExtras(i.getExtras());
            }
            c.startService(out);
        }
    }
}
