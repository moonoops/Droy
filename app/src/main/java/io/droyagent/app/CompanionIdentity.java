package io.droyagent.app;

/** Path-safe identity syntax; the companion service remains authoritative for existence. */
public final class CompanionIdentity {
    private CompanionIdentity() {}

    public static boolean isValid(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}");
    }
}
