package io.droyagent.app;

final class DiagnosticCodes {
    private static final String[] CODES = {"DROY_STARTUP_FAILED", "DROY_SHUTDOWN_FAILED", "ERR_MODULE_NOT_FOUND", "MODULE_NOT_FOUND", "EACCES", "EPERM", "ENOSPC", "EADDRINUSE", "ECONNREFUSED", "ENOTFOUND", "ETIMEDOUT", "ERR_CERT_AUTHORITY_INVALID", "StartupError"};

    static String fromRuntimeOutput(String line) {
        for (String code : CODES) if (line.matches("(?s).*\\b" + code + "\\b.*")) return code;
        return null;
    }
}
