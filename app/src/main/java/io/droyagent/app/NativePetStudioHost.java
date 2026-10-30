package io.droyagent.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Native preview uses the published sprite contract without selecting the candidate or executing package content. */
public final class NativePetStudioHost {
    private final Context context;
    public NativePetStudioHost(Context context) { this.context = context.getApplicationContext(); }
    public String selectedId() { return context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("petId", "default"); }
    public void select(String id) throws Exception { checkId(id); PetOverlay.select(context, id); }
    static void checkId(String id) {
        if (!PetPackage.validId(id)) throw new IllegalArgumentException("invalid_pet_id");
    }
    static String assetPath(String id, String file) {
        checkId(id);
        if (!(file.equals("pet.json") || file.matches("[A-Za-z0-9_-]+\\.png"))) throw new IllegalArgumentException("invalid_pet_asset");
        return "/droy/pets/asset?id=" + id + "&file=" + file;
    }
    private byte[] read(String id, String file, int limit) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("pet_preview_cancelled");
        String path = assetPath(id, file);
        if (id.equals("default")) {
            try (InputStream input = context.getAssets().open("pet/" + file)) { return bounded(input, limit); }
        }
        NativeRuntimeHost.Endpoint endpoint = NativeRuntimeHost.connection();
        if (endpoint == null) throw new IOException("runtime_unavailable");
        HttpURLConnection connection = endpoint.open(path);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("pet_asset_unavailable");
            if (connection.getContentLengthLong() > limit) throw new IOException("pet_asset_too_large");
            try (InputStream input = connection.getInputStream()) { return bounded(input, limit); }
        } finally { connection.disconnect(); }
    }
    static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int count;
        while ((count = input.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("pet_preview_cancelled");
            if (output.size() + count > limit) throw new IOException("pet_asset_too_large");
            output.write(chunk, 0, count);
        }
        return output.toByteArray();
    }
    static int sampleSize(long pixels) {
        int sample = 1;
        while ((pixels + (long)sample * sample - 1) / ((long)sample * sample) > 4_000_000) sample *= 2;
        return sample;
    }
    /** Invoke on an IO dispatcher. A caller owns at most one preview, with a 4 MP decoded image budget. */
    public synchronized Preview loadPreview(String id) throws Exception {
        JSONObject manifest = new JSONObject(new String(read(id, "pet.json", 512 * 1024), StandardCharsets.UTF_8));
        PetSpriteSpec spec = PetSpriteSpec.parse(manifest);
        long total = 0; for (PetSpriteSpec.Atlas atlas : spec.atlases.values()) total += (long)atlas.width * atlas.height;
        int sample = sampleSize(total);
        while (true) {
            long decoded = 0;
            for (PetSpriteSpec.Atlas atlas : spec.atlases.values())
                decoded += ((atlas.width + sample - 1L) / sample) * ((atlas.height + sample - 1L) / sample);
            if (decoded <= 4_000_000) break;
            sample *= 2;
        }
        Map<String, Bitmap> images = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, PetSpriteSpec.Atlas> row : spec.atlases.entrySet()) {
                PetSpriteSpec.Atlas atlas = row.getValue();
                images.put(row.getKey(), decode(read(id, atlas.file, 24 * 1024 * 1024), sample, atlas.width, atlas.height));
            }
            return new Preview(spec, images, sample);
        } catch (Exception failure) { for (Bitmap image : images.values()) image.recycle(); throw failure; }
    }
    /** Invoke on IO; one still at a time, no disk cache or selected-package mutation. */
    public synchronized Bitmap loadStill(String id, String file) throws Exception {
        if (!Set.of("reference.png", "brand-logo.png").contains(file)) throw new IllegalArgumentException("invalid_pet_still");
        byte[] bytes = read(id, file, 24 * 1024 * 1024);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 8192 || bounds.outHeight > 8192) throw new IOException("invalid_pet_image");
        int sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2;
        return decode(bytes, sample, bounds.outWidth, bounds.outHeight);
    }
    private static Bitmap decode(byte[] bytes, int sample, int width, int height) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth != width || options.outHeight != height || !"image/png".equals(options.outMimeType)) throw new IOException("invalid_pet_image");
        options.inJustDecodeBounds = false; options.inScaled = false; options.inSampleSize = sample;
        Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (result == null) throw new IOException("invalid_pet_image");
        return result;
    }
    public static final class Preview implements AutoCloseable {
        private final PetSpriteSpec spec;
        private final Map<String, Bitmap> images;
        private final int sample;
        private Preview(PetSpriteSpec spec, Map<String, Bitmap> images, int sample) { this.spec = spec; this.images = images; this.sample = sample; }
        public void close() { for (Bitmap image : images.values()) if (!image.isRecycled()) image.recycle(); }
        public View createView(Context context) { return new PreviewView(context, this); }
        public void play(View view, String state, boolean left) { ((PreviewView)view).play(state, left); }
    }
    private static final class PreviewView extends View {
        private final Preview preview;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private PetSpriteSpec.Animation animation;
        private long started;
        private boolean left = true;
        private final Runnable tick = new Runnable() {
            public void run() {
                invalidate();
                long elapsed = SystemClock.uptimeMillis() - started;
                if (isAttachedToWindow() && isShown() && elapsed < animation.durationMs && ValueAnimator.areAnimatorsEnabled())
                    postDelayed(this, Math.max(16, animation.nextFrameDelay(elapsed)));
            }
        };
        PreviewView(Context context, Preview preview) { super(context); this.preview = preview; play("idle", true); }
        void play(String state, boolean left) {
            if (!preview.spec.animations.containsKey(state)) return;
            this.left = left;
            PetSpriteSpec.Animation base = preview.spec.animations.get(state);
            animation = preview.spec.animations.get(left ? base.leftState : base.rightState);
            started = SystemClock.uptimeMillis(); removeCallbacks(tick); invalidate();
            if (isAttachedToWindow()) post(tick);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap bitmap = preview.images.get(animation.atlasKey); if (bitmap == null || bitmap.isRecycled()) return;
            long elapsed = ValueAnimator.areAnimatorsEnabled() ? SystemClock.uptimeMillis() - started : animation.durationMs;
            PetSpriteSpec.Frame frame = animation.frames[animation.frameAt(elapsed)];
            Rect source = new Rect(frame.x / preview.sample, frame.y / preview.sample,
                Math.min(bitmap.getWidth(), (frame.x + frame.width + preview.sample - 1) / preview.sample),
                Math.min(bitmap.getHeight(), (frame.y + frame.height + preview.sample - 1) / preview.sample));
            float scale = animation.scaleToFit(getWidth(), getHeight());
            float w = frame.width * scale, h = frame.height * scale;
            RectF destination = new RectF((getWidth() - w) / 2, getHeight() - h, (getWidth() + w) / 2, getHeight());
            canvas.save(); if (animation.mirrored(left)) canvas.scale(-1, 1, getWidth() / 2f, getHeight() / 2f);
            canvas.drawBitmap(bitmap, source, destination, paint); canvas.restore();
        }
    }
}
