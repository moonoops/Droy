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

/** Credential bytes enter through local UI and never become tool output. */
public final class NfcCredentials {
    private static final String ALIAS = "droy-nfc-classic-credentials-v1";
    private final Context context;
    private final SharedPreferences store;
    public NfcCredentials(Context context) {
        this.context = context.getApplicationContext();
        store = this.context.getSharedPreferences("nfc-credentials", Context.MODE_PRIVATE);
    }
    private SecretKey key() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (keys.containsAlias(ALIAS)) return (SecretKey) keys.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public synchronized String save(String name, String keyHex) throws Exception {
        if (name == null || name.isBlank() || name.length() > 80) throw new IllegalArgumentException(HardwareSupport.text(context, R.string.nfc_invalid_request));
        byte[] plain = NfcProtocol.unhex(keyHex, 6);
        if (plain.length != 6) throw new IllegalArgumentException(HardwareSupport.text(context, R.string.nfc_invalid_request));
        String ref = UUID.randomUUID().toString();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(ref.getBytes(StandardCharsets.UTF_8));
            JSONObject item = new JSONObject().put("name", name.trim()).put("iv", NfcProtocol.hex(cipher.getIV()))
                .put("ciphertext", NfcProtocol.hex(cipher.doFinal(plain))).put("createdAt", System.currentTimeMillis());
            if (!store.edit().putString(ref, item.toString()).commit()) throw new java.io.IOException(HardwareSupport.text(context, R.string.nfc_save_failed));
            return ref;
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    public JSONArray list() throws Exception {
        JSONArray items = new JSONArray();
        for (String ref : store.getAll().keySet()) {
            JSONObject item = new JSONObject(store.getString(ref, ""));
            items.put(new JSONObject().put("credentialRef", ref).put("name", item.getString("name"))
                .put("type", "mifare_classic_6byte").put("createdAt", item.getLong("createdAt")));
        }
        return items;
    }
    byte[] read(String ref) throws Exception {
        String saved = store.getString(ref, null);
        if (saved == null) throw HardwareSupport.error(context, "credential_not_found", R.string.nfc_credential_missing);
        JSONObject item = new JSONObject(saved);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, NfcProtocol.unhex(item.getString("iv"), 32)));
        cipher.updateAAD(ref.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(NfcProtocol.unhex(item.getString("ciphertext"), 64));
    }
    public void delete(String ref) throws Exception {
        if (!store.contains(ref)) throw HardwareSupport.error(context, "credential_not_found", R.string.nfc_credential_missing);
        if (!store.edit().remove(ref).commit()) throw new java.io.IOException(HardwareSupport.text(context, R.string.nfc_save_failed));
    }
}
