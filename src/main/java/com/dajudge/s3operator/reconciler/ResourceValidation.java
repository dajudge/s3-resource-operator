package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3User;

import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.INVALID_SPEC;

final class ResourceValidation {
    private ResourceValidation() {
    }

    static void validateUser(S3User user) {
        if (user.getSpec() == null) invalid("S3User spec is required");
        required(user.getSpec().getBackendRef(), "S3User spec.backendRef is required");
    }

    static void validateBucket(S3Bucket bucket) {
        if (bucket.getSpec() == null) invalid("S3Bucket spec is required");
        required(bucket.getSpec().getBackendRef(), "S3Bucket spec.backendRef is required");
        required(bucket.getSpec().getUserRef(), "S3Bucket spec.userRef is required");
    }

    static void validateBackend(S3Backend backend) {
        if (backend.getSpec() == null) invalid("S3Backend spec is required");
        required(backend.getSpec().getEndpoint(), "S3Backend spec.endpoint is required");
        if (backend.getSpec().getAdminCredentialsSecretRef() == null) {
            invalid("S3Backend spec.adminCredentialsSecretRef is required");
        }
        required(backend.getSpec().getAdminCredentialsSecretRef().getName(),
                "S3Backend spec.adminCredentialsSecretRef.name is required");
    }

    static boolean hasUsableUserSpec(S3User user) {
        return user != null && user.getSpec() != null && nonBlank(user.getSpec().getBackendRef());
    }

    static boolean hasUsableBucketSpec(S3Bucket bucket) {
        return bucket != null && bucket.getSpec() != null
                && nonBlank(bucket.getSpec().getBackendRef()) && nonBlank(bucket.getSpec().getUserRef());
    }

    static boolean hasUsableBackendSpec(S3Backend backend) {
        return backend != null && backend.getSpec() != null && nonBlank(backend.getSpec().getEndpoint())
                && backend.getSpec().getAdminCredentialsSecretRef() != null
                && nonBlank(backend.getSpec().getAdminCredentialsSecretRef().getName());
    }

    private static void required(String value, String message) {
        if (!nonBlank(value)) invalid(message);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void invalid(String message) {
        throw new ReconciliationException(INVALID_SPEC, message);
    }
}
