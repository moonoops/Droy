package io.droyagent.app;

import java.io.IOException;
import org.json.JSONObject;

/** Values declared by the signed APK and checked against the live engine. */
final class RuntimeRelease {
    final String droyVersion;
    final long versionCode;
    final String dshVersion;
    final String mobileVersion;
    final String sha256;

    RuntimeRelease(JSONObject manifest) throws Exception {
        droyVersion = manifest.getString("droyVersion");
        Object code = manifest.get("versionCode");
        if (!(code instanceof Integer || code instanceof Long) || ((Number) code).longValue() < 1
                || ((Number) code).longValue() > Integer.MAX_VALUE) throw new IOException(io.droyagent.app.NativeLocale.text("运行包版本编号无效", "Invalid runtime version number"));
        versionCode = ((Number) code).longValue();
        dshVersion = manifest.getString("dshVersion");
        mobileVersion = manifest.getString("mobileVersion");
        sha256 = manifest.getString("sha256");
        if (droyVersion.trim().isEmpty() || dshVersion.trim().isEmpty() || mobileVersion.trim().isEmpty()
                || !sha256.matches("[a-f0-9]{64}")) throw new IOException(io.droyagent.app.NativeLocale.text("运行包版本清单无效", "Invalid runtime version manifest"));
    }

    void verifyPackage(String installedVersion, long installedCode) throws IOException {
        if (!droyVersion.equals(installedVersion) || versionCode != installedCode)
            throw new IOException(io.droyagent.app.NativeLocale.text("运行包与已安装的 Droy 版本不一致，请重新构建或安装完整版本", "The runtime does not match the installed Droy version. Rebuild or install the full version"));
    }

    void verifyReady(JSONObject ready) throws Exception {
        if (!ready.optBoolean("ready") || !dshVersion.equals(ready.optString("dshVersion"))
                || !mobileVersion.equals(ready.optString("mobileVersion")))
            throw new IOException(io.droyagent.app.NativeLocale.text("运行中的 DSH 与此 Droy 版本绑定的运行包不一致", "The running DSH does not match the runtime bundled with this Droy version"));
    }
}
