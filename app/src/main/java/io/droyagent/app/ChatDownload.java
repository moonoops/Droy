package io.droyagent.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.function.BooleanSupplier;

/** Streams only the authenticated local DSH response to the user's chosen document. */
final class ChatDownload {
    private ChatDownload() {}

    static long copy(String address, int port, String cookie, OutputStream destination,
            BooleanSupplier sessionCurrent) throws IOException {
        if (!ChatFilePolicy.isLocalDownload(address, port)) throw new IOException(io.droyagent.app.NativeLocale.text("下载地址不属于当前引擎", "Download address does not belong to the current engine"));
        if (!sessionCurrent.getAsBoolean()) throw new IOException(io.droyagent.app.NativeLocale.text("引擎会话已变化，请重新导出", "The engine session changed. Please export again"));
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (cookie != null && !cookie.trim().isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            int status = connection.getResponseCode();
            // Never forward the local authentication cookies to a redirect target.
            if (status != 200) throw new IOException(io.droyagent.app.NativeLocale.text("下载未完成（HTTP ", "Download incomplete (HTTP ") + status + NativeLocale.text("）", ")"));
            long expected = connection.getContentLengthLong(), written = 0;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (!sessionCurrent.getAsBoolean()) throw new IOException(io.droyagent.app.NativeLocale.text("引擎会话已变化，请重新导出", "The engine session changed. Please export again"));
                    destination.write(buffer, 0, count);
                    written += count;
                }
            }
            if (expected >= 0 && written != expected) throw new IOException(io.droyagent.app.NativeLocale.text("下载内容不完整，请重新导出", "Download content is incomplete. Please export again"));
            destination.flush();
            return written;
        } finally { connection.disconnect(); }
    }
}
