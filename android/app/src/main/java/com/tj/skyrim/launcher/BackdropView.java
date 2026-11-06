package com.tj.skyrim.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;

/**
 * The prelaunch backdrop. With a wallpaper bundled in assets (backdrop.jpg -
 * a local wallpaper file, never committed to the repo), it draws that image
 * covered, darkened, with the vignette and the left scrim
 * that keep the menu readable. Without one, it paints a Nordic night in code:
 * a slate-to-teal sky with stars, a pale moon, three aurora ribbons, three
 * layers of mountains with snow on the ridges, low fog, a vignette and the
 * same scrim. Everything is deterministic, so the scene is identical on every
 * draw.
 */
public final class BackdropView extends View {

    private Bitmap wallpaper;

    public BackdropView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
    }

    /** The bundled wallpaper, or null for the painted night. */
    public void setWallpaper(Bitmap bitmap) {
        wallpaper = bitmap;
        invalidate();
    }

    /** Decodes an asset image, sampled down to ~2048px on its long side. */
    public static Bitmap loadAsset(Context context, String name) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = context.getAssets().open(name)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (wallpaper != null) {
            drawWallpaper(canvas, w, h);
            drawVignette(canvas, w, h);
            drawMenuScrim(canvas, w, h);
            return;
        }
        drawSky(canvas, w, h);
        drawStars(canvas, w, h);
        drawMoon(canvas, w, h);
        drawAurora(canvas, w, h);
        drawMountains(canvas, w, h);
        drawFog(canvas, w, h);
        drawVignette(canvas, w, h);
        drawMenuScrim(canvas, w, h);
    }

    /* ------------------------------------------------------------------ */

    private void drawSky(Canvas c, int w, int h) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0xFF060D14, 0xFF0E1B26, 0xFF1B303C},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /** The wallpaper stretched over the whole screen, darkened so the menu
     * stays readable (the image fills the view, no letterbox or crop). */
    private void drawWallpaper(Canvas c, int w, int h) {
        Paint p = paint();
        p.setFilterBitmap(true);
        android.graphics.Rect src = new android.graphics.Rect(0, 0,
                wallpaper.getWidth(), wallpaper.getHeight());
        android.graphics.Rect dst = new android.graphics.Rect(0, 0, w, h);
        c.drawBitmap(wallpaper, src, dst, p);
        p.setColor(0x59000000);
        c.drawRect(0, 0, w, h, p);
    }

    private void drawStars(Canvas c, int w, int h) {
        Random r = new Random(7);
        Paint p = paint();
        for (int i = 0; i < 90; i++) {
            float x = r.nextFloat() * w, y = r.nextFloat() * h * 0.55f;
            int a = 40 + r.nextInt(110);
            p.setShader(null);
            p.setColor(Color.argb(a, 0xE8, 0xE6, 0xDC));
            c.drawCircle(x, y, 0.8f + r.nextFloat() * 1.4f, p);
        }
    }

    private void drawMoon(Canvas c, int w, int h) {
        float cx = w * 0.78f, cy = h * 0.20f, r = h * 0.045f;
        Paint p = paint();
        p.setShader(new RadialGradient(cx, cy, r * 7, 0x55E8E4D8, 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 7, p);
        p.setShader(null);
        p.setColor(0xFFEAE6DA);
        c.drawCircle(cx, cy, r, p);
    }

    /** Three translucent ribbons waving across the sky. */
    private void drawAurora(Canvas c, int w, int h) {
        int[] colors = {0x503FD0A0, 0x3A59C9C0, 0x2A7FD1B0};
        for (int i = 0; i < 3; i++) {
            float baseY = h * (0.16f + 0.07f * i);
            float amp = h * (0.05f + 0.02f * i);
            float band = h * (0.09f + 0.03f * i);
            double phase = i * 2.1, freq = 1.3 + 0.4 * i;

            Path path = new Path();
            float step = w / 24f;
            for (int k = 0; k <= 24; k++) {
                float x = -w * 0.05f + k * step * 1.1f;
                float y = (float) (baseY + Math.sin(k / 24.0 * Math.PI * 2 * freq + phase) * amp);
                if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            for (int k = 24; k >= 0; k--) {
                float x = -w * 0.05f + k * step * 1.1f;
                float y = (float) (baseY + Math.sin(k / 24.0 * Math.PI * 2 * freq + phase) * amp) + band;
                path.lineTo(x, y);
            }
            path.close();

            Paint p = paint();
            p.setShader(new LinearGradient(0, baseY - amp, 0, baseY + band + amp,
                    colors[i], 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
        }
    }

    /** Three mountain layers, the nearer the lower and the darker. */
    private void drawMountains(Canvas c, int w, int h) {
        int[] fills = {0xFF16242E, 0xFF101B24, 0xFF0A1218};
        float[] bases = {0.45f, 0.55f, 0.68f};
        float[] peaks = {0.10f, 0.14f, 0.19f};
        for (int layer = 0; layer < 3; layer++) {
            Random r = new Random(11 + layer);
            int steps = 12 + layer * 5;
            float step = w * 1.1f / steps;
            float base = h * bases[layer], peak = h * peaks[layer];

            Path ridge = new Path();
            for (int k = 0; k <= steps; k++) {
                float x = -w * 0.05f + k * step;
                float y = base - (float) Math.pow(r.nextFloat(), 1.6) * peak;
                if (k == 0) ridge.moveTo(x, y); else ridge.lineTo(x, y);
            }
            Path fill = new Path(ridge);
            fill.lineTo(w * 1.05f, h);
            fill.lineTo(-w * 0.05f, h);
            fill.close();

            Paint p = paint();
            p.setShader(null);
            p.setColor(fills[layer]);
            c.drawPath(fill, p);

            if (layer >= 1) {   /* snow on the nearer ridges */
                p = paint();
                p.setColor(0x2E9FB4BE);
                p.setStrokeWidth(Math.max(1, h / 720f));
                p.setStyle(Paint.Style.STROKE);
                c.drawPath(ridge, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    private void drawFog(Canvas c, int w, int h) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, h * 0.72f, 0, h,
                0x0016242C, 0x9916242C, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.72f, w, h, p);
    }

    private void drawVignette(Canvas c, int w, int h) {
        Paint p = paint();
        p.setShader(new RadialGradient(w * 0.5f, h * 0.45f, Math.max(w, h) * 0.75f,
                0x00000000, 0x96000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /** The menu sits on the left, so darken it like Dusklight's prelaunch. */
    private void drawMenuScrim(Canvas c, int w, int h) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, 0, w * 0.55f, 0,
                0xE6000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.55f, h, p);
    }

    private static Paint paint() {
        Paint p = new Paint();
        p.setAntiAlias(true);
        return p;
    }
}
