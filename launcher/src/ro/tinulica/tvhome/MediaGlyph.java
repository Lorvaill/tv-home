package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The music card's transport symbols, drawn as shapes (a font's ⏸ looks rough on a TV):
 * previous, play, pause, next — rounded, centred, in the current colour.
 */
final class MediaGlyph extends View {

    static final int PREVIOUS = 0;
    static final int PLAY = 1;
    static final int PAUSE = 2;
    static final int NEXT = 3;

    private int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    MediaGlyph(Context context, int kind) {
        super(context);
        this.kind = kind;
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setKind(int kind) {
        if (this.kind != kind) {
            this.kind = kind;
            invalidate();
        }
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h) * 0.36f;     // the symbol's size inside the round button
        float cx = w / 2f;
        float cy = h / 2f;
        float r = s * 0.12f;                  // corner rounding
        switch (kind) {
            case PAUSE: {
                float bw = s * 0.30f;
                float gap = s * 0.26f;
                rect.set(cx - gap / 2 - bw, cy - s / 2, cx - gap / 2, cy + s / 2);
                canvas.drawRoundRect(rect, r, r, paint);
                rect.set(cx + gap / 2, cy - s / 2, cx + gap / 2 + bw, cy + s / 2);
                canvas.drawRoundRect(rect, r, r, paint);
                break;
            }
            case PLAY:
                triangle(canvas, cx - s * 0.36f, cy, s * 0.92f, s, true);
                break;
            case NEXT:
                triangle(canvas, cx - s * 0.52f, cy, s * 0.62f, s * 0.82f, true);
                triangle(canvas, cx - s * 0.02f, cy, s * 0.62f, s * 0.82f, true);
                break;
            case PREVIOUS:
            default:
                triangle(canvas, cx + s * 0.52f, cy, s * 0.62f, s * 0.82f, false);
                triangle(canvas, cx + s * 0.02f, cy, s * 0.62f, s * 0.82f, false);
                break;
        }
    }

    /** A triangle pointing right (or left) from x, with the given length and height. */
    private void triangle(Canvas canvas, float x, float cy, float length, float height, boolean right) {
        path.reset();
        float tip = right ? x + length : x - length;
        path.moveTo(x, cy - height / 2);
        path.lineTo(tip, cy);
        path.lineTo(x, cy + height / 2);
        path.close();
        canvas.drawPath(path, paint);
    }
}
