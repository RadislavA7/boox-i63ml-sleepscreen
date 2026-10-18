package ru.local.sleepscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Заставка для Onyx Boox i63ML Newton. Два режима:
 * - автоматически: на экране сна обложка книги, которую читаешь (служба CoverService следит за читалкой);
 * - вручную: своя картинка из папки Screensaver.
 * Заставку системе отдаёт файл /data/misc/advt.png (ссылка на файл приложения), "Восстановить" возвращает штатные картинки.
 */
public class MainActivity extends Activity {
    private static final String TAG = "SleepScreen";
    private static final int REQ_PICK = 1;

    private TextView status, fileName;
    private ImageView preview;
    private Button btnPick, btnInstall, btnRestore, btnRefresh;
    private CheckBox cbFill, cbContrast, cbAuto, cbClean;
    private View autoBox, manualTop, manualBottom;
    private SharedPreferences prefs;

    private String selectedPath;
    private byte[] pngReady;
    private Bitmap previewBmp;
    private int generation;
    private boolean busy;
    private boolean ignoreAutoToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        prefs = Prefs.get(this);
        status = (TextView) findViewById(R.id.status);
        fileName = (TextView) findViewById(R.id.fileName);
        preview = (ImageView) findViewById(R.id.preview);
        btnPick = (Button) findViewById(R.id.btnPick);
        btnInstall = (Button) findViewById(R.id.btnInstall);
        btnRestore = (Button) findViewById(R.id.btnRestore);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        cbFill = (CheckBox) findViewById(R.id.cbFill);
        cbContrast = (CheckBox) findViewById(R.id.cbContrast);
        cbAuto = (CheckBox) findViewById(R.id.cbAuto);
        cbClean = (CheckBox) findViewById(R.id.cbClean);
        autoBox = findViewById(R.id.autoBox);
        manualTop = findViewById(R.id.manualTop);
        manualBottom = findViewById(R.id.manualBottom);

