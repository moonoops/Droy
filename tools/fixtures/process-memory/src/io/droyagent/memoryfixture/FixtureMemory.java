package io.droyagent.memoryfixture;

final class FixtureMemory {
    static { System.loadLibrary("memoryfixture"); }
    private FixtureMemory() {}
    static native void initialize();
    static native void reset();
    static native void spend();
    static native String state();
    static native String metadata();
}
