package com.shinoyuki.accesshub.pack;

/** Expected administration failure that an outer API boundary can map to a response. */
public final class PackAdminException extends RuntimeException {
    private final Reason reason;

    public PackAdminException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_FOUND,
        CONFLICT,
        STALE_DIFF,
        IMMUTABLE_VERSION,
        REMOVAL_CONFIRMATION_REQUIRED
    }
}
