package io.droyagent.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads only a bounded image manifest and PNGs, never executable pet content. */
final class PetPackage {
    final PetSpriteSpec spec;
    final Map<String, Bitmap> bitmaps;
    final String id, name;
    final Map<String, String> previousVariants = new LinkedHashMap<>();
    private static PetPackage cached;
    private static String error = "";

    private PetPackage(String id, String name, PetSpriteSpec spec, Map<String, Bitmap> bitmaps) {
        this.id = id; this.name = name; this.spec = spec; this.bitmaps = bitmaps;
    }
    static String error() { return error; }
    static synchronized void use(PetPackage value) { cached = value; error = ""; }
    static boolean validId(String id) { return "default".equals(id) || id != null && id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"); }
    static synchronized PetPackage selected(Context context) {
        String id = context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("petId", "default");
        if (cached != null && cached.id.equals(id)) return cached;
        try { cached = load(context, id); error = ""; }
        catch (Exception failure) {
            if ("default".equals(id)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法加载内置伙伴形象素材", "Unable to load the built-in companion artwork"), failure);
            error = io.droyagent.app.NativeLocale.text("当前伙伴形象的素材无法读取，已临时显示默认猫咪。请重新选择或生成。", "This companion's artwork could not be read. The default cat is shown temporarily. Please select or generate it again.");
            try { cached = load(context, "default"); }
            catch (Exception defaultFailure) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法加载内置伙伴形象素材", "Unable to load the built-in companion artwork"), defaultFailure); }
        }
        return cached;
    }
    static PetPackage load(Context context, String id) throws Exception {
        if (!validId(id)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("伙伴形象编号无效", "Invalid companion artwork ID"));
        File directory = null;
        if (!"default".equals(id)) {
            File root = new File(context.getFilesDir(), "dsh-home/pets").getCanonicalFile();
            directory = new File(root, id).getCanonicalFile();
            if (!root.equals(directory.getParentFile())) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("伙伴形象目录无效", "Invalid companion artwork directory"));
        }
        PetSpriteSpec spec; String name;
        try (InputStream input = open(context, directory, "pet.json", 512 * 1024)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 512 * 1024) throw new IOException(io.droyagent.app.NativeLocale.text("伙伴形象描述文件过大", "Companion artwork description is too large")); }
            JSONObject document = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            name = "default".equals(id) ? "Droy" : document.getString("name").trim();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 40) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("形象名称无效", "Invalid artwork name"));
            spec = PetSpriteSpec.parse(document);
        }
        Map<String, Bitmap> bitmaps = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, PetSpriteSpec.Atlas> entry : spec.atlases.entrySet()) {
                PetSpriteSpec.Atlas atlas = entry.getValue();
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                try (InputStream input = open(context, directory, atlas.file, 24 * 1024 * 1024)) { BitmapFactory.decodeStream(input, null, options); }
                if (options.outWidth != atlas.width || options.outHeight != atlas.height || !"image/png".equals(options.outMimeType))
                    throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("伙伴形象图片尺寸或格式不符", "Companion image dimensions or format do not match"));
                options.inJustDecodeBounds = false; options.inScaled = false;
                Bitmap bitmap;
                try (InputStream input = open(context, directory, atlas.file, 24 * 1024 * 1024)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
                if (bitmap == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("伙伴形象图片无法解码", "Unable to decode companion image"));
                if (!bitmap.hasAlpha() || (bitmap.getPixel(0, 0) >>> 24) != 0
                    || (bitmap.getPixel(bitmap.getWidth() - 1, bitmap.getHeight() - 1) >>> 24) != 0) {
                    bitmap.recycle(); throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("伙伴形象图片必须保留透明背景和边缘留白", "Companion images must preserve transparency and edge padding"));
                }
                bitmaps.put(entry.getKey(), bitmap);
            }
            return new PetPackage(id, name, spec, bitmaps);
        } catch (Exception failure) { for (Bitmap bitmap : bitmaps.values()) bitmap.recycle(); throw failure; }
    }
    private static InputStream open(Context context, File directory, String file, long maxBytes) throws IOException {
        if (directory == null) return context.getAssets().open("pet/" + file);
        File target = new File(directory, file).getCanonicalFile();
        if (!directory.equals(target.getParentFile()) || !target.isFile() || target.length() > maxBytes)
            throw new IOException(io.droyagent.app.NativeLocale.text("伙伴形象素材文件无效", "Invalid companion artwork file"));
        return new FileInputStream(target);
    }
}
