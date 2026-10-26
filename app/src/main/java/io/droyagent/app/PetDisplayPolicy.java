package io.droyagent.app;

/** State keeps adaptive display alive; only an explicitly selected notice extends it after work. */
final class PetDisplayPolicy {
    static final long NOTICE_MS = 6000;
    private String lastNotice = "";
    private long noticeUntil;

    static boolean valid(String value) { return "always".equals(value) || "adaptive".equals(value) || "off".equals(value); }
    static String initial(boolean internal, boolean legacyEnabled) { return !legacyEnabled || internal ? "off" : "always"; }

    void observe(String identity, boolean active, boolean pending, boolean hasMessage, boolean alreadyRead, long now) {
        if (alreadyRead) noticeUntil = 0;
    }

    void announce(String identity, long now) {
        if (identity.equals(lastNotice)) return;
        lastNotice = identity;
        noticeUntil = now + NOTICE_MS;
    }

    boolean visible(String mode, boolean active, boolean pending, boolean preview, long now) {
        if (preview) return true;
        if ("off".equals(mode)) return false;
        return "always".equals(mode) || active || pending || now < noticeUntil;
    }
    long noticeRemaining(long now) { return Math.max(0, noticeUntil - now); }
    void clear() { lastNotice = ""; noticeUntil = 0; }
}
