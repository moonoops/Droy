package io.droyagent.app;

final class DroyPackages {
    static final String RELEASE = "io.droyagent.app";
    static final String DEVELOPMENT = "io.droyagent.app.dev";

    private DroyPackages() {}

    static boolean contains(String name) {
        return RELEASE.equals(name) || DEVELOPMENT.equals(name);
    }
}
