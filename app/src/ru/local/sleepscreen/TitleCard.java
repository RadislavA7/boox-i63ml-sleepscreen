package ru.local.sleepscreen;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * Карточка книги 758x1024: рамка, (необязательно) маленькая обложка и крупное название с автором.
 * Используется, когда у книги нет обложки или обложка слишком мала для полного экрана.
 */
final class TitleCard {
    private TitleCard() {
    }

    /** Возвращает серый портрет PW*PH байт. */
    static byte[] render(String title, String authors, Bitmap cover) {
        final int W = ImageProc.PW, H = ImageProc.PH;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.drawColor(Color.WHITE);

        Paint line = new Paint();
        line.setColor(Color.BLACK);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(10f);
        cv.drawRect(new RectF(26, 26, W - 26, H - 26), line);
        line.setStrokeWidth(2f);
        cv.drawRect(new RectF(44, 44, W - 44, H - 44), line);

        int top = 90;
        int bottomLimit = H - 90;
        if (cover != null && cover.getWidth() > 0 && cover.getHeight() > 0) {
            int boxW = W - 2 * 130;
            int boxH = (int) (H * 0.50);
            double s = Math.min((double) boxW / cover.getWidth(), (double) boxH / cover.getHeight());
            s = Math.min(s, 4.0);
            int cw = (int) Math.round(cover.getWidth() * s);
            int ch = (int) Math.round(cover.getHeight() * s);
            int cx = (W - cw) / 2;
            Paint pf = new Paint();
            pf.setFilterBitmap(true);
            cv.drawBitmap(cover, null, new RectF(cx, top, cx + cw, top + ch), pf);
            line.setStrokeWidth(3f);
            cv.drawRect(new RectF(cx, top, cx + cw, top + ch), line);
            top += ch + 50;
        }

        int textW = W - 2 * 100;
        int avail = bottomLimit - top;
        String t = title == null ? "" : title;
        String a = authors == null ? "" : authors;

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        TextPaint ap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        ap.setColor(Color.BLACK);
        ap.setTypeface(Typeface.DEFAULT);

        float size = 80f;
        StaticLayout tl = null, al = null;
        int total = 0;
        for (; size >= 26f; size -= 4f) {
            tp.setTextSize(size);
            ap.setTextSize(size * 0.62f);
            tl = new StaticLayout(t, tp, textW, Layout.Alignment.ALIGN_CENTER, 1.05f, 0f, false);
            al = a.length() > 0 ? new StaticLayout(a, ap, textW, Layout.Alignment.ALIGN_CENTER, 1.05f, 0f, false) : null;
            total = tl.getHeight() + (al != null ? 36 + al.getHeight() : 0);
            if (total <= avail) {
                break;
            }
        }
        int y = top + Math.max(0, (avail - total) / 2);
        cv.save();
        cv.translate(100, y);
        tl.draw(cv);
        cv.restore();
        if (al != null) {
            cv.save();
            cv.translate(100, y + tl.getHeight() + 36);
            al.draw(cv);
            cv.restore();
        }

        int[] px = new int[W * H];
        bm.getPixels(px, 0, W, 0, 0, W, H);
        bm.recycle();
        byte[] out = new byte[W * H];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            out[i] = (byte) ((((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000);
        }
        return out;
    }
}
