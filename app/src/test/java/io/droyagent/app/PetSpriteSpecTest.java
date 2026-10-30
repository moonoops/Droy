package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class PetSpriteSpecTest {
    @Test public void bundledAtlasCoordinatesMatchRealTransparentImages() throws Exception {
        Path assets = Path.of("src/main/assets/pet");
        if (!Files.isDirectory(assets)) assets = Path.of("app/src/main/assets/pet");
        PetSpriteSpec spec = PetSpriteSpec.parse(new JSONObject(new String(Files.readAllBytes(assets.resolve("pet.json")), StandardCharsets.UTF_8)));
        for (String state : new String[]{"enterPeek", "enterWalk", "enterJump", "exitWalk", "exitHide", "tapTilt", "tapWave", "pet", "petShy", "idleTail", "idleStretch", "blocked", "completed", "stopped"})
            assertNotNull("Bundled pet is missing the agreed interaction: " + state, spec.animations.get(state));
        assertEquals(3, spec.variants.get("enter").length);
        assertEquals(2, spec.variants.get("exit").length);
        assertEquals(3, spec.variants.get("tap").length);
        assertEquals(2, spec.variants.get("pet").length);
        assertEquals(3, spec.variants.get("idle").length);
        assertTrue("Built-in artwork must use crisp pixel rendering", spec.pixelArt);
        for (String state : new String[]{"knead", "tailCurl", "sleep"}) {
            assertTrue(PetInteractionActions.available(spec.animations::containsKey).contains(state));
            assertNotNull(spec.labels.get(state));
        }
        assertTrue("Left-authored body must turn into the screen on the left edge", spec.animations.get("idle").mirrored(false));
        assertFalse(spec.animations.get("idle").mirrored(true));
        PetSpriteSpec.Animation blink = spec.animations.get("peekLeft").withTotalDuration(500);
        assertEquals(2, blink.frameAt(220));
        assertEquals(500, blink.durationMs);
        int frameCount = 0;
        for (PetSpriteSpec.Atlas atlas : spec.atlases.values()) {
            try (DataInputStream image = new DataInputStream(Files.newInputStream(assets.resolve(atlas.file)))) {
                assertEquals("Invalid PNG signature", 0x89504E470D0A1A0AL, image.readLong());
                assertEquals(13, image.readInt());
                assertEquals("Missing PNG IHDR", 0x49484452, image.readInt());
                assertEquals(atlas.width, image.readInt());
                assertEquals(atlas.height, image.readInt());
                assertEquals(8, image.readUnsignedByte());
                assertEquals("Pet atlases must keep their RGBA transparency", 6, image.readUnsignedByte());
            }
            for (PetSpriteSpec.Frame frame : atlas.frames) {
                assertTrue(frame.x >= 0 && frame.y >= 0 && frame.width > 0 && frame.height > 0);
                assertTrue(frame.x + frame.width <= atlas.width && frame.y + frame.height <= atlas.height);
                frameCount++;
            }
        }
        assertEquals(102, frameCount);
    }

    private JSONObject fixture() throws Exception {
        JSONObject atlas = new JSONObject().put("file", "cat-actions.png").put("width", 100).put("height", 100)
            .put("frames", new JSONArray()
                .put(new JSONObject().put("sourceRect", new JSONArray(new int[]{0, 0, 20, 30})))
                .put(new JSONObject().put("sourceRect", new JSONArray(new int[]{25, 0, 24, 36})))
                .put(new JSONObject().put("sourceRect", new JSONArray(new int[]{50, 0, 20, 30}))));
        JSONObject states = new JSONObject();
        for (String state : PetSpriteSpec.STATES) states.put(state, new JSONObject().put("atlas", "actions")
                .put("frames", new JSONArray(new int[]{0, 1, 2})).put("durationsMs", new JSONArray(new int[]{100, 80, 100})));
        return new JSONObject().put("formatVersion", 1).put("atlases", new JSONObject().put("actions", atlas)).put("states", states);
    }

    @Test public void oneShotTimingUsesElapsedTimeAndHoldsLastFrame() throws Exception {
        PetSpriteSpec.Animation animation = PetSpriteSpec.parse(fixture()).animations.get("tap");
        assertEquals(0, animation.frameAt(0));
        assertEquals(0, animation.frameAt(99));
        assertEquals(1, animation.frameAt(100));
        assertEquals(2, animation.frameAt(180));
        assertEquals(2, animation.frameAt(5000));
        assertEquals(100, animation.nextFrameDelay(0));
        assertEquals(30, animation.nextFrameDelay(150));
        assertEquals(0, animation.nextFrameDelay(180));
        assertEquals(0, animation.nextFrameDelay(5000));
    }

    @Test public void allFramesShareOneScaleSoStretchDoesNotClipOrDisappear() throws Exception {
        PetSpriteSpec.Animation animation = PetSpriteSpec.parse(fixture()).animations.get("tap");
        assertEquals(24, animation.maxWidth);
        assertEquals(36, animation.maxHeight);
        assertEquals(1.5f, animation.scaleToFit(56, 54), .001f);
        assertEquals(1f, animation.scaleToFit(24, 54), .001f);
        for (PetSpriteSpec.Frame frame : animation.frames) {
            assertTrue(frame.width * animation.scaleToFit(56, 54) <= 56);
            assertTrue(frame.height * animation.scaleToFit(56, 54) <= 54);
        }
    }

    @Test public void invalidBundledFramesFailInsteadOfDrawingNeighborPixels() throws Exception {
        JSONObject document = fixture();
        document.getJSONObject("atlases").getJSONObject("actions").getJSONArray("frames")
                .getJSONObject(0).put("sourceRect", new JSONArray(new int[]{90, 0, 20, 30}));
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(document));
    }

    @Test public void invalidActionSequencesFailInsteadOfSchedulingIndefinitely() throws Exception {
        JSONObject invalidDuration = fixture();
        invalidDuration.getJSONObject("states").getJSONObject("tap").put("durationsMs", new JSONArray(new int[]{100, 0, 100}));
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(invalidDuration));
        JSONObject invalidIndex = fixture();
        invalidIndex.getJSONObject("states").getJSONObject("tap").put("frames", new JSONArray(new int[]{0, 9, 0}));
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(invalidIndex));
        JSONObject invalidCount = fixture();
        invalidCount.getJSONObject("states").getJSONObject("tap").put("durationsMs", new JSONArray(new int[]{100}));
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(invalidCount));
    }
    @Test public void directionalVariantsAreExplicitAndNonMirrorableArtworkKeepsItsIdentity() throws Exception {
        JSONObject document = fixture().put("formatVersion", 2);
        JSONObject states = document.getJSONObject("states");
        states.put("tapRight", new JSONObject(states.getJSONObject("tap").toString()).put("facing", "right"));
        states.getJSONObject("tap").put("facing", "left").put("mirrorSafe", false).put("rightState", "tapRight");
        document.put("variants", new JSONObject().put("tap", new JSONArray().put("tap").put("tapRight")));
        PetSpriteSpec spec = PetSpriteSpec.parse(document);
        assertFalse(spec.animations.get("tap").mirrorSafe);
        assertEquals("tapRight", spec.animations.get("tap").rightState);
        assertEquals("right", spec.animations.get("tapRight").facing);
        assertEquals(2, spec.variants.get("tap").length);
        states.getJSONObject("tap").put("rightState", "missing");
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(document));
    }
    @Test public void invalidVariantsAndUnboundedAtlasFailBeforeDecoding() throws Exception {
        JSONObject document = fixture().put("formatVersion", 2);
        document.put("variants", new JSONObject().put("enter", new JSONArray().put("missing")));
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(document));
        document.remove("variants");
        document.getJSONObject("atlases").getJSONObject("actions").put("width", 1000000);
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(document));
    }

    @Test public void pairedLabelsAndDelayedEffectsKeepTheirBoundedContract() throws Exception {
        JSONObject document = fixture().put("formatVersion", 2).put("rendering", new JSONObject().put("style", "pixel"));
        document.getJSONObject("states").getJSONObject("tap").put("labelLocales", new JSONObject().put("zh", "轻触").put("en", "Tap"));
        JSONObject effect = new JSONObject().put("atlas", "actions").put("frames", new JSONArray(new int[]{0, 1, 2}))
            .put("durationsMs", new JSONArray(new int[]{100, 100, 200})).put("delayMs", 640);
        document.put("effects", new JSONObject().put("tap", effect));
        PetSpriteSpec spec = PetSpriteSpec.parse(document);
        assertTrue(spec.pixelArt);
        assertEquals("轻触", spec.labels.get("tap").zh); assertEquals("Tap", spec.labels.get("tap").en);
        assertEquals(1040, spec.effects.get("tap").durationMs());
        assertEquals(1, spec.effects.get("tap").animation.frameAt(100));
        assertEquals(2, spec.effects.get("tap").animation.frameAt(200));
        effect.put("delayMs", -1);
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(document));
        effect.put("delayMs", 640).put("frames", new JSONArray(new int[]{99})).put("durationsMs", new JSONArray(new int[]{100}));
        assertThrows(IllegalArgumentException.class, () -> PetSpriteSpec.parse(document));
        effect.put("frames", new JSONArray(new int[]{0})).put("durationsMs", new JSONArray(new int[]{100}));
        document.getJSONObject("states").getJSONObject("tap").getJSONObject("labelLocales").remove("en");
        assertThrows(org.json.JSONException.class, () -> PetSpriteSpec.parse(document));
    }
}