        cbFill.setChecked(prefs.getBoolean("fill", true));
        cbContrast.setChecked(prefs.getBoolean("contrast", true));
        CompoundButton.OnCheckedChangeListener opt = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("fill", cbFill.isChecked()).putBoolean("contrast", cbContrast.isChecked()).commit();
                if (selectedPath != null && !busy) {
                    startProcessing();
                }
            }
        };
        cbFill.setOnCheckedChangeListener(opt);
        cbContrast.setOnCheckedChangeListener(opt);

        boolean cleanPossible = EbcReset.available();
        cbClean.setChecked(cleanPossible && Prefs.cleanOnSleep(this));
        cbClean.setEnabled(cleanPossible);
        cbClean.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setCleanOnSleep(MainActivity.this, checked);
                syncService();
            }
        });
        if (cleanPossible && Prefs.cleanOnSleep(this)) {
            syncService();   // убедиться, что служба работает (например, после обновления приложения)
        }

        setAutoChecked(Prefs.isAuto(this));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (ignoreAutoToggle || busy) {
                    return;
                }
                if (checked) {
                    enableAuto();
                } else {
                    disableAuto();
                }
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setBusy(true);
                status.setText("Обновляю обложку…");
                new AutoTask(false, Prefs.mode(MainActivity.this)).execute();
            }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, PickActivity.class);
                i.putExtra("start", prefs.getString("dir", null));
                startActivityForResult(i, REQ_PICK);
            }
        });
        btnInstall.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pngReady != null) {
                    setBusy(true);
                    status.setText("Устанавливаю… (если появится окно Superuser, нажмите «Разрешить»)");
                    new ManualTask(true, pngReady, new File(selectedPath).getName()).execute();
                }
            }
        });
        btnRestore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmRestore();
            }
        });
        applyModeUi();
        setBusy(false);

        // служебный запуск: am start ... --es path /путь/к/картинке (предвыбор файла, например для проверки)
        String preset = getIntent().getStringExtra("path");
        if (preset != null && new File(preset).isFile() && !Prefs.isAuto(this)) {
            selectedPath = preset;
            startProcessing();
        } else {
            showCurrentPreview();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (Prefs.isAuto(this) && !busy) {
            showCurrentPreview();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            selectedPath = data.getStringExtra("path");
            String dir = data.getStringExtra("dir");
            if (dir != null) {
                prefs.edit().putString("dir", dir).commit();
            }
            startProcessing();
        }
    }

    // ---------------------------------------------------------------- состояние экрана
    private void setAutoChecked(boolean v) {
        ignoreAutoToggle = true;
        cbAuto.setChecked(v);
        ignoreAutoToggle = false;
    }

    private void applyModeUi() {
        boolean auto = Prefs.isAuto(this);
        autoBox.setVisibility(auto ? View.VISIBLE : View.GONE);
        manualTop.setVisibility(auto ? View.GONE : View.VISIBLE);
        manualBottom.setVisibility(auto ? View.GONE : View.VISIBLE);
    }

    private void setBusy(boolean b) {
        busy = b;
        btnPick.setEnabled(!b);
        btnRestore.setEnabled(!b);
        btnRefresh.setEnabled(!b);
        btnInstall.setEnabled(!b && pngReady != null);
        cbFill.setEnabled(!b);
        cbContrast.setEnabled(!b);
        cbAuto.setEnabled(!b);
        cbClean.setEnabled(!b && EbcReset.available());
    }

    private void setPreview(Bitmap bm) {
        Bitmap old = previewBmp;
        previewBmp = bm;
        preview.setImageBitmap(bm);
        if (old != null && old != bm) {
            old.recycle();
        }
    }

    private void showCurrentPreview() {
        File f = new File(getFilesDir(), "preview.png");
        if (Prefs.isAuto(this) && f.exists()) {
            try {
                setPreview(BitmapFactory.decodeFile(f.getPath()));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "предпросмотр не загружен: " + t);
            }
        }
        if (selectedPath == null) {
            setPreview(null);
        }
    }

    private void updateStatus() {
        String mode = Prefs.mode(this);
        String s;
        if (Prefs.MODE_AUTO.equals(mode)) {
            String title = prefs.getString("auto_title", null);
            String authors = prefs.getString("auto_authors", "");
            long t = prefs.getLong("auto_time", 0);
            s = "Сейчас: обложка книги, которую вы читаете";
            if (title != null) {
                s += "\n«" + title + "»" + (authors.length() > 0 ? " — " + authors : "");
                String src = prefs.getString("auto_source", "");
                s += "\n" + src + (t > 0 ? ", " + new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(t)) : "");
            }
            String err = prefs.getString("auto_error", null);
            if (err != null) {
                s += "\nОшибка: " + err;
            }
        } else if (AdvtLink.active(this)) {
            String name = prefs.getString("name", null);
            s = Prefs.MODE_MANUAL.equals(mode) && name != null ? "Сейчас: своя картинка (" + name + ")" : "Сейчас: картинка книги (автообновление выключено)";
        } else {
            s = "Сейчас: стандартные картинки (Ньютон)";
        }
        status.setText(s);
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle("Восстановить по умолчанию")
                .setMessage("Удалить заставку (и автоматическое обновление) и вернуть стандартные картинки с Ньютоном?")
                .setPositiveButton("Да", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setBusy(true);
                        status.setText("Восстанавливаю…");
                        new ManualTask(false, null, null).execute();
                    }
                })
                .setNegativeButton("Нет", null)
                .show();
    }

    private void showError(String msg) {
        new AlertDialog.Builder(this).setTitle("Не получилось").setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ---------------------------------------------------------------- автоматический режим
    private void enableAuto() {
        setBusy(true);
        status.setText("Ищу книгу и делаю заставку… (если появится окно Superuser, нажмите «Разрешить» и «Запомнить»)");
        new AutoTask(true, Prefs.mode(this)).execute();
    }

    private void disableAuto() {
        Prefs.setMode(this, Prefs.MODE_MANUAL);
        startService(new Intent(this, CoverService.class).setAction(CoverService.ACT_DISABLE));
        applyModeUi();
        updateStatus();
    }

    /** Сообщает службе, что настройки изменились: она сама решит, нужно ли ей работать дальше. */
    private void syncService() {
        startService(new Intent(this, CoverService.class).setAction(CoverService.ACT_SYNC));
    }

    private final class AutoTask extends AsyncTask<Void, Void, CoverUpdater.Result> {
        private final boolean enabling;
        private final String prevMode;

        AutoTask(boolean enabling, String prevMode) {
            this.enabling = enabling;
            this.prevMode = prevMode;
        }

        @Override
        protected CoverUpdater.Result doInBackground(Void... params) {
            if (enabling) {
                Prefs.setMode(MainActivity.this, Prefs.MODE_AUTO);
            }
            return CoverUpdater.update(MainActivity.this, true);
        }

        @Override
        protected void onPostExecute(CoverUpdater.Result r) {
            setBusy(false);
            if (r.error != null) {
                if (enabling) {
                    Prefs.setMode(MainActivity.this, prevMode);
                    setAutoChecked(false);
                }
                showError(r.error);
            } else if (enabling) {
                startService(new Intent(MainActivity.this, CoverService.class).setAction(CoverService.ACT_ENABLE));
                Toast.makeText(MainActivity.this, "Готово. Заставка будет менять обложку вместе с книгой.", Toast.LENGTH_LONG).show();
            }
            applyModeUi();
            showCurrentPreview();
            updateStatus();
        }
    }

    // ---------------------------------------------------------------- своя картинка
    private void startProcessing() {
        if (selectedPath == null) {
            return;
        }
        final int gen = ++generation;
        pngReady = null;
        fileName.setText("Файл: " + new File(selectedPath).getName() + " — обработка…");
        setBusy(true);
        new ProcessTask(gen, selectedPath, cbFill.isChecked(), cbContrast.isChecked()).execute();
    }

    private final class ProcessTask extends AsyncTask<Void, Void, Object> {
        private final int gen;
        private final String path;
        private final boolean fill, contrast;

        ProcessTask(int gen, String path, boolean fill, boolean contrast) {
            this.gen = gen;
            this.path = path;
            this.fill = fill;
            this.contrast = contrast;
        }

        @Override
        protected Object doInBackground(Void... params) {
            try {
                long t0 = System.currentTimeMillis();
                ImageProc.Gray g = ImageLoader.load(path, fill);
                byte[] portrait = ImageProc.toPortrait(g, fill, contrast);
                byte[] png = ImageProc.encodePng(ImageProc.rotateCwToStored(portrait));
                int[] px = new int[ImageProc.PW * ImageProc.PH];
                for (int i = 0; i < px.length; i++) {
                    int v = portrait[i] & 0xFF;
                    px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                Bitmap bm = Bitmap.createBitmap(px, ImageProc.PW, ImageProc.PH, Bitmap.Config.ARGB_8888);
                Log.i(TAG, "картинка обработана за " + (System.currentTimeMillis() - t0) + " мс, PNG " + png.length + " байт");
                return new Object[]{png, bm};
            } catch (Throwable t) {
                Log.e(TAG, "process failed", t);
                return t;
            }
        }

        @Override
        protected void onPostExecute(Object r) {
            if (gen != generation) {
                if (r instanceof Object[]) {
                    ((Bitmap) ((Object[]) r)[1]).recycle();
                }
                return;
            }
            if (r instanceof Throwable) {
                pngReady = null;
                fileName.setText("Файл: " + new File(path).getName());
                setBusy(false);
                showError("Не удалось обработать картинку: " + r);
                return;
            }
            Object[] arr = (Object[]) r;
            pngReady = (byte[]) arr[0];
            setPreview((Bitmap) arr[1]);
            fileName.setText("Файл: " + new File(path).getName() + "\nТак заставка будет выглядеть в руках:");
            setBusy(false);
        }
    }

    private final class ManualTask extends AsyncTask<Void, Void, String> {
        private final boolean install;
        private final byte[] png;
        private final String name;

        ManualTask(boolean install, byte[] png, String name) {
            this.install = install;
            this.png = png;
            this.name = name;
        }

        @Override
        protected String doInBackground(Void... params) {
            try {
                if (install) {
                    AdvtLink.write(MainActivity.this, png);
                    return AdvtLink.ensure(MainActivity.this);
                }
                return AdvtLink.remove(MainActivity.this);
            } catch (Throwable t) {
                Log.e(TAG, "manual task failed", t);
                return "Ошибка: " + t;
            }
        }

        @Override
        protected void onPostExecute(String err) {
            setBusy(false);
            if (err == null) {
                startService(new Intent(MainActivity.this, CoverService.class).setAction(CoverService.ACT_DISABLE));
                setAutoChecked(false);
                if (install) {
                    Prefs.setMode(MainActivity.this, Prefs.MODE_MANUAL);
                    prefs.edit().putString("name", name).commit();
                    Toast.makeText(MainActivity.this, "Заставка установлена. Усыпите книгу, чтобы увидеть.", Toast.LENGTH_LONG).show();
                } else {
                    Prefs.setMode(MainActivity.this, Prefs.MODE_OFF);
                    prefs.edit().remove("name").remove("auto_key").remove("auto_error").commit();
                    new File(getFilesDir(), "preview.png").delete();
                    if (selectedPath == null) {
                        setPreview(null);
                    }
                    Toast.makeText(MainActivity.this, "Возвращены стандартные картинки.", Toast.LENGTH_LONG).show();
                }
            } else {
                showError(err);
            }
            applyModeUi();
            updateStatus();
        }
    }
}
