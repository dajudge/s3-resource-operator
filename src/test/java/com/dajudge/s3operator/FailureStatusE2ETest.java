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
        awaitUserCondition("missing-backend-user", "False", "BackendNotFound");

        createBackend("late-backend", "late-admin");
        awaitUserCondition("missing-backend-user", "False", "AdminCredentialsNotFound");

        createAdminSecret("late-admin");
        awaitUserCondition("missing-backend-user", "True", "Reconciled");

        createBucket("missing-user-bucket", "late-backend", "late-user");
        awaitBucketCondition("missing-user-bucket", "False", "UserNotFound");

        createUser("late-user", "late-backend");
        awaitUserCondition("late-user", "True", "Reconciled");
        awaitBucketCondition("missing-user-bucket", "True", "Reconciled");
    }

    private void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToStringData("accessKey", "test-root-access").addToStringData("secretKey", "test-root-secret").build()).create();
    }

    private void createBackend(String name, String adminSecretName) {
        S3BackendSpec.SecretRef ref = new S3BackendSpec.SecretRef();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
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

    private void awaitUserCondition(String name, String status, String reason) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getObservedGeneration()).isEqualTo(user.getMetadata().getGeneration());
            assertThat(user.getStatus().getConditions()).anySatisfy(c -> {
                assertThat(c.getType()).isEqualTo("Ready");
                assertThat(c.getStatus()).isEqualTo(status);
                assertThat(c.getReason()).isEqualTo(reason);
            });
        });
    }

    private void awaitBucketCondition(String name, String status, String reason) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getObservedGeneration()).isEqualTo(bucket.getMetadata().getGeneration());
            assertThat(bucket.getStatus().getConditions()).anySatisfy(c -> {
                assertThat(c.getType()).isEqualTo("Ready");
                assertThat(c.getStatus()).isEqualTo(status);
                assertThat(c.getReason()).isEqualTo(reason);
            });
        });
    }
}
