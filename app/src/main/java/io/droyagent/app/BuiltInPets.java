package io.droyagent.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** APK-owned artwork stays separate from the user's generated-pet directory. */
final class BuiltInPets {
    static final String MARMOT = "8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0";
    static final String ALPACA = "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2";
    private static final Map<String, String> DIRECTORIES = Map.of(
        "default", "pet/", MARMOT, "pet/marmot/", ALPACA, "pet/alpaca/");

    private BuiltInPets() {}
    static String directory(String id) { return id == null ? null : DIRECTORIES.get(id); }
    static boolean contains(String id) { return directory(id) != null; }

    static JSONArray catalog(Context context) throws Exception {
        JSONObject document;
        try (InputStream input = context.getAssets().open("pet/builtins.json")) {
            document = new JSONObject(new String(NativePetStudioHost.bounded(input, 16 * 1024), StandardCharsets.UTF_8));
        }
        JSONArray entries = document.getJSONArray("pets");
        if (entries.length() != DIRECTORIES.size()) throw new IllegalArgumentException("invalid_built_in_pet_catalog");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject row = entries.getJSONObject(index);
            String id = row.getString("id");
            if (!contains(id) || !ids.add(id)) throw new IllegalArgumentException("invalid_built_in_pet_catalog");
            JSONObject names = row.getJSONObject("nameLocales"), descriptions = row.getJSONObject("descriptionLocales");
            row.put("name", NativeLocale.text(names.getString("zh"), names.getString("en")));
            row.put("description", NativeLocale.text(descriptions.getString("zh"), descriptions.getString("en")));
            row.put("referenceUrl", NativePetStudioHost.assetPath(id, "reference.png"));
            row.put("manifestUrl", NativePetStudioHost.assetPath(id, "pet.json"));
        }
        return entries;
    }
}
