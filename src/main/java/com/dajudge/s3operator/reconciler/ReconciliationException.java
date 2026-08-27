package com.dajudge.s3operator.reconciler;

public final class ReconciliationException extends RuntimeException {
    public enum Reason {
        INVALID_SPEC("InvalidSpec"),
        BACKEND_NOT_FOUND("BackendNotFound"),
        UNSUPPORTED_PROVIDER("UnsupportedProvider"),
        USER_NOT_FOUND("UserNotFound"),
        USER_CREDENTIALS_NOT_FOUND("UserCredentialsNotFound"),
        ADMIN_CREDENTIALS_NOT_FOUND("AdminCredentialsNotFound"),
        INVALID_CREDENTIALS_SECRET("InvalidCredentialsSecret"),
        PROVIDER_ERROR("ProviderError");

        private final String conditionReason;

        Reason(String conditionReason) {
            this.conditionReason = conditionReason;
        }

        public String conditionReason() {
            return conditionReason;
        }
    }

    private final Reason reason;

    public ReconciliationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ReconciliationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
