package ru.local.sleepscreen;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Простой выбор картинки: папки и файлы jpg/png/bmp/gif. Начинает с папки Screensaver на книге. */
public class PickActivity extends ListActivity {
    private static final String[] EXT = {".jpg", ".jpeg", ".png", ".bmp", ".gif"};

    private TextView pathView;
    private File dir;
    private boolean hasParent;
    private final List<File> entries = new ArrayList<File>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pick);
        pathView = (TextView) findViewById(R.id.path);

        File start = null;
        String s = getIntent().getStringExtra("start");
        if (s != null && new File(s).isDirectory()) {
            start = new File(s);
        }
        if (start == null && new File("/mnt/storage/Screensaver").isDirectory()) {
            start = new File("/mnt/storage/Screensaver");
        }
        if (start == null && new File("/mnt/storage").isDirectory()) {
            start = new File("/mnt/storage");
        }
        if (start == null) {
            start = Environment.getExternalStorageDirectory();
        }
        show(start);
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ENGLISH);
        for (String e : EXT) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    private void show(File d) {
        dir = d;
        pathView.setText(d.getAbsolutePath());
        entries.clear();
        List<String> labels = new ArrayList<String>();
        File parent = d.getParentFile();
        hasParent = parent != null;
        if (hasParent) {
            entries.add(parent);
            labels.add("[..]  Вверх");
        }
        File[] fs = d.listFiles();
        if (fs == null) {
            Toast.makeText(this, "Нет доступа к папке", Toast.LENGTH_SHORT).show();
        } else {
            List<File> dirs = new ArrayList<File>();
            List<File> files = new ArrayList<File>();
            for (File f : fs) {
                if (f.getName().startsWith(".")) {
                    continue;
                }
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (isImage(f.getName())) {
                    files.add(f);
                }
            }
            Comparator<File> byName = new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            };
            Collections.sort(dirs, byName);
            Collections.sort(files, byName);
            for (File f : dirs) {
                entries.add(f);
                labels.add("[папка]  " + f.getName());
            }
            for (File f : files) {
                entries.add(f);
                labels.add(f.getName() + "   (" + Math.max(1, f.length() / 1024) + " КБ)");
            }
            if (files.isEmpty() && dirs.isEmpty()) {
                Toast.makeText(this, "В этой папке нет картинок", Toast.LENGTH_SHORT).show();
            }
        }
        setListAdapter(new ArrayAdapter<String>(this, R.layout.row, labels));
        getListView().setSelection(0);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        File f = entries.get(position);
        if (f.isDirectory()) {
            show(f);
        } else {
            Intent i = new Intent();
            i.putExtra("path", f.getAbsolutePath());
            i.putExtra("dir", dir.getAbsolutePath());
            setResult(RESULT_OK, i);
            finish();
        }
    }
}
