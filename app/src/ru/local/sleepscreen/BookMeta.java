package ru.local.sleepscreen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Название и автор книги. Берутся из самой книги (FB2, FB2.ZIP, EPUB, MOBI/AZW/PRC), потом из базы Onyx, и только в самом
 * крайнем случае из имени файла (с заменой «мусорных» имён вроде eODaC3S7 на «Без названия»). Чистый Java, без Android.
 */
public final class BookMeta {
    public String title = "";
    public String authors = "";

    private static final long MAX_HEAD = 600L * 1024;
    private static final String NO_TITLE = "Без названия";

    private BookMeta() {
    }

    /** Итоговые название и автор для заставки. dbTitle/dbAuthors — то, что знает база Onyx (могут быть null). */
    public static BookMeta resolve(File book, String dbTitle, String dbAuthors) {
        BookMeta fromFile = read(book);
        BookMeta r = new BookMeta();
        r.title = clean(fromFile.title);
        r.authors = cleanAuthors(fromFile.authors);
        if (r.title.length() == 0) {
            String t = clean(dbTitle);
            if (t.length() > 0 && !sameAsFileName(t, book)) {
                r.title = t;
            }
        }
        if (r.authors.length() == 0) {
            r.authors = cleanAuthors(dbAuthors);
        }
        if (r.title.length() == 0) {
            // по имени файла: «Автор - Название.fb2» разбираем на части, остальное показываем как есть, кроме мусорных имён
            String name = baseName(book.getName());
            Matcher m = Pattern.compile("^(.{2,80}?)\\s+[-\u2013\u2014]\\s+(.{2,})$").matcher(name);
            if (m.matches()) {
                if (r.authors.length() == 0) {
                    r.authors = cleanAuthors(m.group(1));
                }
                r.title = m.group(2).trim();
            } else {
                r.title = name;
            }
            if (looksRandom(r.title)) {
                r.title = NO_TITLE;
            }
        }
        return r;
    }

