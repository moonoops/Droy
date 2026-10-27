package io.droyagent.androiduse;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Local credential lifecycle runs on devices without an NFC adapter as well. */
@RunWith(AndroidJUnit4.class)
public class NfcCredentialsDeviceTest {
    @Test public void encryptedCredentialsCanBeListedAndDeletedWithoutAnEnabledReader() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences choices = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowNfcRead", "allowNfcWrite", "toolsStopped"};
        Map<String, Object> before = new HashMap<>();
        for (String key : keys) before.put(key, choices.getAll().get(key));
        NfcCredentials store = new NfcCredentials(context);
        NfcTools tools = new NfcTools(context);
        String reference = null;
        try {
            assertTrue(choices.edit().putBoolean("allowNfcRead", true).putBoolean("allowNfcWrite", true).putBoolean("toolsStopped", false).commit());
            String fixture = "000102030405";
            reference = store.save("Synthetic NFC test", fixture);
            byte[] plain = store.read(reference);
            try { assertArrayEquals(NfcProtocol.unhex(fixture, 6), plain); }
            finally { Arrays.fill(plain, (byte) 0); }
            assertFalse(context.getSharedPreferences("nfc-credentials", Context.MODE_PRIVATE).getString(reference, "").contains(fixture));
            JSONObject result = tools.execute(new JSONObject().put("operation", "credentials"), "nfc-test", () -> {});
            boolean found = false;
            JSONArray rows = result.getJSONArray("credentials");
            for (int i = 0; i < rows.length(); i++) if (reference.equals(rows.getJSONObject(i).getString("credentialRef"))) {
                found = true; assertFalse(rows.getJSONObject(i).has("key")); assertFalse(rows.getJSONObject(i).has("ciphertext"));
            }
            assertTrue(found);
            assertTrue(choices.edit().putBoolean("allowNfcRead", false).commit());
            try { tools.execute(new JSONObject().put("operation", "credentials"), "nfc-test", () -> {}); fail(); }
            catch (ActionException expected) { assertEquals("product_permission_denied", expected.getCode()); }
            assertTrue(choices.edit().putBoolean("allowNfcRead", true).commit());
            assertEquals("deleted", tools.execute(new JSONObject().put("operation", "credential_delete").put("credentialRef", reference), "nfc-test", () -> {}).getString("status"));
            assertFalse(context.getSharedPreferences("nfc-credentials", Context.MODE_PRIVATE).contains(reference));
            reference = null;
        } finally {
            tools.close();
            if (reference != null) store.delete(reference);
            SharedPreferences.Editor restore = choices.edit();
            for (String key : keys) { Object value = before.get(key); if (value == null) restore.remove(key); else restore.putBoolean(key, (Boolean) value); }
            assertTrue(restore.commit());
        }
    }
}
