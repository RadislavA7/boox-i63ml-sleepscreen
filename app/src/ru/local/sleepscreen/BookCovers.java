package ru.local.sleepscreen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Извлечение полноразмерной обложки из файла книги (FB2, FB2.ZIP, EPUB). Чистый Java, без Android. */
public final class BookCovers {
    private static final long MAX_BOOK = 64L * 1024 * 1024;
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    private BookCovers() {
    }

    /** Возвращает байты картинки (jpeg/png/gif) или null, если обложки нет. */
    public static byte[] find(File book) {
        try {
            String n = book.getName().toLowerCase(Locale.ENGLISH);
            if (n.endsWith(".fb2")) {
                return fromFb2(readAll(new FileInputStream(book), book.length()));
            }
            if (n.endsWith(".fb2.zip") || n.endsWith(".zip")) {
                ZipFile zf = new ZipFile(book);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.getName().toLowerCase(Locale.ENGLISH).endsWith(".fb2")) {
                            return fromFb2(readAll(zf.getInputStream(e), e.getSize()));
                        }
                    }
                } finally {
                    zf.close();
                }
                return null;
            }
            if (n.endsWith(".epub")) {
                return fromEpub(book);
            }
            if (n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3") || n.endsWith(".prc")) {
                return fromMobi(book);
            }
        } catch (Throwable t) {
            // нет обложки: вызывающий код подберёт запасной вариант
        }
        return null;
    }

    // ---------------------------------------------------------------- FB2
    static byte[] fromFb2(byte[] data) throws IOException {
        if (data == null || data.length < 100) {
            return null;
        }
        String head = new String(data, 0, Math.min(data.length, 400000), "ISO-8859-1");
        String id = null;
        int cp = head.toLowerCase(Locale.ENGLISH).indexOf("<coverpage");
        if (cp >= 0) {
            int end = head.toLowerCase(Locale.ENGLISH).indexOf("</coverpage", cp);
            if (end < 0) {
                end = Math.min(head.length(), cp + 2000);
            }
            Matcher m = Pattern.compile("href\\s*=\\s*[\"']#?([^\"']+)[\"']").matcher(head.substring(cp, end));
            if (m.find()) {
                id = m.group(1);
            }
        }
        byte[] tag = "<binary".getBytes("ISO-8859-1");
        byte[] close = "</binary".getBytes("ISO-8859-1");
        byte[] guess = null;   // запасной вариант: бинарник с "cover" в id
        int pos = 0;
        while (true) {
            int s = indexOf(data, tag, pos, true);
            if (s < 0) {
                break;
            }
            int gt = indexOf(data, new byte[]{'>'}, s, false);
            if (gt < 0) {
                break;
            }
            String attrs = new String(data, s + tag.length, gt - s - tag.length, "ISO-8859-1");
            String bid = attr(attrs, "id");
            int e = indexOf(data, close, gt, true);
            if (e < 0) {
                e = data.length;
            }
            boolean want = id != null && bid != null && (bid.equals(id) || bid.equalsIgnoreCase(id));
            boolean coverLike = id == null && bid != null && bid.toLowerCase(Locale.ENGLISH).contains("cover");
            if (want || coverLike) {
                byte[] img = Base64Lite.decode(data, gt + 1, e - gt - 1);
                if (isImage(img)) {
                    if (want) {
                        return img;
                    }
                    if (guess == null) {
                        guess = img;
                    }
                }
            }
            pos = e + close.length;
        }
        return guess;
    }

    private static String attr(String attrs, String name) {
        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) {
                return m.group(3) != null ? m.group(3) : m.group(4);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- MOBI / AZW / PRC
    /** Обложка из MOBI: блок EXTH, запись 201 — смещение обложки среди картинок (отсчёт от «первой картинки» из заголовка MOBI). */
    static byte[] fromMobi(File f) throws IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            byte[] rec0 = BookMeta.mobiRecord(raf, 0, 1 << 20);
            if (rec0 == null || rec0.length < 132 || rec0[16] != 'M' || rec0[17] != 'O' || rec0[18] != 'B' || rec0[19] != 'I') {
                return null;
            }
            int hlen = BookMeta.be32(rec0, 20);
            int firstImage = BookMeta.be32(rec0, 108);
            int exthFlags = BookMeta.be32(rec0, 128);
            int exth = 16 + hlen;
            if ((exthFlags & 0x40) == 0 || exth + 12 > rec0.length || rec0[exth] != 'E' || rec0[exth + 1] != 'X') {
                return null;
            }
            int count = BookMeta.be32(rec0, exth + 8);
            int cover = -1, thumb = -1;
            int p = exth + 12;
            for (int i = 0; i < count && p + 8 <= rec0.length; i++) {
                int type = BookMeta.be32(rec0, p), len = BookMeta.be32(rec0, p + 4);
                if (len < 8 || p + len > rec0.length) {
                    break;
                }
                if (len >= 12 && type == 201) {
                    cover = BookMeta.be32(rec0, p + 8);
                } else if (len >= 12 && type == 202) {
                    thumb = BookMeta.be32(rec0, p + 8);
                }
                p += len;
            }
            int[] wanted = {cover, thumb};
            for (int w : wanted) {
                if (w < 0 || firstImage <= 0) {
                    continue;
                }
                byte[] img = BookMeta.mobiRecord(raf, firstImage + w, 16 << 20);
                if (isImage(img)) {
                    return img;
                }
            }
            return null;
        } finally {
            raf.close();
        }
    }

    // ---------------------------------------------------------------- EPUB
    static byte[] fromEpub(File f) throws IOException {
        ZipFile zf = new ZipFile(f);
        try {
            String container = readText(zf, "META-INF/container.xml");
            if (container == null) {
                return null;
            }
            Matcher cm = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']").matcher(container);
            if (!cm.find()) {
                return null;
            }
            String opfPath = cm.group(1);
            String opf = readText(zf, opfPath);
            if (opf == null) {
                return null;
            }
            String base = dirOf(opfPath);

            String coverId = null;
            String guideHref = null;
            Map<String, String[]> items = new HashMap<String, String[]>();   // id -> {href, media, props}
            java.util.List<String[]> itemList = new java.util.ArrayList<String[]>();
            Matcher tm = Pattern.compile("<(meta|item|reference)\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(opf);
            while (tm.find()) {
                String kind = tm.group(1).toLowerCase(Locale.ENGLISH);
                String attrs = tm.group(2);
                if (kind.equals("meta")) {
                    String name = attr(attrs, "name");
                    if (name != null && name.equalsIgnoreCase("cover")) {
                        coverId = attr(attrs, "content");
                    }
                } else if (kind.equals("item")) {
                    String[] it = {attr(attrs, "href"), attr(attrs, "media-type"), attr(attrs, "properties"), attr(attrs, "id")};
                    itemList.add(it);
                    if (it[3] != null) {
                        items.put(it[3], it);
                    }
                } else {
                    String type = attr(attrs, "type");
                    if (type != null && type.equalsIgnoreCase("cover")) {
                        guideHref = attr(attrs, "href");
                    }
                }
            }

            String href = null;
            if (coverId != null && items.containsKey(coverId) && isImageMedia(items.get(coverId))) {
                href = items.get(coverId)[0];
            }
            if (href == null) {
                for (String[] it : itemList) {
                    if (it[2] != null && it[2].toLowerCase(Locale.ENGLISH).contains("cover-image")) {
                        href = it[0];
                        break;
                    }
                }
            }
            if (href == null && guideHref != null) {
                String g = stripFragment(guideHref);
                if (looksImage(g)) {
                    href = g;
                } else {
                    String page = readText(zf, resolve(base, g));
                    if (page != null) {
                        Matcher im = Pattern.compile("<(?:img|image)\\b[^>]*?(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(page);
                        if (im.find()) {
                            href = resolve(dirOf(g), im.group(1));
                        }
                    }
                }
            }
            if (href == null) {
                for (String[] it : itemList) {
                    String idh = ((it[3] == null ? "" : it[3]) + " " + (it[0] == null ? "" : it[0])).toLowerCase(Locale.ENGLISH);
                    if (isImageMedia(it) && idh.contains("cover")) {
                        href = it[0];
                        break;
                    }
                }
            }
            if (href == null) {
                return null;
            }
            byte[] img = readEntry(zf, resolve(base, href));
            return isImage(img) ? img : null;
        } finally {
            zf.close();
        }
    }

    private static boolean isImageMedia(String[] it) {
        return it != null && ((it[1] != null && it[1].toLowerCase(Locale.ENGLISH).startsWith("image/")) || (it[0] != null && looksImage(it[0])));
    }

    private static boolean looksImage(String h) {
        String s = h.toLowerCase(Locale.ENGLISH);
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".gif");
    }

    private static String stripFragment(String h) {
        int i = h.indexOf('#');
        return i >= 0 ? h.substring(0, i) : h;
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    /** Склеивает путь относительно каталога и разворачивает ../ и %xx. */
    static String resolve(String baseDir, String rel) {
        String r = rel;
        try {
            r = URLDecoder.decode(rel.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        String full = (r.startsWith("/") || baseDir.length() == 0) ? r : baseDir + "/" + r;
        java.util.ArrayList<String> parts = new java.util.ArrayList<String>();
        for (String p : full.split("/")) {
            if (p.length() == 0 || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String readText(ZipFile zf, String name) throws IOException {
        byte[] b = readEntry(zf, name);
        return b == null ? null : new String(b, "UTF-8");
    }

    private static byte[] readEntry(ZipFile zf, String name) throws IOException {
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
        return readAll(zf.getInputStream(e), e.getSize());
    }

    // ---------------------------------------------------------------- утилиты
    static boolean isImage(byte[] b) {
        if (b == null || b.length < 16) {
            return false;
        }
        return ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8)
                || ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
                || (b[0] == 'G' && b[1] == 'I' && b[2] == 'F');
    }

    private static byte[] readAll(InputStream in, long hint) throws IOException {
        if (hint > MAX_BOOK) {
            in.close();
            throw new IOException("слишком большой файл");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(hint > 0 && hint < MAX_BOOK ? (int) hint : 65536);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > MAX_BOOK) {
                    throw new IOException("слишком большой файл");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static int indexOf(byte[] d, byte[] p, int from, boolean ignoreCase) {
        int last = d.length - p.length;
        outer:
        for (int i = Math.max(0, from); i <= last; i++) {
            for (int j = 0; j < p.length; j++) {
                byte a = d[i + j], b = p[j];
                if (a != b) {
                    if (!ignoreCase) {
                        continue outer;
                    }
                    if (a >= 'A' && a <= 'Z') {
                        a += 32;
                    }
                    if (b >= 'A' && b <= 'Z') {
                        b += 32;
                    }
                    if (a != b) {
                        continue outer;
                    }
                }
            }
            return i;
        }
        return -1;
    }
}