    /** Читает название/автора из файла книги; пустые строки, если не получилось или формат не поддержан. */
    public static BookMeta read(File book) {
        BookMeta m = new BookMeta();
        try {
            String n = book.getName().toLowerCase(Locale.ENGLISH);
            if (n.endsWith(".fb2")) {
                fb2(m, head(new FileInputStream(book)));
            } else if (n.endsWith(".zip")) {
                ZipFile zf = new ZipFile(book);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.getName().toLowerCase(Locale.ENGLISH).endsWith(".fb2")) {
                            fb2(m, head(zf.getInputStream(e)));
                            break;
                        }
                    }
                } finally {
                    zf.close();
                }
            } else if (n.endsWith(".epub")) {
                epub(m, book);
            } else if (n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3") || n.endsWith(".prc")) {
                mobi(m, book);
            }
        } catch (Throwable t) {
            // метаданных нет: вызывающий код возьмёт данные из базы Onyx или имени файла
        }
        return m;
    }

    // ---------------------------------------------------------------- FB2
    static void fb2(BookMeta m, byte[] data) throws IOException {
        if (data == null || data.length < 50) {
            return;
        }
        String enc = "UTF-8";
        String prolog = new String(data, 0, Math.min(data.length, 300), "ISO-8859-1");
        Matcher em = Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(prolog);
        if (em.find()) {
            enc = em.group(1);
        }
        Charset cs;
        try {
            cs = Charset.forName(enc);
        } catch (Throwable t) {
            cs = Charset.forName("UTF-8");
        }
        String text = new String(data, cs);
        int ti = indexOfIgnoreCase(text, "<title-info");
        if (ti < 0) {
            return;
        }
        int te = indexOfIgnoreCase(text, "</title-info", ti);
        String info = text.substring(ti, te < 0 ? Math.min(text.length(), ti + 20000) : te);
        Matcher bt = Pattern.compile("<book-title[^>]*>(.*?)</book-title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(info);
        if (bt.find()) {
            m.title = clean(stripTags(bt.group(1)));
        }
        Matcher au = Pattern.compile("<author[^>]*>(.*?)</author>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(info);
        StringBuilder sb = new StringBuilder();
        while (au.find()) {
            String a = au.group(1);
            String first = tag(a, "first-name"), middle = tag(a, "middle-name"), last = tag(a, "last-name"), nick = tag(a, "nickname");
            String full = join(first, middle, last);
            if (full.length() == 0) {
                full = nick;
            }
            if (full.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(full);
            }
        }
        m.authors = sb.toString();
    }

    private static String tag(String s, String name) {
        Matcher m = Pattern.compile("<" + name + "[^>]*>(.*?)</" + name + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        return m.find() ? clean(stripTags(m.group(1))) : "";
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && p.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- EPUB
    static void epub(BookMeta m, File f) throws IOException {
        ZipFile zf = new ZipFile(f);
        try {
            String container = readText(zf, "META-INF/container.xml");
            if (container == null) {
                return;
            }
            Matcher cm = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']").matcher(container);
            if (!cm.find()) {
                return;
            }
            String opf = readText(zf, cm.group(1));
            if (opf == null) {
                return;
            }
            Matcher t = Pattern.compile("<dc:title[^>]*>(.*?)</dc:title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(opf);
            if (t.find()) {
                m.title = clean(stripTags(t.group(1)));
            }
            Matcher c = Pattern.compile("<dc:creator[^>]*>(.*?)</dc:creator>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(opf);
            StringBuilder sb = new StringBuilder();
            while (c.find()) {
                String a = clean(stripTags(c.group(1)));
                if (a.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(a);
                }
            }
            m.authors = sb.toString();
        } finally {
            zf.close();
        }
    }

    // ---------------------------------------------------------------- MOBI / AZW / PRC
    /** Структура PalmDB + заголовок MOBI + блок EXTH: тип 100 — автор, 503 — название, иначе полное имя из заголовка. */
    static void mobi(BookMeta m, File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] rec0 = mobiRecord(raf, 0, 1 << 20);
            if (rec0 == null || rec0.length < 132 || rec0[16] != 'M' || rec0[17] != 'O' || rec0[18] != 'B' || rec0[19] != 'I') {
                return;
            }
            int hlen = be32(rec0, 20);
            int encoding = be32(rec0, 28);
            String csName = encoding == 1252 ? "windows-1252" : "UTF-8";
            int nameOff = be32(rec0, 84), nameLen = be32(rec0, 88);
            String title = "";
            StringBuilder authors = new StringBuilder();
            int exthFlags = be32(rec0, 128);
            int exth = 16 + hlen;
            if ((exthFlags & 0x40) != 0 && exth + 12 <= rec0.length && rec0[exth] == 'E' && rec0[exth + 1] == 'X') {
                int count = be32(rec0, exth + 8);
                int p = exth + 12;
                for (int i = 0; i < count && p + 8 <= rec0.length; i++) {
                    int type = be32(rec0, p), len = be32(rec0, p + 4);
                    if (len < 8 || p + len > rec0.length) {
                        break;
                    }
                    String val = new String(rec0, p + 8, len - 8, csName);
                    if (type == 503 && title.length() == 0) {
                        title = clean(val);
                    } else if (type == 100) {
                        String a = clean(val);
                        if (a.length() > 0) {
                            if (authors.length() > 0) {
                                authors.append(", ");
                            }
                            authors.append(a);
                        }
                    }
                    p += len;
                }
            }
            if (title.length() == 0 && nameOff > 0 && nameLen > 0 && nameOff + nameLen <= rec0.length) {
                title = clean(new String(rec0, nameOff, nameLen, csName));
            }
            m.title = title;
            m.authors = authors.toString();
        } finally {
            raf.close();
        }
    }

    /** Запись номер idx из PalmDB-файла (не больше maxLen байт) или null. */
    static byte[] mobiRecord(RandomAccessFile raf, int idx, int maxLen) throws IOException {
        if (raf.length() < 100) {
            return null;
        }
        byte[] hdr = new byte[8];
        raf.seek(76);
        raf.readFully(hdr, 0, 2);
        int num = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
        if (idx < 0 || idx >= num) {
            return null;
        }
        raf.seek(78 + 8L * idx);
        raf.readFully(hdr, 0, 4);
        long start = be32u(hdr, 0);
        long end;
        if (idx + 1 < num) {
            raf.seek(78 + 8L * (idx + 1));
            raf.readFully(hdr, 0, 4);
            end = be32u(hdr, 0);
        } else {
            end = raf.length();
        }
        if (start < 0 || end <= start || end > raf.length()) {
            return null;
        }
        long len = Math.min(end - start, maxLen);
        byte[] out = new byte[(int) len];
        raf.seek(start);
        raf.readFully(out);
        return out;
    }

    static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static long be32u(byte[] b, int o) {
        return be32(b, o) & 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------- очистка и запасные варианты
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = unescape(s).replace('\u00a0', ' ').replaceAll("[\\s\\u200b]+", " ").trim();
        if (t.equalsIgnoreCase("null")) {
            return "";
        }
        return t;
    }

    /** Авторы без заглушек вроде «Unknown» / «Неизвестен». */
    static String cleanAuthors(String s) {
        String t = clean(s);
        String l = t.toLowerCase(Locale.ROOT);
        if (l.length() == 0 || l.equals("unknown") || l.equals("<unknown>") || l.equals("n/a") || l.equals("none") || l.equals("-")
                || l.startsWith("неизвестн") || l.startsWith("автор неизвестен") || l.equals("не указан") || l.equals("anonymous")) {
            return "";
        }
        return t;
    }

    /** Название совпадает с именем файла (без расширения): считаем, что настоящего названия база не знает. */
    static boolean sameAsFileName(String title, File book) {
        String a = norm(title), b = norm(baseName(book.getName()));
        return a.length() > 0 && a.equals(b);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String baseName(String fileName) {
        String n = fileName;
        String l = n.toLowerCase(Locale.ENGLISH);
        if (l.endsWith(".fb2.zip")) {
            n = n.substring(0, n.length() - 8);
        } else {
            int dot = n.lastIndexOf('.');
            if (dot > 0) {
                n = n.substring(0, dot);
            }
        }
        return n.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Похоже на случайное имя файла: одни цифры или одно «слово» с перемешанными буквами и цифрами. */
    static boolean looksRandom(String s) {
        String t = s.trim();
        if (t.length() == 0) {
            return true;
        }
        if (t.matches("[0-9 ._-]+")) {
            return true;
        }
        if (t.indexOf(' ') < 0 && t.length() >= 5 && t.matches(".*[0-9].*") && t.matches(".*\\p{L}.*")) {
            return true;
        }
        return false;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", " ");
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        String r = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
        Matcher m = Pattern.compile("&#(x[0-9A-Fa-f]+|[0-9]+);").matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1);
            int cp;
            try {
                cp = g.charAt(0) == 'x' ? Integer.parseInt(g.substring(1), 16) : Integer.parseInt(g);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    private static int indexOfIgnoreCase(String s, String what) {
        return indexOfIgnoreCase(s, what, 0);
    }

    private static int indexOfIgnoreCase(String s, String what, int from) {
        return s.toLowerCase(Locale.ENGLISH).indexOf(what.toLowerCase(Locale.ENGLISH), from);
    }

    private static byte[] head(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while (total < MAX_HEAD && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String readText(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry c = en.nextElement();
                if (c.getName().equalsIgnoreCase(name)) {
                    e = c;
                    break;
                }
            }
        }
        if (e == null) {
            return null;
        }
        InputStream in = zf.getInputStream(e);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0 && total < 4L * 1024 * 1024) {
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
