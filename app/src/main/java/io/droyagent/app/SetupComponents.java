package io.droyagent.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** User-requested optional components are verified before Android's installer receives them. */
final class SetupComponents {
    static final String PACKAGE = "moe.shizuku.privileged.api";
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Object lock = new Object();
    private static final ExecutorService downloads = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "droy-setup-download"); thread.setDaemon(true); return thread;
    });
    private static String downloadState = "idle", error = "";
    private static long downloadBytes;
    private static Download active;

    private SetupComponents() {}

    private static final class Download {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
    }

    static JSONObject status(Context context) throws Exception {
        JSONObject spec = specification(context);
        JSONObject result = installed(context, spec);
        synchronized (lock) {
            File cached = verifiedFile(context, spec);
            if ("idle".equals(downloadState) && cached.isFile()) {
                try {
                    verifyArchive(context, cached, spec);
                    downloadState = "ready"; downloadBytes = cached.length();
                } catch (IOException invalid) {
                    delete(cached); downloadState = "failed"; error = io.droyagent.app.NativeLocale.text("安装包校验未通过，请重新下载", "Installation package verification failed. Please download again");
                }
            }
            return result.put("downloadState", downloadState).put("downloadBytes", downloadBytes)
                .put("totalBytes", spec.getLong("bytes")).put("error", error)
                .put("componentVersion", spec.getString("versionName"))
                .put("sourceUrl", spec.getString("sourceUrl")).put("homepage", spec.getString("homepage"))
                .put("canRequestPackageInstalls", context.getPackageManager().canRequestPackageInstalls());
        }
    }

    static JSONObject begin(Context context) throws Exception {
        Context app = context.getApplicationContext();
        JSONObject spec = specification(app);
        JSONObject previous = status(app);
        if (previous.getBoolean("installed") || "ready".equals(previous.getString("downloadState"))) return previous;
        synchronized (lock) {
            if (active != null && !active.cancelled || "ready".equals(downloadState)) return status(app);
            File directory = new File(app.getCacheDir(), "setup-components");
            File[] interrupted = directory.listFiles((parent, name) -> name.endsWith(".part"));
            if (interrupted != null) for (File partial : interrupted) delete(partial);
            Download task = new Download(); active = task;
            downloadState = "downloading"; downloadBytes = 0; error = "";
            downloads.execute(() -> download(app, spec, task));
        }
        return status(app);
    }

    static JSONObject cancel(Context context) throws Exception {
        HttpURLConnection connection = null;
        synchronized (lock) {
            if (active != null) { active.cancelled = true; connection = active.connection; active = null; }
            downloadState = "idle"; downloadBytes = 0; error = "";
        }
        if (connection != null) connection.disconnect();
        return status(context);
    }

    static JSONObject install(Activity activity) throws Exception {
        JSONObject spec = specification(activity);
        JSONObject current = installed(activity, spec);
        if (current.getBoolean("installed")) return status(activity).put("action", "already_installed");
        File apk = verifiedFile(activity, spec);
        verifyArchive(activity, apk, spec);
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
            return status(activity).put("action", "awaiting_source_permission");
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".setup-files", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Shizuku", uri));
        activity.startActivity(intent);
        // The package manager, read on return, is the only source of installation success.
        return status(activity).put("action", "awaiting_install_confirmation");
    }

    static JSONObject open(Activity activity) throws Exception {
        JSONObject current = status(activity);
        if (!current.getBoolean("installed")) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先安装 Shizuku", "Please install Shizuku first"));
        if (!current.getBoolean("trusted")) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("Shizuku 签名与官方版本不符，请先核对安装来源", "The Shizuku signature does not match the official version. Please verify the installation source"));
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch == null) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("当前系统无法打开 Shizuku，请从桌面打开", "The system cannot open Shizuku. Please open it from the launcher"));
        activity.startActivity(launch);
        return current.put("action", "opened");
    }

    private static JSONObject installed(Context context, JSONObject spec) throws Exception {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            return new JSONObject().put("installed", true).put("versionName", info.versionName)
                .put("versionCode", info.getLongVersionCode()).put("trusted", signedBy(info, spec.getString("signerSha256")));
        } catch (PackageManager.NameNotFoundException missing) {
            return new JSONObject().put("installed", false).put("versionName", "").put("trusted", false);
        }
    }

    private static JSONObject specification(Context context) throws Exception {
        JSONObject manifest;
        try (InputStream input = context.getAssets().open("setup-components.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 8192) throw new IOException(io.droyagent.app.NativeLocale.text("组件清单过大", "Component manifest is too large"));
            }
            manifest = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
        if (manifest.getInt("schemaVersion") != 1) throw new IOException(io.droyagent.app.NativeLocale.text("组件清单版本不支持", "Unsupported component manifest version"));
        JSONObject spec = manifest.getJSONObject("shizuku");
        if (!PACKAGE.equals(spec.getString("packageName")) || spec.getLong("bytes") <= 0
                || spec.getLong("bytes") > MAX_BYTES || spec.getLong("versionCode") <= 0
                || !spec.getString("sha256").matches("[a-f0-9]{64}")
                || !spec.getString("signerSha256").matches("[a-f0-9]{64}")
                || !spec.getString("sourceUrl").startsWith("https://github.com/RikkaApps/Shizuku/releases/download/"))
            throw new IOException(io.droyagent.app.NativeLocale.text("组件清单无效", "Invalid component manifest"));
        validateDownloadUrl(spec.getString("sourceUrl"));
        return spec;
    }

    private static File verifiedFile(Context context, JSONObject spec) throws Exception {
        return new File(context.getCacheDir(), "setup-components/verified/" + spec.getString("sha256") + ".apk");
    }

    private static void download(Context context, JSONObject spec, Download task) {
        File partial = new File(context.getCacheDir(), "setup-components/" + java.util.UUID.randomUUID() + ".part");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        try {
            File parent = partial.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("组件缓存无法创建", "Unable to create component cache"));
            URL address = new URL(spec.getString("sourceUrl"));
            HttpURLConnection connection = null;
            for (int redirects = 0; redirects <= 5; redirects++) {
                validateDownloadUrl(address.toString());
                if (task.cancelled || System.nanoTime() >= deadline) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载已取消或超时", "Component download was cancelled or timed out"));
                connection = (HttpURLConnection) address.openConnection();
                task.connection = connection;
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Droy-Setup/1");
                if (task.cancelled) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载已取消", "Component download cancelled"));
                int code = connection.getResponseCode();
                if (code >= 300 && code <= 399) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || redirects == 5) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载重定向无效", "Invalid component download redirect"));
                    URL redirected = new URL(address, next);
                    validateDownloadUrl(redirected.toString());
                    connection.disconnect(); task.connection = null; address = redirected;
                } else {
                    if (code != 200) throw new IOException(io.droyagent.app.NativeLocale.text("组件服务器暂不可用", "Component server is temporarily unavailable"));
                    break;
                }
            }
            if (connection == null) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载连接未建立", "Component download connection was not established"));
            long expected = spec.getLong("bytes"), announced = connection.getContentLengthLong();
            if (announced >= 0 && announced != expected) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载大小不符", "Component download size does not match"));
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                copyVerified(input, output, expected, spec.getString("sha256"),
                    () -> !task.cancelled && System.nanoTime() < deadline,
                    bytes -> { synchronized (lock) { if (active == task) downloadBytes = bytes; } });
                output.getFD().sync();
            }
            verifyArchive(context, partial, spec);
            if (task.cancelled || System.nanoTime() >= deadline) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载已取消或超时", "Component download was cancelled or timed out"));
            File destination = verifiedFile(context, spec);
            File directory = destination.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("组件缓存无法创建", "Unable to create component cache"));
            synchronized (lock) {
                if (active != task || task.cancelled) return;
                if (!partial.renameTo(destination)) throw new IOException(io.droyagent.app.NativeLocale.text("组件缓存无法保存", "Unable to save component cache"));
                downloadState = "ready"; error = "";
            }
        } catch (Exception failure) {
            synchronized (lock) {
                if (active == task && !task.cancelled) {
                    downloadState = "failed";
                    error = io.droyagent.app.NativeLocale.text("组件下载或校验未完成，请检查网络后重试；也可从官方来源安装", "Component download or verification did not complete. Check your connection and retry, or install from an official source");
                }
            }
        } finally {
            delete(partial);
            HttpURLConnection connection = task.connection;
            if (connection != null) connection.disconnect();
            synchronized (lock) { if (active == task) active = null; }
        }
    }

    static void validateDownloadUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443
                    || !(host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com") || host.equals("github-releases.githubusercontent.com")))
                throw new IOException(io.droyagent.app.NativeLocale.text("组件下载地址不属于官方来源", "Component download URL is not an official source"));
        } catch (IllegalArgumentException invalid) { throw new IOException(io.droyagent.app.NativeLocale.text("组件下载地址无效", "Invalid component download URL")); }
    }

    static long copyVerified(InputStream input, OutputStream output, long expected, String expectedSha256,
            BooleanSupplier current, LongConsumer progress) throws IOException {
        if (expected <= 0 || expected > MAX_BYTES || !expectedSha256.matches("[a-f0-9]{64}"))
            throw new IOException(io.droyagent.app.NativeLocale.text("组件下载规格无效", "Invalid component download specification"));
        MessageDigest digest = digest();
        long written = 0;
        byte[] buffer = new byte[32768];
        if (!current.getAsBoolean()) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载已取消或超时", "Component download was cancelled or timed out"));
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (!current.getAsBoolean()) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载已取消或超时", "Component download was cancelled or timed out"));
            written += count;
            if (written > expected) throw new IOException(io.droyagent.app.NativeLocale.text("组件下载大小不符", "Component download size does not match"));
            digest.update(buffer, 0, count); output.write(buffer, 0, count); progress.accept(written);
        }
        if (!current.getAsBoolean() || written != expected || !hex(digest.digest()).equals(expectedSha256))
            throw new IOException(io.droyagent.app.NativeLocale.text("组件下载校验未通过", "Component download verification failed"));
        output.flush();
        return written;
    }

    static void verifyArchive(Context context, File file, JSONObject spec) throws Exception {
        if (!file.isFile() || file.length() != spec.getLong("bytes")) throw new IOException(io.droyagent.app.NativeLocale.text("请先下载并校验安装包", "Please download and verify the installation package first"));
        try (InputStream input = new FileInputStream(file)) {
            copyVerified(input, new OutputStream() {
                @Override public void write(int value) {}
                @Override public void write(byte[] bytes, int offset, int count) {}
            }, spec.getLong("bytes"), spec.getString("sha256"), () -> true, bytes -> {});
        }
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !PACKAGE.equals(info.packageName) || info.getLongVersionCode() != spec.getLong("versionCode")
                || !signedBy(info, spec.getString("signerSha256")))
            throw new IOException(io.droyagent.app.NativeLocale.text("组件包名、版本或签名未通过验证", "Component package name, version, or signature could not be verified"));
    }

    static boolean signingMatches(String expected, String... actual) {
        if (expected == null || !expected.matches("[a-f0-9]{64}") || actual == null || actual.length != 1
                || actual[0] == null || !actual[0].matches("[a-f0-9]{64}")) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual[0].getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean signedBy(PackageInfo info, String expected) {
        if (info.signingInfo == null) return false;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) return false;
        return signingMatches(expected, hex(digest().digest(signers[0].toByteArray())));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) result.append(String.format(Locale.ROOT, "%02x", part & 255));
        return result.toString();
    }

    private static void delete(File file) { if (file.exists() && !file.delete()) file.deleteOnExit(); }
}
