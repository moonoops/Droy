package io.droyagent.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

final class RuntimeInstaller {
    static synchronized File install(Context context) throws Exception {
        return install(context, false);
    }
    static synchronized File install(Context context, boolean forceVerifiedReplacement) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("运行环境准备已停止", "Runtime preparation stopped"));
        File root = new File(context.getFilesDir(), "runtime");
        RuntimeRelease release = release(context);
        String expected = release.sha256;
        File marker = new File(root, ".installed-sha256");
        if (!forceVerifiedReplacement && marker.isFile() && new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8).equals(expected)
                && versionsMatch(root, release)) return root;
        File staging = new File(context.getFilesDir(), "runtime-staging");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create runtime staging directory");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getAssets().open("dsh-runtime.zip");
             java.security.DigestInputStream checked = new java.security.DigestInputStream(input, digest);
             ZipInputStream zip = new ZipInputStream(checked)) {
            ZipEntry entry;
            String prefix = staging.getCanonicalPath() + File.separator;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(staging, entry.getName());
                if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid runtime archive path");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create archive directory");
                } else {
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create archive parent");
                    try (OutputStream out = new FileOutputStream(target)) { copy(zip, out); }
                }
            }
            // ZIP's central directory is not consumed by getNextEntry().
            while (checked.read(new byte[8192]) != -1) { }
        }
        String actual = hex(digest.digest());
        if (!actual.equals(expected)) throw new IOException("Runtime archive checksum mismatch");
        if (!versionsMatch(staging, release)) throw new IOException(io.droyagent.app.NativeLocale.text("运行包内 DSH 版本与发布清单不一致", "The runtime's DSH version does not match the release manifest"));
        preserveLegacyPlugins(new File(context.getFilesDir(), "dsh-home"), root, staging,
            new File(context.getApplicationInfo().nativeLibraryDir, "libdroy_node.so").getAbsolutePath(),
            context.getApplicationInfo().nativeLibraryDir);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("运行环境准备已停止", "Runtime preparation stopped"));
        Files.write(new File(staging, ".installed-sha256").toPath(), expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File previous = new File(context.getFilesDir(), "runtime-previous");
        deleteTree(previous);
        if (root.exists() && !root.renameTo(previous)) throw new IOException("Cannot preserve previous runtime");
        if (!staging.renameTo(root)) {
            if (previous.exists() && !previous.renameTo(root)) throw new IOException("Runtime activation and recovery failed");
            throw new IOException("Runtime activation failed");
        }
        deleteTree(previous);
        return root;
    }

    static boolean needsLegacyPluginMigration(File home) throws Exception {
        Path profile = home.toPath().resolve("profiles/mobile");
        if (Files.exists(profile.resolve(".droy-module-migration.json"), LinkOption.NOFOLLOW_LINKS)) return true;
        if (!Files.isSymbolicLink(profile.resolve("node_modules"))) return false;
        Path manifestPath = profile.resolve("package.json");
        if (!Files.exists(manifestPath)) return false;
        try {
            JSONObject manifest = new JSONObject(new String(Files.readAllBytes(manifestPath), java.nio.charset.StandardCharsets.UTF_8));
            for (String field : new String[]{"dependencies", "devDependencies", "optionalDependencies", "peerDependencies"}) {
                if (!manifest.has(field)) continue;
                JSONObject dependencies = manifest.getJSONObject(field);
                java.util.Iterator<String> names = dependencies.keys();
                while (names.hasNext()) if (!names.next().equals("@droyagent/mobile")) return true;
            }
        } catch (org.json.JSONException invalid) {
            // JSON type errors may include dependency values or private registry URLs.
            throw new IOException(io.droyagent.app.NativeLocale.text("旧插件配置无效，运行包尚未替换，请修复配置后重试", "Old plugin configuration is invalid. The runtime has not been replaced. Fix configuration and retry"));
        }
        return false;
    }

    static void preserveLegacyPlugins(File home, File runtime, File staging, String node, String nativeDirectory) throws Exception {
        if (!needsLegacyPluginMigration(home)) return;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("插件迁移已停止", "Plugin migration stopped"));
        // The old profile link names runtime/, not runtime-previous/. Detach its
        // installed dependencies before replacing that target, using the verified
        // new migrator and its resumable journal while the old files still exist.
        String script = "import {pathToFileURL} from 'node:url';"
            + "try { const {prepareMobileProfile}=await import(pathToFileURL(process.argv[1]));"
            + "await prepareMobileProfile({home:process.argv[2],runtime:process.argv[3]}); }"
            + "catch { process.exitCode=1; }";
        ProcessBuilder builder = new ProcessBuilder(node, "--input-type=module", "--eval", script,
            new File(staging, "mobile/profile-storage.mjs").getAbsolutePath(), home.getAbsolutePath(), runtime.getAbsolutePath());
        builder.directory(staging).redirectErrorStream(true).redirectOutput(new File("/dev/null"));
        builder.environment().put("LD_LIBRARY_PATH", nativeDirectory);
        builder.environment().remove("NODE_OPTIONS");
        builder.environment().remove("NODE_PATH");
        Process migration = builder.start();
        try {
            if (!migration.waitFor(120, TimeUnit.SECONDS)) throw new IOException(io.droyagent.app.NativeLocale.text("插件迁移超时，旧运行包尚未替换，请重试", "Plugin migration timed out. The old runtime has not been replaced. Please retry"));
            if (migration.exitValue() != 0) throw new IOException(io.droyagent.app.NativeLocale.text("插件迁移失败，旧运行包尚未替换，请检查插件安装后重试", "Plugin migration failed. The old runtime has not been replaced. Check plugin installation and retry"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("插件迁移已停止，旧运行包尚未替换", "Plugin migration stopped. The old runtime has not been replaced"));
        } finally {
            if (migration.isAlive()) migration.destroyForcibly();
        }
    }

    static RuntimeRelease release(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("dsh-runtime.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            copy(in, bytes);
            RuntimeRelease release = new RuntimeRelease(new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)));
            android.content.pm.PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            release.verifyPackage(installed.versionName, installed.getLongVersionCode());
            return release;
        }
    }

    private static boolean versionsMatch(File root, RuntimeRelease release) throws Exception {
        File dsh = new File(root, "node_modules/@deepseek-ai/dsh/package.json");
        File mobile = new File(root, "node_modules/@droyagent/mobile/package.json");
        if (!dsh.isFile() || !mobile.isFile()) return false;
        return release.dshVersion.equals(new JSONObject(new String(Files.readAllBytes(dsh.toPath()), java.nio.charset.StandardCharsets.UTF_8)).optString("version"))
            && release.mobileVersion.equals(new JSONObject(new String(Files.readAllBytes(mobile.toPath()), java.nio.charset.StandardCharsets.UTF_8)).optString("version"));
    }

    static void installCertificate(Context context) throws IOException {
        File target = new File(context.getFilesDir(), "ca-certificates.crt");
        try (InputStream in = context.getAssets().open("node/ca-certificates.crt"); OutputStream out = new FileOutputStream(target)) { copy(in, out); }
    }

    static File installTerminfo(Context context) throws IOException {
        File target = new File(context.getFilesDir(), "runtime-support/terminfo");
        copyAssets(context, "node/terminfo", target);
        return target;
    }

    private static void copyAssets(Context context, String source, File target) throws IOException {
        String[] children = context.getAssets().list(source);
        if (children == null) throw new IOException(io.droyagent.app.NativeLocale.text("无法读取运行支持文件", "Unable to read runtime support file"));
        if (children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建运行支持目录", "Unable to create runtime support directory"));
            for (String child : children) copyAssets(context, source + "/" + child, new File(target, child));
        } else {
            try (InputStream in = context.getAssets().open(source); OutputStream out = new FileOutputStream(target)) { copy(in, out); }
        }
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("运行环境准备已停止", "Runtime preparation stopped"));
            output.write(buffer, 0, count);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    static void deleteTree(File file) throws IOException {
        HistoryData.deleteTree(file.toPath());
    }
}
