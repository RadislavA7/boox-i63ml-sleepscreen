import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import javax.imageio.ImageIO;

import ru.local.sleepscreen.ImageProc;

/** Проверка ядра ImageProc на ПК: java ProcTest <вход> <выход.png> <fill|fit> [nocontrast] */
public class ProcTest {
    public static void main(String[] a) throws Exception {
        BufferedImage im = ImageIO.read(new File(a[0]));
        int w = im.getWidth(), h = im.getHeight();
        byte[] g = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = im.getRGB(x, y);
                int al = c >>> 24, r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, b = c & 0xFF;
                if (al < 255) {
                    r = (r * al + 255 * (255 - al)) / 255;
                    gg = (gg * al + 255 * (255 - al)) / 255;
                    b = (b * al + 255 * (255 - al)) / 255;
                }
                g[y * w + x] = (byte) ((r * 299 + gg * 587 + b * 114) / 1000);
            }
        }
        boolean fill = a[2].equals("fill");
        boolean contrast = !(a.length > 3 && a[3].equals("nocontrast"));
        long t0 = System.currentTimeMillis();
        byte[] portrait = ImageProc.toPortrait(new ImageProc.Gray(g, w, h), fill, contrast);
        byte[] stored = ImageProc.rotateCwToStored(portrait);
        byte[] png = ImageProc.encodePng(stored);
        long t1 = System.currentTimeMillis();
        FileOutputStream fos = new FileOutputStream(a[1]);
        fos.write(png);
        fos.close();
        // портрет для просмотра
        BufferedImage pv = new BufferedImage(ImageProc.PW, ImageProc.PH, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < ImageProc.PH; y++) {
            for (int x = 0; x < ImageProc.PW; x++) {
                int v = portrait[y * ImageProc.PW + x] & 0xFF;
                pv.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(pv, "png", new File(a[1] + ".portrait.png"));
        System.out.println("ok " + a[0] + " " + w + "x" + h + " -> png " + png.length + " bytes, " + (t1 - t0) + " ms");
    }
}
