package io.droyagent.app.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/** Limits belong to import admission, regardless of the Android entry point. */
public final class MediaInputPolicy {
    public static final int MAX_IMAGES = 10, MAX_ITEMS = 32, MAX_PENDING_IMPORTS = 32;
    public static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_BATCH_BYTES = 200L * 1024 * 1024;
    public static final int MAX_TEXT = 100_000;

    private MediaInputPolicy() {}

    public static String id(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("附件标识无效", "Invalid attachment ID"));
        return value;
    }

    public static String session(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("目标对话无效", "Invalid target conversation"));
        return value;
    }

    public static String draftHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("草稿校验值无效", "Invalid draft checksum"));
        return value;
    }

    public static String filename(String value) {
        if (value == null) return "attachment";
        String name = value.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "attachment";
        return name.length() > 160 ? name.substring(0, 160) : name;
    }

    public static String mediaType(String value) {
        if (value == null) return "application/octet-stream";
        String type = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.matches("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+") ? type : "application/octet-stream";
    }

    public static boolean contentUri(String value) {
        try {
            URI uri = new URI(value);
            return "content".equals(uri.getScheme()) && uri.getRawAuthority() != null
                && !uri.getRawAuthority().isBlank() && !uri.getRawAuthority().contains("@")
                && uri.getFragment() == null && uri.getUserInfo() == null;
        } catch (Exception invalid) { return false; }
    }

    public static String text(CharSequence value) {
        if (value == null) return "";
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("分享文字过长，最多支持100000个字符", "Shared text supports up to 100,000 characters"));
        return value.toString();
    }

    public static long copy(InputStream input, OutputStream output, long maximum) throws IOException {
        if (input == null) throw new IOException(io.droyagent.app.NativeLocale.text("无法读取分享文件", "Unable to read shared file"));
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        for (int count; (count = input.read(chunk)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException(io.droyagent.app.NativeLocale.text("附件导入已取消", "Attachment import cancelled"));
            total += count;
            if (total > maximum) throw new IOException(io.droyagent.app.NativeLocale.text("附件超过大小上限", "Attachment exceeds size limit"));
            output.write(chunk, 0, count);
        }
        return total;
    }
}
