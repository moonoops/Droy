package io.droyagent.androiduse;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wi-Fi passwords enter through local UI; model tools receive opaque references only. */
public final class WifiCredentialStore {
    private static final String ALIAS = "droy-wifi-credentials-v1";
    private static final Object KEY_LOCK = new Object();
    private final Context context;
    private final SharedPreferences store;

    public WifiCredentialStore(Context context) {
        this.context = context.getApplicationContext();
        store = this.context.getSharedPreferences("wifi-credentials", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        synchronized (KEY_LOCK) {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (keys.containsAlias(ALIAS)) return (SecretKey) keys.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
    }

    public synchronized String save(String name, String password) throws Exception {
        if (name == null || name.isBlank() || name.length() > 80 || !NetworkToolValues.validWifiPassword(password))
            throw new IllegalArgumentException(HardwareSupport.text(context, R.string.wifi_credential_invalid));
        byte[] plain = password.getBytes(StandardCharsets.UTF_8);
        String ref = UUID.randomUUID().toString();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(ref.getBytes(StandardCharsets.UTF_8));
            JSONObject item = new JSONObject().put("name", name.trim()).put("iv", HardwareSupport.hex(cipher.getIV()))
                    .put("ciphertext", HardwareSupport.hex(cipher.doFinal(plain))).put("createdAt", System.currentTimeMillis());
            if (!store.edit().putString(ref, item.toString()).commit())
                throw new java.io.IOException(HardwareSupport.text(context, R.string.wifi_credential_failed));
            return ref;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    public JSONArray list() throws Exception {
        JSONArray result = new JSONArray();
        for (String ref : store.getAll().keySet()) {
            String saved = store.getString(ref, null);
            if (saved == null) continue;
            JSONObject item = new JSONObject(saved);
            result.put(new JSONObject().put("credentialRef", ref).put("name", item.getString("name"))
                    .put("createdAt", item.getLong("createdAt")));
        }
        return result;
    }

    String read(String ref) throws Exception {
        String saved = store.getString(ref, null);
        if (saved == null) throw HardwareSupport.error(context, "credential_not_found", R.string.wifi_credential_missing);
        JSONObject item = new JSONObject(saved);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, HardwareSupport.unhex(item.getString("iv"), 32)));
        cipher.updateAAD(ref.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(HardwareSupport.unhex(item.getString("ciphertext"), 128));
        try { return new String(plain, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public synchronized void delete(String ref) throws Exception {
        if (!store.contains(ref)) throw HardwareSupport.error(context, "credential_not_found", R.string.wifi_credential_missing);
        if (!store.edit().remove(ref).commit())
            throw new java.io.IOException(HardwareSupport.text(context, R.string.wifi_credential_failed));
    }
}
