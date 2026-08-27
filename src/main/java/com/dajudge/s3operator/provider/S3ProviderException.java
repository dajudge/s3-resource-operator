package com.dajudge.s3operator.provider;

public final class S3ProviderException extends RuntimeException {
    public S3ProviderException(String message) {
        super(message);
    }

    public S3ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
