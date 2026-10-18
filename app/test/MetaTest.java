import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import ru.local.sleepscreen.BookCovers;
import ru.local.sleepscreen.BookMeta;

/**
 * Проверка разбора названия/автора на ПК.
 * java MetaTest <папка с книгами> : для каждой книги печатает, что нашлось в файле и что получится итогом;
 * затем прогоняет искусственные случаи (заглушки «Unknown», мусорные имена файлов, синтетический MOBI).
 */
public class MetaTest {
    public static void main(String[] a) throws Exception {
        if (a.length > 0) {
            File[] files = new File(a[0]).listFiles();
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                BookMeta m = BookMeta.read(f);
                BookMeta r = BookMeta.resolve(f, null, null);
                System.out.println(String.format("%-46s | файл: [%s] [%s]", f.getName(), m.title, m.authors));
                System.out.println(String.format("%-46s | итог: [%s] [%s]", "", r.title, r.authors));
            }
        }
        System.out.println("---- искусственные случаи");
        File tmp = new File(System.getProperty("java.io.tmpdir"), "metatest");
        tmp.mkdirs();
        check("база знает всё", touch(tmp, "x1.txt"), "Война и мир", "Толстой Лев", "Война и мир", "Толстой Лев");
        check("автор Unknown", touch(tmp, "x2.txt"), "Важенка", "Unknown", "Важенка", "");
        check("название = имя файла", touch(tmp, "Иван Тургенев - Отцы и дети.txt"), "Иван Тургенев - Отцы и дети", "", "Отцы и дети", "Иван Тургенев");
        check("мусорное имя", touch(tmp, "eODaC3S7.pdf"), null, null, "Без названия", "");
        check("только цифры", touch(tmp, "294148.txt"), "", "", "Без названия", "");
        check("нормальное имя без автора", touch(tmp, "Записки охотника.txt"), null, null, "Записки охотника", "");
        check("подчёркивания", touch(tmp, "Мастер_и_Маргарита.txt"), null, null, "Мастер и Маргарита", "");

        // синтетический MOBI: PalmDB + запись 0 (PalmDOC 16 + MOBI 232 + EXTH) + запись-картинка
        byte[] img = new byte[40];
        img[0] = (byte) 0xFF;
        img[1] = (byte) 0xD8;
        File mobi = new File(tmp, "synthetic.mobi");
        writeMobi(mobi, "Короткое имя", "Полное название из EXTH", "Автор Первый", img);
        BookMeta mm = BookMeta.read(mobi);
        System.out.println("MOBI: название [" + mm.title + "] автор [" + mm.authors + "] (ожидалось: Полное название из EXTH / Автор Первый)");
        byte[] cover = BookCovers.find(mobi);
        System.out.println("MOBI: обложка " + (cover == null ? "НЕ НАЙДЕНА" : cover.length + " байт, первые байты " + (cover[0] & 0xFF) + "," + (cover[1] & 0xFF)));
    }

    static File touch(File dir, String name) throws Exception {
        File f = new File(dir, name);
        new FileOutputStream(f).close();
        return f;
    }

    static void check(String label, File f, String dbTitle, String dbAuthors, String wantTitle, String wantAuthors) {
        BookMeta r = BookMeta.resolve(f, dbTitle, dbAuthors);
        boolean ok = r.title.equals(wantTitle) && r.authors.equals(wantAuthors);
        System.out.println((ok ? "OK    " : "ОШИБКА") + " " + label + ": [" + r.title + "] [" + r.authors + "]"
                + (ok ? "" : "  (ожидалось [" + wantTitle + "] [" + wantAuthors + "])"));
    }

    static void writeMobi(File out, String shortName, String fullTitle, String author, byte[] image) throws Exception {
        byte[] t503 = fullTitle.getBytes("UTF-8");
        byte[] t100 = author.getBytes("UTF-8");
        ByteArrayOutputStream exth = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(exth);
        d.writeBytes("EXTH");
        int len = 12 + (8 + t100.length) + (8 + t503.length) + 12;
        d.writeInt(len);
        d.writeInt(3);
        d.writeInt(100);
        d.writeInt(8 + t100.length);
        d.write(t100);
        d.writeInt(503);
        d.writeInt(8 + t503.length);
        d.write(t503);
        d.writeInt(201);
        d.writeInt(12);
        d.writeInt(0);   // смещение обложки среди картинок
        byte[] ex = exth.toByteArray();

        byte[] rec0 = new byte[16 + 232 + ex.length + 64];
        // PalmDOC заголовок (16 байт) нулевой; MOBI заголовок с 16
        rec0[16] = 'M';
        rec0[17] = 'O';
        rec0[18] = 'B';
        rec0[19] = 'I';
        put32(rec0, 20, 232);
        put32(rec0, 28, 65001);
        put32(rec0, 84, 16 + 232 + ex.length);   // смещение полного имени
        byte[] fn = shortName.getBytes("UTF-8");
        put32(rec0, 88, fn.length);
        put32(rec0, 108, 2);   // индекс первой картинки: запись 2
        put32(rec0, 128, 0x40);   // есть EXTH
        System.arraycopy(ex, 0, rec0, 16 + 232, ex.length);
        System.arraycopy(fn, 0, rec0, 16 + 232 + ex.length, fn.length);

        int nrec = 3;   // 0 = заголовок, 1 = «текст», 2 = картинка
        byte[] rec1 = new byte[10];
        int base = 78 + 8 * nrec + 2;
        DataOutputStream o = new DataOutputStream(new FileOutputStream(out));
        byte[] name = new byte[32];
        o.write(name);
        o.write(new byte[44]);   // до смещения 76
        o.writeShort(nrec);
        int off0 = base, off1 = off0 + rec0.length, off2 = off1 + rec1.length;
        o.writeInt(off0);
        o.writeInt(0);
        o.writeInt(off1);
        o.writeInt(0);
        o.writeInt(off2);
        o.writeInt(0);
        o.writeShort(0);   // зазор
        o.write(rec0);
        o.write(rec1);
        o.write(image);
        o.close();
    }

    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }
}
