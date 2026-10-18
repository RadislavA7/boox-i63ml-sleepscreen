import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import javax.imageio.ImageIO;

import ru.local.sleepscreen.BookCovers;

/** Проверка извлечения обложек на ПК: java CoverTest <папка с книгами> <папка для обложек> */
public class CoverTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File out = new File(a[1]);
        out.mkdirs();
        File[] files = dir.listFiles();
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            byte[] img = BookCovers.find(f);
            long t1 = System.currentTimeMillis();
            if (img == null) {
                System.out.println(String.format("%-60s  НЕТ ОБЛОЖКИ (%d мс)", f.getName(), t1 - t0));
                continue;
            }
            String ext = (img[0] & 0xFF) == 0xFF ? "jpg" : ((img[0] & 0xFF) == 0x89 ? "png" : "gif");
            File o = new File(out, f.getName() + "." + ext);
            FileOutputStream fos = new FileOutputStream(o);
            fos.write(img);
            fos.close();
            String dim = "?";
            try {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img));
                dim = bi.getWidth() + "x" + bi.getHeight();
            } catch (Throwable t) {
                dim = "не читается ImageIO";
            }
            System.out.println(String.format("%-60s  %-4s %8d байт  %-10s (%d мс)", f.getName(), ext, img.length, dim, t1 - t0));
        }
    }
}
