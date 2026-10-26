package io.droyagent.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;

/** Decodes user-supplied attachments; it does not observe the screen or device UI. */
final class AttachmentImageCodec {
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final long MAX_SOURCE_PIXELS = 64_000_000L;
    private static final int MAX_DIMENSION = 8192;
    private static final int MAX_TARGET_BYTES = 4 * 1024 * 1024;
    private static final long MAX_TARGET_PIXELS = 4_194_304L;
    private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private AttachmentImageCodec() {}

    // Native decoding shares the app process, so uploads must not multiply peak bitmap memory.
    static synchronized JSONObject normalize(Context context, JSONObject request) throws Exception {
        String id = validatedId(request.optString("id", ""));
        String mediaType = request.optString("mediaType", "");
        if (!MEDIA_TYPES.contains(mediaType)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的图片类型", "Unsupported image type"));
        JSONObject limits = request.optJSONObject("limits"), target = request.optJSONObject("target");
        int sourceBytes = (int) limit(limits, "maxImageBytes", MAX_SOURCE_BYTES);
        long sourcePixels = limit(limits, "maxImagePixels", MAX_SOURCE_PIXELS);
        int sourceDimension = (int) limit(limits, "maxImageDimension", MAX_DIMENSION);
        int targetBytes = (int) limit(target, "maxBytes", MAX_TARGET_BYTES);
        long targetPixels = limit(target, "maxPixels", MAX_TARGET_PIXELS);
        int targetDimension = (int) limit(target, "maxDimension", MAX_DIMENSION);
        if (targetBytes < 128) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片输出字节上限过小", "Image byte limit is too small"));

        Path directory = stagingDirectory(context);
        Path source = directory.resolve(id + ".source"), destination = directory.resolve(id + ".png");
        byte[] encoded = readSource(source, sourceBytes);
        BitmapFactory.Options header = new BitmapFactory.Options();
        header.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, header);
        if (!mediaType.equals(header.outMimeType)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片内容与声明类型不符", "Image content does not match its declared type"));
        checkDimensions(header.outWidth, header.outHeight, sourcePixels, sourceDimension);

        int[] original = new int[2];
        Bitmap bitmap = null;
        try {
            // ImageDecoder applies EXIF orientation and decodes the first frame of animated images.
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded)), (decoder, info, ignored) -> {
                original[0] = info.getSize().getWidth();
                original[1] = info.getSize().getHeight();
                checkDimensions(original[0], original[1], sourcePixels, sourceDimension);
                int[] dimensions = targetDimensions(original[0], original[1], targetPixels, targetDimension);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                decoder.setTargetSize(dimensions[0], dimensions[1]);
            });
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (converted == null) throw new IOException(io.droyagent.app.NativeLocale.text("无法转换图片像素格式", "Unable to convert image pixel format"));
                bitmap.recycle();
                bitmap = converted;
            }

            byte[] png;
            while (true) {
                LimitedOutput output = new LimitedOutput(targetBytes);
                boolean compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                if (!output.exceeded) {
                    if (!compressed) throw new IOException(io.droyagent.app.NativeLocale.text("无法编码 PNG 图片", "Unable to encode PNG image"));
                    png = output.bytes.toByteArray();
                    break;
                }
                if (bitmap.getWidth() == 1 && bitmap.getHeight() == 1)
                    throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片无法缩小到指定字节上限", "Image cannot be reduced to the requested byte limit"));
                int width = Math.max(1, (int) (bitmap.getWidth() * .75));
                int height = Math.max(1, (int) (bitmap.getHeight() * .75));
                Bitmap smaller = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (smaller != bitmap) bitmap.recycle();
                bitmap = smaller;
            }
            // CREATE_NEW prevents replacing a symlink or an unrelated file on duplicate requests.
            try (SeekableByteChannel output = Files.newByteChannel(destination,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(png);
                while (buffer.hasRemaining()) output.write(buffer);
            }
            return new JSONObject().put("mediaType", "image/png").put("bytes", png.length)
                .put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                .put("originalWidth", original[0]).put("originalHeight", original[1]);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    static String validatedId(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片暂存标识无效", "Invalid staged image ID"));
        }
    }

    static int[] targetDimensions(int width, int height, long pixels, int dimension) {
        if (width < 1 || height < 1 || pixels < 1 || dimension < 1)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片尺寸无效", "Invalid image dimensions"));
        double scale = Math.min(1, Math.min((double) dimension / Math.max(width, height),
            Math.sqrt((double) pixels / ((long) width * height))));
        int resultWidth = Math.max(1, (int) Math.floor(width * scale));
        int resultHeight = Math.max(1, (int) Math.floor(height * scale));
        // A one-pixel short edge can raise the area above the limit after rounding up to one.
        if ((long) resultWidth * resultHeight > pixels) {
            if (resultWidth >= resultHeight) resultWidth = (int) Math.max(1, pixels / resultHeight);
            else resultHeight = (int) Math.max(1, pixels / resultWidth);
        }
        return new int[] { resultWidth, resultHeight };
    }

    private static long limit(JSONObject value, String key, long ceiling) {
        if (value == null || !value.has(key)) return ceiling;
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片限制必须为正整数", "Image limits must be positive integers"));
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || number < 1 || number != Math.floor(number))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片限制必须为正整数", "Image limits must be positive integers"));
        return (long) Math.min(number, ceiling);
    }

    private static void checkDimensions(int width, int height, long pixels, int dimension) {
        if (width < 1 || height < 1 || width > dimension || height > dimension || (long) width * height > pixels)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片尺寸超出限制或图片已损坏", "Image dimensions exceed limits or the image is corrupted"));
    }

    private static Path stagingDirectory(Context context) throws IOException {
        Path path = context.getFilesDir().toPath().toRealPath();
        for (String segment : new String[] { "dsh-home", "attachments", "native" }) {
            path = path.resolve(segment);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片暂存目录无效", "Invalid staged image directory"));
        }
        return path;
    }

    private static byte[] readSource(Path source, int maximum) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片暂存文件无效", "Invalid staged image file"));
        try (SeekableByteChannel channel = Files.newByteChannel(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > maximum) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("图片文件大小超出限制", "Image file exceeds size limit"));
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new IOException(io.droyagent.app.NativeLocale.text("图片暂存文件不完整", "Staged image file is incomplete"));
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException(io.droyagent.app.NativeLocale.text("图片暂存文件在读取期间发生变化", "Staged image file changed while being read"));
            return buffer.array();
        }
    }

    private static final class LimitedOutput extends OutputStream {
        final ByteArrayOutputStream bytes;
        final int maximum;
        boolean exceeded;

        LimitedOutput(int maximum) { this.maximum = maximum; bytes = new ByteArrayOutputStream(Math.min(maximum, 65536)); }

        @Override public void write(int value) {
            if (bytes.size() == maximum) { exceeded = true; return; }
            if (!exceeded) bytes.write(value);
        }

        @Override public void write(byte[] data, int offset, int length) {
            if (exceeded || length > maximum - bytes.size()) { exceeded = true; return; }
            bytes.write(data, offset, length);
        }
    }
}
