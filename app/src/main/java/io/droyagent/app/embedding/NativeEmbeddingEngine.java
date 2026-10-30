package io.droyagent.app.embedding;

import java.nio.charset.StandardCharsets;

/** A fixed model session; only the embedding worker may access its native handle. */
public final class NativeEmbeddingEngine implements AutoCloseable {
    private long handle;
    public NativeEmbeddingEngine(String directory) {
        System.loadLibrary("droy_embedding");
        handle = nativeLoad(directory.getBytes(StandardCharsets.UTF_8));
        if (handle == 0) throw new IllegalStateException("EMBEDDING_LOAD_FAILED");
    }
    public float[] embed(String prefixedText) {
        if (handle == 0) throw new IllegalStateException("EMBEDDING_NOT_READY");
        return nativeEmbed(handle, prefixedText.getBytes(StandardCharsets.UTF_8));
    }
    /** Used by the model equivalence fixture; never exposed by the product bridge. */
    public int[] tokens(String prefixedText) {
        if (handle == 0) throw new IllegalStateException("EMBEDDING_NOT_READY");
        return nativeTokens(handle, prefixedText.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void close() {
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }
    private static native long nativeLoad(byte[] directory);
    private static native float[] nativeEmbed(long handle, byte[] prefixedText);
    private static native int[] nativeTokens(long handle, byte[] prefixedText);
    private static native void nativeClose(long handle);
}
