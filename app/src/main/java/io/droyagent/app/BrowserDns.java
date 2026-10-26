package io.droyagent.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional, human-selected public DNS for networks whose system DNS returns proxy virtual IPs. */
final class BrowserDns {
    private BrowserDns() {}
    static InetAddress[] resolve(Context context, String host) throws IOException {
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length()-1) : host;
        if (name.contains(":") || name.matches("[0-9.]+") || name.matches("0[xX][0-9a-fA-F]+")) return InetAddress.getAllByName(name);
        final boolean encrypted;
        try {
            Bundle policy = context.getContentResolver().call(Uri.parse("content://" + context.getPackageName() + ".browser-ui"), "dns-policy", null, null);
            if (policy == null) throw new IOException("Browser DNS policy unavailable");
            encrypted = policy.getBoolean("encryptedDns");
        } catch (RuntimeException unavailable) { throw new IOException("Browser DNS policy unavailable", unavailable); }
        if (!encrypted) return InetAddress.getAllByName(name);
        List<InetAddress> addresses = new ArrayList<>();
        addresses.addAll(query(name, 1));
        if (addresses.isEmpty()) addresses.addAll(query(name, 28));
        if (addresses.isEmpty()) throw new IOException("Encrypted DNS returned no public address");
        return addresses.toArray(new InetAddress[0]);
    }

    private static List<InetAddress> query(String host, int type) throws IOException {
        if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.-]+")) throw new IOException("Invalid DNS hostname");
        // Numeric endpoint avoids the same fake-DNS bootstrap. Default TLS verification
        // authenticates its IP certificate; redirects and ambient proxy/cookies are disabled.
        URL url = new URL("https://1.1.1.1/dns-query?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8.name()) + "&type=" + type);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/dns-json");
        connection.setRequestProperty("User-Agent", "DroyAgent/0.2");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Encrypted DNS request failed");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 32768) throw new IOException("Encrypted DNS response too large");
                    bytes.write(buffer, 0, count);
                }
            }
            return parse(host, type, bytes.toString(StandardCharsets.UTF_8.name()));
        } finally { connection.disconnect(); }
    }

    static List<InetAddress> parse(String host, int type, String body) throws IOException {
        try {
            JSONObject response = new JSONObject(body);
            if (response.optInt("Status", -1) != 0 || response.optBoolean("TC")) throw new IOException("Encrypted DNS query failed or truncated");
            JSONArray questions = response.getJSONArray("Question");
            if (questions.length() != 1) throw new IOException("Unexpected DNS question");
            JSONObject question = questions.getJSONObject(0);
            String name = question.getString("name");
            if (name.endsWith(".")) name = name.substring(0, name.length()-1);
            if (!name.equalsIgnoreCase(host) || question.getInt("type") != type) throw new IOException("DNS question mismatch");
            JSONArray answers = response.optJSONArray("Answer");
            List<InetAddress> addresses = new ArrayList<>();
            if (answers == null) return addresses;
            if (answers.length() > 64) throw new IOException("Too many DNS answers");
            for (int i=0;i<answers.length();i++) {
                JSONObject answer = answers.getJSONObject(i);
                if (answer.getInt("type") != type) continue;
                String data = answer.getString("data");
                if ((type == 1 && !data.matches("[0-9.]+")) || (type == 28 && (!data.contains(":") || !data.matches("[0-9a-fA-F:]+"))))
                    throw new IOException("DNS answer is not a numeric address");
                InetAddress address = InetAddress.getByName(data);
                if ((type == 1 && address.getAddress().length != 4) || (type == 28 && address.getAddress().length != 16)
                    || !BrowserSession.UrlPolicy.isPublicAddress(address)) throw new IOException("Encrypted DNS returned a non-public address");
                if (!addresses.contains(address)) addresses.add(address);
            }
            return addresses;
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid encrypted DNS response", invalid); }
    }
}
