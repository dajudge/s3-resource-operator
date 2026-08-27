package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
@TestProfile(MalformedPrimaryWatchE2ETest.LongTimers.class)
class MalformedPrimaryWatchE2ETest {
    private static final String NS = "default";

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    @Test
    void malformedUserDoesNotBlockBackendEventForValidUser() {
        createMalformedUser("malformed-user");
        awaitUser("malformed-user", "False", "InvalidSpec", Duration.ofSeconds(15));

        createAdminSecret("isolated-backend-admin");
        createUser("isolated-user", "isolated-backend");
        awaitUser("isolated-user", "False", "BackendNotFound", Duration.ofSeconds(15));

        createBackend("isolated-backend", "isolated-backend-admin");
        awaitUser("isolated-user", "True", "Reconciled", Duration.ofSeconds(10));
    }

    @Test
    void malformedBucketDoesNotBlockUserEventForValidBucket() {
        createMalformedBucket("malformed-bucket");
        awaitBucket("malformed-bucket", "False", "InvalidSpec", Duration.ofSeconds(15));

        createAdminSecret("isolated-user-admin");
        createBackend("isolated-user-backend", "isolated-user-admin");
        createBucket("isolated-bucket", "isolated-user-backend", "isolated-owner");
        awaitBucket("isolated-bucket", "False", "UserNotFound", Duration.ofSeconds(15));

        createUser("isolated-owner", "isolated-user-backend");
        awaitUser("isolated-owner", "True", "Reconciled", Duration.ofSeconds(10));
        awaitBucket("isolated-bucket", "True", "Reconciled", Duration.ofSeconds(10));
    }

    private void createMalformedUser(String name) {
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private void createMalformedBucket(String name) {
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    private void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToStringData("accessKey", "test-root-access")
                .addToStringData("secretKey", "test-root-secret").build()).create();
    }

    private void createBackend(String name, String adminSecretName) {
        S3BackendSpec.SecretRef ref = new S3BackendSpec.SecretRef();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();
    }

    private void createUser(String name, String backendRef) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private void createBucket(String name, String backendRef, String userRef) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    private void awaitUser(String name, String status, String reason, Duration timeout) {
        await().atMost(timeout).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getConditions().getFirst().getStatus()).isEqualTo(status);
            assertThat(user.getStatus().getConditions().getFirst().getReason()).isEqualTo(reason);
        });
    }

    private void awaitBucket(String name, String status, String reason, Duration timeout) {
        await().atMost(timeout).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getConditions().getFirst().getStatus()).isEqualTo(status);
            assertThat(bucket.getStatus().getConditions().getFirst().getReason()).isEqualTo(reason);
        });
    }

    public static class LongTimers implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "s3.operator.resync-interval", "1h",
                    "s3.operator.retry-delay", "1h");
        }
    }
}
