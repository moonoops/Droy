package io.droyagent.androiduse;

public final class ActionException extends Exception {
    private final String code;
    private final String reason;
    private org.json.JSONObject details;

    public ActionException(String code, String message) {
        super(message);
        this.code = code;
        this.reason = null;
    }

    public ActionException(String code, String reason, String message) {
        super(message);
        this.code = code;
        this.reason = reason;
    }

    public ActionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.reason = null;
    }

    public String getCode() {
        return code;
    }

    public String getReason() { return reason; }

    public org.json.JSONObject getDetails() { return details; }

    public ActionException withDetails(org.json.JSONObject value) { details = value; return this; }
}
