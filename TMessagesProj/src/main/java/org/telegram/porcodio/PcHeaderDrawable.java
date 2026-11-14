package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.R;

/** Stemma PP con corona e alloro, divisore verticale sottile: la testata della home (come nella foto di riferimento). */
public class PcHeaderDrawable extends Drawable {
    private final Bitmap crest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int h = dp(36);
    private final int crestW;
    private final int gap = dp(12);

    public PcHeaderDrawable(Context context) {
        crest = BitmapFactory.decodeResource(context.getResources(), R.drawable.porcodio_crest_white);
        crestW = crest == null ? 0 : (int) (h * (crest.getWidth() / (float) crest.getHeight()));
        line.setColor(0x66F3EEE6);
        line.setStrokeWidth(Math.max(1f, dp(0.6f)));
        setBounds(0, 0, getIntrinsicWidth(), h);
    }

    @Override
    public int getIntrinsicWidth() {
        return crestW + gap * 2 + dp(1);
    }

    @Override
    public int getIntrinsicHeight() {
        return h;
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        if (crest != null) {
            canvas.drawBitmap(crest, null, new android.graphics.Rect(b.left, b.top, b.left + crestW, b.top + b.height()), paint);
        }
        float x = b.left + crestW + gap;
        canvas.drawLine(x, b.top + dp(2), x, b.bottom - dp(2), line);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    /** Il colore del tema non si applica: lo stemma resta bianco caldo. */
    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
