package ca.yorku.eecs3311.detourto.agent;

/** A provider call failed. {@link #kind()} lets callers show a useful message or fall back. */
public final class LLMException extends RuntimeException {

    public enum Kind {
        /** Missing, wrong or revoked API key. */
        AUTH,
        /** Too many requests or daily quota used up. */
        RATE_LIMIT,
        /** The request was rejected as invalid (a bug on our side, or an unknown model id). */
        BAD_REQUEST,
        /** Provider error or no network. */
        UNAVAILABLE,
        /** The configuration is incomplete (e.g. no model id). */
        CONFIG
    }

    private final Kind kind;

    public LLMException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LLMException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
