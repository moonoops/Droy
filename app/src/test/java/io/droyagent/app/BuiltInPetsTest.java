package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class BuiltInPetsTest {
    private Path assets() {
        Path root = Path.of("src/main/assets/pet");
        return Files.isDirectory(root) ? root : Path.of("app/src/main/assets/pet");
    }

    @Test public void bundledCatalogContainsExactlyTheThreeOfflinePackages() throws Exception {
        JSONArray catalog = new JSONObject(Files.readString(assets().resolve("builtins.json"))).getJSONArray("pets");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < catalog.length(); index++) {
            JSONObject row = catalog.getJSONObject(index);
            String id = row.getString("id"); assertTrue(ids.add(id));
            assertTrue(BuiltInPets.contains(id)); assertTrue(PetPackage.validId(id));
            assertEquals("pet/" + (row.getString("directory").isEmpty() ? "" : row.getString("directory") + "/"), BuiltInPets.directory(id));
            for (String field : new String[]{"nameLocales", "descriptionLocales"})
                for (String language : new String[]{"zh", "en"}) assertFalse(row.getJSONObject(field).getString(language).trim().isEmpty());
            Path directory = assets().resolve(row.getString("directory"));
            JSONObject manifest = new JSONObject(Files.readString(directory.resolve("pet.json")));
            assertEquals(id, manifest.getString("id"));
            assertEquals("pixel", manifest.getJSONObject("rendering").getString("style"));
            PetSpriteSpec parsed = PetSpriteSpec.parse(manifest);
            for (PetSpriteSpec.Atlas atlas : parsed.atlases.values()) assertTrue(Files.isRegularFile(directory.resolve(atlas.file)));
            assertTrue(Files.isRegularFile(directory.resolve("reference.png")));
            assertTrue(Files.isRegularFile(directory.resolve("brand-logo.png")));
        }
        assertEquals(Set.of("default", BuiltInPets.MARMOT, BuiltInPets.ALPACA), ids);
        assertFalse(Files.exists(assets().resolve("cat-actions.png")));
    }

    @Test public void unknownAndUntrustedIdsCannotBecomeAssetDirectories() {
        for (String id : new String[]{null, "", "../default", "default/..", "marmot", "a00b0000-0000-4000-8000-000000000001"}) {
            assertFalse(BuiltInPets.contains(id)); assertNull(BuiltInPets.directory(id));
        }
    }
}
