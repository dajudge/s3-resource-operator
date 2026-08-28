package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import org.junit.jupiter.api.Test;

import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.INVALID_SPEC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceValidationTest {

    @Test
    void acceptsUsableSpecs() {
        S3User user = user("backend");
        S3Bucket bucket = bucket("backend", "user");
        S3Backend backend = backend("http://versity:7070", "admin");

        ResourceValidation.validateUser(user);
        ResourceValidation.validateBucket(bucket);
        ResourceValidation.validateBackend(backend);

        assertThat(ResourceValidation.hasUsableUserSpec(user)).isTrue();
        assertThat(ResourceValidation.hasUsableBucketSpec(bucket)).isTrue();
        assertThat(ResourceValidation.hasUsableBackendSpec(backend)).isTrue();
    }

    @Test
    void rejectsMissingAndBlankUserBackendReferences() {
        assertInvalid(() -> ResourceValidation.validateUser(new S3User()), "S3User spec is required");
        assertInvalid(() -> ResourceValidation.validateUser(user("  ")), "S3User spec.backendRef is required");

        assertThat(ResourceValidation.hasUsableUserSpec(null)).isFalse();
        assertThat(ResourceValidation.hasUsableUserSpec(new S3User())).isFalse();
        assertThat(ResourceValidation.hasUsableUserSpec(user(" "))).isFalse();
    }

    @Test
    void rejectsMissingAndBlankBucketReferences() {
        assertInvalid(() -> ResourceValidation.validateBucket(new S3Bucket()), "S3Bucket spec is required");
        assertInvalid(() -> ResourceValidation.validateBucket(bucket(" ", "user")),
                "S3Bucket spec.backendRef is required");
        assertInvalid(() -> ResourceValidation.validateBucket(bucket("backend", " ")),
                "S3Bucket spec.userRef is required");

        assertThat(ResourceValidation.hasUsableBucketSpec(null)).isFalse();
        assertThat(ResourceValidation.hasUsableBucketSpec(new S3Bucket())).isFalse();
        assertThat(ResourceValidation.hasUsableBucketSpec(bucket("", "user"))).isFalse();
        assertThat(ResourceValidation.hasUsableBucketSpec(bucket("backend", ""))).isFalse();
    }

    @Test
    void rejectsIncompleteBackendSpecs() {
        assertInvalid(() -> ResourceValidation.validateBackend(new S3Backend()), "S3Backend spec is required");
        assertInvalid(() -> ResourceValidation.validateBackend(backend(" ", "admin")),
                "S3Backend spec.endpoint is required");

        S3Backend missingSecret = new S3Backend();
        S3BackendSpec missingSecretSpec = new S3BackendSpec();
        missingSecretSpec.setEndpoint("http://versity:7070");
        missingSecret.setSpec(missingSecretSpec);
        assertInvalid(() -> ResourceValidation.validateBackend(missingSecret),
                "S3Backend spec.adminCredentialsSecretRef is required");

        assertInvalid(() -> ResourceValidation.validateBackend(backend("http://versity:7070", " ")),
                "S3Backend spec.adminCredentialsSecretRef.name is required");

        assertThat(ResourceValidation.hasUsableBackendSpec(null)).isFalse();
        assertThat(ResourceValidation.hasUsableBackendSpec(new S3Backend())).isFalse();
        assertThat(ResourceValidation.hasUsableBackendSpec(backend("", "admin"))).isFalse();
        assertThat(ResourceValidation.hasUsableBackendSpec(missingSecret)).isFalse();
        assertThat(ResourceValidation.hasUsableBackendSpec(backend("http://versity:7070", ""))).isFalse();
    }

    private static S3User user(String backendRef) {
        S3User user = new S3User();
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        user.setSpec(spec);
        return user;
    }

    private static S3Bucket bucket(String backendRef, String userRef) {
        S3Bucket bucket = new S3Bucket();
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        bucket.setSpec(spec);
        return bucket;
    }

    private static S3Backend backend(String endpoint, String secretName) {
        S3Backend backend = new S3Backend();
        S3BackendSpec spec = new S3BackendSpec();
        spec.setEndpoint(endpoint);
        LocalObjectReference secretRef = new LocalObjectReference();
        secretRef.setName(secretName);
        spec.setAdminCredentialsSecretRef(secretRef);
        backend.setSpec(spec);
        return backend;
    }

    private static void assertInvalid(Runnable operation, String message) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ReconciliationException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(INVALID_SPEC);
                    assertThat(exception).hasMessage(message);
                });
    }
}
