package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
class FailureStatusE2ETest {
    private static final String NS = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    @Test
    void reportsMissingDependenciesAndRecovers() {
        createUser("missing-backend-user", "late-backend");
        Condition missingBackend = awaitUserCondition("missing-backend-user", "False", "BackendNotFound");
        assertStableUserCondition("missing-backend-user", missingBackend);

        createBackend("late-backend", "versity", endpoint, "late-admin");
        awaitUserCondition("missing-backend-user", "False", "AdminCredentialsNotFound");

        createAdminSecret("late-admin");
        awaitUserCondition("missing-backend-user", "True", "Reconciled");

        createBucket("missing-user-bucket", "late-backend", "late-user");
        awaitBucketCondition("missing-user-bucket", "False", "UserNotFound");

        createUser("late-user", "late-backend");
        awaitUserCondition("late-user", "True", "Reconciled");
        awaitBucketCondition("missing-user-bucket", "True", "Reconciled");
    }

    @Test
    void reportsEveryUserFailureReason() {
        createAdminSecret("user-taxonomy-admin");

        createBackend("unsupported-user-backend", "not-versity", endpoint, "user-taxonomy-admin");
        createUser("unsupported-user", "unsupported-user-backend");
        awaitUserCondition("unsupported-user", "False", "UnsupportedProvider");

        createBackend("provider-error-user-backend", "versity", "http://127.0.0.1:1", "user-taxonomy-admin");
        createUser("provider-error-user", "provider-error-user-backend");
        awaitUserCondition("provider-error-user", "False", "ProviderError");

        createBackend("invalid-admin-user-backend", "versity", endpoint, "invalid-user-admin");
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName("invalid-user-admin").withNamespace(NS).endMetadata()
                .addToStringData("accessKey", "test-root-access").build()).create();
        createUser("invalid-admin-user", "invalid-admin-user-backend");
        awaitUserCondition("invalid-admin-user", "False", "InvalidCredentialsSecret");
    }

    @Test
    void reportsEveryBucketFailureReason() {
        createAdminSecret("bucket-taxonomy-admin");
        createBackend("bucket-good-backend", "versity", endpoint, "bucket-taxonomy-admin");
        createUser("bucket-owner", "bucket-good-backend");
        awaitUserCondition("bucket-owner", "True", "Reconciled");

        createBucket("missing-backend-bucket", "does-not-exist", "bucket-owner");
        Condition missingBackend = awaitBucketCondition("missing-backend-bucket", "False", "BackendNotFound");
        assertStableBucketCondition("missing-backend-bucket", missingBackend);

        createBackend("unsupported-bucket-backend", "not-versity", endpoint, "bucket-taxonomy-admin");
        createBucket("unsupported-provider-bucket", "unsupported-bucket-backend", "bucket-owner");
        awaitBucketCondition("unsupported-provider-bucket", "False", "UnsupportedProvider");

        createBucket("missing-user-bucket-taxonomy", "bucket-good-backend", "does-not-exist");
        awaitBucketCondition("missing-user-bucket-taxonomy", "False", "UserNotFound");

        // Use a real S3User whose own reconciliation is blocked so its Secret cannot race with this test.
        createUser("blocked-owner", "does-not-exist");
        awaitUserCondition("blocked-owner", "False", "BackendNotFound");
        createBucket("missing-user-credentials-bucket", "bucket-good-backend", "blocked-owner");
        awaitBucketCondition("missing-user-credentials-bucket", "False", "UserCredentialsNotFound");

        createBackend("invalid-admin-bucket-backend", "versity", endpoint, "invalid-bucket-admin");
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName("invalid-bucket-admin").withNamespace(NS).endMetadata()
                .addToStringData("accessKey", "test-root-access").build()).create();
        createBucket("invalid-admin-bucket", "invalid-admin-bucket-backend", "bucket-owner");
        awaitBucketCondition("invalid-admin-bucket", "False", "InvalidCredentialsSecret");

        createBackend("missing-admin-bucket-backend", "versity", endpoint, "missing-bucket-admin");
        createBucket("missing-admin-bucket", "missing-admin-bucket-backend", "bucket-owner");
        awaitBucketCondition("missing-admin-bucket", "False", "AdminCredentialsNotFound");

        createBackend("provider-error-bucket-backend", "versity", "http://127.0.0.1:1", "bucket-taxonomy-admin");
        createBucket("provider-error-bucket", "provider-error-bucket-backend", "bucket-owner");
        awaitBucketCondition("provider-error-bucket", "False", "ProviderError");
    }

    private void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToStringData("accessKey", "test-root-access").addToStringData("secretKey", "test-root-secret").build()).create();
    }

    private void createBackend(String name, String provider, String backendEndpoint, String adminSecretName) {
        S3BackendSpec.SecretRef ref = new S3BackendSpec.SecretRef();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider(provider);
        spec.setEndpoint(backendEndpoint);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();
    }

    private void createUser(String name, String backendRef) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        spec.setSecretName(name + "-s3");
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private void createBucket(String name, String backendRef, String userRef) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        spec.setBucketName(name);
        spec.setDeletionPolicy(S3BucketSpec.DeletionPolicy.RETAIN);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    private Condition awaitUserCondition(String name, String status, String reason) {
        final Condition[] result = new Condition[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getObservedGeneration()).isEqualTo(user.getMetadata().getGeneration());
            Condition condition = user.getStatus().getConditions().stream()
                    .filter(c -> "Ready".equals(c.getType())).findFirst().orElseThrow();
            assertThat(condition.getStatus()).isEqualTo(status);
            assertThat(condition.getReason()).isEqualTo(reason);
            assertThat(condition.getObservedGeneration()).isEqualTo(user.getMetadata().getGeneration());
            assertThat(condition.getMessage()).isNotBlank();
            result[0] = condition;
        });
        return result[0];
    }

    private Condition awaitBucketCondition(String name, String status, String reason) {
        final Condition[] result = new Condition[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getObservedGeneration()).isEqualTo(bucket.getMetadata().getGeneration());
            Condition condition = bucket.getStatus().getConditions().stream()
                    .filter(c -> "Ready".equals(c.getType())).findFirst().orElseThrow();
            assertThat(condition.getStatus()).isEqualTo(status);
            assertThat(condition.getReason()).isEqualTo(reason);
            assertThat(condition.getObservedGeneration()).isEqualTo(bucket.getMetadata().getGeneration());
            assertThat(condition.getMessage()).isNotBlank();
            result[0] = condition;
        });
        return result[0];
    }

    private void assertStableUserCondition(String name, Condition original) {
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            Condition current = user.getStatus().getConditions().stream()
                    .filter(c -> "Ready".equals(c.getType())).findFirst().orElseThrow();
            assertThat(current.getStatus()).isEqualTo(original.getStatus());
            assertThat(current.getReason()).isEqualTo(original.getReason());
            assertThat(current.getLastTransitionTime()).isEqualTo(original.getLastTransitionTime());
        });
    }

    private void assertStableBucketCondition(String name, Condition original) {
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            Condition current = bucket.getStatus().getConditions().stream()
                    .filter(c -> "Ready".equals(c.getType())).findFirst().orElseThrow();
            assertThat(current.getStatus()).isEqualTo(original.getStatus());
            assertThat(current.getReason()).isEqualTo(original.getReason());
            assertThat(current.getLastTransitionTime()).isEqualTo(original.getLastTransitionTime());
        });
    }
}
